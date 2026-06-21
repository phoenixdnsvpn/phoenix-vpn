package mobile

import (
	"context"
	// "crypto/tls"
	"encoding/json"
	"encoding/binary"
	"fmt"
	"log"
	"math/rand"
	"sync"
	"time"
	"os"
	"net"
	"strings"
	"net/http"
	"net/url"
	"syscall"
	"sync/atomic"
	"strconv"
	"sort"
	// "os/exec"
	// "regexp"
		
	"github.com/Starling226/vaydns-vpn/bridge"
	"github.com/Starling226/vaydns-vpn/vaydns/client"
	"github.com/xjasonlyu/tun2socks/v2/engine"
		
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	singJson "github.com/sagernet/sing/common/json"
)

var (
	mu              sync.Mutex
	activeCtx       context.Context
	activeCancel    context.CancelFunc
	cancel          context.CancelFunc
	activeWg        *sync.WaitGroup 
	isRunning       bool
	activeSocksPort int
	activeProxyURL  string
	wg         sync.WaitGroup
	ProxyRxBytes uint64
	ProxyTxBytes uint64	
	engineLock      sync.Mutex
	activeSSHProxy  *SSHProxyManager
	activeSingBox   *box.Box
	activeSingBoxCancel context.CancelFunc
	activeTunFd     int 
)

type SocketProtector interface {
	Protect(fd int) bool
}

func init() {
	rand.Seed(time.Now().UnixNano())
}

func translateFakeToReal(input string) string {
	input = strings.TrimSpace(input)
	if input == "" {
		return ""
	}

	if strings.HasPrefix(input, "http://") || strings.HasPrefix(input, "https://") {
		parsedUrl, err := url.Parse(input)
		if err != nil {
			return GetRealResolver(input)
		}

		host := parsedUrl.Hostname()
		port := parsedUrl.Port() 

		realHost := GetRealResolver(host)

		if port != "" {
			parsedUrl.Host = net.JoinHostPort(realHost, port)
		} else {
			parsedUrl.Host = realHost
		}

		return parsedUrl.String()
	}

	host, port, err := net.SplitHostPort(input)
	if err != nil {
		realHost := GetRealResolver(input)
		return realHost
	}

	realHost := GetRealResolver(host)
	return net.JoinHostPort(realHost, port)
}
 
// ExtractActiveDomain handles the logic for selecting a single domain or keeping all domains.
func ExtractActiveDomain(rawDomains string, domainIndex int64, useMultiDomains bool) string {
	if useMultiDomains {
		return rawDomains
	}
	parts := strings.Split(rawDomains, ",")
	var cleanParts []string
	for _, p := range parts {
		if strings.TrimSpace(p) != "" {
			cleanParts = append(cleanParts, strings.TrimSpace(p))
		}
	}
	if len(cleanParts) == 0 {
		return ""
	}
	
	idx := int(domainIndex)
	if idx >= 0 && idx < len(cleanParts) {
		return cleanParts[idx]
	}
	
	return cleanParts[0]
}
 
