package mobile

import (
	"context"
	"encoding/json"
	"encoding/binary"
	"fmt"
	"log"
	"math/rand"
	"sync"
	"time"
	"os"
//	"sort"
	"net"
	"strings"
	"net/http"
	"net/url"
	"syscall"
	"sync/atomic"
	"strconv"
	"sort"
	
	"github.com/Starling226/vaydns-vpn/bridge"
	"github.com/Starling226/vaydns-vpn/vaydns/client"
//	"github.com/Starling226/vaydns-vpn/f35"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu              sync.Mutex
	activeCtx       context.Context
	activeCancel    context.CancelFunc
	cancel          context.CancelFunc
	activeWg        *sync.WaitGroup // POINTER prevents the reuse panic!
	isRunning       bool
	activeSocksPort int
	activeProxyURL  string
	wg         sync.WaitGroup // Used to ensure threads fully exit before StopVpn returns
	ProxyRxBytes uint64
	ProxyTxBytes uint64	
	engineLock      sync.Mutex
	activeSSHProxy  *SSHProxyManager
)

// SocketProtector allows the Go code to call Android's VpnService.protect()
type SocketProtector interface {
	Protect(fd int) bool
}

func init() {
	rand.Seed(time.Now().UnixNano())
}

/**
 * StartVpn: The main entry point for Android System-Wide VPN.
*/
 
// translateFakeToReal securely translates UI IPs to backend IPs while preserving ports and DoH URLs
func translateFakeToReal(input string) string {
	input = strings.TrimSpace(input)
	if input == "" {
		return ""
	}

	// 1. Guardrail for DoH URLs (e.g., https://10.250.0.0/dns-query)
	if strings.HasPrefix(input, "http://") || strings.HasPrefix(input, "https://") {
		parsedUrl, err := url.Parse(input)
		if err != nil {
			// Fallback if URL parsing fails
			return GetRealResolver(input)
		}

		// Extract just the IP/Host from the URL
		host := parsedUrl.Hostname()
		port := parsedUrl.Port() // Might be empty

		// Translate the Host
		realHost := GetRealResolver(host)

		// Re-attach the port if it existed in the URL (e.g., https://10.250.0.0:8443/...)
		if port != "" {
			parsedUrl.Host = net.JoinHostPort(realHost, port)
		} else {
			parsedUrl.Host = realHost
		}

		finalUrl := parsedUrl.String()
		return finalUrl
	}

	// 2. Try to split standard UDP/DoT IPs and Ports
	host, port, err := net.SplitHostPort(input)
	if err != nil {
		// No port was provided by the user! Translate the raw IP and return it.
		realHost := GetRealResolver(input)
		return realHost
	}

	// 3. Port was attached, translate the host and strictly preserve the user's port
	realHost := GetRealResolver(host)
	finalAddress := net.JoinHostPort(realHost, port)

	return finalAddress
}
 
