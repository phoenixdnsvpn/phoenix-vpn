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
func PingDirectServer(isDefault bool, configIndex int64, customIP string, configType string, protocol string, globalDnsServer string, getServerIpFromDomain bool, targetCDN string, runtimeVlessIP string) int64 {
	var targetIP string
	var targetPort int = 443
	
	if isDefault {
		actualProtocol := strings.ToLower(protocol)

		if actualProtocol == "hysteria2" {
			targetIP = getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
			targetPort = getHysteriaServerPort(configIndex)
		} else if actualProtocol == "reality-tcp" {
			targetIP = getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
			targetPort = getRealityServerPort(configIndex)
		} else if actualProtocol == "reality-xhttp" {
			targetIP = getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
			serverPortStr := getXhttpPort(configIndex)
			targetPort, err := strconv.Atoi(serverPortStr)
			if err != nil || targetPort == 0 {
				targetPort = 2053
			}				
		} else if actualProtocol == "vless-ws" || actualProtocol == "vless-httpupgrade" || actualProtocol == "vless-grpc" {
			// Priority 2: Trust the global routing hierarchy
			targetIP = GetTargetIP(configIndex, actualProtocol, globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
						
			targetPort = getVlessServerPort(configIndex)			
		} else {
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
	}

	if targetIP == "" {
		return -1
	}

	// =========================================================
	// METHOD 1: Multi-Port TCP Racing
	// =========================================================
	ports := []int{targetPort}
	if targetPort != 443 { ports = append(ports, 443) }
	if targetPort != 80 { ports = append(ports, 80) }
	if targetPort != 22 { ports = append(ports, 22) }

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
			
			conn, err := net.DialTimeout("tcp", addr, 1500*time.Millisecond)
			latency := time.Since(start).Milliseconds()

			if err == nil && conn != nil {
				conn.Close()
				resChan <- pingRes{latency, port}
				return
			}
			if err != nil && strings.Contains(strings.ToLower(err.Error()), "refused") {
				resChan <- pingRes{latency, port}
				return
			}
		}(p)
	}

	go func() {
		wg.Wait()
		close(resChan)
	}()

	var bestLatency int64 = 99999
	var winningPort int = -1

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
	ctx, cancel := context.WithTimeout(context.Background(), 3000*time.Millisecond)
	defer cancel()

	cmd := exec.CommandContext(ctx, "/system/bin/ping", "-c", "1", "-W", "2", targetIP)
	out, err := cmd.Output()
	
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
				return int64(lat)
			}
		}
	}
	return -1
}

// PingAllDirectConfigs securely pings all direct protocols in parallel.
func PingAllDirectConfigs(tasksJson string, globalDnsServer string, getServerIpFromDomain bool) string {
	var tasks []map[string]interface{}
	if err := json.Unmarshal([]byte(tasksJson), &tasks); err != nil {
		return "{}"
	}

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
			
			// JSON numbers unmarshal as float64 by default in maps
			configIndexFloat, _ := task["config_index"].(float64)
			configIndex := int64(configIndexFloat)
			
			configType, _ := task["config_type"].(string)
			if configType == "" { configType = "vaydns" }
			if configType == "vaydns" { return } 

			serverIP, _ := task["server_ip"].(string)
			protocol, _ := task["protocol"].(string)
			
			// Extract the newly injected variables
			vlessWsIp, _ := task["vless_ws_ip"].(string)
			targetCDN, _ := task["target_cdn"].(string)

			// Pass the dynamically extracted CDN and IP directly to the dialer
			latency := PingDirectServer(isDefault, configIndex, serverIP, configType, protocol, globalDnsServer, getServerIpFromDomain, targetCDN, vlessWsIp)
			
			if latency > 0 {
				resultsMu.Lock()
				resultsMap[id] = latency
				resultsMu.Unlock()
			}
		}(t)
	}

	wg.Wait()
	resBytes, err := json.Marshal(resultsMap)
	if err != nil { return "{}" }
	return string(resBytes)
}

