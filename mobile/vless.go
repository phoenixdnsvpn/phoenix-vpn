package mobile

import (
	"strings"
//	"log"
)

// Global memory state storage for custom user inputs and remote server files
/*var globalVlessWsIP string

func SetGlobalVlessWsIP(ip string) {
	globalVlessWsIP = strings.TrimSpace(ip)
	log.Printf("VAY_DEBUG: globalVlessWsIP is set with IP: %v",globalVlessWsIP)
}*/

type VlessCdnNode struct {
	Domain    []string `json:"domain"`
	CdnDomain []string `json:"cdn_domain"`
}

type VlessConfig struct {
	VlessWs          map[string]VlessCdnNode `json:"vless-ws"`
	VlessXhttp       map[string]VlessCdnNode `json:"vless-xhttp"`
	VlessGrpc        map[string]VlessCdnNode `json:"vless-grpc"`
	VlessHttpupgrade map[string]VlessCdnNode `json:"vless-httpupgrade"`
	
	WsPath          string `json:"ws_path"`
	HttpupgradePath string `json:"httpupgrade_path"`
	XhttpPath       string `json:"xhttp_path"`
	ServiceName     string `json:"service_name"`
	ServerName      string `json:"server_name"`
}

/*type VlessConfig struct {
	WsDomain string `json:"ws_domain"` 
	WsPath   string `json:"ws_path"`
	HttpupgradePath   string `json:"httpupgrade_path"`
	XhttpPath   string `json:"xhttp_path"`
	ServiceName   string `json:"service_name"`
	ServerName   string `json:"server_name"`
	HttpUpgradeDomain   string `json:"httpupgrade_domain"`
	GrpcDomain   string `json:"grpc_domain"`
	AwsCdnDomain   string `json:"aws_cdn_domain"`
	AwsDomain   string `json:"aws_domain"`
	VlessXhttpDomain string `json:"vless_xhttp_domain"`
	XhttpCdnDomain string `json:"xhttp_cdn_domain"`
}*/

// =====================================================================
// VLESS WEBSOCKET SECURE INTERNAL GETTERS
// =====================================================================

// =====================================================================
// NEW: DYNAMIC PROTOCOL GETTERS
// =====================================================================

// getVlessProtocolMap dynamically fetches the correct map block
func getVlessProtocolMap(index int64, protocol string) map[string]VlessCdnNode {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return nil
	}
	cfg := defaultConfigs[index]
	switch strings.ToLower(protocol) {
	case "vless-ws":
		return cfg.VlessWs
	case "vless-xhttp":
		return cfg.VlessXhttp
	case "vless-grpc":
		return cfg.VlessGrpc
	case "vless-httpupgrade":
		return cfg.VlessHttpupgrade
	default:
		return nil
	}
}

// GetVlessDomain fetches the strict underlying domain
func GetVlessDomain(index int64, protocol string, targetCDN string) string {
	protoMap := getVlessProtocolMap(index, protocol)
	if protoMap != nil {
		// Case-insensitive CDN lookup
		for cdnKey, node := range protoMap {
			if strings.EqualFold(cdnKey, targetCDN) {
				if len(node.Domain) > 0 && node.Domain[0] != "" {
					return node.Domain[0]
				}
				break
			}
		}
	}
	// Global fallback
	ensureParsed()
	if index >= 0 && index < int64(len(defaultConfigs)) {
		return defaultConfigs[index].Domain
	}
	return ""
}

// GetVlessCdnDomain fetches the masked edge CDN domain (used for SNI/DoH resolution)
func GetVlessCdnDomain(index int64, protocol string, targetCDN string) string {
	protoMap := getVlessProtocolMap(index, protocol)
	if protoMap != nil {
		for cdnKey, node := range protoMap {
			if strings.EqualFold(cdnKey, targetCDN) {
				if len(node.CdnDomain) > 0 && node.CdnDomain[0] != "" {
					return node.CdnDomain[0]
				}
				break
			}
		}
	}
	// Fallback to strict Domain if CDN Domain isn't defined
	return GetVlessDomain(index, protocol, targetCDN)
}

