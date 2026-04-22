package mobile

import (
	"context"
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
	
	"github.com/Starling226/vaydns-vpn/bridge"
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
	wg         sync.WaitGroup // Used to ensure threads fully exit before StopVpn returns
	
	engineLock      sync.Mutex
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
//		log.Printf("VAY_DEBUG_VPN: [TRANS] DoH URL translated. Fake: [%s] -> Real: [%s]", input, finalUrl)
		return finalUrl
	}

	// 2. Try to split standard UDP/DoT IPs and Ports
	host, port, err := net.SplitHostPort(input)
	if err != nil {
		// No port was provided by the user! Translate the raw IP and return it.
		realHost := GetRealResolver(input)
//		log.Printf("VAY_DEBUG_VPN: [TRANS] No port found. Raw: [%s] -> Real: [%s]", input, realHost)
		return realHost
	}

	// 3. Port was attached, translate the host and strictly preserve the user's port
	realHost := GetRealResolver(host)
	finalAddress := net.JoinHostPort(realHost, port)

//	log.Printf("VAY_DEBUG_VPN: [TRANS] Port preserved. Fake: [%s] -> Real: [%s]. Final: [%s]", host, realHost, finalAddress)

	return finalAddress
}
 
func StartVpn(fd int, udp string, doh string, dot string, domain string, pubkey string, recordType string, idleTimeout string, KeepAlive string, clientIDSize int, compatDnstt bool, useAuth bool, protocol string, ssMethod string, user string, pass string, protector SocketProtector) string {
	mu.Lock()

	if activeCancel != nil {
		log.Printf("VAY_DEBUG: Killing old session context")
		activeCancel() 
		time.Sleep(100 * time.Millisecond) 
	}

	newFd, err := syscall.Dup(fd)
	if err != nil {
		mu.Lock() 
		return "Error: Failed to dup FD"
	}

	activeWg = &sync.WaitGroup{}
	activeCtx, activeCancel = context.WithCancel(context.Background())
	isRunning = true

	wg := activeWg
	ctx := activeCtx
	
	activeSocksPort = 10000 + rand.Intn(40000)
	internalSocks := fmt.Sprintf("127.0.0.1:%d", activeSocksPort)
	
	mu.Unlock()

//	log.Printf("VAY_DEBUG_VPN: [START] Android passed raw UDP: '%s'", udp)

	realUdp := translateFakeToReal(udp)
	realDoh := translateFakeToReal(doh)
	realDot := translateFakeToReal(dot)
	
//	log.Printf("VAY_DEBUG_VPN: [START] Final Tunnel UDP target: '%s'", realUdp)
	
	tCfg := bridge.TunnelConfig{
		UdpAddr: realUdp, DohURL: realDoh, DotAddr: realDot, Domain: domain,
		ListenAddr: internalSocks, LogLevel: "error", UtlsDistribution: "chrome",
		RecordType: recordType, PubkeyHex: pubkey, Protector: protector,
		IdleTimeout: idleTimeout, KeepAlive: KeepAlive, UDPTimeout: "2s",
		ClientIDSize: clientIDSize, CompatDnstt: compatDnstt,
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
			log.Printf("VAY_DEBUG: Tunnel Error: %v", err)
		}
	}()

	// 4. BUILD THE PROXY URL WITH AUTHENTICATION
	
	// 🚨 MASTER GUARDRAIL: If authentication is disabled, SSH and Shadowsocks 
	// are impossible. Force fallback to plain SOCKS5.
	if !useAuth || protocol == "" || protocol == "socks" {
		protocol = "socks5"
	} else {
		// Secondary guardrails for invalid UI states when auth IS enabled
		if protocol == "ssh" && (user == "" || user == "none") {
			log.Printf("VAY_DEBUG: SSH selected but no username provided. Falling back to socks5.")
			protocol = "socks5"
		} else if (protocol == "shadowsocks" || protocol == "ss") && (pass == "" || pass == "none") {
			log.Printf("VAY_DEBUG: Shadowsocks selected but no password provided. Falling back to socks5.")
			protocol = "socks5"
		}
	}

	proxyURL := &url.URL{
		Scheme: protocol,
		Host:   internalSocks,
	}

	var sshKeyPath string 

	// ONLY process credentials if Authorization is actually toggled ON
	if useAuth {
		if protocol == "shadowsocks" || protocol == "ss" {
			if ssMethod == "" || ssMethod == "none" {
				ssMethod = "chacha20-ietf-poly1305"
			}
			proxyURL.User = url.UserPassword(ssMethod, pass)

		} else if user != "" && user != "none" {
			
			if protocol == "ssh" && strings.Contains(pass, "-----BEGIN") {
				proxyURL.User = url.User(user) 
				
				tmpFile, err := os.CreateTemp("", "vaydns_ssh_key_*")
				if err == nil {
					tmpFile.Write([]byte(pass)) 
					tmpFile.Close()
					sshKeyPath = tmpFile.Name()
					
					q := proxyURL.Query()
					q.Set("privateKeyFile", sshKeyPath)
					proxyURL.RawQuery = q.Encode()
				} else {
					log.Printf("VAY_DEBUG: Failed to create temp SSH key file: %v", err)
				}
			} else if pass != "" && pass != "none" {
				proxyURL.User = url.UserPassword(user, pass)
			} else {
				proxyURL.User = url.User(user)
			}
		}
	}

	proxyString := proxyURL.String()

	// 5. START TUN2SOCKS ENGINE
	wg.Add(1)
	go func() {
		defer wg.Done()
		
		time.Sleep(300 * time.Millisecond)
		
		key := &engine.Key{
			Proxy:  proxyString,
			Device: fmt.Sprintf("fd://%d", newFd),
			MTU:    1232,
			LogLevel: "silent",
		}
		engine.Insert(key)
		log.Printf("VAY_DEBUG: Tun2Socks Engine starting on FD %d with proxy %s", newFd, proxyString)
		engine.Start() 
	}()

	// 6. SHUTDOWN WATCHER
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-ctx.Done()
		log.Printf("VAY_DEBUG: Shutting down engine...")
		engine.Stop()
		
		if sshKeyPath != "" {
			os.Remove(sshKeyPath)
			log.Printf("VAY_DEBUG: Removed temporary SSH key file.")
		}
		
		log.Printf("VAY_DEBUG: Native engine fully dead.")		
	}()

	return fmt.Sprintf("Success: VPN Started on %s", internalSocks)
}

