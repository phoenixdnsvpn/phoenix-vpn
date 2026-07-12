package mobile

/*import (
	"strconv"
	"strings"
	"log"
)*/

type RealityConfig struct {
	UUID           string `json:"uuid"`
	RealityPubKey  string `json:"reality_pubkey"`
	RealityShortId string `json:"reality_short_id"`
	RealityDomain  string `json:"reality_domain"` // Dedicated SNI for Reality
}

// =====================================================================
// REALITY SECURE INTERNAL GETTERS
// =====================================================================

func getRealityServerIP(index int64, globalDnsServer string) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	
	serverIP := ""
		
	if globalDnsServer != "0.0.0.0" && globalDnsServer != "" {
		// 1. Get the domain name of your actual server		
		serverDomain := getServerDomain(index)
		// 2. Resolve the IP silently via DoH using the Global BootstrapDns variable
		serverIP = resolveDomainOverDoH(serverDomain, globalDnsServer)

		// 3. Fallbacks just in case the encrypted DNS lookup fails
		if serverIP == "" {
			serverIP = defaultConfigs[index].ServerIP
		}
		if serverIP == "" {
			serverIP = serverDomain // Send the raw domain to Xray/Sing-box as a last resort
		}
	}
		
	return serverIP 
}

func getRealityServerPortRaw(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "443"
	}
	if defaultConfigs[index].ServerPort == "" {
		return "443" 
	}
	return defaultConfigs[index].ServerPort
}

func getRealityServerPort(index int64) int {
/*	raw := getRealityServerPortRaw(index)
	raw = strings.ReplaceAll(raw, " ", "")
	raw = strings.ReplaceAll(raw, ":", "-") 
	
	var finalPort int = 443 // Default fallback
	
	parts := strings.Split(raw, "-")
	if len(parts) > 0 {
		firstPart := strings.Split(parts[0], ",")[0]
		if p, err := strconv.Atoi(firstPart); err == nil {
			finalPort = p
		}
	}
	log.Printf("VAY_DEBUG: [Reality Port] Index: %d | Raw JSON String: '%s' | Final Returned Port: %d", index, getRealityServerPortRaw(index), finalPort)
*/
	return 443
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
func getRealityDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	
	// If a specific Reality SNI is provided, use it.
	if defaultConfigs[index].RealityDomain != "" {
		return defaultConfigs[index].RealityDomain
	}
	
	// Fallback to the standard domain if reality_domain is missing
	return defaultConfigs[index].Domain
}

// =====================================================================
// OUTBOUND BUILDER
// =====================================================================

func buildRealityOutbound(configIndex int64, globalDnsServer string) map[string]interface{} {
	serverIP := getRealityServerIP(configIndex, globalDnsServer)
	serverPort := getRealityServerPort(configIndex)
	uuid := getRealityUUID(configIndex)
	pubKey := getRealityPubKey(configIndex)
	shortId := getRealityShortId(configIndex)
		
	/*if globalDnsServer != "0.0.0.0" && globalDnsServer != "" {		
		// 1. Get the domain name of actual server		
		serverDomain := getServerDomain(configIndex)
		// 2. Resolve the IP silently via DoH using the Global BootstrapDns variable
		serverIP := resolveDomainOverDoH(serverDomain, globalDnsServer)
		// 3. Fallbacks just in case the encrypted DNS lookup fails
		if serverIP == "" {
			serverIP = getRealityServerIP(configIndex)
		}
		if serverIP == "" {
			serverIP = serverDomain // Send the raw domain to Xray/Sing-box as a last resort
		}
	}*/	
	// Use the dedicated Reality Domain Getter
	sniDomain := getRealityDomain(configIndex)

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
