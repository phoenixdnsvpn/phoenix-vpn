package mobile

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (

	cfCIDRs = []string{
		"103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
		"104.16.0.0/13", "104.24.0.0/14", "108.162.192.0/18",
		"131.0.72.0/22", "141.101.64.0/18", "162.158.0.0/15",
		"172.64.0.0/13", "173.245.48.0/20", "188.114.96.0/20",
		"190.93.240.0/20", "197.234.240.0/22", "198.41.128.0/17",				
	}
	
	cfScanCancel context.CancelFunc
	cfScanMu     sync.Mutex
)

var amazonCIDRs = []string{
	"13.32.0.0/15", "13.35.0.0/16", "13.113.203.0/24",
	"13.124.199.0/24", "13.224.0.0/14", "13.249.0.0/16",
	"15.158.0.0/16", "15.193.0.0/16", "18.160.0.0/15",
	"18.164.0.0/15", "18.172.0.0/15", "52.46.0.0/18",
	"52.84.0.0/15", "52.124.128.0/17", "52.222.128.0/17",
	"54.182.0.0/16", "54.192.0.0/16", "54.230.0.0/16",
	"54.239.128.0/18", "54.239.192.0/19", "54.240.128.0/18",
	"64.252.64.0/18", "71.152.0.0/17", "99.84.0.0/16",
	"108.138.0.0/15", "130.176.0.0/16", "143.204.0.0/16",
	"204.246.164.0/22", "204.246.168.0/22", "204.246.174.0/23",
	"204.246.176.0/20", "205.251.192.0/19", "205.251.249.0/24",
	"205.251.250.0/23", "205.251.252.0/23", "205.251.254.0/24",
	"216.137.32.0/19", 
}

type CFScannerCallback interface {
	OnUpdate(result string)
}

func getRandomIP(cidr string) (string, error) {
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
	hostNum := rand.Intn(1<<hostBits - 2) + 1

	resultIP := make(net.IP, 4)
	copy(resultIP, ip)

	for i := 3; i >= 0; i-- {
		resultIP[i] += byte(hostNum >> ((3 - i) * 8))
	}
	return resultIP.String(), nil
}
/*
func generateCloudflareIPs(count int) []string {
	ips := make(map[string]bool)
	var result []string

	for len(result) < count {
		cidr := cfCIDRs[rand.Intn(len(cfCIDRs))]
		ip, err := getRandomIP(cidr)
		if err == nil && !ips[ip] {
			ips[ip] = true
			result = append(result, ip)
		}
	}
	return result
}*/

func StopCloudflareScanner() {
	cfScanMu.Lock()
	defer cfScanMu.Unlock()
	if cfScanCancel != nil {
		cfScanCancel()
		cfScanCancel = nil
	}
}

func generateIPs(count int, targetCDN string) []string {
	var cidrs []string
	
	// Route the IP generation based on the dynamic JSON value passed from Kotlin
	switch strings.ToLower(targetCDN) {
	case "amazon":
		cidrs = amazonCIDRs
	case "cloudflare":
		cidrs = cfCIDRs
	// case "fastly":
	// 	cidrs = fastlyCIDRs // (Easy to add in the future)
	default:
		cidrs = cfCIDRs // Safe fallback if the JSON contains an unsupported CDN
	}

	ips := make(map[string]bool)
	var result []string

// Safety check to prevent panics if a future CIDR list is empty
	if len(cidrs) == 0 {
		return result
	}
	
	for len(result) < count {
		cidr := cidrs[rand.Intn(len(cidrs))]
		ip, err := getRandomIP(cidr)
		if err == nil && !ips[ip] {
			ips[ip] = true
			result = append(result, ip)
		}
	}
	return result
}

func RunCloudflareScanner(isDefault bool, configIndex int64, requestedCount int64, targetCDN string, cb CFScannerCallback) string {
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

	defer StopCloudflareScanner()
   
	// 1. Resolve SNI
	domainToUse := ""
	pathToUse := "/vayws"

	if isDefault {
		// APPLY THE TARGET CDN FORK HERE
		if strings.ToLower(targetCDN) == "amazon" {
			if d := getAwsCdnDomain(configIndex); d != "" {
				domainToUse = d
			}
		} else {
			if d := getWsDomain(configIndex); d != "" {
				domainToUse = d
			}
		}
		
		if p := getWsPath(configIndex); p != "" {
			pathToUse = p
		}
	}

	if domainToUse == "" {
		return ""
	}

	if strings.Contains(domainToUse, ":") {
		domainToUse = strings.Split(domainToUse, ":")[0]
	}

	// 2. Setup Batch Parameters using the selected CDN CIDR ranges
	testIPs := generateIPs(int(requestedCount), targetCDN)
	
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
				address := net.JoinHostPort(targetIP, "443")
				start := time.Now()

				dialCtx, dialCancel := context.WithTimeout(ctx, 1500*time.Millisecond)
				defer dialCancel()

				var d net.Dialer
				conn, err := d.DialContext(dialCtx, "tcp", address)
				if err != nil || conn == nil {
					return
				}

				tlsConfig := &tls.Config{
					ServerName:         domainToUse,
					InsecureSkipVerify: false,
				}
				tlsConn := tls.Client(conn, tlsConfig)
				err = tlsConn.HandshakeContext(dialCtx)
				if err != nil {
					tlsConn.Close()
					return
				}

				reqStr := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n", pathToUse, domainToUse)
				_, err = tlsConn.Write([]byte(reqStr))

				if err == nil {
					tlsConn.SetReadDeadline(time.Now().Add(800 * time.Millisecond))
					buf := make([]byte, 1024)
					n, readErr := tlsConn.Read(buf)

					if readErr == nil && n > 0 {
						resp := string(buf[:n])
						if strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 101") {
							lat := time.Since(start).Milliseconds()
							atomic.AddInt32(&foundCount, 1)
							resultsChan <- scanRes{ip: targetIP, latency: lat}
						}
					}
				}
				tlsConn.Close()
			}(ip)
		}

		// Wait for batch to finish
		wg.Wait()

		// 4. Drain, Sort, and Mask Results
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

		maskedDisplay := make([]CFResult, len(validResults))
		for i, v := range validResults {
			if i == 0 {
				maskedDisplay[i] = v
			} else {
				parts := strings.Split(v.IP, ".")
				if len(parts) == 4 {
					maskedDisplay[i] = CFResult{IP: fmt.Sprintf("%s.%s.%s.%s", parts[0], parts[1], parts[2], parts[3]), Latency: v.Latency}
				} else {
					maskedDisplay[i] = CFResult{IP: "***.***.***.***", Latency: v.Latency}
				}
			}
		}

		resBytes, _ := json.Marshal(maskedDisplay)
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