func StartVpn(
	fd int,
	engineType string,
	isDefault bool, 
	configIndex int64,
	configType string,
	useMultiDomains bool,
	domainIndex int64,
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
	vlessWsIp string,
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
	activeTunFd = newFd 

	wg := activeWg
	ctx := activeCtx
							
	activeSocksPort = 10000 + rand.Intn(20000)
	internalSocks := fmt.Sprintf("127.0.0.1:%d", activeSocksPort)

	atomic.StoreUint64(&client.ProxyRxBytes, 0)
	atomic.StoreUint64(&client.ProxyTxBytes, 0)
	
	mu.Unlock()

	domainToUse := customDomain
	pubkeyToUse := customPubkey

	//isDirectMode := (strings.ToLower(configType) == "direct")		
	configTypeLower := strings.ToLower(configType)
	activeProto := strings.ToLower(protocol)

	isDirectMode := !strings.Contains(configTypeLower, "vaydns") || 
					activeProto == "hysteria" || activeProto == "hysteria2" || 
					activeProto == "reality" || activeProto == "vless" || activeProto == "vless-ws" || 
					configTypeLower == "direct"
							
	if isDirectMode {
		engineType = "sing-box"
	}
								
    log.Printf("VAY_DEBUG: protocol: %v", protocol)
    // ==========================================
	// DYNAMIC TUN MTU (OS-Level, completely separate from VayDNS MTU)
	// ==========================================
	var finalMtu int
	if isDirectMode {
		finalMtu = 1500 // High-speed unfragmented pipe for tun2socks -> sing-box
	} else {
		finalMtu = 1232 // Safe pipe for tun2socks -> VayDNS engine
	}
	log.Printf("VAY_DEBUG: OS/TUN MTU set to %d", finalMtu)
	    
    
	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
		pubkeyToUse = getDefaultConfigPubkey(configIndex)
		recordType = GetDefaultConfigRecordType(configIndex)
		idleTimeout = GetDefaultConfigIdleTimeout(configIndex)
		KeepAlive = GetDefaultConfigKeepAlive(configIndex)
		clientIDSize = int(GetDefaultConfigClientIdSize(configIndex))
		compatDnstt = GetDefaultConfigDnsttCompatible(configIndex)
		user = getDefaultConfigUser(configIndex)
		pass = getDefaultConfigPass(configIndex)
		ssMethod = GetDefaultConfigMethod(configIndex)
		
		nativeProto := GetDefaultConfigProtocol(configIndex)
		
		if isDirectMode {
			// STRICT DIRECT MODE:
			// The `protocol` argument from Kotlin (hysteria2/vless) remains untouched.
			// Auth is disabled for direct proxies.
			authProtocol = "none"
		} else {
			// STRICT VAYDNS MODE:
			// The `protocol` from JSON acts as the Authentication Protocol (ssh, ss, etc).
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
			useAuth = (user != "" || pass != "")
		}
	}

    domainToUse = ExtractActiveDomain(domainToUse, domainIndex, useMultiDomains)

    /*if !useMultiDomains {
		parts := strings.Split(domainToUse, ",")
		if len(parts) > 0 {
			domainToUse = strings.TrimSpace(parts[0])
		}
	}*/
	
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

	// log.Printf("VAY_DEBUG: configType: %v configIndex: %v protocol: %v" , configType, configIndex, protocol)
	// =========================================================
	// DIRECT PROTOCOL BYPASS GUARDRAIL
	// =========================================================
	
	if !isDirectMode {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
				log.Printf("VAY_DEBUG: Tunnel Error: %v", err)
			}
		}()
	}
	
/*	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
			log.Printf("VAY_DEBUG: Tunnel Error: %v", err)
		}
	}()*/

	if protocol == "" || protocol == "socks" {
		protocol = "socks5"
	}

	scheme := protocol

	if useAuth {
		if authProtocol == "ssh" {
			if user == "" || user == "none" {
				authProtocol = "socks"
			} else {
				scheme = "ssh"
			}
		} else if authProtocol == "shadowsocks" || authProtocol == "ss" {
			if pass == "" || pass == "none" {
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
		Scheme: scheme, 
		Host:   internalSocks,
	}

	var sshKeyPath string

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

			manager, err := StartSSHClient(internalSocks, user, cleanPass, isPrivKey)
			if err != nil {
				return fmt.Sprintf("Error: SSH Handshake Failed - %v", err)
			}

			mu.Lock()
			activeSSHProxy = manager
			mu.Unlock()

			scheme = "socks5"
			proxyURL.Scheme = "socks5"
			proxyURL.Host = fmt.Sprintf("127.0.0.1:%d", manager.ProxyPort)
			proxyURL.User = nil 
		} else if authProtocol == "socks" {
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
				
	// 5. START TUN ENGINE (HYBRID ARCHITECTURE WITH DYNAMIC AUTH MAPPER)
	if engineType == "sing-box" {
		log.Printf("VAY_DEBUG: Booting modern sing-box processing pipeline...")
		log.Printf("VAY_DEBUG: server IP address is %v ",vlessWsIp)		
		singBoxListenPort := 30000 + rand.Intn(20000) 
		
		err := startSingBoxEngine(singBoxListenPort, proxyString, configType, protocol, configIndex, vlessWsIp)
		if err != nil {
			// Print immediately to logcat before Kotlin can trigger System.exit(0)
			errMsg := fmt.Sprintf("Error: sing-box layer failure: %v", err)
			log.Println("VAY_DEBUG: FATAL -> " + errMsg)
			return errMsg
		}
		
		singBoxProxyString := fmt.Sprintf("socks5://127.0.0.1:%d", singBoxListenPort)
		startTun2SocksEngine(wg, newFd, singBoxProxyString, finalMtu)
	} else {				
		startTun2SocksEngine(wg, newFd, proxyString, finalMtu)
	}

	// 6. SHUTDOWN WATCHER
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-ctx.Done()
		log.Printf("VAY_DEBUG: Shutting down engine watcher...")
		engine.Stop()

		mu.Lock()
		if activeSSHProxy != nil {
			activeSSHProxy.Stop()
			activeSSHProxy = nil
		}
		mu.Unlock()
		
		if sshKeyPath != "" {
			os.Remove(sshKeyPath)
		}
	}()

	return fmt.Sprintf("Success: VPN Started on %s", internalSocks)
}

