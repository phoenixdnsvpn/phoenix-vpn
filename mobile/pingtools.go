package mobile

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// PingDirectServer securely executes a latency check entirely within the native layer.
func PingDirectServer(isDefault bool, configIndex int64, customIP string, configType string, protocol string, globalDnsServer string) int64 {
	var targetIP string
	var targetPort int = 443

	// log.Printf("VAY_DEBUG: [Native Ping] protocol: %s %v", strings.ToLower(protocol),configType)
	
	if isDefault {
		actualProtocol := strings.ToLower(protocol)

		if actualProtocol == "hysteria2" {
			targetIP = getHysteriaServerIP(configIndex, globalDnsServer)
			targetPort = getHysteriaServerPort(configIndex)
		} else if actualProtocol == "reality-tcp" {
			targetIP = getRealityServerIP(configIndex, globalDnsServer)
			targetPort = getRealityServerPort(configIndex)
		} else if actualProtocol == "reality-xhttp" {
			targetIP = getRealityServerIP(configIndex, globalDnsServer)
			serverPortStr := getXhttpPort(configIndex)
			targetPort, err := strconv.Atoi(serverPortStr)
			if err != nil || targetPort == 0 {
				targetPort = 2053 // Safe fallback if the JSON is empty or invalid
			}				
		} else if actualProtocol == "vless-ws" || actualProtocol == "vless-httpupgrade" {
			// Priority 1: Use the IP passed from Kotlin (which holds the user's custom choice or the global fallback)
			if customIP != "" && customIP != "0.0.0.0" {
				targetIP = customIP
			} else {
				// Priority 2: Fallback to the native vault memory
				targetIP = getCloudflareIP(configIndex)
			}
			targetPort = getVlessServerPort(configIndex)			
		} else {
			log.Printf("VAY_DEBUG: [Native Ping] Unknown direct protocol: %s", actualProtocol)
			return -1
		}
	} else {
		// For custom configs
		targetIP = customIP
		if strings.Contains(targetIP, ":") {
			parts := strings.Split(targetIP, ":")
			targetIP = parts[0]
			if p, err := strconv.Atoi(parts[1]); err == nil {
				targetPort = p
			}
		}
	}

	if targetIP == "" {
		return -1
	}
	log.Printf("VAY_DEBUG: XXXX : %v %v", targetIP, targetPort)
	// =========================================================
	// METHOD 1: Multi-Port TCP Racing
	// =========================================================
	// Stealth servers often drop TCP packets on their proxy ports to evade DPI.
	// We race the target port against standard open VPS ports (443, 80, 22) 
	// to guarantee a latency response without waiting for sequential timeouts.
	
	ports := []int{targetPort}
	if targetPort != 443 { ports = append(ports, 443) }
	if targetPort != 80 { ports = append(ports, 80) }
	if targetPort != 22 { ports = append(ports, 22) } // SSH is almost always open on a Linux VPS

	type pingRes struct {
		latency int64
		port    int
	}
	resChan := make(chan pingRes, len(ports))
	var wg sync.WaitGroup

	for _, p := range ports {
		wg.Add(1)
		go func(port int) {
			defer wg.Done()
			addr := net.JoinHostPort(targetIP, strconv.Itoa(port))
			start := time.Now()
			
			// Dial with a timeout to prevent hanging
			conn, err := net.DialTimeout("tcp", addr, 1500*time.Millisecond)
			latency := time.Since(start).Milliseconds()

			// If the port is Open
			if err == nil && conn != nil {
				conn.Close()
				resChan <- pingRes{latency, port}
				return
			}
			// If the port is actively Refused (Closed but responsive)
			if err != nil && strings.Contains(strings.ToLower(err.Error()), "refused") {
				resChan <- pingRes{latency, port}
				return
			}
		}(p)
	}

	// Wait for all TCP races to finish in the background
	go func() {
		wg.Wait()
		close(resChan)
	}()

	var bestLatency int64 = 99999
	var winningPort int = -1

	// Extract the fastest successful response
	for r := range resChan {
		if r.latency > 0 && r.latency < bestLatency {
			bestLatency = r.latency
			winningPort = r.port
		}
	}

	if bestLatency < 99999 {
		log.Printf("VAY_DEBUG: [Native Ping] SUCCESS (TCP %d won the race) -> Latency: %dms", winningPort, bestLatency)
		return bestLatency
	}

	// =========================================================
	// METHOD 2: ICMP Ping Fallback
	// =========================================================
	// log.Printf("VAY_DEBUG: [Native Ping] ALL TCP ports timed out. Attempting ICMP...")
	
   // : Wrap the external OS command in a strict 3-second execution context
	ctx, cancel := context.WithTimeout(context.Background(), 3000*time.Millisecond)
	defer cancel()

	cmd := exec.CommandContext(ctx, "/system/bin/ping", "-c", "1", "-W", "2", targetIP)
	out, err := cmd.Output()
	
	// If the first command failed, and the 3-second context HAS NOT expired, try the generic binary
	if err != nil && ctx.Err() == nil {
		cmd = exec.CommandContext(ctx, "ping", "-c", "1", "-W", "2", targetIP)
		out, err = cmd.Output()
	}

	if err == nil {
		outStr := string(out)
		re := regexp.MustCompile(`time=([0-9.]+)\s*ms`)
		matches := re.FindStringSubmatch(outStr)
		
		if len(matches) > 1 {
			if lat, err := strconv.ParseFloat(matches[1], 64); err == nil {
				// log.Printf("VAY_DEBUG: [Native Ping] SUCCESS (ICMP) -> Latency: %dms", int64(lat))
				return int64(lat)
			}
		}
	}

	log.Printf("VAY_DEBUG: [Native Ping] ALL METHODS FAILED. Returning -1.")
	return -1
}