/*func GetTargetIP(configIndex int64, activeProtocol string, globalDnsServer string, getServerIpFromDomain bool, targetCdn string, runtimeVlessIP string) string {
	

	if activeProtocol == "vless-ws" || activeProtocol == "vless-httpupgrade" || activeProtocol == "vless-grpc" || activeProtocol == "vless-xhttp"{
		
		// Priority 1: User explicitly checked an IP in the Android Scanner Vault
		if runtimeVlessIP != "" && runtimeVlessIP != "0.0.0.0" {
			return runtimeVlessIP
		}
		
		// Determine the EXACT domain the outbound needs to dial
		var domainToResolve string
		if activeProtocol == "vless-ws" {
			domainToResolve = getWsDomain(configIndex)
			if strings.ToLower(targetCdn) == "cloudy" {
				domainToResolve = getCloudYCdnDomain(configIndex)
			}
		} else if activeProtocol == "vless-grpc" {
			domainToResolve = getGrpcDomain(configIndex)
			if strings.ToLower(targetCdn) == "cloudy" {
				domainToResolve = getCloudYCdnDomain(configIndex)
			}
		} else if activeProtocol == "vless-httpupgrade" {
			domainToResolve = getHttpupgradeDomain(configIndex)
		}else if activeProtocol == "vless-xhttp" {
			domainToResolve = getVlessXhttpDomain(configIndex)
		}		
		// Priority 2: DoH Resolution (The primary, dynamic mechanism!)
		resolvedIP := resolveDomainOverDoH(domainToResolve, globalDnsServer)
		if resolvedIP != "" {
			return resolvedIP
		}
		
		// Priority 3: Fallback to the JSON "vless_ws_ip" (LAST ATTEMPT AFTER DOH FAILS)
		if targetCdn != "" {
			if cdnIp := GetCdnVlessWsIP(targetCdn); cdnIp != "" && cdnIp != "0.0.0.0" {
				return cdnIp
			}
		}
		
		// Priority 4: Return raw domain so the proxy engine resolves it natively via OS
		return domainToResolve 
	}
	
	return getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
}*/

func GetTargetIP(configIndex int64, activeProtocol string, globalDnsServer string, getServerIpFromDomain bool, targetCdn string, runtimeVlessIP string) string {
	if activeProtocol == "vless-ws" || activeProtocol == "vless-httpupgrade" || activeProtocol == "vless-grpc" || activeProtocol == "vless-xhttp"{
		
		if runtimeVlessIP != "" && runtimeVlessIP != "0.0.0.0" {
			return runtimeVlessIP
		}
		
		// Priority 2: Use the new dynamic unified CDN Domain getter!
		domainToResolve := GetVlessCdnDomain(configIndex, activeProtocol, targetCdn)
		
		resolvedIP := resolveDomainOverDoH(domainToResolve, globalDnsServer)
		if resolvedIP != "" {
			return resolvedIP
		}
		
		if targetCdn != "" {
			if cdnIp := GetCdnVlessWsIP(targetCdn); cdnIp != "" && cdnIp != "0.0.0.0" {
				return cdnIp
			}
		}
		
		return domainToResolve 
	}
	
	return getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
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

/*func getWsDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].WsDomain != "" {
		return defaultConfigs[index].WsDomain
	}
// to be fixed	
	return defaultConfigs[index].Domain
}*/

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

/*func getAwsDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].AwsDomain != "" {
		return defaultConfigs[index].AwsDomain
	}
// to be fixed	
	return defaultConfigs[index].Domain
}*/

/*func getCloudYCdnDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].AwsCdnDomain != "" {
		return defaultConfigs[index].AwsCdnDomain
	}
// to be fixed	
	return defaultConfigs[index].AwsDomain
}*/

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

/*func getHttpupgradeDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].HttpUpgradeDomain != "" {
		return defaultConfigs[index].HttpUpgradeDomain
	}
	return defaultConfigs[index].HttpUpgradeDomain
}*/

/*func getGrpcDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].GrpcDomain != "" {
		return defaultConfigs[index].GrpcDomain
	}
	return defaultConfigs[index].GrpcDomain
}*/

func getXhttpPath(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "/xhttp"
	}
	if defaultConfigs[index].XhttpPath != "" {
		return defaultConfigs[index].XhttpPath
	}
	return "/xhttp"
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

func buildVlessWsOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIP string, targetCDN string) map[string]interface{} {
	CdnIP := runtimeVlessIP
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = GetTargetIP(configIndex, "vless-ws", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	WsDomainName := GetVlessCdnDomain(configIndex, "vless-ws", targetCDN)
	wsPath := getWsPath(configIndex)

	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = WsDomainName	
	}
	
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
		"headers": map[string]string{
			"Host": WsDomainName,
		},
	}

	outbound := map[string]interface{}{
		"type":            "vless",
		"tag":             "proxy-out",
		"server":          CdnIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		"packet_encoding": "xudp",
		"tls":             tlsObj,
		"transport":       transportObj,
		"tcp_fast_open":   true,				
	}

	return outbound
}

/*func buildVlessWsOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIP string, targetCDN string) map[string]interface{} {
	CdnIP := runtimeVlessIP
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		// CdnIP = getCdnFallbackIP(configIndex, targetCDN)
		CdnIP = GetTargetIP(configIndex, "vless-ws", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	WsDomainName := getWsDomain(configIndex)
	
	if strings.ToLower(targetCDN) == "cloudy" {
		WsDomainName = getCloudYCdnDomain(configIndex)
	}
	wsPath := getWsPath(configIndex)

	// Strict Matched-Domain Fallback Logic
	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = WsDomainName	
	}
	
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
//		"early_data_header_name": "Sec-WebSocket-Protocol",		
	}

	outbound := map[string]interface{}{
		"type":            "vless",
		"tag":             "proxy-out",
		"server":          CdnIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		
// 		2. XUDP: Crucial for performance. It tightly packs UDP traffic (DNS, Video, Voice) so it doesn't choke the TCP websocket.
		"packet_encoding": "xudp",
				
		"tls":             tlsObj,
		"transport":       transportObj,
// 		3. TCP FAST OPEN: Bypasses the initial TCP 3-way handshake on subsequent connections.
		"tcp_fast_open":   true,
//		"tcp_fast_open":   false,				
	}

	return outbound
}*/

