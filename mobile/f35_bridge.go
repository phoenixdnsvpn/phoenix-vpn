package mobile

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
//	"sync/atomic"	
	"time"
	"fmt"
	"net/http"
	"runtime"
	"runtime/debug"
	"log"
	
	"github.com/Starling226/phoenix-vpn/f35"
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

var (	
	pingCancel context.CancelFunc
	pingMu     sync.Mutex
)

var (	
	rowPingCancel context.CancelFunc
	rowPingMu     sync.Mutex
)

var (
	domainScanCancel context.CancelFunc
	domainScanMu     sync.Mutex
)

type PingTask struct {
	ID           string `json:"id"`
	IsDefault    bool   `json:"is_default"`
	ConfigIndex  int64  `json:"config_index"`
	DomainIndex  int64  `json:"domain_index"`
	ConfigType   string `json:"config_type"` 
	ServerIP     string `json:"server_ip"`   
	DnsMode      string `json:"dns_mode"`
	CustomDomain string `json:"custom_domain"`
	CustomPubkey string `json:"custom_pubkey"`
	Resolvers    string `json:"resolvers"`
	BaseDohUrl   string `json:"base_doh_url"`
	ProxyType    string `json:"proxy_type"`
	Protocol     string `json:"protocol"`
	User         string `json:"user"`
	Pass         string `json:"pass"`
	SSMethod     string `json:"ss_method"`
	RecordType   string `json:"record_type"`
	IdleTimeout  string `json:"idle_timeout"`
	KeepAlive    string `json:"keep_alive"`
	ClientIDSize int64  `json:"client_id_size"`
	MTU          int64  `json:"mtu"`
}

