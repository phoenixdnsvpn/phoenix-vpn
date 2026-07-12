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
	WsDomain string `json:"ws_domain"` 
	WsPath   string `json:"ws_path"`
	HttpupgradePath   string `json:"httpupgrade_path"`
	ServiceName   string `json:"service_name"`
	ServerName   string `json:"server_name"`
	HttpUpgradeDomain   string `json:"httpupgrade_domain"`	
}

// =====================================================================
// VLESS WEBSOCKET SECURE INTERNAL GETTERS
// =====================================================================


func getCloudflareIP(index int64) string {
	ensureParsed()

//	return defaultConfigs[index].ServerIP
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
	return defaultConfigs[index].WsDomain
}

// GetActiveVlessWsIP is an exported JNI function for Android's VpnBuilder
// to retrieve the final dialed IP and exclude it from the VPN routing table.
func GetActiveVlessWsIP(index int64) string {
	return getCloudflareIP(index)
}

func GetTargetIP(configIndex int64, activeProtocol string, globalDnsServer string) string {
	if activeProtocol == "vless-ws" || activeProtocol == "vless-httpupgrade"{
		return getCloudflareIP(configIndex)
	}
	// 1. Get the domain name of actual server		
	serverDomain := getServerDomain(configIndex)
	// 2. Resolve the IP silently via DoH using the Global BootstrapDns variable
	serverIP := resolveDomainOverDoH(serverDomain, globalDnsServer)
	// 3. Fallbacks just in case the encrypted DNS lookup fails
	if serverIP == "" {
		serverIP = getServerIpAddress(configIndex)
	}
	if serverIP == "" {
		serverIP = serverDomain
	}	
	return serverIP
		
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

func getWsDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].WsDomain != "" {
		return defaultConfigs[index].WsDomain
	}
// to be fixed	
	return defaultConfigs[index].Domain
}

func getWsPath(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "/vayws"
	}
	if defaultConfigs[index].WsPath != "" {
		return defaultConfigs[index].WsPath
	}
	return "/vayws"
}

func getHttpupgradeServerName(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].ServerName != "" {
		return defaultConfigs[index].ServerName
	}
	return defaultConfigs[index].ServerName
}

func getHttpupgradeDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].HttpUpgradeDomain != "" {
		return defaultConfigs[index].HttpUpgradeDomain
	}
	return defaultConfigs[index].HttpUpgradeDomain
}

func getHttpupgradePath(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "/vayupgrade"
	}
	if defaultConfigs[index].HttpupgradePath != "" {
		return defaultConfigs[index].HttpupgradePath
	}
	return "/vayupgrade"
}

func getGrpcServiceName(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "/vaygrpc"
	}
	if defaultConfigs[index].ServiceName != "" {
		return defaultConfigs[index].ServiceName
	}
	return "/vaygrpc"
}

// =====================================================================
// OUTBOUND BUILDER FOR VLESS WEBSOCKETS (CLOUDFLARE CDN)
// =====================================================================

func buildVlessWsOutbound(configIndex int64, runtimeVlessWsIp string) map[string]interface{} {
	CloudflareIP := runtimeVlessWsIp
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0" {
		CloudflareIP = getCloudflareIP(configIndex)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	WsDomainName := getWsDomain(configIndex)
	wsPath := getWsPath(configIndex)


	tlsObj := map[string]interface{}{
		"enabled":     true,
		"insecure":    false,
		"server_name": WsDomainName,
		"alpn": []string{"http/1.1"},
		"utls": map[string]interface{}{
			"enabled":     true,
			"fingerprint": "chrome",
		},
	}

	transportObj := map[string]interface{}{
		"type": "ws",
		"path": wsPath,
//		"headers": map[string]interface{}{
		"headers": map[string]string{
			"Host": WsDomainName,
		},
//		1. EARLY DATA: Embeds the first VLESS payload directly into the HTTP handshake, saving 1 RTT (Round Trip Time).
		"early_data_header_name": "Sec-WebSocket-Protocol",		
	}

	outbound := map[string]interface{}{
		"type":            "vless",
		"tag":             "proxy-out",
		"server":          CloudflareIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		
// 		2. XUDP: Crucial for performance. It tightly packs UDP traffic (DNS, Video, Voice) so it doesn't choke the TCP websocket.
		"packet_encoding": "xudp",
				
		"tls":             tlsObj,
		"transport":       transportObj,
// 		3. TCP FAST OPEN: Bypasses the initial TCP 3-way handshake on subsequent connections.
		"tcp_fast_open":   true,		
	}

	return outbound
}

