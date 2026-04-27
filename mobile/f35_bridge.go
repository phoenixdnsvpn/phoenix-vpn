package mobile

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"
	"fmt"
//	"net"
	"net/http"
//	"log"
//	"runtime/debug"

	"github.com/Starling226/vaydns-vpn/f35"
)

var (	
	scanCancel context.CancelFunc
	scanMu     sync.Mutex
)

// ScanResultCallback is an interface that Android can implement to receive results
type ScanResultCallback interface {
	OnResult(jsonResult string)
}

// StartF35Scan initiates a scan using the f35 engine logic.
// Returns a JSON string of the initial configuration or an error JSON.
func StartF35Scan(
	isDefault bool,
	configIndex int64,
	customDomain string,
	customPublicKey string,
	resolversList string, // Comma-separated
	proxyType string,
	proxyUser string,
	proxyPass string,
	recordType string,
	idleTimeout string,
	keepAlive string,
	clientIdSize int,
	workers int,          
	tunnelWait int,       
	probeTimeout int,     
	retries int,          
	callback ScanResultCallback,
) string {
	scanMu.Lock()
	if scanCancel != nil {
		scanCancel() // Stop any existing scan
	}
	   	
	ctx, cancel := context.WithCancel(context.Background())
	scanCancel = cancel
	scanMu.Unlock()
	
	fmt.Println("GO_DEBUG: StartF35Scan entry")

	// --- THE NATIVE VAULT LOGIC ---
	domainToUse := customDomain
	pubkeyToUse := customPublicKey


	if isDefault {
		domainToUse = getDefaultConfigDomain(configIndex)
		pubkeyToUse = getDefaultConfigPubkey(configIndex)
		
		// Enforce official parameters to prevent tampering
		recordType = GetDefaultConfigRecordType(configIndex)
		idleTimeout = GetDefaultConfigIdleTimeout(configIndex)
		keepAlive = GetDefaultConfigKeepAlive(configIndex)
		clientIdSize = int(GetDefaultConfigClientIdSize(configIndex))
		
		// Fetch actual credentials from vault using internal lowercase getters
		proxyUser = getDefaultConfigUser(configIndex)
		proxyPass = getDefaultConfigPass(configIndex)
	}

	cfg := f35.DefaultConfig()
		
	rawResolvers := strings.Split(resolversList, ",")
	var cleaned []string
    
	// --- SECURITY TRANSLATION: Fake -> Real ---    
	// We build a local map to quickly translate the results back to fake IPs later
	realToFake := make(map[string]string) 

	for _, r := range rawResolvers {
		fakeFull := strings.TrimSpace(r)
		if fakeFull != "" {
			// Leverage the helper we already built in mobile.go!
			// This splits the port, translates the IP, and re-attaches the port.
			realFull := translateFakeToReal(fakeFull)
			
			// Store the reverse mapping for the callback
			realToFake[realFull] = fakeFull       
			
			// Give the engine the REAL IP + PORT to scan
			cleaned = append(cleaned, realFull) 
		}
	}
	
	if len(cleaned) == 0 {
		status, _ := json.Marshal(map[string]string{"status": "error", "message": "No resolvers provided"})
		return string(status)
	}

	cfg.Resolvers = cleaned
	cfg.Engine = "vaydns"
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

	// Handle Proxy Credentials (now securely populated from vault if isDefault)
	if proxyUser != "" && proxyUser != "none" {
		cfg.ProxyUser = proxyUser
	}
	if proxyPass != "" && proxyPass != "none" {
		cfg.ProxyPass = proxyPass
	}
    
	// Build ExtraArgs for vaydns
	cfg.ExtraArgs = []string{
		"-pubkey", pubkeyToUse,
		"-record-type", strings.ToLower(recordType),
		"-log-level", "error",
		"-clientid-size", fmt.Sprintf("%d", clientIdSize),
		"-utls", "chrome",
		"-keepalive", keepAlive,
		"-idle-timeout", idleTimeout,
		"-udp-timeout", "2s",
	}

	cfg.Pubkey = pubkeyToUse 
	if err := f35.ValidateConfig(cfg); err != nil {
		status, _ := json.Marshal(map[string]string{"status": "error", "message": err.Error()})
		return string(status)
	}

	hooks := f35.Hooks{
		OnResult: func(res f35.Result) {
			// Safety: If the scan was cancelled, stop calling Java
			select {
			case <-ctx.Done():
				return
			default:
				if callback != nil {
					// --- SECURITY TRANSLATION: Real -> Fake ---
					// The engine scanned the real IP, but we must hide it from Android
					if fakeIP, exists := realToFake[res.Resolver]; exists {
						res.Resolver = fakeIP 
					}

					b, _ := json.Marshal(res)
					callback.OnResult(string(b))
				}
			}
		},
	}
	
	// Running scan in a goroutine so it doesn't block the UI thread
	go func() {	                	
		// Recovery block to prevent process-wide crashes
		defer func() {
			if r := recover(); r != nil {
				fmt.Printf("GO_BRIDGE_PANIC: %v\n", r)
			}
		}()

		// 1. Execute the actual scan
		err := f35.ScanWithContext(ctx, cfg, hooks)
		
		fmt.Println("GO_DEBUG: Scan engine finished processing resolvers. Entering 15s grace period...")

		// 3. Notify the App that we are truly finished
		if callback != nil {
			if err != nil && err != context.Canceled {
				fmt.Printf("GO_SCAN_ERROR: %v\n", err)
				errMsg, _ := json.Marshal(map[string]string{"status": "finished", "error": err.Error()})
				callback.OnResult(string(errMsg))
			} else {
				doneMsg, _ := json.Marshal(map[string]string{"status": "finished"})
				callback.OnResult(string(doneMsg))
			}
		}

		// 4. Final Resource Termination
		cancel() 

		scanMu.Lock()
		scanCancel = nil
		scanMu.Unlock()
		
	}()	
	
	status, _ := json.Marshal(map[string]string{"status": "started", "engine": "vaydns"})
	return string(status)
}

// StopF35Scan stops any currently running scan immediately
func StopF35Scan() {
	scanMu.Lock()
	if scanCancel != nil {
		scanCancel()
		scanCancel = nil
	}
	scanMu.Unlock()

	// 1. Terminate all lingering HTTP Keep-Alive connections
	if transport, ok := http.DefaultTransport.(*http.Transport); ok {
		transport.CloseIdleConnections()
	}

	fmt.Println("VAY_DEBUG: Scanner stopped. Network resources will release naturally via IdleTimeout.")
}