func StartProxy(
	engineType string, isDefault bool, configIndex int64, configType string, useMultiDomains bool, domainIndex int64, udp string, tcp string, doh string, dot string, baseDohUrl string,
	customDomain string, customPubkey string, recordType string, idleTimeout string,
	KeepAlive string, clientIDSize int, mtu int, compatDnstt bool, useAuth bool,
	protocol string, ssMethod string, user string, pass string, customPort int, vlessWsIp string,
) string {
	mu.Lock()

	if activeCancel != nil {
		activeCancel()
		time.Sleep(100 * time.Millisecond)
	}

	activeSocksPort = customPort
	proxyAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(customPort))
	
	l, err := net.Listen("tcp", proxyAddr)
	if err != nil {
		mu.Unlock()
		return fmt.Sprintf("Error|%v", err)
	}
	l.Close() 

	activeSocksPort = customPort
	activeWg = &sync.WaitGroup{}
	activeCtx, activeCancel = context.WithCancel(context.Background())
	isRunning = true

	wg := activeWg
	ctx := activeCtx
	
	atomic.StoreUint64(&ProxyRxBytes, 0)
	atomic.StoreUint64(&ProxyTxBytes, 0)
		
	mu.Unlock()

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
	
	domainToUse = ExtractActiveDomain(domainToUse, domainIndex, useMultiDomains)

    /*if !useMultiDomains {
		parts := strings.Split(domainToUse, ",")
		if len(parts) > 0 {
			domainToUse = strings.TrimSpace(parts[0])
		}
	}*/
	
	realUdp := translateMultipathFakeToReal(udp)
	realTcp := translateMultipathFakeToReal(tcp)
	realDoh := translateMultipathFakeToReal(doh)
	realDot := translateMultipathFakeToReal(dot)
    	    	
	configTypeLower := strings.ToLower(configType)
	activeProto := strings.ToLower(protocol)

	isDirectMode := !strings.Contains(configTypeLower, "vaydns") || 
					activeProto == "hysteria" || activeProto == "hysteria2" || 
					activeProto == "reality" || activeProto == "vless" || activeProto == "vless-ws" || 
					configTypeLower == "direct"
					
	// SILENT ENGINE FALLBACK
	if isDirectMode {
		engineType = "sing-box"
	}
						
	if isDirectMode {
		// PATH A: DIRECT PROTOCOLS (Sing-box)
		if engineType == "sing-box" {
			log.Printf("VAY_DEBUG: Booting Sing-box in PROXY MODE on port %d", customPort)
			
			// We pass 'customPort' directly to Sing-box so it creates a SOCKS5 server on that exact port.
			// No tun2socks is needed!
			err := startSingBoxEngine(customPort, "", configType, protocol, configIndex, vlessWsIp)
			
			if err != nil {
				return fmt.Sprintf("Error|sing-box proxy failure: %v", err)
			}
		} else {
			return "Error|Only sing-box is supported for direct proxy mode"
		}
	} else {
		// PATH B: VAYDNS TUNNEL
		tCfg := bridge.TunnelConfig{
			BaseDohURL:       baseDohUrl,
			UdpAddr:          realUdp,
			TcpAddr:          realTcp,
			DohURL:           realDoh,
			DotAddr:          realDot,
			Domain:           domainToUse,
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
	}

	return fmt.Sprintf("Success|%s", proxyAddr)
}