// StartVpn: The main entry point for Android System-Wide VPN.
func StartVpn(
	fd int, 
	isDefault bool, 
	configIndex int64,
	useMultiDomains bool,
	udp string,
	tcp string,
	doh string, 
	dot string,
	baseDohUrl string,
	customDomain string, 
	customPubkey string, 
	recordType string, 
	idleTimeout string, 
	KeepAlive string, 
	clientIDSize int,
	mtu int,
	compatDnstt bool, 
	useAuth bool, 
	protocol string,     
	authProtocol string,
	ssMethod string, 
	user string, 
	pass string, 
	protector SocketProtector,
) string {
	mu.Lock()

	if activeCancel != nil {
		log.Printf("VAY_DEBUG: Killing old session context")
		activeCancel()
		time.Sleep(100 * time.Millisecond)
	}

	newFd, err := syscall.Dup(fd)
	if err != nil {
		mu.Unlock()
		return "Error: Failed to dup FD"
	}

	activeWg = &sync.WaitGroup{}
	activeCtx, activeCancel = context.WithCancel(context.Background())
	isRunning = true

	wg := activeWg
	ctx := activeCtx
			
//	fmt.Printf("VAY_DEBUG: mtu %d\n",mtu)
				
	activeSocksPort = 10000 + rand.Intn(40000)
	internalSocks := fmt.Sprintf("127.0.0.1:%d", activeSocksPort)

	atomic.StoreUint64(&client.ProxyRxBytes, 0)
	atomic.StoreUint64(&client.ProxyTxBytes, 0)
	
	mu.Unlock()

	// --- THE NATIVE VAULT LOGIC ---
	domainToUse := customDomain
	pubkeyToUse := customPubkey

	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
		pubkeyToUse = getDefaultConfigPubkey(configIndex)
		
		recordType = GetDefaultConfigRecordType(configIndex)
		idleTimeout = GetDefaultConfigIdleTimeout(configIndex)
		KeepAlive = GetDefaultConfigKeepAlive(configIndex)
		clientIDSize = int(GetDefaultConfigClientIdSize(configIndex))
		compatDnstt = GetDefaultConfigDnsttCompatible(configIndex)
		
		// Fetch actual credentials from vault using internal lowercase getters
		user = getDefaultConfigUser(configIndex)
		pass = getDefaultConfigPass(configIndex)
		ssMethod = GetDefaultConfigMethod(configIndex)
		
		// Automatically separate the old mixed string from the Native Vault
		nativeProto := GetDefaultConfigProtocol(configIndex)
		if nativeProto == "ssh" || nativeProto == "shadowsocks" || nativeProto == "ss" {
			protocol = "socks5"
			authProtocol = nativeProto
		} else if nativeProto == "http" || nativeProto == "https" {
			protocol = "http"
			authProtocol = "socks"
		} else {
			protocol = "socks5"
			authProtocol = "socks"
		}
		
		// Re-evaluate auth boolean based on vault contents
		useAuth = (user != "" || pass != "")
	}

    if !useMultiDomains {
		parts := strings.Split(domainToUse, ",")
		if len(parts) > 0 {
			domainToUse = strings.TrimSpace(parts[0])
		}
	}
	
	realUdp := translateMultipathFakeToReal(udp)
	realTcp := translateMultipathFakeToReal(tcp)
	realDoh := translateMultipathFakeToReal(doh)
	realDot := translateMultipathFakeToReal(dot)
			
	tCfg := bridge.TunnelConfig{
		BaseDohURL:       baseDohUrl,
		UdpAddr:          realUdp,
		TcpAddr:          realTcp,
		DohURL:           realDoh,
		DotAddr:          realDot,
		Domain:           domainToUse,
		ListenAddr:       internalSocks,
		LogLevel:         "error",
//		LogLevel:         "info",
		UtlsDistribution: "chrome",
		RecordType:       recordType,
		PubkeyHex:        pubkeyToUse,
		Protector:        protector,
		IdleTimeout:      idleTimeout,
		KeepAlive:        KeepAlive,
		UDPTimeout:       "1s",
		ClientIDSize:     clientIDSize,
		MTU:              mtu,
		CompatDnstt:      compatDnstt,
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
			log.Printf("VAY_DEBUG: Tunnel Error: %v", err)
		}
	}()

	// 4. BUILD THE PROXY URL WITH AUTHENTICATION

	// Fallback to socks5 if proxy protocol is empty
	if protocol == "" || protocol == "socks" {
		protocol = "socks5"
	}

	// The base transport scheme (http or socks5)
	scheme := protocol

	// MASTER GUARDRAIL: Override the scheme if the user selected an advanced Auth Protocol
	if useAuth {
		if authProtocol == "ssh" {
			if user == "" || user == "none" {
				log.Printf("VAY_DEBUG: SSH selected but no username provided. Falling back to %s.", scheme)
				authProtocol = "socks"
			} else {
				scheme = "ssh"
			}
		} else if authProtocol == "shadowsocks" || authProtocol == "ss" {
			if pass == "" || pass == "none" {
				log.Printf("VAY_DEBUG: Shadowsocks selected but no password provided. Falling back to %s.", scheme)
				authProtocol = "socks"
			} else {
				scheme = "ss"
			}
		} else {
			authProtocol = "socks"
		}
	} else {
		authProtocol = "none"
	}

	proxyURL := &url.URL{
		Scheme: scheme, // This correctly passes "http", "socks5", "ssh", or "shadowsocks" to tun2socks!
		Host:   internalSocks,
	}

	var sshKeyPath string

	// Process credentials based on the explicit authProtocol
	if useAuth {
		if authProtocol == "shadowsocks" || authProtocol == "ss" {
			if ssMethod == "" || ssMethod == "none" {
				ssMethod = "chacha20-ietf-poly1305"
			}
			proxyURL.User = url.UserPassword(ssMethod, pass)
		} else if authProtocol == "ssh" {
			isPrivKey := strings.Contains(pass, "-----BEGIN")
			cleanPass := pass
			if isPrivKey {
				cleanPass = FormatSSHKey(pass)
			}

			// 1. Boot our custom SSH/SOCKS5 Translator
			// internalSocks is the VayDNS bridge address (e.g. 127.0.0.1:34021)
			log.Printf("VAY_DEBUG: Booting custom SSH Translator...")
			manager, err := StartSSHClient(internalSocks, user, cleanPass, isPrivKey)
			
			if err != nil {
				log.Printf("VAY_DEBUG: Fatal SSH Error: %v", err)
				return fmt.Sprintf("Error: SSH Handshake Failed - %v", err)
			}

			// Save it globally so we can shut it down later
			mu.Lock()
			activeSSHProxy = manager
			mu.Unlock()

			// 2. Redirect tun2socks to our NEW local SOCKS5 server!
			scheme = "socks5"
			proxyURL.Scheme = "socks5"
			proxyURL.Host = fmt.Sprintf("127.0.0.1:%d", manager.ProxyPort)
			proxyURL.User = nil // Our local translator doesn't need auth from tun2socks
			
			log.Printf("VAY_DEBUG: SSH Translator active. tun2socks redirecting to %s", proxyURL.Host)
			
		} else if authProtocol == "socks" {

			// This covers standard SOCKS5 and HTTP Authentication
			if user != "" && user != "none" {
				if pass != "" && pass != "none" {
					proxyURL.User = url.UserPassword(user, pass)
				} else {
					proxyURL.User = url.User(user)
				}
			}
		}
	}

	if scheme == "ss" {
		proxyURL.User = url.UserPassword(ssMethod, pass)
	}
	proxyString := proxyURL.String()
	
			
	mu.Lock()
	activeProxyURL = proxyString
	mu.Unlock()
				
	// 5. START TUN2SOCKS ENGINE
	wg.Add(1)
	go func() {
		defer wg.Done()

		time.Sleep(300 * time.Millisecond)

		key := &engine.Key{
			Proxy:    proxyString,
			Device:   fmt.Sprintf("fd://%d", newFd),
			MTU:      1232,
			LogLevel: "silent",
		}
		engine.Insert(key)
		
		safeURL := *proxyURL
		if safeURL.User != nil {
			// Mask both the username and password
			safeURL.User = url.UserPassword("MASKED", "MASKED")
		}
//		log.Printf("VAY_DEBUG: Tun2Socks Engine starting on FD %d with proxy %s://MASKED:MASKED@%s", newFd, scheme, internalSocks)
		log.Printf("VAY_DEBUG: Tun2Socks Engine starting on FD %d with proxy %s", newFd, safeURL.String())			

		engine.Start()
	}()

	// 6. SHUTDOWN WATCHER
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-ctx.Done()
		log.Printf("VAY_DEBUG: Shutting down engine...")
		engine.Stop()

		mu.Lock()
		if activeSSHProxy != nil {
			activeSSHProxy.Stop()
			activeSSHProxy = nil
		}
		mu.Unlock()
		
		if sshKeyPath != "" {
			os.Remove(sshKeyPath)
			log.Printf("VAY_DEBUG: Removed temporary SSH key file.")
		}

		log.Printf("VAY_DEBUG: Native engine fully dead.")
	}()

	return fmt.Sprintf("Success: VPN Started on %s", internalSocks)
}