// StartF35Scan initiates a scan using the f35 engine logic.
func StartF35Scan(
	isDefault bool, configIndex int64, domainIndex int64, dnsMode string, customDomain string, customPublicKey string,
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

	domainToUse = ExtractActiveDomain(domainToUse, domainIndex, false)

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
	cfg.ProbeTimeout = time.Duration(probeTimeout) * time.Millisecond
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

// PingMultipleServers concurrently tests all backup resolvers using the advanced f35 worker engine
// and prints detailed real-time benchmarks for each node directly to Logcat.
func PingMultipleServers(
	isDefault bool, configIndex int64, domainIndex int64, dnsMode string, customDomain string, customPublicKey string,
	resolversList string, baseDohUrl string, proxyType string, tunnelProtocol string, proxyUser string,
	proxyPass string, ssMethod string, recordType string, idleTimeout string, keepAlive string,
	clientIdSize int64, mtu int64, workers int64, tunnelWait int64, udpTimeout int64, probeTimeout int64, 
	retries int64, lightE2EEnabled bool, engineQuickScan bool,
) int64 {

	// fmt.Printf("VAY_DEBUG: [Multi-Ping Engine] Starting parallel batch check for resolvers group...\n")

	rowPingMu.Lock()
	ctx, cancel := context.WithCancel(context.Background())
	rowPingCancel = cancel
	rowPingMu.Unlock()
	
	// fmt.Printf("VAY_DEBUG: A [Multi-Ping Engine] %v - %v - %v\n",tunnelProtocol,proxyUser,proxyPass)
	domainToUse := customDomain
	pubkeyToUse := customPublicKey

	if isDefault {
		// fmt.Printf("VAY_DEBUG: B [Multi-Ping Engine] %v - %v - %v\n",tunnelProtocol,proxyUser,proxyPass)
		domainToUse = getDefaultConfigDomain(configIndex)
		pubkeyToUse = getDefaultConfigPubkey(configIndex)
		recordType = GetDefaultConfigRecordType(configIndex)
		idleTimeout = GetDefaultConfigIdleTimeout(configIndex)
		keepAlive = GetDefaultConfigKeepAlive(configIndex)
		clientIdSize = GetDefaultConfigClientIdSize(configIndex)
		proxyUser = getDefaultConfigUser(configIndex)
		proxyPass = getDefaultConfigPass(configIndex)
		// fmt.Printf("VAY_DEBUG: C [Multi-Ping Engine] %v - %v - %v\n",tunnelProtocol,proxyUser,proxyPass)
	}

	domainToUse = ExtractActiveDomain(domainToUse, domainIndex, false)

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

	if tunnelProtocol == "ssh" && strings.Contains(proxyPass, "-----BEGIN") {
		proxyPass = FormatSSHKey(proxyPass)
	}

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
				if translated != "" {
					realFull = translated
				}
			}
			realToFake[realFull] = fakeFull       
			cleaned = append(cleaned, realFull) 
		}
	}

	if len(cleaned) == 0 {
		fmt.Printf("VAY_DEBUG: [Multi-Ping Engine] Aborted - No valid resolvers provided.\n")
		return -2
	}

	cfg.Resolvers = cleaned
	cfg.Domain = domainToUse
	cfg.Probe = true
	cfg.ProbeURL = "http://www.google.com/gen_204"
	cfg.ProbeTimeout = time.Duration(probeTimeout) * time.Millisecond
	cfg.Proxy = proxyType
	cfg.Workers = int(workers)
	cfg.Retries = int(retries)
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
		"-udp-timeout", fmt.Sprintf("%dms", effectiveUdpTimeout),
		"-protocol", tunnelProtocol,
		"-ss-method", ssMethod,
		"-user", proxyUser,
		"-pass", proxyPass,
		"-fast-fail-enabled", fmt.Sprintf("%v", lightE2EEnabled),
		"-quick-scan", fmt.Sprintf("%v", engineQuickScan),
	}
	cfg.Pubkey = pubkeyToUse

	if err := f35.ValidateConfig(cfg); err != nil {
		fmt.Printf("VAY_DEBUG: [Multi-Ping Engine] Config Validation Failed: %v\n", err)
		return -2
	}

	var minLatency int64 = 99999
	var mu sync.Mutex

	// HOOKS ENGINE WITH REAL-TIME LOGCAT PRINT TRACES
	hooks := f35.Hooks{
		OnResult: func(res f35.Result) {
			// Map real IP references back onto fake string formats if needed
			displayIP := res.Resolver
			if fakeIP, exists := realToFake[res.Resolver]; exists {
				displayIP = fakeIP
			}

			if res.Probe == "ok" && res.LatencyMS > 0 && res.LatencyMS < 99999 {
				// Success Log Print
				fmt.Printf("VAY_DEBUG: [Multi-Ping Worker] -> %s responded SUCCESS in %dms\n", displayIP, res.LatencyMS)
				
				mu.Lock()
				if res.LatencyMS < minLatency {
					minLatency = res.LatencyMS
				}
				mu.Unlock()
			} else {
				// Failure/Timeout Log Print
				fmt.Printf("VAY_DEBUG: [Multi-Ping Worker] -> %s FAILED/DEAD (Status: %s, Latency: %dms)\n", displayIP, res.Probe, res.LatencyMS)
			}
		},
	}

	// Execute synchronously using the job-channels engine embedded within scan.go
//	_ = f35.ScanWithContext(context.Background(), cfg, hooks)
	_ = f35.ScanWithContext(ctx, cfg, hooks)	

	if minLatency == 99999 {
		fmt.Printf("VAY_DEBUG: [Multi-Ping Engine] Finished - All resolvers are DEAD. Returning -2.\n")
		return -2
	}

	fmt.Printf("VAY_DEBUG: [Multi-Ping Engine] Finished - Absolute fastest latency discovered: %dms\n", minLatency)
	return minLatency
}

