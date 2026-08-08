package mobile

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	_ "embed"	
	"sync/atomic"
	"time"
    "bytes"
)

//go:embed cdn_data.enc
var encryptedCDNData []byte

type CDNLists struct {
	CloudXCIDRs []string `json:"cloudx_cidrs"`
	CloudYCIDRs []string `json:"cloudy_cidrs"`
	CloudZCIDRs []string `json:"cloudz_cidrs"`
}

var (
	cfScanCancel context.CancelFunc
	cfScanMu     sync.Mutex
	
	// Global variable to hold your decrypted lists in memory
	TargetCDNs CDNLists 
)

// Call this function once when scanner initializes
func LoadCDNData() error {
	// 1. Safety check for community builds where the key might be empty
	if InjectedPrivateKey == "" {
		return errors.New("no injected private key found for CDN data decryption")
	}

	// 2. Decrypt the embedded byte array using the native AES-GCM helper and injected key
	decryptedJSON, err := decryptAESGCM(encryptedCDNData, InjectedPrivateKey)
	if err != nil {
		log.Printf("VAY_DEBUG: [Scanner] AES DECRYPTION FAILED: %v", err)
		return err
	}


	// 3. Strip hidden UTF-8 BOMs or stray characters before the JSON starts
	startIdx := bytes.IndexByte(decryptedJSON, '{')
	if startIdx != -1 {
		decryptedJSON = decryptedJSON[startIdx:]
	}

	// 4. Parse the JSON into your Go struct
	err = json.Unmarshal(decryptedJSON, &TargetCDNs)
	if err != nil {
		log.Printf("VAY_DEBUG: [Scanner] JSON PARSE ERROR: %v", err)
		return err
	}

	return nil
}

type CdnScannerCallback interface {
	OnUpdate(result string)
}

// Updated to accept our custom time-seeded random generator
func getRandomIP(cidr string, rnd *rand.Rand) (string, error) {
	_, ipv4Net, err := net.ParseCIDR(cidr)
	if err != nil {
		return "", err
	}
	ip := ipv4Net.IP.To4()
	if ip == nil {
		return "", fmt.Errorf("not an ipv4 address")
	}

	ones, bits := ipv4Net.Mask.Size()
	hostBits := bits - ones
	hostNum := rnd.Intn(1<<hostBits - 2) + 1

	resultIP := make(net.IP, 4)
	copy(resultIP, ip)

	for i := 3; i >= 0; i-- {
		resultIP[i] += byte(hostNum >> ((3 - i) * 8))
	}
	return resultIP.String(), nil
}

func StopCdnScanner() {
	cfScanMu.Lock()
	defer cfScanMu.Unlock()
	if cfScanCancel != nil {
		cfScanCancel()
		cfScanCancel = nil
	}
}

func generateIPs(count int, targetCDN string) []string {
	cdnLower := strings.ToLower(targetCDN)

	// Create a localized, time-seeded random generator to ensure a unique series every run
	rnd := rand.New(rand.NewSource(time.Now().UnixNano()))

	// Ensure the CDN data is loaded before attempting to access it
	if len(TargetCDNs.CloudZCIDRs) == 0 {
		err := LoadCDNData()
		if err != nil {
			log.Printf("VAY_DEBUG: [Scanner] WARNING: Failed to load encrypted CDN data: %v", err)
		}
	}

	if cdnLower == "cloudz" {
		ips := make([]string, len(TargetCDNs.CloudZCIDRs))
		copy(ips, TargetCDNs.CloudZCIDRs) // Work on a copy to avoid modifying the global list
		
		if len(ips) > 0 {
			// Use our custom time-seeded generator to shuffle
			rnd.Shuffle(len(ips), func(i, j int) {
				ips[i], ips[j] = ips[j], ips[i]
			})

			if count > 0 && len(ips) > count {
				return ips[:count]
			}
			return ips
		}

		log.Printf("VAY_DEBUG: [Scanner] WARNING: No IP was found!")
		return []string{}
	}

	var cidrs []string
	switch cdnLower {
	case "cloudy":
		cidrs = TargetCDNs.CloudYCIDRs
	case "cloudx":
		cidrs = TargetCDNs.CloudXCIDRs
	default:
		cidrs = TargetCDNs.CloudXCIDRs
	}

	ips := make(map[string]bool)
	var result []string

	if len(cidrs) == 0 {
		return result
	}

	for len(result) < count {
		// Use our custom time-seeded generator here as well
		cidr := cidrs[rnd.Intn(len(cidrs))]
		ip, err := getRandomIP(cidr, rnd)
		if err == nil && !ips[ip] {
			ips[ip] = true
			result = append(result, ip)
		}
	}
	return result
}