// StartProxy: Starts the DNS tunnel and local SOCKS5 proxy WITHOUT Android VPN routing.
func StartProxy(
	isDefault bool, configIndex int64, useMultiDomains bool, udp string, tcp string, doh string, dot string, baseDohUrl string,
	customDomain string, customPubkey string, recordType string, idleTimeout string,
	KeepAlive string, clientIDSize int, mtu int, compatDnstt bool, useAuth bool,
	protocol string, ssMethod string, user string, pass string, customPort int,
) string {
	mu.Lock()

	if activeCancel != nil {
		log.Printf("VAY_DEBUG: Killing old session context")
		activeCancel()
		time.Sleep(100 * time.Millisecond)
	}

	// Set the port from the user
	activeSocksPort = customPort

	proxyAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(customPort))
			
// PRE-CHECK: Ensure the port is not already in use
//	l, err := net.Listen("tcp", proxyAddr)
	l, err := net.Listen("tcp", proxyAddr)
	if err != nil {
		mu.Unlock()
//		return fmt.Sprintf("Error|Port %d is already in use. Please select another.", customPort)
		return fmt.Sprintf("Error|%v", err)
	}
	l.Close() // Release it immediately so the tunnel can use it

	activeSocksPort = customPort
	activeWg = &sync.WaitGroup{}
	activeCtx, activeCancel = context.WithCancel(context.Background())
	isRunning = true

	wg := activeWg
	ctx := activeCtx
	
	atomic.StoreUint64(&ProxyRxBytes, 0)
	atomic.StoreUint64(&ProxyTxBytes, 0)
		
	mu.Unlock()

	// Fetch Configs
	domainToUse := customDomain
	pubkeyToUse := customPubkey

	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
		pubkeyToUse = getDefaultConfigPubkey(configIndex)
		recordType = GetDefaultConfigRecordType(configIndex)
		idleTimeout = GetDefaultConfigIdleTimeout(configIndex)
		KeepAlive = GetDefaultConfigKeepAlive(configIndex)
		clientIDSize = int(GetDefaultConfigClientIdSize(configIndex))
		compatDnstt = GetDefaultConfigDnsttCompatible(configIndex)
	}

    if !useMultiDomains {
		parts := strings.Split(domainToUse, ",")
		if len(parts) > 0 {
			domainToUse = strings.TrimSpace(parts[0])
		}
	}
	
	realUdp := translateMultipathFakeToReal(udp)
    realTcp := translateMultipathFakeToReal(tcp)
    realDoh := translateMultipathFakeToReal(doh)
    realDot := translateMultipathFakeToReal(dot)
    	    	
	// Note: Protector is nil because Proxy Mode doesn't need to bypass Android's VpnService
	tCfg := bridge.TunnelConfig{
		BaseDohURL:       baseDohUrl,
		UdpAddr:          realUdp,
		TcpAddr:          realTcp,
		DohURL:           realDoh,
		DotAddr:          realDot,
		Domain:           domainToUse,
//		ListenAddr:       internalSocks,
		ListenAddr:       proxyAddr,
		LogLevel:         "error",
		UtlsDistribution: "chrome",
		RecordType:       recordType,
		PubkeyHex:        pubkeyToUse,
		Protector:        nil, 
		IdleTimeout:      idleTimeout,
		KeepAlive:        KeepAlive,
		UDPTimeout:       "1s",
		ClientIDSize:     clientIDSize,
		MTU:              mtu,
		CompatDnstt:      compatDnstt,
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
			log.Printf("VAY_DEBUG: Proxy Tunnel Error: %v", err)
		}
	}()

	log.Printf("VAY_DEBUG: Proxy started on %s", proxyAddr)
	// We skip the tun2socks engine entirely in Proxy Mode!
	return fmt.Sprintf("Success|%s", proxyAddr)
}