// PingAllDirectConfigs securely pings all direct protocols in parallel.
// It parses the exact same JSON array but strictly targets direct nodes.
func PingAllDirectConfigs(tasksJson string, globalDnsServer string) string {
	var tasks []PingTask
	if err := json.Unmarshal([]byte(tasksJson), &tasks); err != nil {
		return "{}"
	}

	var wg sync.WaitGroup
	resultsMu := sync.Mutex{}
	resultsMap := make(map[string]int64)

	// Semaphore to limit concurrent TCP connections to 8 to prevent OS socket exhaustion
	sem := make(chan struct{}, 8)

	for _, task := range tasks {
		// 1. Fetch the discriminator
		configType := task.ConfigType
		if configType == "" {
			configType = "vaydns"
		}
		//if task.IsDefault {
		//	configType = GetDefaultConfigType(task.ConfigIndex)
		//}

		// 2. Skip VayDNS configs (The heavy scanner handles those)
		if configType == "vaydns" {
			continue
		}
			
		wg.Add(1)
		sem <- struct{}{} // Acquire concurrency token

		go func(t PingTask, cType string) {
			defer wg.Done()
			defer func() { <-sem }() // Release concurrency token

			// Execute the secure native ping. 
			// t.ServerIP will securely be "" for official configs, triggering the native vault lookup.
			latency := PingDirectServer(t.IsDefault, t.ConfigIndex, t.ServerIP, cType, t.Protocol, globalDnsServer)
			
			if latency > 0 {
				resultsMu.Lock()
				resultsMap[t.ID] = latency
				resultsMu.Unlock()
			}
		}(task, configType)
	}

	wg.Wait()
	
	resBytes, err := json.Marshal(resultsMap)
	if err != nil {
		return "{}"
	}
	return string(resBytes)
}

