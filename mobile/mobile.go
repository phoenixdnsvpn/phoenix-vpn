package mobile

import (
	"context"
	"fmt"
	"math/rand"
	"time"
	"sync"
	"github.com/Starling226/vaydns-vpn/bridge"
	"github.com/xjasonlyu/tun2socks/v2/engine"
	"log"
)

var (
	mu         sync.Mutex
	currentCtx    context.Context
	ctx    context.Context
	cancel context.CancelFunc
	isRunning  bool
)

type SocketProtector interface {
    Protect(fd int) bool
}

/**
 * StartVpn: The main entry point for Android System-Wide VPN.
 * @param fd      : The File Descriptor from Android's VpnService.establish()
 * @param udp     : UDP DNS Resolver address
 * @param doh     : DoH URL
 * @param dot     : DoT Address
 * @param domain  : Your tunnel domain
 * @param pubkey  : Your server's hex public key
 */

// Seed the random number generator once when the package is loaded
func init() {
	rand.Seed(time.Now().UnixNano())
}

func StartVpn(fd int, udp, doh, dot, domain, pubkey string, protector SocketProtector) string {

	mu.Lock()
	defer mu.Unlock()

	// 1. Safety check: Stop any existing instance
	if isRunning && cancel != nil {
		log.Printf("VAY_DEBUG: Stopping previous session before restart")
		cancel()
		time.Sleep(1500 * time.Millisecond) // give engine and tunnel time to shut down
	}

	ctx, cancel = context.WithCancel(context.Background())
	currentCtx = ctx
	isRunning = true

//	internalSocks := "127.0.0.1:10808"

//  Dynamic port to avoid bind conflicts
	port := 10800 + rand.Intn(901) // 10800-10900
	internalSocks := fmt.Sprintf("127.0.0.1:%d", port)


	// 2. Start the VayDNS Tunnel (SOCKS5 Server) - runs in background
	go func() {
		cfg := bridge.TunnelConfig{
			UdpAddr:      udp,
			DohURL:       doh,
			DotAddr:      dot,
			Domain:       domain,
			PubkeyHex:    pubkey,
			ListenAddr:   internalSocks,
			ClientIDSize: 2,
			Protector:    protector,
			UDPTimeout:   "2s",
			KeepAlive:    "5s",
		}
		log.Printf("VAY_DEBUG: Starting bridge on %s for domain %s", internalSocks, domain)

		err := bridge.RunTunnel(ctx, cfg)
		if err != nil {
			log.Printf("VAY_DEBUG: BRIDGE FATAL ERROR: %v", err)
		}
	}()

	// 3. Give the SOCKS5 server a tiny bit of time to bind
	time.Sleep(2000 * time.Millisecond)

	// 4. Configure and start tun2socks (global engine)
	key := &engine.Key{
		Proxy:  "socks5://" + internalSocks,
		Device: fmt.Sprintf("fd://%d", fd),
		MTU:    500,
	}
	engine.Insert(key)

	// Start the blocking engine in its own goroutine
	go func() {
		log.Printf("VAY_DEBUG: Starting tun2socks engine...")
		defer func() {
			if r := recover(); r != nil {
				log.Printf("VAY_DEBUG: tun2socks panic recovered: %v", r)
			}
		}()
		engine.Start() // this blocks until Stop() is called
	}()

	// Separate watcher that calls Stop() when context is cancelled
	go func() {
		<-ctx.Done()
		log.Printf("VAY_DEBUG: Context done → stopping tun2socks engine")
		engine.Stop()
		time.Sleep(800 * time.Millisecond) // let engine clean up
	}()

	return fmt.Sprintf("Success: Tunneling %s via FD %d", domain, fd)
}

/**
 * StopVpn: Cleans up all resources.
 */

func StopVpn() string {
	mu.Lock()
	defer mu.Unlock()

	if !isRunning || cancel == nil {
		return "VPN was not running"
	}

	log.Printf("VAY_DEBUG: StopVpn called")
	cancel()
	cancel = nil
	isRunning = false

	time.Sleep(2200 * time.Millisecond) // important: let engine and tunnel fully exit
	return "VPN Stopped"
}

/*func StopVpn() string {
	if cancel != nil {
		cancel()
		time.Sleep(500 * time.Millisecond) // Give the OS time to release port 10808
		cancel = nil
		return "VPN Stopped"
	}
	return "VPN was not running"
}*/

/**
 * StartTunnel: Standalone SOCKS5 mode (No System VPN).
 */
func StartTunnel(udp, doh, dot, domain, pubkey, listen string) string {
	if cancel != nil {
		cancel()
	}

	ctx, cancel = context.WithCancel(context.Background())

	cfg := bridge.TunnelConfig{
		UdpAddr:    udp,
		DohURL:     doh,
		DotAddr:    dot,
		Domain:     domain,
		PubkeyHex:  pubkey,
		ListenAddr: listen,
	}

	go bridge.RunTunnel(ctx, cfg)
	return "SOCKS5 Proxy Started"
}
