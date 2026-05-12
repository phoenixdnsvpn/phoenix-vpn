package f35

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
	"fmt"
	"strings"
	"runtime/debug"

	"github.com/Starling226/vaydns-vpn/bridge"
	"github.com/shadowsocks/go-shadowsocks2/core"
	"github.com/shadowsocks/go-shadowsocks2/socks"
	"golang.org/x/crypto/ssh"
)

var globalDynamicPort int32
var InjectedPingDomain string
var dynamicPort int32 = 20000

// THE FIX: Master FD Guardrail (Max 150 concurrent tunnels)
var tunnelSemaphore = make(chan struct{}, 50)

type scanStats struct {
	mu        sync.Mutex
	total     int
	processed int
	healthy   int
}

func ScanWithContext(ctx context.Context, cfg Config, hooks Hooks) error {

	runtime, err := prepareConfig(cfg)
	if err != nil {
		return err
	}

	return scanResolversWithContext(ctx, &runtime, hooks)
}

func scanResolversWithContext(ctx context.Context, runtime *runtimeConfig, hooks Hooks) error {
	total := len(runtime.parsedResolvers)
	jobs := make(chan parsedResolver, runtime.Workers*2)
	stats := newScanStats(total)

	atomic.StoreInt32(&globalDynamicPort, int32(runtime.StartPort))

	var wg sync.WaitGroup
	for i := 0; i < runtime.Workers; i++ {
		wg.Add(1)
		go func(port int) {
			defer wg.Done()
			defer func() {
				if r := recover(); r != nil {
					fmt.Printf("GO_WORKER_CRASH [%d]: %v\n", port, r)
				}
			}()
			worker(ctx, runtime, jobs, hooks, stats)
		}(i)
	}

	for _, resolver := range runtime.parsedResolvers {
		select {
		case <-ctx.Done():
			close(jobs)
			wg.Wait()
			return ctx.Err()
		case jobs <- resolver:
		}
	}
	close(jobs)
	wg.Wait()
	return nil
}

func (s *scanStats) update(healthy bool) Progress {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.processed++
	if healthy {
		s.healthy++
	}
	return Progress{
		Total:     s.total,
		Processed: s.processed,
		Healthy:   s.healthy,
		Failed:    s.processed - s.healthy,
	}
}

func newScanStats(total int) *scanStats {
	return &scanStats{total: total}
}