// PingDirectServerLayer7 performs a true Layer 7 (TLS/HTTP) latency check.
func PingDirectServerLayer7(isDefault bool, configIndex int64, customIP string, configType string, protocol string, customSni string, customPath string, globalDnsServer string) int64 {
	var targetIP string
	var targetPort int = 443
	var sni string
//	var wsPath string

	actualProtocol := strings.ToLower(protocol)
	
	log.Printf("VAY_DEBUG: [L7 Ping] START -> Protocol: %s, isDefault: %v, Index: %d, CustomIP: %s, CustomSNI: %s", actualProtocol, isDefault, configIndex, customIP, customSni)

	// 1. Extract Routing & Layer 7 Data (SNI / Path)
	if isDefault {
        if actualProtocol == "hysteria2" || actualProtocol == "vless-ws" || actualProtocol == "vless-httpupgrade" {
			log.Printf("VAY_DEBUG: [L7 Ping] Redirecting Hysteria2 to Layer 4 (UDP Obfuscated)")
			return PingDirectServer(isDefault, configIndex, customIP, configType, protocol, globalDnsServer)
		} else if actualProtocol == "reality-tcp" {
			targetIP = getRealityServerIP(configIndex, globalDnsServer)
			targetPort = getRealityServerPort(configIndex)
			sni = getRealityDomain(configIndex)            
		} else if actualProtocol == "reality-xhttp" {
			targetIP = getRealityServerIP(configIndex, globalDnsServer)
			serverPortStr := getXhttpPort(configIndex)
			targetPort, err := strconv.Atoi(serverPortStr)
			if err != nil || targetPort == 0 {
				targetPort = 2053 // Safe fallback if the JSON is empty or invalid
			}
				
			sni = getRealityDomain(configIndex)  
		} else {
			log.Printf("VAY_DEBUG: [L7 Ping] FAILED -> Unknown default protocol: %s", actualProtocol)
			return -1
		}
	} else {
		targetIP = customIP
		if strings.Contains(targetIP, ":") {
			parts := strings.Split(targetIP, ":")
			targetIP = parts[0]
			if p, err := strconv.Atoi(parts[1]); err == nil {
				targetPort = p
			}
		}
		sni = customSni
//		wsPath = customPath
		
        if actualProtocol == "hysteria2" || actualProtocol == "vless-ws" || actualProtocol == "vless-httpupgrade" {		
			log.Printf("VAY_DEBUG: [L7 Ping] Redirecting Custom direct protocol to Layer 4")
			return PingDirectServer(isDefault, configIndex, customIP, configType, protocol, globalDnsServer)
		}
	}

	if targetIP == "" {
		log.Printf("VAY_DEBUG: [L7 Ping] FAILED -> Target IP is completely empty")
		return -1
	}

	address := net.JoinHostPort(targetIP, strconv.Itoa(targetPort))
	// log.Printf("VAY_DEBUG: [L7 Ping] Dialing -> %s with SNI: %s and Path: %s", address, sni, wsPath)
	
	start := time.Now()

	// 2. Dial TCP (Layer 4)
	// INCREASED TO 4000ms: Gives heavily throttled DPI firewalls time to process the TCP SYN
	dialCtx, dialCancel := context.WithTimeout(context.Background(), 4000*time.Millisecond)
	defer dialCancel()

	var d net.Dialer
	conn, err := d.DialContext(dialCtx, "tcp", address)
	if err != nil || conn == nil {
		log.Printf("VAY_DEBUG: [L7 Ping] FAILED [Layer 4] -> TCP connection refused or timed out: %v", err)
		return -1 
	}
	
	log.Printf("VAY_DEBUG: [L7 Ping] SUCCESS [Layer 4] -> TCP Connected")

	// 3. Upgrade to TLS (Layer 7)
	tlsConfig := &tls.Config{
		InsecureSkipVerify: true, 
	}
	// Inject the dedicated SNI to pass the server's strict routing rules
	if sni != "" {
		tlsConfig.ServerName = sni
	}

	tlsConn := tls.Client(conn, tlsConfig)
	err = tlsConn.HandshakeContext(dialCtx)
	if err != nil {
		log.Printf("VAY_DEBUG: [L7 Ping] FAILED [Layer 7 TLS] -> Handshake rejected by server or DPI: %v", err)
		tlsConn.Close()
		return -1 
	}
	
	log.Printf("VAY_DEBUG: [L7 Ping] SUCCESS [Layer 7 TLS] -> Handshake complete!")

	// For Reality: A successful TLS Handshake confirms the engine is alive.
	tlsConn.Close()
	latency := time.Since(start).Milliseconds()
	log.Printf("VAY_DEBUG: [L7 Ping] SUCCESS [Layer 7 Reality/TCP] -> Latency: %d ms", latency)
	return latency
}

// PingAllDirectConfigsLayer7 securely pings all direct protocols in parallel using L7.
func PingAllDirectConfigsLayer7(tasksJson string, globalDnsServer string) string {
	var tasks []map[string]interface{}
	if err := json.Unmarshal([]byte(tasksJson), &tasks); err != nil {
		log.Printf("VAY_DEBUG: [L7 Ping] FAILED to unmarshal tasks JSON: %v", err)
		return "{}"
	}

	log.Printf("VAY_DEBUG: [L7 Ping] Dispatching %d configs for Layer 7 scanning...", len(tasks))

	var wg sync.WaitGroup
	resultsMu := sync.Mutex{}
	resultsMap := make(map[string]int64)

	sem := make(chan struct{}, 8)

	for _, t := range tasks {
		wg.Add(1)
		sem <- struct{}{}

		go func(task map[string]interface{}) {
			defer wg.Done()
			defer func() { <-sem }() 

			id, _ := task["id"].(string)
			if id == "" { return }

			isDefault, _ := task["is_default"].(bool)
			configIndexFloat, _ := task["config_index"].(float64)
			configIndex := int64(configIndexFloat)
			configType, _ := task["config_type"].(string)
			if configType == "" { configType = "vaydns" }
			if configType == "vaydns" { return } 

			serverIP, _ := task["server_ip"].(string)
			protocol, _ := task["protocol"].(string)
			customDomain, _ := task["custom_domain"].(string)

			latency := PingDirectServerLayer7(isDefault, configIndex, serverIP, configType, protocol, customDomain, "/", globalDnsServer)
			
			if latency > 0 {
				resultsMu.Lock()
				resultsMap[id] = latency
				resultsMu.Unlock()
			}
		}(t)
	}

	wg.Wait()
	
	resBytes, err := json.Marshal(resultsMap)
	if err != nil {
		return "{}"
	}
	
	log.Printf("VAY_DEBUG: [L7 Ping] Scan Complete. Found %d active servers.", len(resultsMap))
	return string(resBytes)
}

