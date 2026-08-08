package mobile

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
)

// SniResult represents the payload sent back to Kotlin
type SniResult struct {
	Index   int    `json:"sni_index"`
	Sni     string `json:"sni"`
	Success bool   `json:"success"`
	Latency int64  `json:"latency_ms"`
	Message string `json:"message"`
}

// checkSniHealthInternal contains the core networking logic
func checkSniHealthInternal(sni string, targetIp string, serverPort int) (bool, int64, string) {
	// If getServerIP returned an empty string, fallback to DNS lookup via standard dialing
	address := targetIp
	if address == "" {
		address = fmt.Sprintf("%s:%d", sni, serverPort)
	} else if !strings.Contains(address, ":") {
		address = fmt.Sprintf("%s:%d", address, serverPort)
	}

	dialer := &net.Dialer{
		Timeout: 4 * time.Second,
	}

	startTime := time.Now()

	conn, err := dialer.Dial("tcp", address)
	if err != nil {
		return false, -1, fmt.Sprintf("TCP Dial Failed: %v", err)
	}
	defer conn.Close()

	tlsConfig := &tls.Config{
		ServerName:         sni,
		MinVersion:         tls.VersionTLS13,
		MaxVersion:         tls.VersionTLS13,
		InsecureSkipVerify: false, // Keep this false to enforce validation
	}

	tlsConn := tls.Client(conn, tlsConfig)
	tlsConn.SetDeadline(time.Now().Add(4 * time.Second))

	err = tlsConn.Handshake()
	if err != nil {
		errMsg := err.Error()
		
		// Sanitize the massive x509 certificate mismatch error to prevent UI bloat and domain leaking
		if strings.Contains(errMsg, "certificate is valid for") || strings.Contains(errMsg, "failed to verify certificate") {
			return false, -1, "TLS Handshake Failed: SNI Certificate Mismatch"
		}
		
		// For all other errors (like timeouts), return the standard truncated message
		return false, -1, fmt.Sprintf("TLS Handshake Failed: %v", errMsg)
	}

	latency := time.Since(startTime).Milliseconds()
	return true, latency, "Healthy"
}

// checkSniHealthInternal contains the core networking logic
func checkSniHealthInternal2(sni string, targetIp string, serverPort int) (bool, int64, string) {
	// If getServerIP returned an empty string, fallback to DNS lookup via standard dialing
	address := targetIp
	if address == "" {
		address = fmt.Sprintf("%s:%d", sni, serverPort)
	} else if !strings.Contains(address, ":") {
		address = fmt.Sprintf("%s:%d", address, serverPort)
	}

	dialer := &net.Dialer{
		Timeout: 4 * time.Second,
	}

	startTime := time.Now()

	conn, err := dialer.Dial("tcp", address)
	if err != nil {
		return false, -1, fmt.Sprintf("TCP Dial Failed: %v", err)
	}
	defer conn.Close()

	tlsConfig := &tls.Config{
		ServerName:         sni,
		MinVersion:         tls.VersionTLS13,
		MaxVersion:         tls.VersionTLS13,
		InsecureSkipVerify: false,
	}

	tlsConn := tls.Client(conn, tlsConfig)
	tlsConn.SetDeadline(time.Now().Add(4 * time.Second))

	err = tlsConn.Handshake()
	if err != nil {
		return false, -1, fmt.Sprintf("TLS Handshake Failed: %v", err)
	}

	latency := time.Since(startTime).Milliseconds()
	return true, latency, "Healthy"
}

// CheckAllSniPool tests ALL SNIs in the pool concurrently using the specified number of workers.
func CheckAllSniPool(configIndex int64, configType string, globalDnsServer string, getServerIpFromDomain bool, serverPort int, customIp string, workers int) string {
	ensureParsed()

	// 1. Resolve the port dynamically if empty
	finalPort := resolveServerPort(configIndex, serverPort, configType)

	// Fetch the Target IP cleanly. Prioritize customIp if provided!
	targetIp := customIp
	if targetIp == "" {
		targetIp = getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	}

	// 2. Safely copy the SNI pool from memory
	configMu.Lock()
	poolSize := len(sniPool)
	poolCopy := make([]string, poolSize)
	copy(poolCopy, sniPool)
	configMu.Unlock()

	// 3. Return an empty JSON array if the pool is empty
	if poolSize == 0 {
		return "[]"
	}

	if workers <= 0 {
		workers = 5 // Safe default
	}

	var wg sync.WaitGroup
	var mu sync.Mutex
	results := make([]SniResult, 0, poolSize)
	
	// Semaphore channel to strictly limit concurrency
	sem := make(chan struct{}, workers)

	// 4. Dispatch workers
	for i, sni := range poolCopy {
		wg.Add(1)
		sem <- struct{}{} // Block if we've reached the worker limit

		go func(idx int, sniName string) {
			defer wg.Done()
			defer func() { <-sem }() // Release the worker slot when done

			// Pass the resolved finalPort here
			success, latency, msg := checkSniHealthInternal(sniName, targetIp, finalPort)

			mu.Lock()
			results = append(results, SniResult{
				Index:   idx,
				Sni:     sniName,
				Success: success,
				Latency: latency,
				Message: msg,
			})
			mu.Unlock()
		}(i, sni)
	}

	// 5. Wait for all threads to finish
	wg.Wait()

	// 6. Sort the results back into their original Spinner index order
	sort.Slice(results, func(i, j int) bool {
		return results[i].Index < results[j].Index
	})

	// 7. Convert to JSON and return to Kotlin
	bytes, _ := json.Marshal(results)
	return string(bytes)
}

// CheckSniHealth tests a single SNI
func CheckSniHealth(sni string, configIndex int64, configType string, globalDnsServer string, getServerIpFromDomain bool, serverPort int, customIp string) string {
	// 1. Resolve the port dynamically if empty
	finalPort := resolveServerPort(configIndex, serverPort, configType)
	
	// Fetch the Target IP cleanly. Prioritize customIp if provided!
	targetIp := customIp
	if targetIp == "" {
		targetIp = getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	}
	
	// Pass the resolved finalPort here
	success, latency, msg := checkSniHealthInternal(sni, targetIp, finalPort)
	
	if success {
		return fmt.Sprintf("SUCCESS|%d|%s", latency, msg)
	}
	return fmt.Sprintf("ERROR|%s", msg)
}

// Helper function to dynamically resolve the port if it was passed as empty (0)
func resolveServerPort(configIndex int64, providedPort int, configType string) int {
	if providedPort != 0 {
		return providedPort // Use the explicit port if Kotlin provided one
	}

	configTypeLower := strings.ToLower(configType)

	if strings.Contains(configTypeLower, "vless-tcp") {
		return getRealityTcpPort(configIndex)
	} else if strings.Contains(configTypeLower, "vless-xhttp") {
		return getXhttpPort(configIndex)
	}

	return 443 // Safe default fallback
}
