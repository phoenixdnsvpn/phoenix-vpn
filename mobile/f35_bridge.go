package mobile

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"sync/atomic"	
	"time"
	"fmt"
	"net/http"
	"runtime"
	"runtime/debug"
	
	"github.com/Starling226/vaydns-vpn/f35"
)

var (	
	scanCancel context.CancelFunc
	scanMu     sync.Mutex
	
	// --- THE POLLING QUEUE ---
	scanStatus  string
	scanError   string
	resultQueue []string
	resultMu    sync.Mutex
	gcCounter int32
	testCounter int32
)

// StartF35Scan initiates a scan using the f35 engine logic.
func StartF35Scan(
	isDefault bool, configIndex int64, dnsMode string, customDomain string, customPublicKey string,
	resolversList string, baseDohUrl string, proxyType string, tunnelProtocol string, proxyUser string,
	proxyPass string, ssMethod string, recordType string, idleTimeout string, keepAlive string,
	clientIdSize int, mtu int, workers int, tunnelWait int, udpTimeout int, probeTimeout int, 
	retries int, lightE2EEnabled bool, engineQuickScan bool,
) string {
	scanMu.Lock()
	if scanCancel != nil {
		scanCancel() 
	}
	   	
	ctx, cancel := context.WithCancel(context.Background())
	scanCancel = cancel
	scanStatus = "started"
	scanError = ""
	scanMu.Unlock()
	
	// Reset the results queue for a fresh scan
	resultMu.Lock()
	resultQueue = make([]string, 0)
	resultMu.Unlock()
	
	fmt.Println("VAY_DEBUG: StartF35Scan entry")

	domainToUse := customDomain
	pubkeyToUse := customPublicKey

	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
		pubkeyToUse = getDefaultConfigPubkey(configIndex)
		recordType = GetDefaultConfigRecordType(configIndex)
		idleTimeout = GetDefaultConfigIdleTimeout(configIndex)
		keepAlive = GetDefaultConfigKeepAlive(configIndex)
		clientIdSize = int(GetDefaultConfigClientIdSize(configIndex))
		proxyUser = getDefaultConfigUser(configIndex)
		proxyPass = getDefaultConfigPass(configIndex)
	}
	
//	engineQuickScan = false //we will enable this in future
	cfg := f35.DefaultConfig()
	cfg.Mode = strings.ToLower(dnsMode)
		
	rawResolvers := strings.Split(resolversList, ",")
	var cleaned []string
	realToFake := make(map[string]string) 

	for _, r := range rawResolvers {
		fakeFull := strings.TrimSpace(r)
		if fakeFull != "" {
			realFull := fakeFull
			if !strings.HasPrefix(fakeFull, "http") {
				translated := translateFakeToReal(fakeFull)
//				fmt.Printf("VAY_DEBUG %v %v\n",fakeFull,translated)

				if translated != "" {
					realFull = translated
				}
			}
			realToFake[realFull] = fakeFull       
			cleaned = append(cleaned, realFull) 
		}
	}
	
	if len(cleaned) == 0 {
		return "error|No resolvers provided"
	}

	cfg.Resolvers = cleaned
	cfg.Domain = domainToUse
	cfg.Probe = true
	cfg.ProbeURL = "http://www.google.com/gen_204"
	cfg.ProbeTimeout = time.Duration(probeTimeout) * time.Second
	cfg.Proxy = proxyType
	cfg.Workers = workers
	cfg.Retries = retries
	cfg.TunnelWait = time.Duration(tunnelWait) * time.Millisecond
	cfg.StartPort = 40000
	cfg.Whois = false
	cfg.Download = false
	cfg.Upload = false
	cfg.Engine = "vaydns"

	if proxyUser != "" && proxyUser != "none" {
		cfg.ProxyUser = proxyUser
	}
	if proxyPass != "" && proxyPass != "none" {
		cfg.ProxyPass = proxyPass
	}
//    fmt.Printf("VAY_DEBUG:  proxyType %v  tunnelProtocol %v \n", proxyType, tunnelProtocol)
    
	effectiveUdpTimeout := udpTimeout
    if strings.ToLower(dnsMode) == "doh" || strings.ToLower(dnsMode) == "dot" {
        if effectiveUdpTimeout < 5000 {
            effectiveUdpTimeout = 5000 
        }
    }
        
	cfg.ExtraArgs = []string{
		"-base-doh", baseDohUrl,
		"-pubkey", pubkeyToUse,
		"-record-type", strings.ToLower(recordType),
		"-log-level", "error",
		"-clientid-size", fmt.Sprintf("%d", clientIdSize),
		"-utls", "chrome",
		"-keepalive", keepAlive,
		"-idle-timeout", idleTimeout,
		"-mtu", fmt.Sprintf("%d", mtu),		
		"-udp-timeout", fmt.Sprintf("%dms", udpTimeout),
		"-protocol", tunnelProtocol,
		"-ss-method", ssMethod,
		"-user", proxyUser,
		"-pass", proxyPass,
		"-fast-fail-enabled", fmt.Sprintf("%v", lightE2EEnabled),
		"-quick-scan", fmt.Sprintf("%v", engineQuickScan),
	}

	cfg.Pubkey = pubkeyToUse 
	if err := f35.ValidateConfig(cfg); err != nil {
		return "error|" + err.Error()
	}

	hooks := f35.Hooks{
		OnResult: func(res f35.Result) {
//			fmt.Printf("GO_DEBUG: Result generated for %s - Latency: %d\n", res.Resolver, res.LatencyMS)
			select {
			case <-ctx.Done():
				return
			default:
		
				if fakeIP, exists := realToFake[res.Resolver]; exists {
					res.Resolver = fakeIP 
				}
//				fmt.Printf("VAY_DEBUG: Progress - Resolver: %s  %d\n", res.Resolver, res.LatencyMS)
				jsonMap := map[string]interface{}{
					"resolver":   res.Resolver,
					"latency_ms": res.LatencyMS,
				}				
				b, _ := json.Marshal(jsonMap)
				
				resultMu.Lock()
				resultQueue = append(resultQueue, string(b))
				resultMu.Unlock()
			}
		},
	}
	
	go func() {	                	
		defer func() {
			if r := recover(); r != nil {
				fmt.Printf("GO_BRIDGE_PANIC: %v\n", r)
			}
		}()

		err := f35.ScanWithContext(ctx, cfg, hooks)
		
		// Update Engine Status when finished
		scanMu.Lock()
		if err != nil && err != context.Canceled {
			scanStatus = "error"
			scanError = err.Error()
		} else {
			scanStatus = "finished"
		}
		scanMu.Unlock()

		time.Sleep(3 * time.Second)
		cancel() 

		scanMu.Lock()
		scanCancel = nil
		scanMu.Unlock()
	}()	
	
	return "started"
}

func GetScanResults() string {
	resultMu.Lock()
	defer resultMu.Unlock()
	
	if len(resultQueue) == 0 {
		return ""
	}
	
	res := strings.Join(resultQueue, "\n")
	resultQueue = make([]string, 0) // Empty the queue instantly
	return res
}

// KOTLIN FETCHES THE ENGINE STATUS ONCE A SECOND
func GetScanStatus() string {
	scanMu.Lock()
	defer scanMu.Unlock()
	if scanStatus == "error" {
		return "error|" + scanError
	}
	return scanStatus
}

func StopF35Scan() {
	scanMu.Lock()
	if scanCancel != nil {
		scanCancel()
		scanCancel = nil
	}
	scanStatus = "stopped"
	scanMu.Unlock()

	if transport, ok := http.DefaultTransport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
}
