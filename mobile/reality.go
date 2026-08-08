package mobile

import (
	"strconv"
//	"strings"
//	"log"
)

type RealityConfig struct {
	UUID           string `json:"uuid"`
	RealityPubKey  string `json:"reality_pubkey"`
	RealityShortId string `json:"reality_short_id"`
	RealityDomain  string `json:"reality_domain"` // Dedicated SNI for Reality
	RealityTcpPort string `json:"reality_tcp_port"`
}

func getRealityTcpPort(index int64) int {
    ensureParsed()
    
    // 1. Array bounds check
    if index < 0 || index >= int64(len(defaultConfigs)) {
        return 443
    }
    
    // 2. Empty value check
    portStr := defaultConfigs[index].RealityTcpPort
    if portStr == "" {
        return 443
    }
    
    // 3. Convert string to int64 safely
    port, err := strconv.Atoi(portStr)
    if err != nil {
        return 443 // Fallback if the string isn't a valid number
    }
    
    return port
}

func getRealityUUID(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].UUID
}

func getRealityPubKey(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].RealityPubKey
}

func getRealityShortId(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].RealityShortId
}

// NEW: Smart Getter for Reality SNI
func getRealityDomain(index int64, sniIndex int64) string {
	ensureParsed()
	
	// Priority 1: Check the SNI Pool first (if the user enabled it in the app)
	if sniIndex >= 0 {
		poolSni := getSniFromPool(sniIndex)
		if poolSni != "" {
			return poolSni
		}
	}
	
	// Priority 2 & 3 require a valid config index
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	
	// Priority 2: If a specific Reality SNI is provided in the JSON, use it.
	if defaultConfigs[index].RealityDomain != "" {
		return defaultConfigs[index].RealityDomain
	}
	
	// Priority 3: Fallback to the standard domain
	return defaultConfigs[index].Domain
}

// =====================================================================
// OUTBOUND BUILDER
// =====================================================================

func buildRealityOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, fragment bool, sniIndex int64) map[string]interface{} {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	serverPort := getRealityTcpPort(configIndex)
	uuid := getRealityUUID(configIndex)
	pubKey := getRealityPubKey(configIndex)
	shortId := getRealityShortId(configIndex)
		
	// Use the dedicated Reality Domain Getter
	sniDomain := getRealityDomain(configIndex, sniIndex)

	tlsObj := map[string]interface{}{
		"enabled":     true,
		"server_name": sniDomain,
		
		"utls": map[string]interface{}{
			"enabled":     true,
			"fingerprint": "chrome", 
		},
				
		"reality": map[string]interface{}{
			"enabled":    true,
			"public_key": pubKey,
			"short_id":   shortId,
		},
	}

	// Dynamic conditional Injection for Sing-Box fragmentation
	if fragment {
		tlsObj["fragment"] = true
		tlsObj["fragment_fallback_delay"] = "500ms"
	}
	
//	log.Printf("VAY_DEBUG: serverIP: %v | sniDomain: '%v' | fragment: %v", serverIP, sniDomain, fragment)
		
	outbound := map[string]interface{}{
		"type":            "vless",
		"tag":             "proxy-out",
		"server":          serverIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		"flow":            "xtls-rprx-vision",
		"packet_encoding": "xudp",
		"tls":             tlsObj,
		"tcp_fast_open":   true,
	}

	return outbound
}