func worker(ctx context.Context, cfg *runtimeConfig, jobs <-chan parsedResolver, hooks Hooks, stats *scanStats) {
	defer func() {
		if r := recover(); r != nil {
			fmt.Printf("GO_WORKER_PANIC [%d]: %v\nstack: %s\n", r, debug.Stack())
		}
	}()

	for {
		select {
		case <-ctx.Done(): 
			return
		case resolver, ok := <-jobs:
			if !ok {
				return
			}

			atomic.CompareAndSwapInt32(&dynamicPort, 60000, 20000)
			uniquePort := int(atomic.AddInt32(&dynamicPort, 1))
            
			vaydnsLocal := net.JoinHostPort("127.0.0.1", strconv.Itoa(uniquePort))

			// THE PROBE ROUTER
			var dialContext func(ctx context.Context, network, addr string) (net.Conn, error)
			var proxyFunc func(*http.Request) (*url.URL, error)

			// Read the backend parameters we passed through f35_bridge
			tCfg := ParseExtraArgs(cfg.ExtraArgs)

			if tCfg.Protocol == "ssh" && tCfg.User != "" && tCfg.User != "none" {
				// --- 1. SSH TUNNELING ---
				dialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
					rawConn, err := net.DialTimeout("tcp", vaydnsLocal, 10*time.Second)
					if err != nil {
						return nil, err
					}

					var authMethods []ssh.AuthMethod
					
					// Replicating mobile.go: Check for SSH Private Key
					if strings.Contains(tCfg.Pass, "-----BEGIN") {
						// ADVANTAGE: We parse it securely in-memory instead of writing a temp file!
						signer, err := ssh.ParsePrivateKey([]byte(tCfg.Pass))
						if err != nil {
							rawConn.Close()
							return nil, fmt.Errorf("failed to parse SSH private key: %v", err)
						}
						authMethods = append(authMethods, ssh.PublicKeys(signer))
					} else if tCfg.Pass != "" && tCfg.Pass != "none" {
						// Password Auth
						authMethods = append(authMethods, ssh.Password(tCfg.Pass))
					}

					sshConfig := &ssh.ClientConfig{
						User:            tCfg.User,
						Auth:            authMethods,
						HostKeyCallback: ssh.InsecureIgnoreHostKey(),
						Timeout:         10 * time.Second,
					}

					c, chans, reqs, err := ssh.NewClientConn(rawConn, vaydnsLocal, sshConfig)
					if err != nil {
						rawConn.Close()
						return nil, err
					}
					sshClient := ssh.NewClient(c, chans, reqs)

					return sshClient.Dial(network, addr)
				}
			} else if tCfg.Protocol == "shadowsocks" || tCfg.Protocol == "ss" {
				// --- 2. SHADOWSOCKS ---
				ssMethod := tCfg.SSMethod
				if ssMethod == "" || ssMethod == "none" {
					ssMethod = "chacha20-ietf-poly1305" // Fallback matching mobile.go
				}
				
				// Custom Shadowsocks Encrypted Dialer
				dialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
					// 1. Connect to our local multiplexed tunnel port
					rawConn, err := net.DialTimeout("tcp", vaydnsLocal, 10*time.Second)
					if err != nil {
						return nil, err
					}

					// 2. Setup the Shadowsocks AEAD Cipher using the user's password
					ciph, err := core.PickCipher(ssMethod, nil, tCfg.Pass)
					if err != nil {
						rawConn.Close()
						return nil, err
					}

					// 3. Wrap the raw connection in the Shadowsocks stream
					ssConn := ciph.StreamConn(rawConn)

					// 4. Shadowsocks protocol requires sending the target address in SOCKS5 format first
					rawAddr := socks.ParseAddr(addr)
					if len(rawAddr) == 0 {
						ssConn.Close()
						return nil, fmt.Errorf("failed to parse shadowsocks target address: %s", addr)
					}
					
					if _, err := ssConn.Write(rawAddr); err != nil {
						ssConn.Close()
						return nil, err
					}

					return ssConn, nil
				}

			} else {
				// --- 3. STANDARD SOCKS5 & DANTE ---
				proxyURL := &url.URL{
					Scheme: cfg.Proxy, // "socks5h"
					Host:   vaydnsLocal,
				}
				
				// Replicating mobile.go: ONLY apply Auth if user exists
				if tCfg.User != "" && tCfg.User != "none" {
					if tCfg.Pass != "" && tCfg.Pass != "none" {
						proxyURL.User = url.UserPassword(tCfg.User, tCfg.Pass)
					} else {
						proxyURL.User = url.User(tCfg.User)
					}
				}
				// If tCfg.User is "none", proxyURL.User stays nil (Fixes Dante No-Auth bug!)
				
				proxyFunc = http.ProxyURL(proxyURL)
			}

			// Apply the smart routing to the HTTP Client
			transport := &http.Transport{
				Proxy:             proxyFunc,   // Active for SOCKS/Dante/SS
				DialContext:       dialContext, // Active for SSH
				DisableKeepAlives: true, 
			}

			client := &http.Client{
				Transport: transport,
			}

			result, healthy := checkResolver(ctx, client, cfg, resolver, uniquePort)

			transport.CloseIdleConnections()

			select {
			case <-ctx.Done():
				return
			default:
				if hooks.OnResult != nil {
					hooks.OnResult(result)
				}
				if hooks.OnProgress != nil {
					hooks.OnProgress(stats.update(healthy))
				}
			}
		}
	}
}