// PingAllConfigs concurrently runs the advanced f35 engine across ALL configs at once.
func PingAllConfigs(
	tasksJson string, workers int, tunnelWait int, udpTimeout int, 
	probeTimeout int, retries int, lightE2EEnabled bool, engineQuickScan bool,
) string {

pingMu.Lock()
	ctx, cancel := context.WithCancel(context.Background())
	pingCancel = cancel
	pingMu.Unlock()
	
	var tasks []PingTask
	if err := json.Unmarshal([]byte(tasksJson), &tasks); err != nil {
		return fmt.Sprintf(`{"error":%q}`, err.Error())
	}

	var wg sync.WaitGroup
	results := make(map[string]int64)
	var mu sync.Mutex

	// Parallel Task Dispatch: Spawns isolated execution environments for each config simultaneously
	for _, task := range tasks {
		wg.Add(1)
		go func(t PingTask) {
			defer wg.Done()
			defer func() { recover() }() // Guard rail: Prevent a single configuration crash from killing the app

			// =========================================================
			// SKIP DIRECT CONFIGS IN THE VAYDNS SCANNER
			// =========================================================
			configType := t.ConfigType
			if configType == "" {
				configType = "vaydns"
			}
			//if t.IsDefault {
			//	configType = GetDefaultConfigType(t.ConfigIndex)
			//}

			if configType != "vaydns" {
				return // Immediately exit the goroutine. The Direct Scanner handles this.
			}
			
			domainToUse := t.CustomDomain
			pubkeyToUse := t.CustomPubkey
			recordType := t.RecordType
			idleTimeout := t.IdleTimeout
			keepAlive := t.KeepAlive
			clientIdSize := t.ClientIDSize
			proxyUser := t.User
			proxyPass := t.Pass
			dnsMode := t.DnsMode
			tunnelProtocol := t.Protocol
			proxyType := t.ProxyType

			// Official Config Parameter Recovery Layer
			if t.IsDefault {
				domainToUse = getDefaultConfigDomain(t.ConfigIndex)
				pubkeyToUse = getDefaultConfigPubkey(t.ConfigIndex)
				recordType = GetDefaultConfigRecordType(t.ConfigIndex)
				idleTimeout = GetDefaultConfigIdleTimeout(t.ConfigIndex)
				keepAlive = GetDefaultConfigKeepAlive(t.ConfigIndex)
				clientIdSize = GetDefaultConfigClientIdSize(t.ConfigIndex)
				proxyUser = getDefaultConfigUser(t.ConfigIndex)
				proxyPass = getDefaultConfigPass(t.ConfigIndex)
				dnsMode = t.DnsMode // Retain active mode set from main workspace rows
			}

			domainToUse = ExtractActiveDomain(domainToUse, t.DomainIndex, false)

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

			if tunnelProtocol == "ssh" && strings.Contains(proxyPass, "-----BEGIN") {
				proxyPass = FormatSSHKey(proxyPass)
			}

			cfg := f35.DefaultConfig()
			cfg.Mode = strings.ToLower(dnsMode)

			rawResolvers := strings.Split(t.Resolvers, ",")
			var cleaned []string
			for _, r := range rawResolvers {
				trimmed := strings.TrimSpace(r)
				if trimmed != "" {
					if !strings.HasPrefix(trimmed, "http") {
						translated := translateFakeToReal(trimmed)
						if translated != "" {
							trimmed = translated
						}
					}
					cleaned = append(cleaned, trimmed)
				}
			}

			if len(cleaned) == 0 {
				mu.Lock()
				results[t.ID] = -2
				mu.Unlock()
				return
			}

			cfg.Resolvers = cleaned
			cfg.Domain = domainToUse
			cfg.Probe = true
			cfg.ProbeURL = "http://www.google.com/gen_204"
			cfg.ProbeTimeout = time.Duration(probeTimeout) * time.Millisecond
			cfg.Proxy = proxyType
			cfg.Workers = workers
			cfg.Retries = retries
			cfg.TunnelWait = time.Duration(tunnelWait) * time.Millisecond
			cfg.StartPort = 42000
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

			effectiveUdpTimeout := udpTimeout
			if strings.ToLower(dnsMode) == "doh" || strings.ToLower(dnsMode) == "dot" {
				if effectiveUdpTimeout < 5000 {
					effectiveUdpTimeout = 5000
				}
			}

			cfg.ExtraArgs = []string{
				"-base-doh", t.BaseDohUrl,
				"-pubkey", pubkeyToUse,
				"-record-type", strings.ToLower(recordType),
				"-log-level", "error",
				"-clientid-size", fmt.Sprintf("%d", clientIdSize),
				"-utls", "chrome",
				"-keepalive", keepAlive,
				"-idle-timeout", idleTimeout,
				"-mtu", fmt.Sprintf("%d", t.MTU),
				"-udp-timeout", fmt.Sprintf("%dms", effectiveUdpTimeout),
				"-protocol", tunnelProtocol,
				"-ss-method", t.SSMethod,
				"-user", proxyUser,
				"-pass", proxyPass,
				"-fast-fail-enabled", fmt.Sprintf("%v", lightE2EEnabled),
				"-quick-scan", fmt.Sprintf("%v", engineQuickScan),
			}
			cfg.Pubkey = pubkeyToUse

			if err := f35.ValidateConfig(cfg); err != nil {
				mu.Lock()
				results[t.ID] = -2
				mu.Unlock()
				return
			}

			var minLatency int64 = 99999
			hooks := f35.Hooks{
				OnResult: func(res f35.Result) {
					if res.Probe == "ok" && res.LatencyMS > 0 && res.LatencyMS < 99999 {
						if res.LatencyMS < minLatency {
							minLatency = res.LatencyMS
						}
					}
				},
			}

			// Native Multiplex Scope handling internally via jobs inside scan.go
//			_ = f35.ScanWithContext(context.Background(), cfg, hooks)
			_ = f35.ScanWithContext(ctx, cfg, hooks)

			mu.Lock()
			if minLatency == 99999 {
				results[t.ID] = -2
			} else {
				results[t.ID] = minLatency
			}
			mu.Unlock()
		}(task)
		
		// CRITICAL ROUTER GUARDRAIL: Stagger the kickoff of each config engine profile.
		// Spreading out the launch sequence across 50ms slices breaks up the initial localized
		// packet avalanche, allowing home router state tables to track the requests cleanly.
		time.Sleep(50 * time.Millisecond)		
	}

	wg.Wait()

	bytes, _ := json.Marshal(results)
	return string(bytes)
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

// CheckHealthyDomains tests a list of domains against a resolver using the f35 E2E scanner engine.
func CheckHealthyDomains(
	useMultiDomains bool, isDefault bool, configIndex int64,
	domainIndex int64, domains string, resolverIP string,
	dnsMode string, pubkey string, baseDohUrl string, proxyType string,
	tunnelProtocol string, proxyUser string, proxyPass string, ssMethod string,
	recordType string, idleTimeout string, keepAlive string,
	clientIdSize int64, mtu int64, workers int64, tunnelWait int64,
	udpTimeout int64, probeTimeout int64, retries int64, lightE2EEnabled bool, engineQuickScan bool,
) string {

	domainToUse := domains
	pubkeyToUse := pubkey

	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
		pubkeyToUse = getDefaultConfigPubkey(configIndex)
		recordType = GetDefaultConfigRecordType(configIndex)
		idleTimeout = GetDefaultConfigIdleTimeout(configIndex)
		keepAlive = GetDefaultConfigKeepAlive(configIndex)
		clientIdSize = GetDefaultConfigClientIdSize(configIndex)
		proxyUser = getDefaultConfigUser(configIndex)
		proxyPass = getDefaultConfigPass(configIndex)
	}

	domainToUse = ExtractActiveDomain(domainToUse, domainIndex, useMultiDomains)
    log.Printf("VAY_DEBUG: Domain to USe: %v", domainToUse)

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

	if tunnelProtocol == "ssh" && strings.Contains(proxyPass, "-----BEGIN") {
		proxyPass = FormatSSHKey(proxyPass)
	}

    // not used for now since we always send mutiple domains
	if !useMultiDomains {
		parts := strings.Split(domainToUse, ",")
		if len(parts) > 0 {
			domainToUse = strings.TrimSpace(parts[0])
		}
	}

	fmt.Printf("VAY_DEBUG: [Domain Scanner E2E] Target: '%s', MultiDomains: %v\n", domainToUse, useMultiDomains)

	rawDomains := strings.Split(domainToUse, ",")
	var healthy []string
	var mu sync.Mutex
	var wg sync.WaitGroup

	// Prepare base config
	cfgBase := f35.DefaultConfig()
	cfgBase.Mode = strings.ToLower(dnsMode)

	fakeFull := strings.TrimSpace(resolverIP)
	realFull := fakeFull
	if fakeFull != "" && !strings.HasPrefix(fakeFull, "http") {
		translated := translateFakeToReal(fakeFull)
		if translated != "" {
			realFull = translated
		}
	}
	cfgBase.Resolvers = []string{realFull}

	cfgBase.Probe = true
	cfgBase.ProbeURL = "http://www.google.com/gen_204"
	cfgBase.ProbeTimeout = time.Duration(probeTimeout) * time.Millisecond
	cfgBase.Proxy = proxyType
	cfgBase.Workers = int(workers)
	cfgBase.Retries = int(retries)
	cfgBase.TunnelWait = time.Duration(tunnelWait) * time.Millisecond
	cfgBase.StartPort = 44000
	cfgBase.Whois = false
	cfgBase.Download = false
	cfgBase.Upload = false
	cfgBase.Engine = "vaydns"

	if proxyUser != "" && proxyUser != "none" {
		cfgBase.ProxyUser = proxyUser
	}
	if proxyPass != "" && proxyPass != "none" {
		cfgBase.ProxyPass = proxyPass
	}

	effectiveUdpTimeout := udpTimeout
	if strings.ToLower(dnsMode) == "doh" || strings.ToLower(dnsMode) == "dot" {
		if effectiveUdpTimeout < 5000 {
			effectiveUdpTimeout = 5000
		}
	}

	for i, d := range rawDomains {
		cleanDomain := strings.TrimSpace(d)
		if cleanDomain == "" {
			continue
		}

		wg.Add(1)
		go func(domainToTest string, index int) {
			defer wg.Done()
			defer func() { recover() }()

			cfg := cfgBase
			// Isolate local proxy ports across concurrent checks
			cfg.StartPort = 44000 + (index * 100)
			cfg.Domain = domainToTest
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
				"-udp-timeout", fmt.Sprintf("%dms", effectiveUdpTimeout),
				"-protocol", tunnelProtocol,
				"-ss-method", ssMethod,
				"-user", proxyUser,
				"-pass", proxyPass,
				"-fast-fail-enabled", fmt.Sprintf("%v", lightE2EEnabled),
				"-quick-scan", fmt.Sprintf("%v", engineQuickScan),
			}
			cfg.Pubkey = pubkeyToUse

			if err := f35.ValidateConfig(cfg); err != nil {
				return
			}

			var isHealthy bool
			hooks := f35.Hooks{
				OnResult: func(res f35.Result) {
					if res.Probe == "ok" && res.LatencyMS > 0 && res.LatencyMS < 99999 {
						isHealthy = true
					}
				},
			}

			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()

			_ = f35.ScanWithContext(ctx, cfg, hooks)

			if isHealthy {
				mu.Lock()
				healthy = append(healthy, domainToTest)
				mu.Unlock()
				log.Printf("VAY_DEBUG: [Domain Scanner E2E] Domain %s PASSED", domainToTest)
			} else {
				log.Printf("VAY_DEBUG: [Domain Scanner E2E] Domain %s FAILED", domainToTest)
			}
		}(cleanDomain, i)

		// Stagger the launch sequence by 50ms so routers don't panic on sudden parallel traffic
		time.Sleep(50 * time.Millisecond)
	}

	wg.Wait()

	return strings.Join(healthy, ",")
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

	// Explicitly close idle connections to notify the router.
	// This forces a TCP FIN handshake, which immediately clears NAT entries.
	if transport, ok := http.DefaultTransport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
	time.Sleep(1000 * time.Millisecond)
}

