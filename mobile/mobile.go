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
 
 
func StartVpn(fd int, udp string, doh string, dot string, domain string, pubkey string, recordType string, idleTimeout string, KeepAlive string, clientIDSize int, compatDnstt bool, user string, pass string, protector SocketProtector) string {
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
		ListenAddr: internalSocks, LogLevel: "info", UtlsDistribution: "chrome",
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