func checkResolver(ctx context.Context, client *http.Client, cfg *runtimeConfig, resolver parsedResolver, port int) (Result, bool) {

	// ACQUIRE FD SLOT: Throttles workers so we NEVER crash Android
	tunnelSemaphore <- struct{}{} 

	baseWarmup := cfg.TunnelWait
	pingTimeout := 2000 * time.Millisecond
	
	if cfg.Mode == "doh" || cfg.Mode == "dot" {
		pingTimeout = 2500 * time.Millisecond
		baseWarmup += 2 * time.Second
	}
		
	tCfg := ParseExtraArgs(cfg.ExtraArgs)
			
	// --- STEP 1: PRE-TUNNEL QUICK SCAN (DNS PING ONLY) ---
	// If the user selected "Quick DNS Check", we ONLY ping the resolver,
	// return the latency instantly, and completely bypass tunnel creation.
	if tCfg.EngineQuickScan {
		start := time.Now()
		dnsPassed := quickDNSCheck(ctx, resolver.addr, pingTimeout, cfg.Mode, tCfg.BaseDohURL)
		
		// CRITICAL: We are exiting early, so we must manually free the worker slot
		<-tunnelSemaphore 
		
		if dnsPassed {
			latency := time.Since(start).Milliseconds()
			if latency == 0 { latency = 1 }
			return Result{
				Resolver: resolver.addr,
				Probe: "ok",
				LatencyMS: latency,
			}, true
		} else {
			return Result{
				Resolver: resolver.addr,
				Probe: "dead",
				LatencyMS: 99999,
			}, false
		}
	}
			
	listenAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))

	if cfg.Mode == "doh" {
		tCfg.DohURL = resolver.addr
	} else if cfg.Mode == "dot" {
		tCfg.DotAddr = resolver.addr
	} else if cfg.Mode == "tcp" {
		tCfg.TcpAddr = resolver.addr
	} else {
		tCfg.UdpAddr = resolver.addr
	}	
	
	tCfg.ListenAddr  = listenAddr
	tCfg.Domain = cfg.Domain
	tCfg.IsScanner = true
	
	var smuxUsed bool // Tracks if we need the extra 1-second HTTP flush delay
	tunnelCtx, cancelTunnel := context.WithCancel(ctx)

	// SMART ASYNCHRONOUS CLEANUP
	defer func() {
		go func() {
			if smuxUsed {
				// Only delay if HTTP data was ACTUALLY sent (prevents KCP panic)
				time.Sleep(3 * time.Second) 
			}
			cancelTunnel() // Instantly closes the socket in run.go
			<-tunnelSemaphore // Instantly frees the slot for the next worker
		}()
	}()

	go func() {
		defer func() {
			if r := recover(); r != nil {
				fmt.Printf("CAUGHT_TUNNEL_PANIC [%s]: %v\n", resolver.addr, r)
			}
		}()

		err := bridge.RunTunnel(tunnelCtx, tCfg) 
		if err != nil && err != context.Canceled {
			fmt.Printf("TUNNEL_ERROR [%s]: %v\n", resolver.addr, err)			
		}
	}()

	// --- STEP 2: INTRA-TUNNEL FAST FAIL & QUICK SCAN (HANDSHAKE) ---

	performHandshake := tCfg.LightE2EEnabled 	
	
	var handshakeLatency int64
	if performHandshake {
		time.Sleep(250 * time.Millisecond)
		
		start := time.Now()
		if !verifyTunnelHandshake(ctx, port, tCfg) {
			return Result{
				Resolver: resolver.addr, 
				Probe: "h-fail", 
				LatencyMS: 99999,
			}, false
		}
		handshakeLatency = time.Since(start).Milliseconds()
		if handshakeLatency == 0 { handshakeLatency = 1 } 
	}
	
	// --- STEP 3: QUICK SCAN EXIT ---

	if tCfg.LightE2EEnabled {
		return Result{
			Resolver: resolver.addr,
			Probe: "ok",
			LatencyMS: handshakeLatency,
		}, true
	}
		
	finalWait := baseWarmup
	if performHandshake {
		finalWait = 250 * time.Millisecond 
	}
	
	select {
	case <-time.After(finalWait):
	case <-ctx.Done():
		return Result{Resolver: resolver.addr, Probe: "stopped"}, false
	}

	// WE PASSED WARMUP. HTTP STREAMS ARE ABOUT TO OPEN.
	smuxUsed = true

	result := Result{
		Resolver: resolver.addr,
		Download: "off",
		Upload:   "off",
		Whois:    "off",
		Probe:    "off",
	}

	isHealthy := false
	bestPriority := 0

	if cfg.Download && ctx.Err() == nil {
		result.Download = "fail"
		latency, ok := doHTTPCheck(ctx, client, cfg.DownloadURL, cfg.DownloadTimeout, true)
		if ok {
			result.Download = "ok"
			isHealthy = true
			result.LatencyMS = latency
			bestPriority = 4
		}
	}

	if cfg.Upload && ctx.Err() == nil {
		result.Upload = "fail"
		latency, ok := doUploadCheck(ctx, client, cfg.UploadURL, cfg.UploadTimeout, cfg.uploadPayload)
		if ok {
			result.Upload = "ok"
			isHealthy = true
			if bestPriority < 3 {
				result.LatencyMS = latency
				bestPriority = 3
			}			
		}
	}

	if cfg.Whois && ctx.Err() == nil {
		result.Whois = "fail"
		latency, org, country, ok := lookupResolverInfo(ctx, client, resolver.ip.String(), cfg.WhoisTimeout)
		if ok {
			result.Whois = "ok"
			result.Org = org
			result.Country = country
			isHealthy = true
			if bestPriority < 2 {
				result.LatencyMS = latency
				bestPriority = 2
			}			
		}
	}

	if cfg.Probe && ctx.Err() == nil {
		result.Probe = "fail"
		latency, ok := doHTTPCheck(ctx, client, cfg.ProbeURL, cfg.ProbeTimeout, true)
		if ok {
			result.Probe = "ok"
			if bestPriority < 1 {
				result.LatencyMS = latency
				isHealthy = true
				bestPriority = 1
			}
		}
	}
		
	if bestPriority == 0 {
		result.LatencyMS = 99999
		return result, false
	}
		
	return result, isHealthy
}