// Inside f35_bridge.go
func ManualCleanup() {
    go func() {
                
        runtime.GC()
        debug.FreeOSMemory()

		fmt.Println("VAY_DEBUG: Manual GC and Memory Free executed")
    }()
}

func StopPingAllConfigs() {
	pingMu.Lock()
	if pingCancel != nil {
		pingCancel()
		pingCancel = nil
	}
	pingMu.Unlock()

	// Flush active connections to send FIN packets to the router
	if transport, ok := http.DefaultTransport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
	// Give the kernel 1000ms to physically send the TCP FIN packets
	time.Sleep(1000 * time.Millisecond)
}

func StopRowPing() {
	rowPingMu.Lock()
	if rowPingCancel != nil {
		rowPingCancel()
		rowPingCancel = nil
	}
	rowPingMu.Unlock()

	// Flush active connections to send FIN packets to the router
	if transport, ok := http.DefaultTransport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
	// Give the kernel 1000ms to physically send the TCP FIN packets
	time.Sleep(1000 * time.Millisecond)
}

// StopDomainScanner cancels the E2E domain check and frees network sockets.
func StopDomainScanner() {
	domainScanMu.Lock()
	if domainScanCancel != nil {
		domainScanCancel()
		domainScanCancel = nil
	}
	domainScanMu.Unlock()

	// Flush active connections to send FIN packets to the router
	if transport, ok := http.DefaultTransport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}
}
