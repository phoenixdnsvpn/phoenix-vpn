package f35

import (
	"context"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"
	"fmt"
//	"runtime/debug"

	"github.com/Starling226/vaydns-vpn/bridge"
)

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
			// Ensure worker function signature accepts context.Context
			worker(ctx, port, runtime, jobs, hooks, stats)
		}(runtime.StartPort + i)
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

func worker(ctx context.Context, port int, cfg *runtimeConfig, jobs <-chan parsedResolver, hooks Hooks, stats *scanStats) {
	// Panic recovery to catch the 0x70 error (nil pointer dereference)
	defer func() {
		if r := recover(); r != nil {
			// This logs the exact line causing the crash to LogCat
//			fmt.Printf("GO_WORKER_PANIC [%d]: %v\nstack: %s\n", port, r, debug.Stack())
			fmt.Printf("Worker %d recovered from an unexpected error: %v\n", port, r)
		}
	}()

	for {
		select {
		case <-ctx.Done(): 
			// Exit immediately if the scan is cancelled or grace period ends
			return
		case resolver, ok := <-jobs:
			if !ok {
				// No more resolvers in the channel
				return
			}

			// 1. Setup per-check networking
			proxyURL := &url.URL{
				Scheme: cfg.Proxy,
				Host:   net.JoinHostPort("127.0.0.1", strconv.Itoa(port)),
			}
			if cfg.ProxyUser != "" {
				proxyURL.User = url.UserPassword(cfg.ProxyUser, cfg.ProxyPass)
			}

			transport := &http.Transport{
				Proxy:             http.ProxyURL(proxyURL),
				DisableKeepAlives: true, // Do not reuse sockets for different resolvers
				DialContext: (&net.Dialer{
					Timeout:   30 * time.Second,
					KeepAlive: 30 * time.Second,
				}).DialContext,
			}

			client := &http.Client{
				Transport: transport,
			}

			// 2. Run the check
			// We pass 'ctx' so that if the scan is stopped, checkResolver 
			// cancels its internal tunnel and probes IMMEDIATELY.
			result, healthy := checkResolver(ctx, client, cfg, resolver, port)

			// 3. Clean up native sockets
			transport.CloseIdleConnections()

			// 4. Report results ONLY if the context is still active.
			// This is the most important check to prevent the 0x70 crash 
			// when the Android activity is closing.
			select {
			case <-ctx.Done():
				return
			default:
				// Call the Java hooks through Gomobile
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
	// 1. Link to parent context for coordinated shutdown
	tunnelCtx, cancelTunnel := context.WithCancel(ctx)
	defer cancelTunnel()

	listenAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))

	tCfg := bridge.TunnelConfig{
		UdpAddr:          resolver.addr,
		Domain:           cfg.Domain,
		ListenAddr:       listenAddr,
		LogLevel:         "error",
		UtlsDistribution: "chrome",
		RecordType:       "txt",
		CompatDnstt:      false,
		PubkeyHex:        cfg.Pubkey,
	}

	// 2. Start the tunnel with a recovery safety net
	go func() {
		defer func() {
			if r := recover(); r != nil {
				// This catches the 0x70 panic if the bridge is unstable
				fmt.Printf("CAUGHT_TUNNEL_PANIC [%s]: %v\n", resolver.addr, r)
			}
		}()

		err := bridge.RunTunnel(tunnelCtx, tCfg)
		if err != nil && err != context.Canceled {
			fmt.Printf("TUNNEL_ERROR [%s]: %v\n", resolver.addr, err)			
		}
	}()

	// 3. IMMEDIATE TERMINATION: Replace time.Sleep with a select block
	// This allows the worker to stop instantly if the scan ends during the wait
	select {
	case <-time.After(cfg.TunnelWait):
		// Tunnel wait finished naturally, proceed to probes
	case <-ctx.Done():
		// Main scan context was cancelled; exit immediately
		return Result{Resolver: resolver.addr, Probe: "stopped"}, false
	}

	result := Result{
		Resolver: resolver.addr,
		Download: "off",
		Upload:   "off",
		Whois:    "off",
		Probe:    "off",
	}

	isHealthy := false
	bestPriority := 0

	// 4. Context-Aware Probe Execution
	// Before each test, we check if the context is still valid

	// Download
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

	// Upload
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

	// Whois
	if cfg.Whois && ctx.Err() == nil {
		result.Whois = "fail"
		latency, org, country, ok := lookupResolverInfo(ctx, client, resolver.ip.String(), cfg.WhoisTimeout)
		if ok {
			result.Whois = "ok"
			result.Org = org
			result.Country = country
			if bestPriority < 2 {
				result.LatencyMS = latency
				bestPriority = 2
			}			
		}
	}

	// Probe
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