// PingDirectServerLayer7 performs a true Layer 7 (TLS/HTTP) latency check.
func PingDirectServerLayer7(isDefault bool, configIndex int64, customIP string, configType string, protocol string, customSni string, customPath string, globalDnsServer string, getServerIpFromDomain bool, targetCDN string, runtimeVlessIP string) int64 {
	var targetIP string
	var targetPort int = 443
	var sni string

	actualProtocol := strings.ToLower(protocol)
	log.Printf("VAY_DEBUG: [L7 Ping] START -> Protocol: %s, isDefault: %v, Index: %d, CustomIP: %s, CustomSNI: %s, CDN: %s", actualProtocol, isDefault, configIndex, customIP, customSni, targetCDN)

	// =================================================================
	// 1. EXTRACTION & ROUTING
	// =================================================================
	if isDefault {
		if actualProtocol == "hysteria2" || actualProtocol == "vless-grpc" {		
			log.Printf("VAY_DEBUG: [L7 Ping] Redirecting %s to Layer 4", actualProtocol)
			return PingDirectServer(isDefault, configIndex, customIP, configType, protocol, globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
		} else if actualProtocol == "vless-ws" || actualProtocol == "vless-httpupgrade" {
			// Priority 1: Use the IP passed from Kotlin
			targetIP = GetTargetIP(configIndex, actualProtocol, globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
							
			targetPort = getVlessServerPort(configIndex)
			
			// Set the correct SNI based on CDN
			if actualProtocol == "vless-httpupgrade" {
				sni = getHttpupgradeDomain(configIndex)
			} else {
				sni = getWsDomain(configIndex)
				if strings.ToLower(targetCDN) == "amazon" { sni = getAwsCdnDomain(configIndex) }
			}
		} else if actualProtocol == "reality-tcp" {
			targetIP = getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
			targetPort = getRealityServerPort(configIndex)
			sni = getRealityDomain(configIndex)            
		} else if actualProtocol == "reality-xhttp" {
			targetIP = getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
			serverPortStr := getXhttpPort(configIndex)
			targetPort, err := strconv.Atoi(serverPortStr)
			if err != nil || targetPort == 0 { targetPort = 2053 }
			sni = getRealityDomain(configIndex)  
		} else {
			return -1
		}
	} else {
		targetIP = customIP
		if strings.Contains(targetIP, ":") {
			parts := strings.Split(targetIP, ":")
			targetIP = parts[0]
			if p, err := strconv.Atoi(parts[1]); err == nil { targetPort = p }
		}
		sni = customSni
		if actualProtocol == "hysteria2" || actualProtocol == "vless-grpc" {		
			log.Printf("VAY_DEBUG: [L7 Ping] Redirecting Custom direct protocol to Layer 4")
			return PingDirectServer(isDefault, configIndex, customIP, configType, protocol, globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
		}
	}

	if targetIP == "" {
		return -1
	}

	// =================================================================
	// 2. LAYER 7 WEBSOCKET / HTTP-UPGRADE EXECUTION
	// =================================================================
	if actualProtocol == "vless-ws" || actualProtocol == "vless-httpupgrade" {
		log.Printf("VAY_DEBUG: [L7 Ping] Executing True L7 Ping for %s", actualProtocol)
		
		wsPath := "/"
		hostDomain := sni
		if isDefault {
			if actualProtocol == "vless-httpupgrade" {
				wsPath = getHttpupgradePath(configIndex)
			} else {
				wsPath = getWsPath(configIndex)
			}
		} else if customPath != "" {
			wsPath = customPath
		}

		address := net.JoinHostPort(targetIP, strconv.Itoa(targetPort))
		start := time.Now()

		dialCtx, dialCancel := context.WithTimeout(context.Background(), 3000*time.Millisecond)
		defer dialCancel()
		var d net.Dialer
		conn, err := d.DialContext(dialCtx, "tcp", address)
		
		if err == nil && conn != nil {
			tlsConfig := &tls.Config{
				ServerName:         hostDomain,
				InsecureSkipVerify: true,
			}
			tlsConn := tls.Client(conn, tlsConfig)
			
			if err := tlsConn.HandshakeContext(dialCtx); err == nil {
				reqStr := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n", wsPath, hostDomain)
				tlsConn.Write([]byte(reqStr))

				tlsConn.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))
				buf := make([]byte, 1024)
				n, readErr := tlsConn.Read(buf)

				if readErr == nil && n > 0 {
					resp := string(buf[:n])
					if strings.Contains(resp, "HTTP/1.1 101") || strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 403") || strings.Contains(resp, "HTTP/1.1 404") {
						latency := time.Since(start).Milliseconds()
						tlsConn.Close()
						log.Printf("VAY_DEBUG: [L7 Ping] SUCCESS -> Latency: %dms", latency)
						return latency
					}
				}
				tlsConn.Close()
			} else {
				tlsConn.Close()
			}
		}

		log.Printf("VAY_DEBUG: [L7 Ping] L7 validation failed. Falling back to Layer 4 TCP Ping...")
		return PingDirectServer(isDefault, configIndex, customIP, configType, protocol, globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
	}

	// =================================================================
	// 3. LAYER 7 REALITY EXECUTION
	// =================================================================
	address := net.JoinHostPort(targetIP, strconv.Itoa(targetPort))
	start := time.Now()

	dialCtx, dialCancel := context.WithTimeout(context.Background(), 4000*time.Millisecond)
	defer dialCancel()

	var d net.Dialer
	conn, err := d.DialContext(dialCtx, "tcp", address)
	if err != nil || conn == nil {
		return -1 
	}
	
	tlsConfig := &tls.Config{
		InsecureSkipVerify: true, 
	}
	if sni != "" {
		tlsConfig.ServerName = sni
	}

	tlsConn := tls.Client(conn, tlsConfig)
	err = tlsConn.HandshakeContext(dialCtx)
	if err != nil {
		tlsConn.Close()
		return -1 
	}
	
	tlsConn.Close()
	latency := time.Since(start).Milliseconds()
	log.Printf("VAY_DEBUG: [L7 Ping] SUCCESS [Layer 7 Reality/TCP] -> Latency: %d ms", latency)
	return latency
}

// PingAllDirectConfigsLayer7 securely pings all direct protocols in parallel using L7.
func PingAllDirectConfigsLayer7(tasksJson string, globalDnsServer string, getServerIpFromDomain bool) string {
	var tasks []map[string]interface{}
	if err := json.Unmarshal([]byte(tasksJson), &tasks); err != nil {
		return "{}"
	}

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

			vlessWsIp, _ := task["vless_ws_ip"].(string)
			targetCDN, _ := task["target_cdn"].(string)
			
			latency := PingDirectServerLayer7(isDefault, configIndex, serverIP, configType, protocol, customDomain, "/", globalDnsServer, getServerIpFromDomain, targetCDN, vlessWsIp)
			
			if latency > 0 {
				resultsMu.Lock()
				resultsMap[id] = latency
				resultsMu.Unlock()
			}
		}(t)
	}

	wg.Wait()
	resBytes, err := json.Marshal(resultsMap)
	if err != nil { return "{}" }
	return string(resBytes)
}