func StopVpn() string {
	mu.Lock()
	if activeCancel == nil {
		mu.Unlock()
		return "Not running"
	}

	activeCancel() 
	
	if activeTunFd != 0 {
		_ = syscall.Close(activeTunFd)
		activeTunFd = 0
	}

	engine.Stop()
	
	if activeSingBox != nil {
		activeSingBox.Close()
		if activeSingBoxCancel != nil {
			activeSingBoxCancel()
		}
		activeSingBox = nil
		activeSingBoxCancel = nil
	}
		
	tempWg := activeWg
	activeCancel = nil 
	activeWg = nil    
	isRunning = false
	mu.Unlock()

	if tempWg != nil {
		done := make(chan struct{})
		go func() {
			tempWg.Wait()
			close(done)
		}()
		select {
		case <-done:
		case <-time.After(1500 * time.Millisecond):
		}
	}

	return "Stopped"
}

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
	log.Printf("VAY_DEBUG: [Watchdog] VerifyTunnel execution started in Go.")
	mu.Lock()
	port := activeSocksPort
	running := isRunning
	appCtx := activeCtx
	pUrl := activeProxyURL
	mu.Unlock()

	if !running || port == 0 || appCtx == nil {
		log.Printf("VAY_DEBUG: [Watchdog] Aborted: VPN state not running.")
		return "Fail: VPN not running"
	}

	proxyURL, err := url.Parse(pUrl)
	if err != nil {
		log.Printf("VAY_DEBUG: [Watchdog] Aborted: Invalid proxy URL.")
		return "Fail: Invalid proxy URL"
	}	
	
	if proxyURL.Scheme != "http" && proxyURL.Scheme != "socks5" {
		log.Printf("VAY_DEBUG: [Watchdog] Bypassing ping for unsupported scheme: %s", proxyURL.Scheme)
		return "Success" 
	}	
	
	client := &http.Client{
		Transport: &http.Transport{
			Proxy: http.ProxyURL(proxyURL),
		},
		Timeout: 5 * time.Second, 
	}

	req, err := http.NewRequest("HEAD", "http://www.google.com/generate_204", nil)
	if err != nil {
		return "Success"
	}

	log.Printf("VAY_DEBUG: [Watchdog] Firing HTTP ping to bypass connectivity killer...")
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("VAY_DEBUG: [Watchdog] Ping dropped. Bypassing kill-switch to maintain connection state.")
		return "Success" 
	}
	defer resp.Body.Close()

	log.Printf("VAY_DEBUG: [Watchdog] Network verified successfully.")
	return "Success"
}

func GetProxyStats() string {
	rx := atomic.LoadUint64(&client.ProxyRxBytes)
	tx := atomic.LoadUint64(&client.ProxyTxBytes)
	return fmt.Sprintf("%d|%d", rx, tx)
}

func FormatSSHKey(raw string) string {
	raw = strings.ReplaceAll(raw, "\\n", "\n")

	beginIdx := strings.Index(raw, "-----BEGIN")
	endIdx := strings.Index(raw, "-----END")

	if beginIdx == -1 || endIdx == -1 || beginIdx >= endIdx {
		return strings.TrimSpace(raw) + "\n"
	}

	headerEnd := strings.Index(raw[beginIdx:], "KEY-----")
	if headerEnd == -1 {
		return strings.TrimSpace(raw) + "\n"
	}
	headerEnd += beginIdx + 8 

	header := strings.TrimSpace(raw[beginIdx:headerEnd])
	footer := strings.TrimSpace(raw[endIdx:])
	body := raw[headerEnd:endIdx]

	body = strings.ReplaceAll(body, " ", "")
	body = strings.ReplaceAll(body, "\t", "")
	body = strings.ReplaceAll(body, "\n", "")
	body = strings.ReplaceAll(body, "\r", "")

	var formattedBody strings.Builder
	for i := 0; i < len(body); i += 64 {
		end := i + 64
		if end > len(body) {
			end = len(body)
		}
		formattedBody.WriteString(body[i:end])
		formattedBody.WriteString("\n")
	}

	return header + "\n" + formattedBody.String() + footer + "\n"
}

