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

	// 1. Fallback to standard SOCKS if authentication credentials are missing
	if proxyUser == "" || proxyUser == "none" {
		if tunnelProtocol == "ssh" {
			tunnelProtocol = "socks"
		}
	}
	if proxyPass == "" || proxyPass == "none" {
		if tunnelProtocol == "shadowsocks" || tunnelProtocol == "ss" {
			tunnelProtocol = "socks"
		}
	}

	// 2. Mathematically reconstruct the squashed PEM key from Android
	if tunnelProtocol == "ssh" && strings.Contains(proxyPass, "-----BEGIN") {
		proxyPass = FormatSSHKey(proxyPass)
	}
	
/*
fmt.Printf("VAY_DEBUG: --- StartF35Scan Parameters ---\n")
	fmt.Printf("VAY_DEBUG: isDefault: %t\n", isDefault)
	fmt.Printf("VAY_DEBUG: configIndex: %d\n", configIndex)
	fmt.Printf("VAY_DEBUG: dnsMode: %q\n", dnsMode)
	fmt.Printf("VAY_DEBUG: customDomain: %q\n", customDomain)
	fmt.Printf("VAY_DEBUG: customPublicKey: %q\n", customPublicKey)
	fmt.Printf("VAY_DEBUG: resolversList length: %d chars\n", len(resolversList))
	fmt.Printf("VAY_DEBUG: baseDohUrl: %q\n", baseDohUrl)
	fmt.Printf("VAY_DEBUG: proxyType: %q\n", proxyType)
	fmt.Printf("VAY_DEBUG: tunnelProtocol: %q\n", tunnelProtocol)
	fmt.Printf("VAY_DEBUG: proxyUser: %q\n", proxyUser)
	fmt.Printf("VAY_DEBUG: proxyPass: %q\n", proxyPass)
	fmt.Printf("VAY_DEBUG: ssMethod: %q\n", ssMethod)
	fmt.Printf("VAY_DEBUG: recordType: %q\n", recordType)
	fmt.Printf("VAY_DEBUG: idleTimeout: %q\n", idleTimeout)
	fmt.Printf("VAY_DEBUG: keepAlive: %q\n", keepAlive)
	fmt.Printf("VAY_DEBUG: clientIdSize: %d\n", clientIdSize)
	fmt.Printf("VAY_DEBUG: mtu: %d\n", mtu)
	fmt.Printf("VAY_DEBUG: workers: %d\n", workers)
	fmt.Printf("VAY_DEBUG: tunnelWait: %d\n", tunnelWait)
	fmt.Printf("VAY_DEBUG: udpTimeout: %d\n", udpTimeout)
	fmt.Printf("VAY_DEBUG: probeTimeout: %d\n", probeTimeout)
	fmt.Printf("VAY_DEBUG: retries: %d\n", retries)
	fmt.Printf("VAY_DEBUG: lightE2EEnabled: %t\n", lightE2EEnabled)
	fmt.Printf("VAY_DEBUG: engineQuickScan: %t\n", engineQuickScan)
	fmt.Printf("VAY_DEBUG: --------------------------------\n")
*/
	
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

/*
	hooks := f35.Hooks{
	    OnResult: func(res f35.Result) {
	        // 1. Increment the counter thread-safely
	        count := atomic.AddInt32(&testCounter, 1)

	        // 2. Print directly to Logcat
	        // Standard fmt.Printf in Go Mobile usually maps to Logcat 'I' or 'D'
	        fmt.Printf("VAY_DEBUG: Progress [%d / 5024] - Resolver: %s\n", count, res.Resolver)

	        // 3. Ghost Mode: Do NOT store or queue anything
	        // resultMu.Lock() ... (commented out)
	    },
	}
*/

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

func GetScanResults4() string {
    // 📭 Kotlin will call this and get nothing, keeping Java RAM empty
    return ""
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


// 🔴 KOTLIN FETCHES THE RESULTS ONCE A SECOND
func GetScanResults3() string {
	resultMu.Lock()
	defer resultMu.Unlock()
	
	if len(resultQueue) == 0 {
		return ""
	}
	
	count := len(resultQueue)
	res := strings.Join(resultQueue, "\n")
	resultQueue = make([]string, 0) // Empty the queue instantly
	
	atomic.AddInt32(&gcCounter, int32(count))
    if atomic.LoadInt32(&gcCounter) > 1000 {
        atomic.StoreInt32(&gcCounter, 0)
        go func() {
            runtime.GC()
            debug.FreeOSMemory()
        }()
    }
    	    	
	return res
}

// 🔴 KOTLIN FETCHES THE ENGINE STATUS ONCE A SECOND
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
