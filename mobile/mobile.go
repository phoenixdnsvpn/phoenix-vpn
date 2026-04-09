package mobile

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"sync"
	"time"
    "net/http"
    "net/url"
    
	"github.com/Starling226/vaydns-vpn/bridge"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu         sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	isRunning  bool
	wg         sync.WaitGroup // Used to ensure threads fully exit before StopVpn returns
	activeSocksPort int
)

// SocketProtector allows the Go code to call Android's VpnService.protect()
type SocketProtector interface {
	Protect(fd int) bool
}

func init() {
	// Seed the randomizer once at startup
	rand.Seed(time.Now().UnixNano())
}

/**
 * StartVpn: The main entry point for Android System-Wide VPN.
 */
func StartVpn(fd int, udp, doh, dot, domain, pubkey string, protector SocketProtector) string {
	mu.Lock()
	defer mu.Unlock()

	// 1. Safety check: Properly shut down any existing session
	if isRunning && cancel != nil {
		log.Printf("VAY_DEBUG: Stopping previous session before restart")
		cancel()
		wg.Wait() // Wait for all threads to confirm they've exited
	}

	// 2. Port Randomization: Avoids "address already in use" on rapid restarts
	randomPort := 10000 + rand.Intn(40000)
	activeSocksPort = randomPort
	internalSocks := fmt.Sprintf("127.0.0.1:%d", randomPort)
	log.Printf("VAY_DEBUG: VPN starting on %s with MTU 500", internalSocks)

	// 3. Initialize Lifecycle
	ctx, cancel = context.WithCancel(context.Background())
	isRunning = true

	tCfg := bridge.TunnelConfig{
		UdpAddr:          udp,
		DohURL:           doh,
		DotAddr:          dot,
		Domain:           domain,
		ListenAddr:       internalSocks,
		LogLevel:         "info",
		UtlsDistribution: "chrome",
		RecordType:       "txt",
		CompatDnstt:      false,
		PubkeyHex:        pubkey,
		Protector:        protector,
		// Performance Settings
		UDPTimeout:       "2s",
		KeepAlive:        "5s",
	}

	// 4. Start Tunnel Goroutine
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer func() {
			if r := recover(); r != nil {
				log.Printf("VAY_DEBUG: Tunnel Panic Recovered: %v", r)
			}
		}()
		
		if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
			log.Printf("VAY_DEBUG: Tunnel Error: %v", err)
		}
	}()

	// 5. Start Tun2Socks Engine with MTU 500
	key := &engine.Key{
		Proxy:  "socks5://" + internalSocks,
		Device: fmt.Sprintf("fd://%d", fd),
		MTU:    500, // Critical for preventing fragmentation on filtered networks
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		defer func() {
			if r := recover(); r != nil {
				log.Printf("VAY_DEBUG: Engine Panic Recovered: %v", r)
			}
		}()

		engine.Insert(key)
		
		log.Printf("VAY_DEBUG: Tun2Socks Engine starting")
		engine.Start() // This blocks until engine.Stop() is called
	}()

	// 6. Graceful Watcher: Bridges the Context to the Engine's manual Stop()
	wg.Add(1)
	go func() {
		defer wg.Done()
		<-ctx.Done()
		log.Printf("VAY_DEBUG: Context cancelled, signaling engine shutdown")
		engine.Stop()
	}()

	return fmt.Sprintf("Success: VPN Started on %s", internalSocks)
}


/**
 * StartTunnel: Standalone SOCKS5 mode (No System VPN).
 */