/**
 * StopVpn: Cleans up all resources safely without deadlocks.
*/

func StopVpn() string {
	mu.Lock()
	if activeCancel == nil {
		mu.Unlock()
		return "Not running"
	}

	log.Printf("VAY_DEBUG: [STOP] Native signal sent")
	activeCancel() 
	
	tempWg := activeWg
	activeCancel = nil 
	activeWg = nil    
	isRunning = false
	mu.Unlock()

	// THE FIX: Wait, but don't wait forever.
	if tempWg != nil {
		done := make(chan struct{})
		go func() {
			tempWg.Wait()
			close(done)
		}()

		select {
		case <-done:
			log.Printf("VAY_DEBUG: [STOP] All threads exited cleanly.")
		case <-time.After(1000 * time.Millisecond):
			// If bridge.RunTunnel is stuck in its UDP loop, we force-exit
			log.Printf("VAY_DEBUG: [STOP] Shutdown timed out. Proceeding anyway.")
		}
	}

	return "Stopped"
}

// translateMultipathFakeToReal handles strings like "8.8.8.8,1.1.1.1"
func translateMultipathFakeToReal(input string) string {
	parts := strings.Split(input, ",")
	var translated []string
	for _, p := range parts {
		res := translateFakeToReal(p)
		if res != "" {
			translated = append(translated, res)
		}
	}
	return strings.Join(translated, ",")
}