// =====================================================================
// OUTBOUND BUILDER FOR VLESS gRPC (CLOUDFLARE CDN MULTIPLEXING)
// =====================================================================

func buildVlessGrpcOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIP string, targetCDN string) map[string]interface{} {
	CdnIP := runtimeVlessIP
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = GetTargetIP(configIndex, "vless-grpc", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	GrpcDomain := GetVlessCdnDomain(configIndex, "vless-grpc", targetCDN)

	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = GrpcDomain
	}
		
	grpcServiceName := getGrpcServiceName(configIndex)
	if strings.HasPrefix(grpcServiceName, "/") {
		grpcServiceName = strings.TrimPrefix(grpcServiceName, "/")
	}
	
	tlsObj := map[string]interface{}{
		"enabled":     true,
		"insecure":    false,
		"server_name": GrpcDomain,
		"alpn":        []string{"h2"},
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
		"server":          CdnIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		"tls":             tlsObj,
		"transport":       transportObj,
		"tcp_fast_open":   true,
	}

	return outbound
}


/*func buildVlessGrpcOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIP string, targetCDN string) map[string]interface{} {
	CdnIP := runtimeVlessIP
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		// CdnIP = getCdnFallbackIP(configIndex, targetCDN)
		CdnIP = GetTargetIP(configIndex, "vless-grpc", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	GrpcDomain := getGrpcDomain(configIndex)

	if strings.ToLower(targetCDN) == "cloudy" {
		GrpcDomain = getCloudYCdnDomain(configIndex)
	}
	
	// Strict Matched-Domain Fallback Logic
	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = GrpcDomain
	}
		
	// We use the path variable to store the gRPC Service Name (e.g., "vaygrpc")
	grpcServiceName := getGrpcServiceName(configIndex)
//	grpcServiceName	:= "vaygrpc"

	if strings.HasPrefix(grpcServiceName, "/") {
		grpcServiceName = strings.TrimPrefix(grpcServiceName, "/")
	}
	
	tlsObj := map[string]interface{}{
		"enabled":     true,
		"insecure":    false,
		"server_name": GrpcDomain,
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
		"server":          CdnIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		
//		"packet_encoding": "xudp", 
        
		"tls":             tlsObj,
		"transport":       transportObj,
		"tcp_fast_open":   true, // false
	}

	return outbound
}*/

// =====================================================================
// OUTBOUND BUILDER FOR VLESS HTTPUPGRADE (MAX HIGH-LATENCY CDN SPEED)
// =====================================================================

func buildVlessHttpUpgradeOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIP string, targetCDN string) map[string]interface{} {
	CdnIP := runtimeVlessIP
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = GetTargetIP(configIndex, "vless-httpupgrade", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	HttpupgradeServerName := getHttpupgradeServerName(configIndex)
	HttpupgradeDomain := GetVlessCdnDomain(configIndex, "vless-httpupgrade", targetCDN)
	HttpupgradePath := getHttpupgradePath(configIndex)
	
	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = HttpupgradeDomain
	}
		
	tlsObj := map[string]interface{}{
		"enabled":     true,
		"insecure":    false,
		"server_name": HttpupgradeServerName,
		"alpn":        []string{"http/1.1"}, 
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
		"server":          CdnIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		"packet_encoding": "xudp", 
		"tls":             tlsObj,
		"transport":       transportObj,
		"tcp_fast_open":   true,
	}

	return outbound
}

/*func buildVlessHttpUpgradeOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIP string, targetCDN string) map[string]interface{} {
	CdnIP := runtimeVlessIP
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		// CdnIP = getCdnFallbackIP(configIndex, targetCDN)
		CdnIP = GetTargetIP(configIndex, "vless-httpupgrade", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIP)
	}
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	HttpupgradeServerName := getHttpupgradeServerName(configIndex)
	HttpupgradeDomain := getHttpupgradeDomain(configIndex)
	HttpupgradePath := getHttpupgradePath(configIndex)
	// vlessPath	:= "/vayupgrade"
	
	// Strict Matched-Domain Fallback Logic
	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = HttpupgradeDomain
	}
		
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
		"server":          CdnIP,
		"server_port":     serverPort,
		"uuid":            uuid,
		
		// XUDP runs flawlessly over HTTPUpgrade pipes
		"packet_encoding": "xudp", 
		"tls":             tlsObj,
		"transport":       transportObj,
		"tcp_fast_open":   true, //false
	}

	return outbound
}*/