func StartVpn_no_auth(fd int, udp string, doh string, dot string, domain string, pubkey string, recordType string, idleTimeout string, KeepAlive string, clientIDSize int, compatDnstt bool, user string, pass string, protector SocketProtector) string {
	mu.Lock()

	// 1. SIGNAL OLD SESSION TO DIE
	if activeCancel != nil {
		log.Printf("VAY_DEBUG: Killing old session context")
		activeCancel() 
		time.Sleep(100 * time.Millisecond) //just added 
	}

	// 2. THE SECRET SAUCE: DUPLICATE THE FD
	// This prevents the 'fdsan' crash on Android 10+
	newFd, err := syscall.Dup(fd)
	if err != nil {
		mu.Unlock()
		return "Error: Failed to dup FD"
	}

//    syscall.Close(fd)

	activeWg = &sync.WaitGroup{}
	activeCtx, activeCancel = context.WithCancel(context.Background())
	isRunning = true

	// Capture locals for goroutines
	wg := activeWg
	ctx := activeCtx
	
	activeSocksPort = 10000 + rand.Intn(40000)
	internalSocks := fmt.Sprintf("127.0.0.1:%d", activeSocksPort)
	
	mu.Unlock()

	tCfg := bridge.TunnelConfig{
		UdpAddr: udp, DohURL: doh, DotAddr: dot, Domain: domain,
		ListenAddr: internalSocks, LogLevel: "error", UtlsDistribution: "chrome",
		RecordType: recordType, PubkeyHex: pubkey, Protector: protector,
		IdleTimeout: idleTimeout, KeepAlive: KeepAlive, UDPTimeout: "2s",
		ClientIDSize: clientIDSize, CompatDnstt: compatDnstt,
	}

	// 3. START THE DNS TUNNEL (The part that was missing!)
	wg.Add(1)
	go func() {
		defer wg.Done()
		if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
			log.Printf("VAY_DEBUG: Tunnel Error: %v", err)
		}
	}()

	// 4. START TUN2SOCKS ENGINE (Using newFd)
	wg.Add(1)
	go func() {
		defer wg.Done()
		
		time.Sleep(300 * time.Millisecond)
		
		key := &engine.Key{
			Proxy:  "socks5://" + internalSocks,
			Device: fmt.Sprintf("fd://%d", newFd),
			MTU:    1232, 
		}
		engine.Insert(key)
		log.Printf("VAY_DEBUG: Tun2Socks Engine starting on FD %d", newFd)
		engine.Start() 
	}()

	// 5. SHUTDOWN WATCHER
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-ctx.Done()
		log.Printf("VAY_DEBUG: Shutting down engine...")
		engine.Stop()
		// Important: We also close the newFd here because Go owns it now
//		syscall.Close(newFd) 
		log.Printf("VAY_DEBUG: Native engine fully dead.")		
	}()

	return fmt.Sprintf("Success: VPN Started on %s", internalSocks)
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

func VerifyTunnel() string {
	mu.Lock()
	port := activeSocksPort
	running := isRunning
	appCtx := activeCtx
	mu.Unlock()

	if !running || port == 0 || appCtx == nil {
		return "Fail: VPN not running"
	}

	proxyURL, _ := url.Parse(fmt.Sprintf("socks5://127.0.0.1:%d", port))
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
