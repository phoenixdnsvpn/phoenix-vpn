package mobile

import (
	"bytes"
//	"context"
	"crypto/tls"
	_ "embed" // Required for embedding files
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math/rand"
	"net"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// 1. EMBED THE JSON FILE DIRECTLY INTO THE BINARY
//go:embed dns.json
var globalDNSJson []byte

// GlobalDNS mirrors the structure of your dns.json file
type GlobalDNS struct {
	ID          int      `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Addresses   []string `json:"addresses"`
	DohUrl      string   `json:"dohUrl"`
	Website     string   `json:"website"`
}

// ScannerResult is the output sent back to Kotlin
type ScannerResult struct {
	ProviderName string `json:"provider_name"`
	Address      string `json:"address"`
	Mode         string `json:"mode"`
	LatencyMs    int64  `json:"latency_ms"`
}

// RunGlobalDNSScanner is the exported function for Kotlin to call.
// mode can be "udp", "tcp", or "doh"
func RunGlobalDNSScanner(mode string, timeoutMs int, workers int) string {
	mode = strings.ToLower(mode)

	// 1. Parse the embedded JSON database
	var dnsList []GlobalDNS
	if err := json.Unmarshal(globalDNSJson, &dnsList); err != nil {
		return fmt.Sprintf(`[{"error": "Failed to parse embedded dns.json: %v"}]`, err)
	}

	// 2. Setup the Worker Pool channels
	type task struct {
		ProviderName string
		Target       string // IP for UDP/TCP, URL for DoH
		FallbackURL  string // The URL to try if the IP fails
	}

	// Increased buffer size to handle the extra URL tasks safely
	tasks := make(chan task, len(dnsList)*4)
	results := make(chan ScannerResult, len(dnsList)*4)
	var wg sync.WaitGroup
	var testedFallback sync.Map // Thread-safe map to prevent duplicate URL pings

	timeout := time.Duration(timeoutMs) * time.Millisecond

	// 3. Start Workers
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for t := range tasks {
				// Step A: Always test the primary IP target first
				latency := measureDNSLatency(t.Target, mode, timeout)
				
				results <- ScannerResult{
					ProviderName: t.ProviderName,
					Address:      t.Target,
					Mode:         mode,
					LatencyMs:    latency,
				}

				// Step B: FALLBACK LOGIC
				// If the IP timed out, we are in DoH mode, and a fallback URL exists
				if latency <= 0 && mode == "doh" && t.FallbackURL != "" && t.FallbackURL != t.Target {
					
					// Ensure we only test this specific fallback URL once across all concurrent workers
					if _, alreadyTried := testedFallback.LoadOrStore(t.FallbackURL, true); !alreadyTried {
						
						urlLatency := measureDNSLatency(t.FallbackURL, mode, timeout)
						
						// Emit the URL as a completely new result row
						results <- ScannerResult{
							ProviderName: t.ProviderName + " (URL)", // Label it so users know it's the URL fallback
							Address:      t.FallbackURL,
							Mode:         mode,
							LatencyMs:    urlLatency,
						}
					}
				}
			}
		}()
	}
	
	// 4. Feed Tasks into the Queue
	for _, provider := range dnsList {
		// Test every IP address in the JSON array for ALL modes (UDP, TCP, and DoH)
		for _, ip := range provider.Addresses {
			if ip != "" {
				tasks <- task{ProviderName: provider.Name, Target: ip, FallbackURL: provider.DohUrl}
			}
		}
	}
	close(tasks)
		
	// 5. Wait for all workers to finish IN A BACKGROUND GOROUTINE
	// CRITICAL FIX: This prevents the channel from deadlocking if the buffer fills up with the new fallback tasks!
	go func() {
		wg.Wait()
		close(results)
	}()

	// 6. Collect and Sort Results (Draining the channel continuously)
	var finalResults []ScannerResult
	for res := range results {
		finalResults = append(finalResults, res)
	}

	// Sort: Fastest at the top, Failures (-1) at the very bottom
	sort.Slice(finalResults, func(i, j int) bool {
		if finalResults[i].LatencyMs <= 0 {
			return false
		}
		if finalResults[j].LatencyMs <= 0 {
			return true
		}
		return finalResults[i].LatencyMs < finalResults[j].LatencyMs
	})
	
	// 7. Return to Kotlin as JSON
	outBytes, err := json.Marshal(finalResults)
	if err != nil {
		return "[]"
	}

	return string(outBytes)
}

// RunGlobalDNSScanner is the exported function for Kotlin to call.
// mode can be "udp", "tcp", or "doh"
func RunGlobalDNSScanner_IP(mode string, timeoutMs int, workers int) string {
	mode = strings.ToLower(mode)

	// 1. Parse the embedded JSON database
	var dnsList []GlobalDNS
	if err := json.Unmarshal(globalDNSJson, &dnsList); err != nil {
		return fmt.Sprintf(`[{"error": "Failed to parse embedded dns.json: %v"}]`, err)
	}

	// 2. Setup the Worker Pool channels
	type task struct {
		ProviderName string
		Target       string // IP for UDP/TCP, URL for DoH
	}

	tasks := make(chan task, len(dnsList)*2) // Buffer size covers multiple IPs per provider
	results := make(chan ScannerResult, len(dnsList)*2)
	var wg sync.WaitGroup

	timeout := time.Duration(timeoutMs) * time.Millisecond

	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for t := range tasks {
				latency := measureDNSLatency(t.Target, mode, timeout)
				// REMOVED the "if latency > 0" check so we keep failures (-1)
				results <- ScannerResult{
					ProviderName: t.ProviderName,
					Address:      t.Target,
					Mode:         mode,
					LatencyMs:    latency,
				}
			}
		}()
	}
	
	// 4. Feed Tasks into the Queue
	for _, provider := range dnsList {
		// Test every IP address in the JSON array for ALL modes (UDP, TCP, and DoH)
		for _, ip := range provider.Addresses {
			if ip != "" {
				tasks <- task{ProviderName: provider.Name, Target: ip}
			}
		}
	}
	close(tasks)
		
	// 5. Wait for all workers to finish, then close results channel
	wg.Wait()
	close(results)

	// 6. Collect and Sort Results
	var finalResults []ScannerResult
	for res := range results {
		finalResults = append(finalResults, res)
	}

/**	sort.Slice(finalResults, func(i, j int) bool {
		return finalResults[i].LatencyMs < finalResults[j].LatencyMs
	})*/

// Sort: Fastest at the top, Failures (-1) at the very bottom
	sort.Slice(finalResults, func(i, j int) bool {
		if finalResults[i].LatencyMs <= 0 {
			return false
		}
		if finalResults[j].LatencyMs <= 0 {
			return true
		}
		return finalResults[i].LatencyMs < finalResults[j].LatencyMs
	})
	
	// 7. Return to Kotlin as JSON
	outBytes, err := json.Marshal(finalResults)
	if err != nil {
		return "[]"
	}

	return string(outBytes)
}

// =====================================================================
// LATENCY MEASUREMENT ENGINE
// =====================================================================

func measureDNSLatency(target string, mode string, timeout time.Duration) int64 {
	start := time.Now()

	testDomain := "example.com"

	if mode == "doh" {
		query := buildRawDNSQuery(testDomain)

		// 1. Format the raw IP address into a DoH HTTPS URL
		dohTarget := target
		if !strings.HasPrefix(dohTarget, "http") {
			// Some providers use /resolve, but /dns-query is the widest industry standard for RFC 8484
			dohTarget = "https://" + target + "/dns-query" 
		}

		req, err := http.NewRequest("POST", dohTarget, bytes.NewReader(query))
		if err != nil {
			return -1
		}
		
		req.Header.Set("Content-Type", "application/dns-message")
		req.Header.Set("Accept", "application/dns-message")
		req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)") 

		// 2. CRITICAL: Bypass TLS Certificate mismatch errors for direct IP connections!
		customTransport := &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		}

		client := &http.Client{
			Timeout:   timeout,
			Transport: customTransport, // Inject the bypass here
		}

		resp, err := client.Do(req)
		if err != nil {
			return -1
		}
		
		// 3. Read and close body to free up memory for the worker pool
		_, _ = io.ReadAll(resp.Body)
		resp.Body.Close()

		if resp.StatusCode == 200 {
			return time.Since(start).Milliseconds()
		}
		return -1
	}
	
/*	if mode == "doh" {
		// Test DoH by performing a real JSON API lookup for google.com
		client := &http.Client{Timeout: timeout}
		reqURL := target + "?name=google.com&type=A"
		req, err := http.NewRequest("GET", reqURL, nil)
		if err != nil {
			return -1
		}
		req.Header.Set("Accept", "application/dns-json")

		resp, err := client.Do(req)
		if err != nil {
			return -1
		}
		defer resp.Body.Close()

		if resp.StatusCode == 200 {
			return time.Since(start).Milliseconds()
		}
		return -1
	}*/

	// For UDP and TCP, we must send a valid DNS Wire Format packet
	// Otherwise, firewalls might just accept the TCP connection but drop the data.
	if !strings.Contains(target, ":") {
		target = target + ":53" // Append port if missing
	}

	query := buildRawDNSQuery("google.com")
	conn, err := net.DialTimeout(mode, target, timeout)
	if err != nil {
		return -1
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(timeout))

	if mode == "tcp" {
		// TCP DNS requires a 2-byte length prefix
		tcpQuery := make([]byte, 2+len(query))
		binary.BigEndian.PutUint16(tcpQuery[0:2], uint16(len(query)))
		copy(tcpQuery[2:], query)
		_, err = conn.Write(tcpQuery)
	} else {
		_, err = conn.Write(query)
	}

	if err != nil {
		return -1
	}

	// Wait for any response to prove the server is alive and responding to DNS
	respBuf := make([]byte, 512)
	_, err = conn.Read(respBuf)
	if err != nil {
		return -1
	}

	return time.Since(start).Milliseconds()
}

// buildRawDNSQuery constructs a standard UDP/TCP DNS request packet (A Record)
func buildRawDNSQuery(domain string) []byte {
	id := uint16(rand.Intn(65535))
	buf := new(bytes.Buffer)

	// 12-byte DNS Header
	binary.Write(buf, binary.BigEndian, id)
	binary.Write(buf, binary.BigEndian, uint16(0x0100)) // Flags: Standard query, Recursion Desired
	binary.Write(buf, binary.BigEndian, uint16(1))      // Questions: 1
	binary.Write(buf, binary.BigEndian, uint16(0))      // Answer RRs: 0
	binary.Write(buf, binary.BigEndian, uint16(0))      // Authority RRs: 0
	binary.Write(buf, binary.BigEndian, uint16(0))      // Additional RRs: 0

	// Question Section
	for _, part := range strings.Split(domain, ".") {
		buf.WriteByte(byte(len(part)))
		buf.WriteString(part)
	}
	buf.WriteByte(0) // End of QNAME

	binary.Write(buf, binary.BigEndian, uint16(1)) // QTYPE: A (1)
	binary.Write(buf, binary.BigEndian, uint16(1)) // QCLASS: IN (1)

	return buf.Bytes()
}