/*func RunCloudflareScanner(isDefault bool, configIndex int64, requestedCount int64, cb CFScannerCallback) string {
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

	defer StopCloudflareScanner()
   
	// 1. Resolve SNI
	domainToUse := ""
	pathToUse := "/vayws"

	if isDefault {
		if d := getWsDomain(configIndex); d != "" {
			domainToUse = d
		}
		if p := getWsPath(configIndex); p != "" {
			pathToUse = p
		}
	}

	if domainToUse == "" {
		return ""
	}

	if strings.Contains(domainToUse, ":") {
		domainToUse = strings.Split(domainToUse, ":")[0]
	}

	// 2. Setup Batch Parameters
	testIPs := generateCloudflareIPs(int(requestedCount))
	
	// INCREASED: Now processes 512 IPs before pushing an update
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
				address := net.JoinHostPort(targetIP, "443")
				start := time.Now()

				dialCtx, dialCancel := context.WithTimeout(ctx, 1500*time.Millisecond)
				defer dialCancel()

				var d net.Dialer
				conn, err := d.DialContext(dialCtx, "tcp", address)
				if err != nil || conn == nil {
					return
				}

				tlsConfig := &tls.Config{
					ServerName:         domainToUse,
					InsecureSkipVerify: false,
				}
				tlsConn := tls.Client(conn, tlsConfig)
				err = tlsConn.HandshakeContext(dialCtx)
				if err != nil {
					tlsConn.Close()
					return
				}

				reqStr := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n", pathToUse, domainToUse)
				_, err = tlsConn.Write([]byte(reqStr))

				if err == nil {
					tlsConn.SetReadDeadline(time.Now().Add(800 * time.Millisecond))
					buf := make([]byte, 1024)
					n, readErr := tlsConn.Read(buf)

					if readErr == nil && n > 0 {
						resp := string(buf[:n])
						if strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 101") {
							lat := time.Since(start).Milliseconds()
							atomic.AddInt32(&foundCount, 1)
							resultsChan <- scanRes{ip: targetIP, latency: lat}
						}
					}
				}
				tlsConn.Close()
			}(ip)
		}

		// Wait for batch to finish
		wg.Wait()

		// 4. Drain, Sort, and Mask Results
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

		// ANTI-CENSORSHIP: Create a masked copy for the UI
		maskedDisplay := make([]CFResult, len(validResults))
		for i, v := range validResults {
			if i == 0 {
				maskedDisplay[i] = v
			} else {
				parts := strings.Split(v.IP, ".")
				if len(parts) == 4 {
					maskedDisplay[i] = CFResult{IP: fmt.Sprintf("%s.%s.%s.%s", parts[0], parts[1], parts[2], parts[3]), Latency: v.Latency}
					// maskedDisplay[i] = CFResult{IP: fmt.Sprintf("%s.*.*.*", parts[0]), Latency: v.Latency}
				} else {
					maskedDisplay[i] = CFResult{IP: "***.***.***.***", Latency: v.Latency}
				}
			}
		}

		resBytes, _ := json.Marshal(maskedDisplay)
		currentResultStr := fmt.Sprintf("%s|%d|%d", string(resBytes), atomic.LoadInt32(&scannedCount), atomic.LoadInt32(&foundCount))

		// 5. Push Update and Pause NAT
		if end < len(testIPs) {
			if cb != nil {
				cb.OnUpdate(currentResultStr)
			}
			
			// INCREASED: Wait 30 seconds for Router NAT table to flush completely
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
}*/

// resolveDomainOverDoH safely queries a list of domains using encrypted HTTPS.
// It accepts a comma-separated string of domains, testing each one sequentially,
// and returns the first valid IP address it successfully resolves.
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
