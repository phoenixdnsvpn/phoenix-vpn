package mobile

import (
	"strconv"
	"strings"
//	"log"
//    "time"	
)

// HysteriaConfig holds the direct-protocol parameters.
type HysteriaConfig struct {
	ServerIP       string `json:"server_ip"`
	ServerPort     string `json:"server_port"` // Strictly string for port hopping
	Network        string `json:"network"`
	UpMbps         int    `json:"up_mbps"`
	DownMbps       int    `json:"down_mbps"`
	ObfsPassword   string `json:"obfs_password"`
	AuthPassword   string `json:"auth_password"`
	HysteriaDomain string `json:"hysteria_domain"` // NEW: Dedicated SNI for Hysteria
}

// =====================================================================
// HYSTERIA2 SECURE INTERNAL GETTERS
// =====================================================================

func getHysteriaServerIP(index int64, globalDnsServer string) string {
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

func getHysteriaNetwork(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Network
}

func getHysteriaServerPortRaw(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "8443"
	}
	if defaultConfigs[index].ServerPort == "" {
		return "8443"
	}
	return defaultConfigs[index].ServerPort
}

// getHysteriaServerPort dynamically extracts a single integer port for the Ping Scanner.
func getHysteriaServerPort(index int64) int {
	raw := getHysteriaServerPortRaw(index)
	raw = strings.ReplaceAll(raw, " ", "")
	raw = strings.ReplaceAll(raw, ":", "-") // Normalize to dash to extract the first port
	
	parts := strings.Split(raw, "-")
	if len(parts) > 0 {
		firstPart := strings.Split(parts[0], ",")[0]
		if p, err := strconv.Atoi(firstPart); err == nil {
			return p
		}
	}
	return 8443
}

func getHysteriaUpMbps(index int64) int {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return 10
	}
	if defaultConfigs[index].UpMbps == 0 {
		return 10
	}
	
	return defaultConfigs[index].UpMbps
}

func getHysteriaDownMbps(index int64) int {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return 100
	}
	if defaultConfigs[index].DownMbps == 0 {
		return 100
	}

	return defaultConfigs[index].DownMbps
}

func getHysteriaAuthPass(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].AuthPassword
}

func getHysteriaObfsPass(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].ObfsPassword
}

// NEW: Smart Getter for Hysteria SNI
func getHysteriaDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	
	// If a specific Hysteria SNI is provided, use it.
	if defaultConfigs[index].HysteriaDomain != "" {
		return defaultConfigs[index].HysteriaDomain
	}
	
	// Fallback to the standard domain if hysteria_domain is missing
	return defaultConfigs[index].Domain
}

// =====================================================================
// OUTBOUND BUILDER
// =====================================================================

// buildHysteriaOutbound securely constructs the sing-box Hysteria2 JSON object.
func buildHysteriaOutbound(configIndex int64, globalDnsServer string) map[string]interface{} {
	serverIP := getHysteriaServerIP(configIndex, globalDnsServer)	
	rawPort := getHysteriaServerPortRaw(configIndex)
	network := getHysteriaNetwork(configIndex)
	upMbps := getHysteriaUpMbps(configIndex)
	downMbps := getHysteriaDownMbps(configIndex)
	obfsPass := getHysteriaObfsPass(configIndex)
	
	// USE THE NEW GETTER INSTEAD OF getDefaultConfigDomain
	sniDomain := getHysteriaDomain(configIndex)
	authPass := getHysteriaAuthPass(configIndex)
			
	tlsObj := map[string]interface{}{
		"enabled":     true,
		"server_name": sniDomain,
		"insecure":    true,
	}

	obfsObj := map[string]interface{}{
		"type":     "salamander",
		"password": obfsPass,
	}

	outbound := map[string]interface{}{
		"type":      "hysteria2",
		"tag":       "proxy-out",
		"server":    serverIP,
		"password":  authPass,
		"network":   network,
		"up_mbps":   upMbps,
		"down_mbps": downMbps,
		"obfs":      obfsObj,
		"tls":       tlsObj,
	}

	// =========================================================
	// DYNAMIC PORT INJECTION (Sing-Box Syntax Transformation)
	// =========================================================
	cleanPortStr := strings.ReplaceAll(rawPort, " ", "")
	cleanPortStr = strings.ReplaceAll(cleanPortStr, "\r", "")
	cleanPortStr = strings.ReplaceAll(cleanPortStr, "\n", "")
	
	// FIX: Transform standard dashes into Sing-Box required colons
	cleanPortStr = strings.ReplaceAll(cleanPortStr, "–", ":") 
	cleanPortStr = strings.ReplaceAll(cleanPortStr, "-", ":") 

	var portList []string
	for _, p := range strings.Split(cleanPortStr, ",") {
		if p != "" {
			portList = append(portList, p)
		}
	}

	if len(portList) == 1 && !strings.Contains(portList[0], ":") {
		if p, err := strconv.Atoi(portList[0]); err == nil {
			outbound["server_port"] = p // Single Integer
		} else {
			outbound["server_port"] = 8443
		}
	} else if len(portList) > 0 {
		outbound["server_ports"] = portList // Explicit Array of Strings with colons
	} else {
		outbound["server_port"] = 8443
	}

	return outbound
}
