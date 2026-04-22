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
	
    /*for _, r := range rawResolvers {
		fakeFull := strings.TrimSpace(r) // e.g., "10.255.0.1:53"
		if fakeFull != "" {
			
			// 1. Safely split the IP from the Port
			var ipPart, portSuffix string
			host, port, err := net.SplitHostPort(fakeFull)
			if err != nil {
				// No port was attached
				ipPart = fakeFull
				portSuffix = ""
			} else {
				// Port exists
				ipPart = host
				portSuffix = ":" + port
			}

			// 2. Ask default_configs.go for the real IP (using JUST the IP part)
			realIP := GetRealResolver(ipPart) 
			
			// 3. Re-attach the port to the real IP
			realFull := realIP + portSuffix

			// --- DEBUG PRINTS ---
//			if ipPart == realIP {
//				fmt.Printf("VAY_DEBUG_SCAN: 2. [WARNING] No translation found for Fake IP: [%s]. Using as-is.\n", ipPart)
//			} else {
//				fmt.Printf("VAY_DEBUG_SCAN: 2. [SUCCESS] Translated Fake IP [%s] -> Real IP [%s]\n", fakeFull, realFull)
//			}

			// 4. Store the reverse mapping for the callback using the FULL strings (with ports)
			realToFake[realFull] = fakeFull       
			
			// Give the engine the REAL IP + PORT to scan
			cleaned = append(cleaned, realFull) 
		}
	}*/
	 
//	fmt.Printf("VAY_DEBUG_SCAN: 3. Final List of IPs sent to Scanner Engine: %v\n", cleaned)
	 
    /*for _, r := range rawResolvers {
        trimmed := strings.TrimSpace(r)
        if trimmed != "" {
            cleaned = append(cleaned, trimmed)
        }
    }*/
    
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
	
	/*hooks := f35.Hooks{
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
	}*/
	
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
/*		select {
		case <-time.After(15 * time.Second):
			fmt.Println("GO_DEBUG: Grace period ended.")
		case <-ctx.Done():
			fmt.Println("GO_DEBUG: Scan was manually stopped during grace period.")
		}*/

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