// =====================================================================
// OUTBOUND BUILDER FOR VLESS gRPC (CLOUDFLARE CDN MULTIPLEXING)
// =====================================================================

func buildVlessGrpcOutbound(configIndex int64, runtimeVlessIp string) map[string]interface{} {
	CloudflareIP := runtimeVlessIp
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0" {
		CloudflareIP = getCloudflareIP(configIndex)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	WsDomain := getWsDomain(configIndex)

	// We use the path variable to store the gRPC Service Name (e.g., "vaygrpc")
	grpcServiceName := getGrpcServiceName(configIndex)
//	grpcServiceName	:= "vaygrpc"

	if strings.HasPrefix(grpcServiceName, "/") {
		grpcServiceName = strings.TrimPrefix(grpcServiceName, "/")
	}
	
	tlsObj := map[string]interface{}{
		"enabled":     true,
		"insecure":    false,
		"server_name": WsDomain,
		"alpn":        []string{"h2"}, // gRPC requires HTTP/2
		"utls": map[string]interface{}{
			"enabled":     true,
			"fingerprint": "chrome",
		},
	}

	transportObj := map[string]interface{}{
		"type":         "grpc",
		"service_name": grpcServiceName,
	}

	outbound := map[string]interface{}{
		"type":            "vless",
		"tag":             "proxy-out",
		"server":          CloudflareIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		
//		"packet_encoding": "xudp", 
        
		"tls":             tlsObj,
		"transport":       transportObj,
		"tcp_fast_open":   true,
	}

	return outbound
}

// =====================================================================
// OUTBOUND BUILDER FOR VLESS HTTPUPGRADE (MAX HIGH-LATENCY CDN SPEED)
// =====================================================================

func buildVlessHttpUpgradeOutbound(configIndex int64, runtimeVlessIp string) map[string]interface{} {
	CloudflareIP := runtimeVlessIp
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0" {
		CloudflareIP = getCloudflareIP(configIndex)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	HttpupgradeServerName := getHttpupgradeServerName(configIndex)
	HttpupgradeDomain := getHttpupgradeDomain(configIndex)
	HttpupgradePath := getHttpupgradePath(configIndex)
//    vlessPath	:= "/vayupgrade"
	
	tlsObj := map[string]interface{}{
		"enabled":     true,
		"insecure":    false,
		"server_name": HttpupgradeServerName,
//		"server_name": HttpupgradeDomain,
		"alpn":        []string{"http/1.1"}, // Stays on fast HTTP/1.1
		"utls": map[string]interface{}{
			"enabled":     true,
			"fingerprint": "chrome",
		},
	}

	transportObj := map[string]interface{}{
		"type": "httpupgrade",
		"path": HttpupgradePath,
		"headers": map[string]string{
			"Host": HttpupgradeDomain,
		},
	}

	outbound := map[string]interface{}{
		"type":            "vless",
		"tag":             "proxy-out",
		"server":          CloudflareIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		
		// XUDP runs flawlessly over HTTPUpgrade pipes
		"packet_encoding": "xudp", 
		"tls":             tlsObj,
		"transport":       transportObj,
		"tcp_fast_open":   true, 
	}

	return outbound
}