func RunCdnScanner(isDefault bool, configIndex int64, requestedCount int64, targetCDN string, dialTimeoutMs int, readDeadlineMs int, cb CdnScannerCallback) string {
	cfScanMu.Lock()
	if cfScanCancel != nil {
		cfScanCancel()
	}

	if requestedCount <= 0 {
		requestedCount = 512
	}

	ctx, cancel := context.WithCancel(context.Background())
	cfScanCancel = cancel
	cfScanMu.Unlock()

	defer StopCdnScanner()

	// 1. Resolve SNI & Path
	domainToUse := ""
	pathToUse := "/"
	isWebsocket := true

	if isDefault {
		cdnLower := strings.ToLower(targetCDN)

		if cdnLower == "cloudy" {
			if d := getCloudYCdnDomain(configIndex); d != "" {
				domainToUse = d
			}
		} else if cdnLower == "cloudz" {
			if d := getXhttpCdnDomain(configIndex); d != "" {
				domainToUse = d
			}
			isWebsocket = false
		} else {
			if d := getWsDomain(configIndex); d != "" {
				domainToUse = d
			}
		}

		if cdnLower == "cloudz" {
			if p := getXhttpPath(configIndex); p != "" {
				pathToUse = p
			}
		} else {
			if p := getWsPath(configIndex); p != "" {
				pathToUse = p
			}
		}
	}

	if domainToUse == "" {
		log.Printf("VAY_DEBUG: [Scanner] ERROR: Resolved domain is EMPTY! Cannot scan without SNI.")
		return ""
	}

	if strings.Contains(domainToUse, ":") {
		domainToUse = strings.Split(domainToUse, ":")[0]
	}

	// 2. Generate target IP list
	testIPs := generateIPs(int(requestedCount), targetCDN)
//	log.Printf("VAY_DEBUG: [Scanner] STARTING SCAN -> CDN: %s | Total IPs to test: %d | SNI Domain: %s | Path: %s | Mode: %s",
//		targetCDN, len(testIPs), domainToUse, pathToUse, map[bool]string{true: "WebSocket", false: "xHTTP"}[isWebsocket])

// ADD THESE DEBUG LOGS:
	if len(testIPs) == 0 {
		log.Printf("VAY_DEBUG: [Scanner] ERROR: generateIPs returned 0 IPs for target: %s! The scan loop will be skipped.", targetCDN)
	}

//	log.Printf("VAY_DEBUG: [Scanner] STARTING SCAN -> CDN: %s | Total IPs to test: %d | SNI Domain: %s | Path: %s | Mode: %s",
//		targetCDN, len(testIPs), domainToUse, pathToUse, map[bool]string{true: "WebSocket", false: "xHTTP"}[isWebsocket])
		
	batchSize := 1024

	type scanRes struct {
		ip      string
		latency int64
	}

	type CFResult struct {
		IP      string `json:"ip"`
		Latency int64  `json:"latency"`
	}

	resultsChan := make(chan scanRes, requestedCount)
	var scannedCount int32 = 0
	var foundCount int32 = 0

	var validResults []CFResult

	// 3. Start Batch Loop
	for i := 0; i < len(testIPs); i += batchSize {
		end := i + batchSize
		if end > len(testIPs) {
			end = len(testIPs)
		}
		batchIPs := testIPs[i:end]

		var wg sync.WaitGroup
		sem := make(chan struct{}, 20)

		for _, ip := range batchIPs {
			wg.Add(1)
			sem <- struct{}{}

			go func(targetIP string) {
				defer wg.Done()
				defer func() { <-sem }()

				select {
				case <-ctx.Done():
					return
				default:
				}

				atomic.AddInt32(&scannedCount, 1)
				// log.Printf("VAY_DEBUG: [Scanner] Processing IP: %s", targetIP)

				address := net.JoinHostPort(targetIP, "443")
				start := time.Now()

				dialCtx, dialCancel := context.WithTimeout(ctx, time.Duration(dialTimeoutMs)*time.Millisecond)
				defer dialCancel()

				var d net.Dialer
				conn, err := d.DialContext(dialCtx, "tcp", address)
				if err != nil || conn == nil {
					// log.Printf("VAY_DEBUG: [Scanner] %s -> TCP Dial Failed: %v", targetIP, err)
					return
				}

				tlsConfig := &tls.Config{
					ServerName:         domainToUse,
					InsecureSkipVerify: false,
				}
				tlsConn := tls.Client(conn, tlsConfig)
				err = tlsConn.HandshakeContext(dialCtx)
				if err != nil {
					// log.Printf("VAY_DEBUG: [Scanner] %s -> TLS Handshake Failed (SNI: %s): %v", targetIP, domainToUse, err)
					tlsConn.Close()
					return
				}

				var reqStr string
				if isWebsocket {
					reqStr = fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n", pathToUse, domainToUse)
				} else {
					reqStr = fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nConnection: keep-alive\r\nUser-Agent: Mozilla/5.0\r\n\r\n", pathToUse, domainToUse)
				}

				_, err = tlsConn.Write([]byte(reqStr))
				if err != nil {
					//log.Printf("VAY_DEBUG: [Scanner] %s -> Failed to send HTTP probe: %v", targetIP, err)
					tlsConn.Close()
					return
				}

				tlsConn.SetReadDeadline(time.Now().Add(time.Duration(readDeadlineMs) * time.Millisecond))
				buf := make([]byte, 1024)
				n, readErr := tlsConn.Read(buf)

				if readErr == nil && n > 0 {
					resp := string(buf[:n])
					//Log the exact response header line for debugging in logcat
					/*firstLine := resp
					if idx := strings.Index(resp, "\r\n"); idx != -1 {
						firstLine = resp[:idx]
					}
					log.Printf("VAY_DEBUG: [Scanner] %s -> Response Received: %s", targetIP, firstLine)*/

					if strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 101") || strings.Contains(resp, "HTTP/1.1 200") || strings.Contains(resp, "HTTP/1.1 403") || strings.Contains(resp, "HTTP/1.1 404") {
						lat := time.Since(start).Milliseconds()
						atomic.AddInt32(&foundCount, 1)
						// log.Printf("VAY_DEBUG: [Scanner] SUCCESS -> Valid IP Found: %s (%dms)", targetIP, lat)
						resultsChan <- scanRes{ip: targetIP, latency: lat}
					}
				} else {
					// log.Printf("VAY_DEBUG: [Scanner] %s -> Read Timeout or Empty Response: %v", targetIP, readErr)
				}
				tlsConn.Close()
			}(ip)
		}

		wg.Wait()

	drainLoop:
		for {
			select {
			case res := <-resultsChan:
				validResults = append(validResults, CFResult{IP: res.ip, Latency: res.latency})
			default:
				break drainLoop
			}
		}

		sort.Slice(validResults, func(x, y int) bool {
			return validResults[x].Latency < validResults[y].Latency
		})
	
		resBytes, _ := json.Marshal(validResults)
		currentResultStr := fmt.Sprintf("%s|%d|%d", string(resBytes), atomic.LoadInt32(&scannedCount), atomic.LoadInt32(&foundCount))

		if end < len(testIPs) {
			if cb != nil {
				cb.OnUpdate(currentResultStr)
			}

			select {
			case <-ctx.Done():
				return currentResultStr
			case <-time.After(30 * time.Second):
			}
		} else {
			return currentResultStr
		}
	}

	return ""
}