func StartTunnel(udp, doh, dot, domain, pubkey string, protector SocketProtector) string {
	mu.Lock()
	defer mu.Unlock()

	if isRunning && cancel != nil {
		cancel()
		wg.Wait()
	}

	randomPort := 10000 + rand.Intn(40000)
	internalSocks := fmt.Sprintf("127.0.0.1:%d", randomPort)

	ctx, cancel = context.WithCancel(context.Background())
	isRunning = true

	tCfg := bridge.TunnelConfig{
		UdpAddr:          udp,
		DohURL:           doh,
		DotAddr:          dot,
		Domain:           domain,
		ListenAddr:       internalSocks,
		LogLevel:         "info",
		UtlsDistribution: "chrome",
		RecordType:       "txt",
		CompatDnstt:      false,
		PubkeyHex:        pubkey,
		Protector:        protector,
		UDPTimeout:       "2s",
		KeepAlive:        "5s",
	}

	wg.Add(1)
	go func() {
		defer wg.Done()
		defer func() {
			if r := recover(); r != nil {
				log.Printf("VAY_DEBUG: Standalone Tunnel Panic: %v", r)
			}
		}()
		
		log.Printf("VAY_DEBUG: Standalone Tunnel starting on %s", internalSocks)
		if err := bridge.RunTunnel(ctx, tCfg); err != nil && err != context.Canceled {
			log.Printf("VAY_DEBUG: Standalone Tunnel Error: %v", err)
		}
	}()

	return fmt.Sprintf("Success: Standalone Tunnel started on %s", internalSocks)
}

/**
 * StopVpn: Cleans up all resources.
 */

func StopVpn() string {
	mu.Lock()
	if !isRunning || cancel == nil {
		mu.Unlock()
		return "VPN was not running"
	}

	log.Printf("VAY_DEBUG: Initiating synchronized shutdown")
	
	// 1. Signal all goroutines to exit
	cancel()
	
	// 2. IMPORTANT: Unlock before waiting! 
	// This allows StartVpn to run if needed, though usually we wait.
	mu.Unlock() 

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	
	select {
	case <-done:
		log.Printf("VAY_DEBUG: All threads exited cleanly")
	case <-time.After(2 * time.Second):
		log.Printf("VAY_DEBUG: Shutdown timed out - forcing release")
	}
		
	// 3. Wait for goroutines to confirm exit
	// This replaces the unstable time.Sleep()

	mu.Lock()
	cancel = nil
	isRunning = false
	mu.Unlock()

//	log.Printf("VAY_DEBUG: All threads finished. Safe to stop service.")
	return "VPN Stopped"
}
 
/*
func StopVpn() string {
	mu.Lock()
	defer mu.Unlock()

	if !isRunning || cancel == nil {
		return "VPN was not running"
	}

	log.Printf("VAY_DEBUG: StopVpn called - triggering synchronized shutdown")
	
	// Signal all goroutines (Tunnel, Engine, Watcher) to stop
	cancel()

	// CRITICAL: Block until every thread has confirmed it finished cleanup.
	// This ensures port 10808 (or the random port) and FD are truly released.
	wg.Wait()

	cancel = nil
	isRunning = false
	log.Printf("VAY_DEBUG: All threads exited. VPN resources cleared.")

	return "VPN Stopped"
}
*/

func VerifyTunnel() string {
	mu.Lock()
	port := activeSocksPort
	running := isRunning
	appCtx := ctx
	mu.Unlock()

	if !running || port == 0 || appCtx == nil {
//	if !running || port == 0 {
		return "Fail: VPN not running"
	}

	// 1. Build a client that strictly routes through the local vaydns proxy
	proxyURL, _ := url.Parse(fmt.Sprintf("socks5://127.0.0.1:%d", port))
	client := &http.Client{
		Transport: &http.Transport{
			Proxy: http.ProxyURL(proxyURL),
		},
		Timeout: 30 * time.Second, // Give the DNS tunnel 30s to handshake
	}

	// 2. Do a lightweight HTTPS HEAD request
	req, err := http.NewRequest("HEAD", "https://1.1.1.1", nil)
	if err != nil {
		return "Fail: " + err.Error()
	}

	// 3. Fire the request. 
	// If the domain is fake, vaydns will fail to resolve it, and client.Do will return an error.
	resp, err := client.Do(req)
	if err != nil {
		return "Fail: " + err.Error()
	}
	defer resp.Body.Close()

	// If we get a response, the tunnel is 100% active end-to-end.
	return "Success"
}