func VerifyTunnel() string {
	mu.Lock()
	port := activeSocksPort
	running := isRunning
	appCtx := activeCtx
	pUrl := activeProxyURL
	mu.Unlock()

	if !running || port == 0 || appCtx == nil {
		return "Fail: VPN not running"
	}

//	proxyURL, _ := url.Parse(fmt.Sprintf("socks5://127.0.0.1:%d", port))
	proxyURL, err := url.Parse(pUrl)
	if err != nil {
		return "Fail: Invalid proxy URL"
	}	
	
// Go's standard HTTP client cannot ping through Shadowsocks directly.
	if proxyURL.Scheme != "http" && proxyURL.Scheme != "socks5" {
		log.Printf("VAY_DEBUG: Bypassing VerifyTunnel ping for unsupported scheme: %s", proxyURL.Scheme)
		return "Success" // Assume success and let tun2socks handle the connection
	}	
	
	client := &http.Client{
		Transport: &http.Transport{
			Proxy: http.ProxyURL(proxyURL),
		},
		Timeout: 30 * time.Second,
	}

	req, err := http.NewRequest("HEAD", "https://1.1.1.1", nil)
	if err != nil {
		return "Fail: " + err.Error()
	}

	resp, err := client.Do(req)
	if err != nil {
		return "Fail: " + err.Error()
	}
	defer resp.Body.Close()

	return "Success"
}

// GetProxyStats returns the current RX and TX bytes separated by a pipe.
func GetProxyStats() string {
	rx := atomic.LoadUint64(&client.ProxyRxBytes)
	tx := atomic.LoadUint64(&client.ProxyTxBytes)
	return fmt.Sprintf("%d|%d", rx, tx)
}

// FormatSSHKey ensures the SSH private key is perfectly formatted with newlines,
// even if the Android UI squashed it into a single line or replaced newlines with spaces.
func FormatSSHKey(raw string) string {
	// 1. Replace literal "\n" strings that might have been escaped by JSON/Kotlin
	raw = strings.ReplaceAll(raw, "\\n", "\n")

	// 2. Find the header and footer boundaries
	beginIdx := strings.Index(raw, "-----BEGIN")
	endIdx := strings.Index(raw, "-----END")

	// If we can't find standard boundaries, just return it trimmed
	if beginIdx == -1 || endIdx == -1 || beginIdx >= endIdx {
		return strings.TrimSpace(raw) + "\n"
	}

	// 3. Extract the parts safely
	headerEnd := strings.Index(raw[beginIdx:], "KEY-----")
	if headerEnd == -1 {
		return strings.TrimSpace(raw) + "\n"
	}
	headerEnd += beginIdx + 8 // Add length of "KEY-----"

	header := strings.TrimSpace(raw[beginIdx:headerEnd])
	footer := strings.TrimSpace(raw[endIdx:])
	body := raw[headerEnd:endIdx]

	// 4. Sanitize the body (remove ALL spaces, tabs, and newlines)
	body = strings.ReplaceAll(body, " ", "")
	body = strings.ReplaceAll(body, "\t", "")
	body = strings.ReplaceAll(body, "\n", "")
	body = strings.ReplaceAll(body, "\r", "")

	// 5. Reconstruct the key with strict newlines (64 chars per line is standard for PEM)
	var formattedBody strings.Builder
	for i := 0; i < len(body); i += 64 {
		end := i + 64
		if end > len(body) {
			end = len(body)
		}
		formattedBody.WriteString(body[i:end])
		formattedBody.WriteString("\n")
	}

	// Return the mathematically perfect PEM string
	return header + "\n" + formattedBody.String() + footer + "\n"
}