func ParseExtraArgs(args []string) bridge.TunnelConfig {
	config := bridge.TunnelConfig{}

	config.CompatDnstt = false

	for i := 0; i < len(args); i++ {
		key := strings.TrimSpace(args[i])

		if key == "-dnstt-compat" {
			config.CompatDnstt = true
			continue 
		}

		if i+1 >= len(args) {
			break
		}

		value := strings.TrimSpace(args[i+1])

		switch key {
		case "-base-doh":
			config.BaseDohURL = value		
		case "-doh":
			config.DohURL = value
		case "-dot":
			config.DotAddr = value
		case "-udp":
			config.UdpAddr = value
		case "-domain":
			config.Domain = value
		case "-listen":
			config.ListenAddr = value
		case "-pubkey":
			config.PubkeyHex = value
		case "-mtu":
			config.MTU, _ = strconv.Atoi(value)
		case "-utls":
			config.UtlsDistribution = value
		case "-record-type":
			config.RecordType = value
		case "-log-level":
			config.LogLevel = value
		case "-idle-timeout":
			config.IdleTimeout = value
		case "-keepalive":
			config.KeepAlive = value
		case "-reconnect-min":
			config.ReconnectMin = value
		case "-reconnect-max":
			config.ReconnectMax = value
		case "-session-check-interval":
			config.SessionCheck = value
		case "-open-stream-timeout":
			config.OpenStreamTimeout = value
		case "-udp-timeout":
			config.UDPTimeout = value
		case "-max-qname-len":
			config.MaxQnameLen, _ = strconv.Atoi(value)
		case "-max-num-labels":
			config.MaxNumLabels, _ = strconv.Atoi(value)
		case "-max-streams":
			config.MaxStreams, _ = strconv.Atoi(value)
		case "-clientid-size":
			config.ClientIDSize, _ = strconv.Atoi(value)
		case "-rps":
			config.RpsLimit, _ = strconv.ParseFloat(value, 64)
		case "-protocol":
			config.Protocol = value
		case "-ss-method":
			config.SSMethod = value
		case "-user":
			config.User = value
		case "-pass":
			config.Pass = value
		case "-fast-fail-enabled":
			enabled, err := strconv.ParseBool(value)
			if err == nil {
				config.LightE2EEnabled = enabled
			}
		case "-quick-scan":
			parsed, err := strconv.ParseBool(value)
			if err == nil { 
				config.EngineQuickScan = parsed
			}						
		}
		i++
	}

	return config
}

// encodeDNSName converts a string like "google.com" into DNS wire format
func encodeDNSName(domain string) []byte {

	// Strips any accidental spaces, tabs, or newlines injected by the CI environment
	cleanDomain := strings.TrimSpace(domain)
	
	var buf []byte
	for _, part := range strings.Split(cleanDomain, ".") {
		buf = append(buf, byte(len(part)))
		buf = append(buf, []byte(part)...)
	}
	buf = append(buf, 0x00)
	return buf
}

