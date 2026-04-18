package f35

import (
	"context"
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
)

var globalDynamicPort int32

var dynamicPort int32 = 20000

// 🚨 CRITICAL FIX: The Global Tunnel Registry
// This prevents the Go Garbage Collector from deleting tunnels when the app backgrounds.
// By holding a permanent reference, we stop kcp-go finalizers from triggering the 0x70 panic.
var (
//	activeTunnels []context.CancelFunc
	tunnelMu      sync.Mutex
)

type scanStats struct {
	mu        sync.Mutex
	total     int
	processed int
	healthy   int
}

func ScanWithContext(ctx context.Context, cfg Config, hooks Hooks) error {
	// Clear the registry at the start of a new scan
	tunnelMu.Lock()
//	activeTunnels = make([]context.CancelFunc, 0)
	tunnelMu.Unlock()

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

			if resolver.ip == nil {
				fmt.Printf("DEBUG: Skipping nil IP for %s\n", resolver.addr)
				continue
			}

			uniquePort := int(atomic.AddInt32(&dynamicPort, 1))
            
			proxyURL := &url.URL{
				Scheme: cfg.Proxy,
				Host:   net.JoinHostPort("127.0.0.1", strconv.Itoa(uniquePort)),
			}
			if cfg.ProxyUser != "" {
				proxyURL.User = url.UserPassword(cfg.ProxyUser, cfg.ProxyPass)
			}

			transport := &http.Transport{
				Proxy:             http.ProxyURL(proxyURL),
				DisableKeepAlives: true, 
				DialContext: (&net.Dialer{
					Timeout:   30 * time.Second,
					KeepAlive: 30 * time.Second,
				}).DialContext,
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
	// 🚨 CRITICAL FIX: We create a context, but we DO NOT defer the cancellation.
	// We save the cancel function to a global registry so the GC can never touch the tunnel.
//	tunnelCtx, cancelTunnel := context.WithCancel(context.Background())
	
//	tunnelMu.Lock()
//	activeTunnels = append(activeTunnels, cancelTunnel)
//	tunnelMu.Unlock()

	
	listenAddr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))

	tCfg := ParseExtraArgs(cfg.ExtraArgs)
	tCfg.UdpAddr =  resolver.addr
	tCfg.ListenAddr  = listenAddr
	tCfg.Domain = cfg.Domain

	go func() {
		defer func() {
			if r := recover(); r != nil {
				fmt.Printf("CAUGHT_TUNNEL_PANIC [%s]: %v\n", resolver.addr, r)
			}
		}()

//		err := bridge.RunTunnel(tunnelCtx, tCfg)
		err := bridge.RunTunnel(context.Background(), tCfg) //Sandboxing
		if err != nil && err != context.Canceled {
			fmt.Printf("TUNNEL_ERROR [%s]: %v\n", resolver.addr, err)			
		}
	}()

	select {
	case <-time.After(cfg.TunnelWait):
	case <-ctx.Done():
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
		}
		i++
	}

	return config
}