func SyncPreScanResolvers(
	isDefault bool, configIndex int64, domainIndex int64,
	resolversList string, dnsMode string, domain string, pubkey string, baseDohUrl string, 
	proxyType string, authProtocol string, user string, pass string, ssMethod string,
	recordType string, idleTimeout string, keepAlive string, clientIdSize int,
	lightE2EEnabled bool, workers int, tunnelWait int, probeTimeout int, udpTimeout int, retries int,
) string {
	parts := strings.Split(resolversList, ",")
	if len(parts) <= 1 {
		return resolversList 
	}

	resultMu.Lock()
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	mtu := 0
	engineQuickScan := false

	StartF35Scan(
		isDefault, configIndex, domainIndex, dnsMode, domain, pubkey, resolversList,
		baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType,
		idleTimeout, keepAlive, clientIdSize, mtu, workers, tunnelWait, udpTimeout,
		probeTimeout, retries, lightE2EEnabled, engineQuickScan,
	)

	maxWait := time.Duration(probeTimeout + tunnelWait + 1000) * time.Millisecond
	timeout := time.After(maxWait)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

waitLoop:
	for {
		select {
		case <-timeout:
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

	resultMu.Lock()
	resList := append([]string{}, resultQueue...)
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	if len(resList) == 0 {
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

	sort.Slice(valid, func(i, j int) bool {
		return valid[i].lat < valid[j].lat
	})

	var sorted []string
	for _, v := range valid {
		sorted = append(sorted, v.addr)
	}

	return strings.Join(sorted, ",")
}

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
	default: return 16 
	}
}

// Helper functions
func mustMarshal(v interface{}) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func mustParseURL(raw string) *url.URL {
	u, _ := url.Parse(raw)
	return u
}

func CheckHealthyDomainsQuick(useMultiDomains bool, isDefault bool, configIndex int64, domainIndex int64, domains string, resolverIP string, recordType string) string {
	domainToUse := domains

	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
	}

	domainToUse = ExtractActiveDomain(domainToUse, domainIndex, useMultiDomains)    

	/*if !useMultiDomains {
		parts := strings.Split(domainToUse, ",")
		if len(parts) > 0 {
			domainToUse = strings.TrimSpace(parts[0])
		}
	}*/

	rawDomains := strings.Split(domainToUse, ",")
	
	if !strings.Contains(resolverIP, ":") {
		resolverIP = resolverIP + ":53"
	}

	qtype := getQType(recordType)

	buildQuery := func(domain string) []byte {
		id := uint16(rand.Intn(65535))
		buf := make([]byte, 12)
		binary.BigEndian.PutUint16(buf[0:2], id)
		binary.BigEndian.PutUint16(buf[2:4], 0x0100) 
		binary.BigEndian.PutUint16(buf[4:6], 1)      
		
		for _, part := range strings.Split(domain, ".") {
			buf = append(buf, byte(len(part)))
			buf = append(buf, []byte(part)...)
		}
		buf = append(buf, 0) 
		
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
					return
				}
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(2000 * time.Millisecond))

				if protocol == "tcp" {
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

				respBuf := make([]byte, 512)
				_, err = conn.Read(respBuf)

				if err == nil {
					mu.Lock()
					healthy = append(healthy, domain)
					mu.Unlock()
				}
			}(cleanDomain)
		}
		wg.Wait()
		return healthy
	}

	results := runScan("udp")
	if len(results) == 0 {
		results = runScan("tcp")
	}

	return strings.Join(results, ",")
}