// SyncPreScanResolvers wraps StartF35Scan to synchronously test and sort resolvers.
func SyncPreScanResolvers(
	isDefault bool, configIndex int64,
	resolversList string, dnsMode string, domain string, pubkey string, baseDohUrl string, 
	proxyType string, authProtocol string, user string, pass string, ssMethod string,
	recordType string, idleTimeout string, keepAlive string, clientIdSize int,
	lightE2EEnabled bool, workers int, tunnelWait int, probeTimeout int, udpTimeout int, retries int,
) string {
	parts := strings.Split(resolversList, ",")
	if len(parts) <= 1 {
		return resolversList // Skip scan if there's only 1 resolver
	}

	// 1. Empty the queue first to ensure we only read fresh results
	resultMu.Lock()
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	mtu := 0
	engineQuickScan := false

	log.Printf("VAY_DEBUG: [Pre-Scan] Triggering background scan for %d %s resolvers...", len(parts), dnsMode)

	StartF35Scan(
		isDefault, configIndex, dnsMode, domain, pubkey, resolversList,
		baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType,
		idleTimeout, keepAlive, clientIdSize, mtu, workers, tunnelWait, udpTimeout,
		probeTimeout, retries, lightE2EEnabled, engineQuickScan,
	)

	// 3. Wait for scan to finish (Timeout safety: 15s probe + 3s wait + 1s buffer)
//	timeout := time.After(19 * time.Second)
	maxWait := time.Duration(probeTimeout + tunnelWait + 1000) * time.Millisecond
	timeout := time.After(maxWait)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

waitLoop:
	for {
		select {
		case <-timeout:
			log.Printf("VAY_DEBUG: [Pre-Scan] Timed out waiting for scan engine.")
			break waitLoop
		case <-ticker.C:
			scanMu.Lock()
			status := scanStatus
			scanMu.Unlock()
			if status == "finished" || status == "error" {
				break waitLoop
			}
		}
	}

	// 4. Parse the results from the bridge's resultQueue
	resultMu.Lock()
	resList := append([]string{}, resultQueue...)
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	if len(resList) == 0 {
		log.Printf("VAY_DEBUG: [Pre-Scan] All dead. Falling back to default list.")
		return resolversList
	}

	type rankedRes struct {
		addr string
		lat  int64
	}
	var valid []rankedRes

	for _, item := range resList {
		var j map[string]interface{}
		if err := json.Unmarshal([]byte(item), &j); err == nil {
			addr, ok1 := j["resolver"].(string)
			latFloat, ok2 := j["latency_ms"].(float64)
			if ok1 && ok2 && latFloat > 0 {
				valid = append(valid, rankedRes{addr, int64(latFloat)})
			}
		}
	}

	if len(valid) == 0 {
		return resolversList
	}

	// 5. Sort by lowest latency
	sort.Slice(valid, func(i, j int) bool {
		return valid[i].lat < valid[j].lat
	})

	var sorted []string
	for _, v := range valid {
		log.Printf("VAY_DEBUG: [Pre-Scan] LIVE: %s -> %vms", v.addr, v.lat)
		sorted = append(sorted, v.addr)
	}

	return strings.Join(sorted, ",")
}