// resolveDomainOverDoH safely queries a list of domains using encrypted HTTPS.
func resolveDomainOverDoH(rawDomains string, customDohServer string) string {
	// 1. Format the DoH URL safely (Prepared once for all domains)
	dohServer := "https://1.1.1.1/dns-query" // Ultimate Fallback
	if customDohServer != "" {
		if strings.HasPrefix(customDohServer, "http") {
			dohServer = customDohServer
		} else {
			dohServer = "https://" + customDohServer + "/dns-query"
		}
	}

	// 2. Initialize the HTTP client once to reuse connections
	client := &http.Client{Timeout: 5 * time.Second}

	// 3. Split the comma-separated domains into a list
	domainList := strings.Split(rawDomains, ",")

	// 4. Iterate through each domain and attempt to resolve it
	for _, d := range domainList {
		domain := strings.TrimSpace(d)
		if domain == "" {
			continue // Skip empty entries
		}

		// If the entry is already a raw IP address, return it instantly
		if net.ParseIP(domain) != nil {
			return domain
		}

		// Prepare the encrypted HTTP Request
		reqURL := dohServer + "?name=" + domain + "&type=A"
		req, err := http.NewRequest("GET", reqURL, nil)
		if err != nil {
			continue // Try the next domain on request build failure
		}
		req.Header.Set("Accept", "application/dns-json") // Request JSON format

		// Execute the request
		resp, err := client.Do(req)
		if err != nil {
			continue // Try the next domain if the network fails
		}

		// Read the response and explicitly close the body inside the loop
		body, err := io.ReadAll(resp.Body)
		resp.Body.Close() 
		if err != nil {
			continue
		}

		// Parse the JSON response
		var result map[string]interface{}
		if err := json.Unmarshal(body, &result); err != nil {
			continue
		}

		// Extract the IPv4 Address
		answers, ok := result["Answer"].([]interface{})
		if !ok || len(answers) == 0 {
			continue // No DNS answers for this domain, try the next one
		}

		for _, ans := range answers {
			ansMap, ok := ans.(map[string]interface{})
			if ok {
				if data, exists := ansMap["data"].(string); exists {
					if net.ParseIP(data) != nil {
						return data // Found a valid IP! Short-circuit and return immediately.
					}
				}
			}
		}
	}

	// 5. If we exhausted all domains in the list and found nothing, return empty
	return ""
}