func PingServer(
	isDefault bool, configIndex int64, domainIndex int64,
	address string, dnsMode string, domain string, pubkey string, baseDohUrl string,
	proxyType string, authProtocol string, user string, pass string, ssMethod string,
	recordType string, idleTimeout string, keepAlive string, clientIdSize int,
	lightE2EEnabled bool, workers int, tunnelWait int, probeTimeout int, udpTimeout int, retries int,
) int64 {
	address = strings.TrimSpace(address)
	if address == "" {
		return -1
	}

	resultMu.Lock()
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	mtu := 0
	engineQuickScan := false

	StartF35Scan(
		isDefault, configIndex, domainIndex, dnsMode, domain, pubkey, address,
		baseDohUrl, proxyType, authProtocol, user, pass, ssMethod, recordType,
		idleTimeout, keepAlive, clientIdSize, mtu, workers, tunnelWait, udpTimeout,
		probeTimeout, retries, lightE2EEnabled, engineQuickScan,
	)

	maxWait := time.Duration(probeTimeout + tunnelWait + 1000) * time.Millisecond
	timeout := time.After(maxWait)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

waitLoop:
	for {
		select {
		case <-timeout:
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

	resultMu.Lock()
	resList := append([]string{}, resultQueue...)
	resultQueue = make([]string, 0)
	resultMu.Unlock()

	if len(resList) == 0 {
		return -1
	}

	var finalLatency int64 = -1

	for _, item := range resList {
		var j map[string]interface{}
		if err := json.Unmarshal([]byte(item), &j); err == nil {
			addr, ok1 := j["resolver"].(string)
			latFloat, ok2 := j["latency_ms"].(float64)
			if ok1 && ok2 && strings.Contains(addr, address) && latFloat > 0 {
				finalLatency = int64(latFloat)
			}
		}
	}

	return finalLatency
}

func startSingBoxEngine(singBoxListenPort int, upstreamProxyUrl string, configType string, protocol string, configIndex int64, vlessWsIp string) error {

	var outboundMap map[string]interface{}
	var upstreamScheme string
	
	// =========================================================
	// THE ARCHITECTURAL FORK
	// =========================================================
	// isDirectMode := (strings.ToLower(configType) == "direct")
	configTypeLower := strings.ToLower(configType)
	activeProto := strings.ToLower(protocol)

	isDirectMode := !strings.Contains(configTypeLower, "vaydns") || 
					activeProto == "hysteria" || activeProto == "hysteria2" || 
					activeProto == "reality" || activeProto == "vless" || activeProto == "vless-ws" || 
					configTypeLower == "direct"
					
	if isDirectMode {
		// SUB-FORK: Determine which direct protocol to build strictly from the protocol string
		activeProto := strings.ToLower(protocol)
		
		if activeProto == "hysteria" || activeProto == "hysteria2" {
			upstreamScheme = "hysteria2"
			if configIndex >= 0 {
				outboundMap = buildHysteriaOutbound(configIndex)
			} else {
				return fmt.Errorf("invalid config index for direct protocol")
			}
			
		} else if activeProto == "reality" || activeProto == "vless" {
			upstreamScheme = "vless"
			if configIndex >= 0 {
				outboundMap = buildRealityOutbound(configIndex)
			} else {
				return fmt.Errorf("invalid config index for direct protocol")
			}
		} else if activeProto == "vless-ws" {
			upstreamScheme = "vless"
			if configIndex >= 0 {
				outboundMap = buildVlessWsOutbound(configIndex, vlessWsIp)
			} else {
				return fmt.Errorf("invalid config index for direct protocol")
			}			
		} else {
			return fmt.Errorf("unknown direct protocol selected: %s", protocol)
		}

	} else {
		// PATH B: VAYDNS DNS TUNNEL
		// Parse the local upstream URL (e.g. Dante proxy) to chain the connection.
		u, err := url.Parse(upstreamProxyUrl)
		if err != nil {
			return fmt.Errorf("invalid upstream proxy url: %v", err)
		}

		upstreamScheme = u.Scheme
		port, _ := strconv.Atoi(u.Port())
		host := u.Hostname()

		outboundMap = map[string]interface{}{
			"tag":         "proxy-out",
			"server":      host,
			"server_port": port,
		}

		switch u.Scheme {
		case "ss", "shadowsocks":
			outboundMap["type"] = "shadowsocks"
			outboundMap["method"] = u.User.Username()
			pwd, _ := u.User.Password()
			outboundMap["password"] = pwd
		case "http", "https":
			outboundMap["type"] = "http"
			if u.User != nil {
				outboundMap["username"] = u.User.Username()
				pwd, _ := u.User.Password()
				if pwd != "" {
					outboundMap["password"] = pwd
				}
			}
		case "socks5", "socks":
			outboundMap["type"] = "socks"
			if u.User != nil {
				username := u.User.Username()
				if username != "" {
					outboundMap["username"] = username
				}
				pwd, _ := u.User.Password()
				if pwd != "" {
					outboundMap["password"] = pwd
				}
			}
		default:
			outboundMap["type"] = "socks"
			if u.User != nil {
				username := u.User.Username()
				if username != "" {
					outboundMap["username"] = username
				}
				pwd, _ := u.User.Password()
				if pwd != "" {
					outboundMap["password"] = pwd
				}
			}
		}
	}

	outboundBytes, _ := json.Marshal(outboundMap)
	outboundJson := string(outboundBytes)

	// THE HEISENBUG FIX: 
	// 1. "level" remains "debug" to preserve the micro-delay that fixes the FakeIP race condition.
	// 2. "output" is set to "/dev/null" so the logs are instantly destroyed and never reach Logcat.

	rawConfig := fmt.Sprintf(`{
		"log": {
			"level": "debug",
			"output": "/dev/null",
			"timestamp": true
		},
		"dns": {
			"servers": [
				{
					"tag": "remote-dns",
					"address": "https://8.8.8.8/dns-query",
					"detour": "proxy-out"
				},
				{
					"tag": "fake-dns",
					"address": "fakeip"
				},
				{
					"tag": "block-dns",
					"address": "rcode://success"
				}
			],
			"rules": [
				{
					"domain_suffix": [
						"gstatic.com",
						"googleapis.com",
						"google.com",
						"apple.com"
					],
					"server": "remote-dns"
				},
				{
					"query_type": ["HTTPS"],
					"server": "block-dns"
				},
				{
					"query_type": ["A", "AAAA"],
					"server": "fake-dns"
				}
			],
			"final": "remote-dns",
			"fakeip": {
				"enabled": true,
				"inet4_range": "198.18.0.0/15"
			},
			"strategy": "ipv4_only"
		},
		"inbounds": [
			{
				"type": "socks",
				"tag": "socks-in",
				"listen": "127.0.0.1",
				"listen_port": %d
			}
		],
		"outbounds": [
			%s,
			{
				"type": "block",
				"tag": "block-out"
			}
		],
		"route": {
			"rules": [
				{
					"inbound": ["socks-in"],
					"action": "sniff"
				},
				{
					"inbound": ["socks-in"],
					"protocol": "dns",
					"action": "hijack-dns"
				},
				{
					"inbound": ["socks-in"],
					"port": [53],
					"action": "hijack-dns"
				},
				{
					"inbound": ["socks-in"],
					"port": [853],
					"outbound": "block-out"
				},
				{
					"inbound": ["socks-in"],
					"network": "udp",
					"port": [443],
					"outbound": "block-out"
				}
			],
			"auto_detect_interface": false,
			"final": "proxy-out"
		}
	}`, singBoxListenPort, outboundJson)

	ctx, cancel := context.WithCancel(context.Background())
	registryCtx := box.Context(
		ctx,
		include.InboundRegistry(),
		include.OutboundRegistry(),
		include.EndpointRegistry(),
		include.DNSTransportRegistry(),
		include.ServiceRegistry(),
	)

	opts, err := singJson.UnmarshalExtendedContext[option.Options](registryCtx, []byte(rawConfig))
	if err != nil {
		cancel()
		log.Printf("VAY_DEBUG: FATAL - Failed to parse sing-box JSON schema: %v", err)
		return fmt.Errorf("failed to parse sing-box proxy config: %v", err)
	}

	instance, err := box.New(box.Options{
		Context: registryCtx,
		Options: opts,
	})
	if err != nil {
		cancel()
		return fmt.Errorf("failed to initialize sing-box proxy instance: %v", err)
	}

	if err := instance.Start(); err != nil {
		cancel()
		return fmt.Errorf("failed to start sing-box proxy layer: %v", err)
	}

	mu.Lock()
	activeSingBox = instance
	activeSingBoxCancel = cancel
	mu.Unlock()
	
	log.Printf("VAY_DEBUG: Sing-box Engine mapped natively to '%s' upstream protocol on port %d.", upstreamScheme, singBoxListenPort)

	// Keep your socket race-condition protection intact
	time.Sleep(200 * time.Millisecond)

	return nil
}

func startTun2SocksEngine(wg *sync.WaitGroup, fd int, proxyString string, finalMtu int) {
	wg.Add(1)
	go func() {
		defer wg.Done()

		time.Sleep(300 * time.Millisecond)

		key := &engine.Key{
			Proxy:    proxyString,
			Device:   fmt.Sprintf("fd://%d", fd),
			MTU:      finalMtu,
//			LogLevel: "debug",
			LogLevel: "warn",
		}
		engine.Insert(key)
	
		if proxyURL, err := url.Parse(proxyString); err == nil {
			if proxyURL.User != nil {
				proxyURL.User = url.UserPassword("MASKED", "MASKED")
			}
			log.Printf("VAY_DEBUG: Tun2Socks Engine starting on FD %d with proxy %s", fd, proxyURL.String())
		} else {
			log.Printf("VAY_DEBUG: Tun2Socks Engine starting on FD %d", fd)
		}			

		engine.Start()
	}()
}