// Returns latency in milliseconds, or -1 if the server is dead/timed out.
// PingServer performs a synchronous latency test for a single server using StartF35Scan.
// PingServer performs a synchronous latency test for a single server using StartF35Scan.
func PingServer(
	isDefault bool, configIndex int64,
	address string, dnsMode string, domain string, pubkey string, baseDohUrl string,
	proxyType string, authProtocol string, user string, pass string, ssMethod string,
	recordType string, idleTimeout string, keepAlive string, clientIdSize int,
	lightE2EEnabled bool, workers int, tunnelWait int, probeTimeout int, udpTimeout int, retries int,
) int64 {
	address = strings.TrimSpace(address)
	if address == "" {
		return -1
	}

	// 1. Empty the shared queue first to ensure we only read fresh results
	resultMu.Lock()
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	// Hardcoded engine-specific params
	mtu := 0
	engineQuickScan := false

	log.Printf("VAY_DEBUG: [Ping] Triggering f35 scan for %s...", address)

	// 2. StartF35Scan handles the Native Vault and SSH formatting internally!
	StartF35Scan(
		isDefault, configIndex, dnsMode, domain, pubkey, address,
		baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType,
		idleTimeout, keepAlive, clientIdSize, mtu, workers, tunnelWait, udpTimeout,
		probeTimeout, retries, lightE2EEnabled, engineQuickScan,
	)

	// 3. Wait for scan to finish (Timeout safety buffer calculated dynamically)
	maxWait := time.Duration(probeTimeout + tunnelWait + 1000) * time.Millisecond
	timeout := time.After(maxWait)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

waitLoop:
	for {
		select {
		case <-timeout:
			log.Printf("VAY_DEBUG: [Ping] Timed out waiting for scan engine.")
			break waitLoop
		case <-ticker.C:
			scanMu.Lock()
			status := scanStatus
			scanMu.Unlock()
			if status == "finished" || status == "error" {
				break waitLoop
			}
		}
	}

	// 4. Parse the results from the bridge's resultQueue
	resultMu.Lock()
	resList := append([]string{}, resultQueue...)
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	if len(resList) == 0 {
		log.Printf("VAY_DEBUG: [Ping] Dead or timed out.")
		return -1
	}

	var finalLatency int64 = -1

	for _, item := range resList {
		var j map[string]interface{}
		if err := json.Unmarshal([]byte(item), &j); err == nil {
			addr, ok1 := j["resolver"].(string)
			latFloat, ok2 := j["latency_ms"].(float64)
			// Match the address to prevent reading stray results
			if ok1 && ok2 && strings.Contains(addr, address) && latFloat > 0 {
				finalLatency = int64(latFloat)
			}
		}
	}

	log.Printf("VAY_DEBUG: [Ping] Result for %s -> %vms", address, finalLatency)
	return finalLatency
}

// CheckHealthyDomains tests a comma-separated list of domains against a specific resolver.
// It automatically attempts a fast UDP ping first. If the DPI firewall drops all UDP traffic,
// it autonomously falls back to a TCP ping before returning the results to Android.
// getQType converts a string record type to its official DNS integer value
func getQType(recordType string) uint16 {
	switch strings.ToLower(strings.TrimSpace(recordType)) {
	case "a": return 1
	case "ns": return 2
	case "cname": return 5
	case "null": return 10
	case "mx": return 15
	case "txt": return 16
	case "aaaa": return 28
	case "srv": return 33
	case "caa": return 257
	default: return 16 // Fallback to TXT
	}
}

// CheckHealthyDomains tests a list of domains against a resolver using the specific record type.
//Altough is quick, but quickly dropped by DPI, so not used anymore
func CheckHealthyDomainsQuick(useMultiDomains bool, isDefault bool, configIndex int64, domains string, resolverIP string, recordType string) string {
	domainToUse := domains

	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
	}

   fmt.Printf("VAY_DEBUG: [Domain Scanner] Entering CheckHealthyDomains with domainToUse: '%s', resolver: '%s', type: '%s'\n", domainToUse, resolverIP, recordType)	

	if !useMultiDomains {
		parts := strings.Split(domainToUse, ",")
		if len(parts) > 0 {
			domainToUse = strings.TrimSpace(parts[0])
		}
	}
	 fmt.Printf("VAY_DEBUG: [Domain Scanner] Final target: '%s', MultiDomains: %v, isDefault: %v\n", domainToUse, useMultiDomains, isDefault)