// =====================================================================
// SECURE INTERNAL GETTERS for SERVER IP
// =====================================================================

func getServerIP(index int64, globalDnsServer string, getServerIpFromDomain bool) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	
	if !getServerIpFromDomain {
		serverIP := getServerIpAddress(index)
		if serverIP != ""{
			return serverIP
		}
	}
		
	serverIP := ""
		
	if globalDnsServer != "0.0.0.0" && globalDnsServer != "" {
		// 1. Get the domain name of your actual server		
		serverDomain := getServerDomain(index)
		// 2. Resolve the IP silently via DoH using the Global BootstrapDns variable
		serverIP = resolveDomainOverDoH(serverDomain, globalDnsServer)

		// 3. Fallbacks just in case the encrypted DNS lookup fails
		if serverIP == "" {
			serverIP = defaultConfigs[index].ServerIP
		}
		if serverIP == "" {
			domainList := strings.Split(serverDomain, ",")
			for _, d := range domainList {
				domain := strings.TrimSpace(d)
				if domain == "" {
					continue // Skip empty entries
				}
				serverIP = domain // Send the raw domain to Xray/Sing-box as a last resort
				break
			}
		}
	} else {
		serverIP = defaultConfigs[index].ServerIP
		if serverIP == "" {
			serverDomain := getServerDomain(index)
			domainList := strings.Split(serverDomain, ",")
			for _, d := range domainList {
				domain := strings.TrimSpace(d)
				if domain != "" {
					serverIP = domain
					break
				}
			}
		}
	}
		
	return serverIP 
}

// GetCloudIPCounts calculates the total number of available IP addresses
// for each CDN and returns them as a JSON string to Kotlin.
func GetCloudIPCounts() string {
	// Ensure the encrypted JSON has been decrypted and loaded into memory
	if len(TargetCDNs.CloudZCIDRs) == 0 && len(TargetCDNs.CloudXCIDRs) == 0 {
		_ = LoadCDNData()
	}

	// Helper function to calculate the total number of IPs inside a list of CIDRs
	countCIDRIPs := func(cidrs []string) int64 {
		var total int64 = 0
		for _, cidrStr := range cidrs {
			_, ipv4Net, err := net.ParseCIDR(cidrStr)
			if err != nil {
				continue // Skip invalid CIDRs
			}
			ones, bits := ipv4Net.Mask.Size()
			hostBits := bits - ones
			if hostBits >= 0 && hostBits <= 32 {
				total += int64(1) << hostBits // 2^hostBits
			}
		}
		return total
	}

	// Use a map to ensure we only count strictly unique IPs for Cloudz
	uniqueCloudZ := make(map[string]bool)
	for _, ip := range TargetCDNs.CloudZCIDRs {
		uniqueCloudZ[ip] = true
	}

	// Map the totals
	counts := map[string]int64{
		"cloudx": countCIDRIPs(TargetCDNs.CloudXCIDRs),
		"cloudy": countCIDRIPs(TargetCDNs.CloudYCIDRs),
		"cloudz": int64(len(uniqueCloudZ)),
	}

	// Convert to a JSON string for the Kotlin frontend
	resBytes, err := json.Marshal(counts)
	if err != nil {
		return `{"cloudx": 0, "cloudy": 0, "cloudz": 0}`
	}

	return string(resBytes)
}
