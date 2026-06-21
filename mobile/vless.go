package mobile

import (
	"strings"
)

// Global memory state storage for custom user inputs and remote server files
var globalVlessWsIP string
var rootVlessWsIP   string // <-- Added to hold root property from remote server JSON file

func SetGlobalVlessWsIP(ip string) {
	globalVlessWsIP = strings.TrimSpace(ip)
}

// SetRootVlessWsIP maps the top-level unmarshaled proxy endpoint to native memory cache
func SetRootVlessWsIP(ip string) {
	rootVlessWsIP = strings.TrimSpace(ip)
}

type VlessWsConfig struct {
	VlessDomain string `json:"vless_domain"` 
	VlessPath   string `json:"vless_path"`   
}

// =====================================================================
// VLESS WEBSOCKET SECURE INTERNAL GETTERS
// =====================================================================


func getVlessServerIP(index int64) string {
	ensureParsed()

	return defaultConfigs[index].ServerIP
	// Priority 1: Use user-defined custom Anycast IP from UI settings (if not empty or 0.0.0.0)
	if globalVlessWsIP != "" && globalVlessWsIP != "0.0.0.0" {
		return globalVlessWsIP
	}

	// Priority 2: Fall back to the root-level "vless_ws_ip" provided in your server JSON file
	if rootVlessWsIP != "" && rootVlessWsIP != "0.0.0.0" {
		return rootVlessWsIP
	}

	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}

	// Priority 3: Ultimate fallback to profile configuration host domain
	return defaultConfigs[index].VlessDomain
}

// GetActiveVlessWsIP is an exported JNI function for Android's VpnBuilder
// to retrieve the final dialed IP and exclude it from the VPN routing table.
func GetActiveVlessWsIP(index int64) string {
	return getVlessServerIP(index)
}

func getVlessServerPort(index int64) int {
	return 443
}

func getVlessUUID(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].UUID
}

func getVlessDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].VlessDomain != "" {
		return defaultConfigs[index].VlessDomain
	}
	return defaultConfigs[index].Domain
}

func getVlessPath(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "/assignment"
	}
	if defaultConfigs[index].VlessPath != "" {
		return defaultConfigs[index].VlessPath
	}
	return "/assignment"
}

// =====================================================================
// OUTBOUND BUILDER FOR VLESS WEBSOCKETS (CLOUDFLARE CDN)
// =====================================================================

func buildVlessWsOutbound(configIndex int64, runtimeVlessWsIp string) map[string]interface{} {
	serverIP := runtimeVlessWsIp
	if serverIP == "" || serverIP == "0.0.0.0" {
		serverIP = getVlessServerIP(configIndex)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	vlessDomain := getVlessDomain(configIndex)
	vlessPath := getVlessPath(configIndex)

	tlsObj := map[string]interface{}{
		"enabled":     true,
		"insecure":    false,
		"server_name": vlessDomain,
		"alpn": []string{"http/1.1"},
		"utls": map[string]interface{}{
			"enabled":     true,
			"fingerprint": "chrome",
		},
	}

	transportObj := map[string]interface{}{
		"type": "ws",
		"path": vlessPath,
//		"headers": map[string]interface{}{
		"headers": map[string]string{
			"Host": vlessDomain,
		},
	}

	outbound := map[string]interface{}{
		"type":            "vless",
		"tag":             "proxy-out",
		"server":          serverIP,
		"server_port":     serverPort,
		"uuid":            uuid,
//		"packet_encoding": "xudp",
/*		"multiplex": map[string]interface{}{
			"enabled":         true,
			"protocol":        "h2mux",
			"max_connections": 4,
			"min_streams":     4,
		},*/
		"tls":             tlsObj,
		"transport":       transportObj,
	}

	return outbound
}