//	fmt.Printf("VAY_DEBUG: [Domain Scanner] Entering CheckHealthyDomains with domainToUse: '%s', resolver: '%s', type: '%s'\n", domainToUse, resolverIP, recordType)

	rawDomains := strings.Split(domainToUse, ",")
	
	if !strings.Contains(resolverIP, ":") {
		resolverIP = resolverIP + ":53"
	}

	qtype := getQType(recordType)

	// Build the raw DNS query bytes manually
	buildQuery := func(domain string) []byte {
		id := uint16(rand.Intn(65535))
		buf := make([]byte, 12)
		binary.BigEndian.PutUint16(buf[0:2], id)
		binary.BigEndian.PutUint16(buf[2:4], 0x0100) // Flags: Standard Query, Recursion Desired
		binary.BigEndian.PutUint16(buf[4:6], 1)      // QDCOUNT = 1
		
		// Encode the QNAME
		for _, part := range strings.Split(domain, ".") {
			buf = append(buf, byte(len(part)))
			buf = append(buf, []byte(part)...)
		}
		buf = append(buf, 0) // Root label
		
		// Encode QTYPE and QCLASS (IN)
		qtypeBytes := make([]byte, 2)
		binary.BigEndian.PutUint16(qtypeBytes, qtype)
		buf = append(buf, qtypeBytes...)
		buf = append(buf, 0x00, 0x01) 
		
		return buf
	}

	runScan := func(protocol string) []string {
		var healthy []string
		var mu sync.Mutex
		var wg sync.WaitGroup

		for _, d := range rawDomains {
			cleanDomain := strings.TrimSpace(d)
			if cleanDomain == "" {
				continue
			}

			wg.Add(1)
			go func(domain string) {
				defer wg.Done()
				query := buildQuery(domain)
				
				d := net.Dialer{Timeout: 2000 * time.Millisecond}
				conn, err := d.Dial(protocol, resolverIP)
				if err != nil {
					log.Printf("VAY_DEBUG: [Domain Scanner] %s failed to connect via %s: %v", domain, protocol, err)
					return
				}
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(2000 * time.Millisecond))

				// Send the query
				if protocol == "tcp" {
					// TCP requires a 2-byte length prefix
					tcpQuery := make([]byte, 2+len(query))
					binary.BigEndian.PutUint16(tcpQuery[0:2], uint16(len(query)))
					copy(tcpQuery[2:], query)
					_, err = conn.Write(tcpQuery)
				} else {
					_, err = conn.Write(query)
				}

				if err != nil {
					return
				}

				// Read the response
				respBuf := make([]byte, 512)
				_, err = conn.Read(respBuf)

				// If we get ANY read response (even an NXDOMAIN or 0 answers), the packet survived the DPI!
				if err == nil {
					mu.Lock()
					healthy = append(healthy, domain)
					mu.Unlock()
					log.Printf("VAY_DEBUG: [Domain Scanner] %s passed via %s", domain, protocol)
				} else {
					if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
						log.Printf("VAY_DEBUG: [Domain Scanner] %s TIMED OUT via %s", domain, protocol)
					} else {
						log.Printf("VAY_DEBUG: [Domain Scanner] %s read error via %s: %v", domain, protocol, err)
					}
				}
			}(cleanDomain)
		}
		wg.Wait()
		return healthy
	}

	log.Printf("VAY_DEBUG: [Domain Scanner] Testing domains via UDP...")
	results := runScan("udp")

	if len(results) == 0 {
		log.Printf("VAY_DEBUG: [Domain Scanner] UDP blocked or timed out. Falling back to TCP...")
		results = runScan("tcp")
	} else {
		log.Printf("VAY_DEBUG: [Domain Scanner] UDP scan successful.")
	}

	return strings.Join(results, ",")
}