// PingBestDirectIP securely races a comma-separated list of IPs and returns the fastest one using Layer 4.
func PingBestDirectIP(isDefault bool, configIndex int64, ipList string, configType string, protocol string, globalDnsServer string, getServerIpFromDomain bool, targetCDN string) string {
	parts := strings.Split(ipList, ",")
	var cleanIPs []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" { cleanIPs = append(cleanIPs, p) }
	}

	if len(cleanIPs) == 0 { return "" }

	type pingRes struct {
		ip      string
		latency int64
	}
	resChan := make(chan pingRes, len(cleanIPs))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 20)

	for _, ip := range cleanIPs {
		wg.Add(1)
		go func(targetIP string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			latency := PingDirectServer(isDefault, configIndex, targetIP, configType, protocol, globalDnsServer, getServerIpFromDomain, targetCDN, targetIP)
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
		if r.latency > 0 && r.latency < bestLatency {
			bestLatency = r.latency
			bestIP = r.ip
		}
	}

	if bestIP != "" { return fmt.Sprintf("%s|%d", bestIP, bestLatency) }
	return ""
}

// GetFastestCloudflareIP races a list of IPs using a full Layer 7 (TLS + HTTP/WS) handshake.
func GetFastestCloudflareIP(isDefault bool, configIndex int64, ipList string, customDomain string, targetCDN string, activeProtocol string) string {
	parts := strings.Split(ipList, ",")
	var cleanIPs []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" { cleanIPs = append(cleanIPs, p) }
	}

	if len(cleanIPs) == 0 { return "" }

	domainToUse := customDomain
	pathToUse := "/"
	actualProtocol := strings.ToLower(activeProtocol)

	if isDefault {
		if actualProtocol == "vless-grpc" {
			if strings.ToLower(targetCDN) == "amazon" {
				if d := getAwsCdnDomain(configIndex); d != "" { domainToUse = d }
			} else {
				if d := getGrpcDomain(configIndex); d != "" { domainToUse = d }
			}
			
			// Format the gRPC service name as an HTTP path
			serviceName := getGrpcServiceName(configIndex)
			if !strings.HasPrefix(serviceName, "/") {
				pathToUse = "/" + serviceName
			} else {
				pathToUse = serviceName
			}

		} else if actualProtocol == "vless-httpupgrade" {
			if d := getHttpupgradeDomain(configIndex); d != "" { domainToUse = d }
			if p := getHttpupgradePath(configIndex); p != "" { pathToUse = p }

		} else {
			// vless-ws (Default)
			if strings.ToLower(targetCDN) == "amazon" {
				if d := getAwsCdnDomain(configIndex); d != "" { domainToUse = d }
			} else {
				if d := getWsDomain(configIndex); d != "" { domainToUse = d }
			}
			if p := getWsPath(configIndex); p != "" { pathToUse = p }
		}
	}

	if domainToUse == "" { return "" }
	if strings.Contains(domainToUse, ":") {
		domainToUse = strings.Split(domainToUse, ":")[0]
	}

	type pingRes struct {
		ip      string
		latency int64
	}
	resChan := make(chan pingRes, len(cleanIPs))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 20) 
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	for _, ip := range cleanIPs {
		wg.Add(1)
		sem <- struct{}{}

		go func(targetIP string) {
			defer wg.Done()
			defer func() { <-sem }()

			address := net.JoinHostPort(targetIP, "443")
			start := time.Now()

			dialCtx, dialCancel := context.WithTimeout(ctx, 2000*time.Millisecond)
			defer dialCancel()

			var d net.Dialer
			conn, err := d.DialContext(dialCtx, "tcp", address)
			if err != nil || conn == nil { return }

			tlsConfig := &tls.Config{
				ServerName:         domainToUse,
				InsecureSkipVerify: false,
			}
			
			// FIX 1: gRPC requires HTTP/2 ALPN negotiation
			if actualProtocol == "vless-grpc" {
				tlsConfig.NextProtos = []string{"h2"}
			}
			
			tlsConn := tls.Client(conn, tlsConfig)
			err = tlsConn.HandshakeContext(dialCtx)
			if err != nil {
				tlsConn.Close()
				return
			}

			// FIX 2: If gRPC, the successful TLS Handshake is our Layer 7 validation!
			if actualProtocol == "vless-grpc" {
				lat := time.Since(start).Milliseconds() 
				resChan <- pingRes{targetIP, lat}
				tlsConn.Close()
				return
			}

			// For vless-ws and vless-httpupgrade, continue with the HTTP/1.1 payload
			reqStr := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n", pathToUse, domainToUse)
			_, err = tlsConn.Write([]byte(reqStr))

			if err == nil {
				tlsConn.SetReadDeadline(time.Now().Add(1000 * time.Millisecond))
				buf := make([]byte, 1024)
				n, readErr := tlsConn.Read(buf)

				if readErr == nil && n > 0 {
					resp := string(buf[:n])
					if strings.Contains(resp, "HTTP/1.1 101") || strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 403") || strings.Contains(resp, "HTTP/1.1 404") {
						lat := time.Since(start).Milliseconds() 
						resChan <- pingRes{targetIP, lat}
					}
				}
			}
			tlsConn.Close()
		}(ip)
	}

	go func() {
		wg.Wait()
		close(resChan)
	}()

	var bestIP string
	var bestLatency int64 = 999999

	for r := range resChan {
		if r.latency > 0 && r.latency < bestLatency {
			bestLatency = r.latency
			bestIP = r.ip
		}
	}

	if bestIP != "" { return fmt.Sprintf("%s|%d", bestIP, bestLatency) }
	return ""
}

