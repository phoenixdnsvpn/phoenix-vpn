package mobile

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"
	"fmt"
	"log"
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
	domain string,
	publicKey string,
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
	
/*	log.Printf("VAY_F35_DEBUG Parameters Loaded: "+
        "domain=%s, pubkey=%s, type=%s, user=%s, pass=%s, record=%s, "+
        "workers=%d, wait=%d, probe=%d, retries=%d",
        domain, publicKey, proxyType, proxyUser, proxyPass, strings.ToLower(recordType),
        workers, tunnelWait, probeTimeout, retries)*/
	    
	cfg := f35.DefaultConfig()
		
	rawResolvers := strings.Split(resolversList, ",")
    var cleaned []string
    for _, r := range rawResolvers {
        trimmed := strings.TrimSpace(r)
        if trimmed != "" {
            cleaned = append(cleaned, trimmed)
        }
    }
    
	if len(cleaned) == 0 {
        status, _ := json.Marshal(map[string]string{"status": "error", "message": "No resolvers provided"})
        return string(status)
    }

	cfg.Resolvers = cleaned
	cfg.Engine = "vaydns"
	cfg.Domain = domain
	        
	// Default keys provided by user 
	
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

	// Handle Proxy Credentials
	if proxyUser != "" && proxyUser != "none" {
		cfg.ProxyUser = proxyUser
	}
	if proxyPass != "" && proxyPass != "none" {
		cfg.ProxyPass = proxyPass
	}
	
//	log.Printf("VAY_DEBUG: SCANNER RESOLVER started | Type: %s | ID Size: %v | KeepAlive: %s | Idle: %s", 
//    recordType, clientIdSize, keepAlive, idleTimeout)
    
	// Build ExtraArgs for vaydns
	cfg.ExtraArgs = []string{
		"-pubkey", publicKey,
		"-record-type", strings.ToLower(recordType),
		"-log-level", "error",
		"-clientid-size", fmt.Sprintf("%d", clientIdSize),
		"-utls", "chrome",
		"-keepalive", keepAlive,
		"-idle-timeout", idleTimeout,
		"-udp-timeout", "2s",
	}

	// Apply Conservative mode overrides
/*	if isConservative {
		cfg.Workers = 10
		cfg.TunnelWait = 2000 * time.Millisecond
		cfg.ProbeTimeout = 8 * time.Second
		cfg.Retries = 1
	}*/
    
	cfg.Pubkey = publicKey // Ensure this field is set in your bridge DEBUG
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
//				fmt.Printf("GO_BRIDGE_PANIC: %v\nstack: %s\n", r, debug.Stack())
				fmt.Printf("GO_BRIDGE_PANIC: %v\n", r)
			}

			// It will be called after our 15-second grace period below.
		}()

		// 1. Execute the actual scan
		err := f35.ScanWithContext(ctx, cfg, hooks)
		
		fmt.Println("GO_DEBUG: Scan engine finished processing resolvers. Entering 30s grace period...")

		// 2. IMPLEMENT THE TASK: Wait 15 seconds after the last resolver
		// This matches cfg.ProbeTimeout (15s) to ensure everything has time to finish
		select {
		case <-time.After(15 * time.Second):
			fmt.Println("GO_DEBUG: Grace period ended.")
		case <-ctx.Done():
			fmt.Println("GO_DEBUG: Scan was manually stopped during grace period.")
		}

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
		// Now that the 15s is up and the app is notified, it is safe to kill the context.
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
	defer scanMu.Unlock()
	if scanCancel != nil {
		scanCancel()
		scanCancel = nil
	}
}