func quickDNSCheck(ctx context.Context, addr string, timeout time.Duration, mode string, baseDohURL string) bool {
	dialCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	// --- DoH Check: Quick HTTP request ---
	if mode == "doh" {
		isStrategyB := !strings.HasPrefix(addr, "http")
		var reqURL string
		var dialIP string

		if isStrategyB {
			reqURL = baseDohURL
			if reqURL == "" { return false }
			dialIP = addr
			if !strings.Contains(dialIP, ":") {
				dialIP = net.JoinHostPort(dialIP, "443")
			}
		} else {
			reqURL = addr
		}

		transport := &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		}
		
		if isStrategyB {
			transport.DialContext = func(ctx context.Context, network, dummyAddr string) (net.Conn, error) {
				return net.DialTimeout("tcp", dialIP, timeout)
			}
		}

		tempClient := &http.Client{Transport: transport, Timeout: timeout}
		
		req, err := http.NewRequestWithContext(dialCtx, "GET", reqURL, nil)
		if err != nil { return false }

		resp, err := tempClient.Do(req)
		if err != nil { return false }
		resp.Body.Close()

		return true
	}

	// --- DoT Check: Quick TCP Connect to port 853 ---
	if mode == "dot" {
		var d net.Dialer
		conn, err := d.DialContext(dialCtx, "tcp", addr)
		if err != nil { return false }
		conn.Close()
		return true
	}

	//  GENERATE CRYPTOGRAPHICALLY SECURE TRANSACTION ID
	txID := make([]byte, 2)
	if _, err := rand.Read(txID); err != nil {
		txID = []byte{0xab, 0xcd} // Safe fallback if the system crypto pool is temporarily exhausted
	}

	domain := InjectedPingDomain
	if domain == "" {
		domain = "google.com" 
	}

	// Build the shared DNS Query using the dynamic Transaction ID
	header := []byte{
		txID[0], txID[1], //  Dynamic Random Bytes
		0x01, 0x00, // Flags: Standard query
		0x00, 0x01, // Questions: 1
		0x00, 0x00, // Answer RRs: 0
		0x00, 0x00, // Authority RRs: 0
		0x00, 0x00, // Additional RRs: 0
	}
	
	qname := encodeDNSName(domain)
	footer := []byte{0x00, 0x01, 0x00, 0x01}

	query := append(header, qname...)
	query = append(query, footer...)

	// --- TCP Check: DNS over TCP (RFC 1035 requires 2-byte length prefix) ---
	if mode == "tcp" {
		var d net.Dialer
		conn, err := d.DialContext(dialCtx, "tcp", addr)
		if err != nil { return false }
		defer conn.Close()

		// 🔴 OPTIMAL TCP: Prepend the 2-byte length of the payload
		length := uint16(len(query))
		tcpQuery := append([]byte{byte(length >> 8), byte(length)}, query...)

		conn.SetWriteDeadline(time.Now().Add(timeout))
		if _, err := conn.Write(tcpQuery); err != nil { return false }

		conn.SetReadDeadline(time.Now().Add(timeout))
		buf := make([]byte, 512)
		if _, err := conn.Read(buf); err != nil { return false }

		return true
	}

	// --- UDP Check: Evading DPI Signatures ---
	var d net.Dialer
	conn, err := d.DialContext(dialCtx, "udp", addr)
	if err != nil { return false }
	defer conn.Close()

	conn.SetWriteDeadline(time.Now().Add(timeout))
	if _, err := conn.Write(query); err != nil { return false }

	conn.SetReadDeadline(time.Now().Add(timeout))
	buf := make([]byte, 512)
	if _, err := conn.Read(buf); err != nil { return false }

	return true
}

func verifyTunnelHandshake(ctx context.Context, port int, tCfg bridge.TunnelConfig) bool {
	dialer := net.Dialer{Timeout: 3 * time.Second}
	conn, err := dialer.DialContext(ctx, "tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return false
	}
	defer conn.Close()

	// Give the multiplexer a tiny moment to establish the stream to the VPS
	time.Sleep(100 * time.Millisecond)

	protocol := strings.ToLower(tCfg.Protocol)

	// --- 1. SSH Handshake Check ---
	if protocol == "ssh" {
		// SSH servers always send a banner string immediately upon connection (e.g., "SSH-2.0-OpenSSH...")
		buf := make([]byte, 4)
		conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := conn.Read(buf)
		if err != nil || n < 4 {
			return false
		}
		// Check if the response starts with "SSH-"
		return string(buf[:4]) == "SSH-"
	}

	// --- 2. Shadowsocks Handshake Check ---
	if protocol == "shadowsocks" || protocol == "ss" {
		// Shadowsocks does not have a plaintext handshake greeting. 
		// If we successfully opened the TCP stream through the smux tunnel and it 
		// didn't instantly crash or close, the tunnel bridge itself is healthy.
		return true 
	}

	// --- 3. SOCKS5 Handshake Check (Auth & No Auth) ---
	// Send SOCKS5 Greeting: \x05 (version 5), \x02 (2 methods supported), \x00 (no auth), \x02 (user/pass)
	_, err = conn.Write([]byte{0x05, 0x02, 0x00, 0x02})
	if err != nil {
		return false
	}

	buf := make([]byte, 2)
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := conn.Read(buf)
	
	if err != nil || n != 2 {
		return false
	}
	
	// Success if server returns SOCKS5 (0x05) AND either No Auth (0x00) or User/Pass Auth (0x02)
	return buf[0] == 0x05 && (buf[1] == 0x00 || buf[1] == 0x02)
}