// GetFastestCloudflareIP races a list of IPs using a full Layer 7 (TLS + HTTP/WS) handshake. Not used anymore
func GetFastestCloudflareIPWs(isDefault bool, configIndex int64, ipList string, customDomain string, targetCDN string) string {
	parts := strings.Split(ipList, ",")
	var cleanIPs []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" { cleanIPs = append(cleanIPs, p) }
	}

	if len(cleanIPs) == 0 { return "" }

	domainToUse := customDomain
	pathToUse := "/vayws"

	if isDefault {
		if strings.ToLower(targetCDN) == "amazon" {
			if d := getAwsCdnDomain(configIndex); d != "" { domainToUse = d }
		} else {
			if d := getWsDomain(configIndex); d != "" { domainToUse = d }
		}
		
		if p := getWsPath(configIndex); p != "" { pathToUse = p }
	}

	if domainToUse == "" { return "" }
	if strings.Contains(domainToUse, ":") {
		domainToUse = strings.Split(domainToUse, ":")[0]
	}

	type pingRes struct {
		ip      string
		latency int64
	}
	resChan := make(chan pingRes, len(cleanIPs))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 20) 
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	for _, ip := range cleanIPs {
		wg.Add(1)
		sem <- struct{}{}

		go func(targetIP string) {
			defer wg.Done()
			defer func() { <-sem }()

			address := net.JoinHostPort(targetIP, "443")
			start := time.Now()

			dialCtx, dialCancel := context.WithTimeout(ctx, 2000*time.Millisecond)
			defer dialCancel()

			var d net.Dialer
			conn, err := d.DialContext(dialCtx, "tcp", address)
			if err != nil || conn == nil { return }

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
				tlsConn.SetReadDeadline(time.Now().Add(1000 * time.Millisecond))
				buf := make([]byte, 1024)
				n, readErr := tlsConn.Read(buf)

				if readErr == nil && n > 0 {
					resp := string(buf[:n])
					if strings.Contains(resp, "HTTP/1.1 101") || strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 403") || strings.Contains(resp, "HTTP/1.1 404") {
						lat := time.Since(start).Milliseconds() 
						resChan <- pingRes{targetIP, lat}
					}
				}
			}
			tlsConn.Close()
		}(ip)
	}

	go func() {
		wg.Wait()
		close(resChan)
	}()

	var bestIP string
	var bestLatency int64 = 999999

	for r := range resChan {
		if r.latency > 0 && r.latency < bestLatency {
			bestLatency = r.latency
			bestIP = r.ip
		}
	}

	if bestIP != "" { return fmt.Sprintf("%s|%d", bestIP, bestLatency) }
	return ""
}