// PingBestDirectIP securely races a comma-separated list of IPs and returns the fastest one using Layer 4.
// Returns format: "IP|Latency" or "" if all fail.

func PingBestDirectIP(isDefault bool, configIndex int64, ipList string, configType string, protocol string, globalDnsServer string) string {
	parts := strings.Split(ipList, ",")
	var cleanIPs []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			cleanIPs = append(cleanIPs, p)
		}
	}

	if len(cleanIPs) == 0 {
		return ""
	}

	type pingRes struct {
		ip      string
		latency int64
	}
	resChan := make(chan pingRes, len(cleanIPs))
	var wg sync.WaitGroup
	// Limit concurrency to 20 to prevent OS socket exhaustion
	sem := make(chan struct{}, 20)

	for _, ip := range cleanIPs {
		wg.Add(1)
		go func(targetIP string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			latency := PingDirectServer(isDefault, configIndex, targetIP, configType, protocol, globalDnsServer)
			if latency > 0 {
				resChan <- pingRes{targetIP, latency}
			}
		}(ip)
	}

	go func() {
		wg.Wait()
		close(resChan)
	}()

	var bestIP string
	var bestLatency int64 = 999999

	for r := range resChan {
		// log.Printf("VAY_DEBUG: IP Latency %v %v",r.ip,r.latency)
		if r.latency > 0 && r.latency < bestLatency {
			bestLatency = r.latency
			bestIP = r.ip
		}
	}

	if bestIP != "" {
		return fmt.Sprintf("%s|%d", bestIP, bestLatency)
	}
	return ""
}

// GetFastestCloudflareIP races a list of IPs using a full Layer 7 (TLS + HTTP/WS) handshake.
func GetFastestCloudflareIP(isDefault bool, configIndex int64, ipList string, customDomain string) string {
	parts := strings.Split(ipList, ",")
	var cleanIPs []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			cleanIPs = append(cleanIPs, p)
		}
	}

	if len(cleanIPs) == 0 {
		return ""
	}

	// 1. Resolve SNI (Server Name Indication) and Path
	domainToUse := customDomain
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
	// Strip port if present in domain
	if strings.Contains(domainToUse, ":") {
		domainToUse = strings.Split(domainToUse, ":")[0]
	}

	type pingRes struct {
		ip      string
		latency int64
	}
	resChan := make(chan pingRes, len(cleanIPs))
	var wg sync.WaitGroup
	
	// Limit concurrency to prevent socket exhaustion
	sem := make(chan struct{}, 20) 

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// 2. Race the IPs concurrently
	for _, ip := range cleanIPs {
		wg.Add(1)
		sem <- struct{}{}

		go func(targetIP string) {
			defer wg.Done()
			defer func() { <-sem }()

			address := net.JoinHostPort(targetIP, "443")
			start := time.Now() // Timer Start

			dialCtx, dialCancel := context.WithTimeout(ctx, 2000*time.Millisecond)
			defer dialCancel()

			var d net.Dialer
			conn, err := d.DialContext(dialCtx, "tcp", address)
			if err != nil || conn == nil {
				return
			}

			// Perform TLS Handshake
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

			// Send HTTP 1.1 WebSocket Upgrade Request
			reqStr := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n", pathToUse, domainToUse)
			_, err = tlsConn.Write([]byte(reqStr))

			if err == nil {
				tlsConn.SetReadDeadline(time.Now().Add(1000 * time.Millisecond))
				buf := make([]byte, 1024)
				n, readErr := tlsConn.Read(buf)

				if readErr == nil && n > 0 {
					resp := string(buf[:n])
					// Cloudflare Edge Server Success Codes
					if strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 101") {
						lat := time.Since(start).Milliseconds() // Timer Stop
						resChan <- pingRes{targetIP, lat}
					}
				}
			}
			tlsConn.Close()
		}(ip)
	}

	// 3. Wait and Close
	go func() {
		wg.Wait()
		close(resChan)
	}()

	// 4. Find the Absolute Fastest
	var bestIP string
	var bestLatency int64 = 999999

	for r := range resChan {
		if r.latency > 0 && r.latency < bestLatency {
			bestLatency = r.latency
			bestIP = r.ip
		}
	}

	if bestIP != "" {
		return fmt.Sprintf("%s|%d", bestIP, bestLatency)
	}
	return ""
}


