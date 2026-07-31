package mobile

import (
	"bytes"
	"fmt"
	"log"
	"strconv"
	"strings"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Import all standard Xray protocols and transports
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	activeXrayServer *core.Instance
)

type XhttpRealityConfig struct {
	XhttpPort string `json:"xhttp_port"`
//	XhttpPath string `json:"xhttp_path"`
}

func getXhttpPort(index int64) string {
	ensureParsed()

	return defaultConfigs[index].XhttpPort
}
/*
func getXhttpPath(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "/vayxhttp"
	}
	if defaultConfigs[index].XhttpPath != "" {
		return defaultConfigs[index].XhttpPath
	}
	return "/vayxhttp"
}*/

// StartXrayEngine generates the Xray JSON config and boots the core for either VPN or Proxy mode.
func StartXrayEngine(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, CloudflareIP string, targetCDN string, isProxyMode bool, localPort int, vpnMtu int, protocol string, debug bool, fragment bool, blockQuic bool) error {

	// Ensure no orphaned instance is running
	StopXrayEngine()

	log.Printf("VAY_DEBUG: Fragmentation: %v",fragment)
	log.Printf("VAY_DEBUG: Block QUIC: %v",blockQuic)
	// 1. Generate the correct Inbounds dynamically (TUN + Watchdog SOCKS, or just Proxy SOCKS)
	var inboundsJSON string
	if isProxyMode {
		inboundsJSON = fmt.Sprintf(`{
			"listen": "127.0.0.1",
			"port": %d,
			"protocol": "socks",
			"settings": {
				"auth": "noauth",
				"udp": true
			},
			"sniffing": {
				"enabled": true,
				"destOverride": ["http", "tls", "quic"],
				"routeOnly": true
			}
		}`, localPort)
	} else {
		inboundsJSON = fmt.Sprintf(`{
			"protocol": "tun",
			"settings": {
				"mtu": %d,
                "autoRoute": false,
				"strictRoute": false,
				"endpointIndependentNat": true,
				"stack": "system"				
			},
			"sniffing": {
				"enabled": true,
				"destOverride": ["http", "tls", "quic"],
				"routeOnly": true
			}
		},
		{
			"listen": "127.0.0.1",
			"port": %d,
			"protocol": "socks",
			"settings": {
				"auth": "noauth",
				"udp": true
			}
		}`, vpnMtu, localPort)
	}

	// 2. Route to the correct Outbound builder based on the protocol
	var outboundJSON string
	actualProtocol := strings.ToLower(protocol)

	switch actualProtocol {
	case "reality-xhttp":
		outboundJSON = buildXrayRealityXHTTPOutbound(configIndex, globalDnsServer, getServerIpFromDomain, fragment)
	case "reality-tcp":
		outboundJSON = buildXrayRealityTCPOutbound(configIndex, globalDnsServer, getServerIpFromDomain, fragment)
	case "vless-ws":
		outboundJSON = buildXrayVlessWsOutbound(configIndex, globalDnsServer, getServerIpFromDomain, CloudflareIP, targetCDN)
	case "vless-httpupgrade":
		outboundJSON = buildXrayVlessHttpUpgradeOutbound(configIndex, globalDnsServer, getServerIpFromDomain, CloudflareIP, targetCDN)
	case "vless-grpc":
		outboundJSON = buildXraVlessGrpcOutbound(configIndex, globalDnsServer, getServerIpFromDomain, CloudflareIP, targetCDN)			
	case "hysteria2":
		outboundJSON = buildXrayHysteria2Outbound(configIndex, globalDnsServer, getServerIpFromDomain)
	default:
		return fmt.Errorf("unsupported Xray protocol: %s", protocol)
	}

	// Set log level based on the debug flag
	logLevel := "warning"
	if debug {
		logLevel = "info"
	}

	// 3. Build the final unified JSON config with the UDP/443 Blocking Rules
	/*rawConfig := fmt.Sprintf(`{
		"log": {
			"loglevel": "%s"
		},
		"routing": {
			"domainStrategy": "IPIfNonMatch",
			"rules": [
				{
					"type": "field",
					"network": "udp",
					"port": "443",
					"outboundTag": "block"
				},
				{
					"type": "field",
					"network": "tcp,udp",
					"outboundTag": "proxy"
				}
			]
		},
		"inbounds": [
			%s
		],
		"outbounds": [
			%s,
			{
				"protocol": "blackhole",
				"tag": "block"
			}
		]
	}`, logLevel, inboundsJSON, outboundJSON)*/

	// 1. Dynamically build the fragmentation outbound ONLY if requested
	fragmentOutboundJSON := ""
	if fragment {
		fragmentOutboundJSON = `,
		{
			"tag": "fragment",
			"protocol": "freedom",
			"settings": {
				"domainStrategy": "AsIs",
				"fragment": {
					"packets": "tlshello",
					"length": "100-200",
					"interval": "10-20"
				}
			},
			"streamSettings": {
				"sockopt": {
					"tcpNoDelay": true,
					"tcpKeepAliveIdle": 100
				}
			}
		}`
	}
	
	// 2. Dynamically build the QUIC blocking rule ONLY if requested by the user
	quicBlockRuleJSON := ""
	if blockQuic {
		quicBlockRuleJSON = `
				{
					"type": "field",
					"network": "udp",
					"port": "443",
					"outboundTag": "block"
				},`
	}

	// 3. Build the final unified JSON config with dynamic rule and outbound injection
	rawConfig := fmt.Sprintf(`{
		"log": {
			"loglevel": "%s"
		},
		"routing": {
			"domainStrategy": "IPIfNonMatch",
			"rules": [%s
				{
					"type": "field",
					"network": "tcp,udp",
					"outboundTag": "proxy"
				}
			]
		},
		"inbounds": [
			%s
		],
		"outbounds": [
			%s%s,
			{
				"protocol": "blackhole",
				"tag": "block"
			}
		]
	}`, logLevel, quicBlockRuleJSON, inboundsJSON, outboundJSON, fragmentOutboundJSON)
		
		
	// 2. Build the final unified JSON config with dynamic outbound injection
	// Imrpoved Instagram but blocked youtube
	/*rawConfig := fmt.Sprintf(`{
		"log": {
			"loglevel": "%s"
		},
		"routing": {
			"domainStrategy": "IPIfNonMatch",
			"rules": [
				{
					"type": "field",
					"network": "tcp,udp",
					"outboundTag": "proxy"
				}
			]
		},
		"inbounds": [
			%s
		],
		"outbounds": [
			%s%s,
			{
				"protocol": "blackhole",
				"tag": "block"
			}
		]
	}`, logLevel, inboundsJSON, outboundJSON, fragmentOutboundJSON)*/
	
// pass the youtube	and instagram but has intermittent delays
	/*rawConfig := fmt.Sprintf(`{
		"log": {
			"loglevel": "%s"
		},
		"routing": {
			"domainStrategy": "IPIfNonMatch",
			"rules": [
				{
					"type": "field",
					"network": "udp",
					"port": "443",
					"outboundTag": "block"
				},
				{
					"type": "field",
					"network": "tcp,udp",
					"outboundTag": "proxy"
				}
			]
		},
		"inbounds": [
			%s
		],
		"outbounds": [
			%s%s,
			{
				"protocol": "blackhole",
				"tag": "block"
			}
		]
	}`, logLevel, inboundsJSON, outboundJSON, fragmentOutboundJSON)*/
			
	// Decode the JSON into Xray's internal Protobuf structure
	jsonReader := bytes.NewReader([]byte(rawConfig))
	conf, err := serial.DecodeJSONConfig(jsonReader)
	if err != nil {
		return fmt.Errorf("failed to parse Xray JSON config: %v", err)
	}

	pbConfig, err := conf.Build()
	if err != nil {
		return fmt.Errorf("failed to build Xray Protobuf config: %v", err)
	}

	// Initialize the core instance
	server, err := core.New(pbConfig)
	if err != nil {
		return fmt.Errorf("failed to initialize Xray core: %v", err)
	}

	// Start the engine
	if err := server.Start(); err != nil {
		return fmt.Errorf("failed to start Xray core: %v", err)
	}

	// Store the instance globally so we can shut it down later
	activeXrayServer = server

	if isProxyMode {
		log.Printf("VAY_DEBUG: Xray Engine started successfully in PROXY MODE (Port %d) via %s.", localPort, protocol)
	} else {
		log.Printf("VAY_DEBUG: Xray Engine started successfully on native TUN interface via %s.", protocol)
	}

	return nil
}

// =====================================================================
// HYSTERIA2 OUTBOUND. Not in use
// =====================================================================
func buildXrayHysteria2Outbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool) string {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	serverPort := getHysteriaServerPort(configIndex)
	authPass := getHysteriaAuthPass(configIndex)
	sniDomain := getHysteriaDomain(configIndex)
	obfsPass := getHysteriaObfsPass(configIndex)
	upMbps := getHysteriaUpMbps(configIndex)
	downMbps := getHysteriaDownMbps(configIndex)

	// In Xray-core, Hysteria2 obfuscation and brutal limits DO NOT go in hysteriaSettings.
	// They must be injected into the 'finalmask' transport object.
	finalMaskJson := ""
	var finalMaskParts []string

	// 1. Salamander Obfuscation (UDP Mask)
	if obfsPass != "" {
		salamander := fmt.Sprintf(`"udp": [{ "type": "salamander", "settings": { "password": "%s" } }]`, obfsPass)
		finalMaskParts = append(finalMaskParts, salamander)
	}

	// 2. Brutal Congestion Control (QUIC Params)
	if upMbps > 0 && downMbps > 0 {
		quicParams := fmt.Sprintf(`"quicParams": { "brutalUp": %d, "brutalDown": %d }`, upMbps, downMbps)
		finalMaskParts = append(finalMaskParts, quicParams)
	}

	// Combine them if they exist
	if len(finalMaskParts) > 0 {
		finalMaskJson = fmt.Sprintf(`, "finalmask": { %s }`, strings.Join(finalMaskParts, ", "))
	}

	return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "hysteria",
		"settings": {
			"version": 2,
			"address": "%s",
			"port": %d
		},
		"streamSettings": {
			"network": "hysteria",
			"security": "tls",
			"tlsSettings": {
				"serverName": "%s",
				"alpn": ["h3"],
				"allowInsecure": true
			},
			"hysteriaSettings": {
				"version": 2,
				"auth": "%s"
			}%s
		}
	}`, serverIP, serverPort, sniDomain, authPass, finalMaskJson)
}

// =====================================================================
// OUTBOUND BUILDER FOR VLESS gRPC (XRAY-CORE FORMAT)
// =====================================================================
func buildXraVlessGrpcOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIp string, targetCDN string) string {

	CloudflareIP := runtimeVlessIp
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0" {
		// CloudflareIP = getCdnFallbackIP(configIndex, targetCDN)
		CloudflareIP = GetTargetIP(configIndex, "vless-grpc", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIp)
	}	
	
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	GrpcDomain := getGrpcDomain(configIndex)

	if strings.ToLower(targetCDN) == "amazon" {
		GrpcDomain = getAwsCdnDomain(configIndex)
	}
	
	// Strict Matched-Domain Fallback Logic
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0"{
		CloudflareIP = GrpcDomain	
	}
	// We use the path variable to store the gRPC Service Name (e.g., "vaygrpc")
	grpcServiceName := getGrpcServiceName(configIndex)

	// Xray-core, like sing-box, expects the serviceName without a leading slash
	if strings.HasPrefix(grpcServiceName, "/") {
		grpcServiceName = strings.TrimPrefix(grpcServiceName, "/")
	}

	return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "vless",
		"settings": {
			"vnext": [{
				"address": "%s",
				"port": %d,
				"users": [{
					"id": "%s",
					"encryption": "none",
					"flow": ""
				}]
			}]
		},
		"streamSettings": {
			"network": "grpc",
			"security": "tls",
			"tlsSettings": {
				"serverName": "%s",
				"alpn": ["h2"],
				"fingerprint": "chrome"
			},
			"grpcSettings": {
				"serviceName": "%s"
			},
			"sockopt": {
				"tcpFastOpen": true
			}
		}
	}`, CloudflareIP, serverPort, uuid, GrpcDomain, grpcServiceName)
}

//			"sockopt": {
//				"tcpFastOpen": true
//			}
			
// =====================================================================
// VLESS WEBSOCKET OUTBOUND
// =====================================================================
func buildXrayVlessWsOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIp string, targetCDN string) string {
	// Uses the exact same fallback logic for Cloudflare Anycast IP as Sing-box
	
	CloudflareIP := runtimeVlessIp
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0" {
		// CloudflareIP = getCdnFallbackIP(configIndex, targetCDN)
		CloudflareIP = GetTargetIP(configIndex, "vless-ws", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIp)
	}
		
//	log.Printf("VAY_DEBUG: Cloudflare IP: %v", CloudflareIP)
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	wsDomain := getWsDomain(configIndex)
	
	if strings.ToLower(targetCDN) == "amazon" {
		wsDomain = getAwsCdnDomain(configIndex)
	}
		
//	log.Printf("VAY_DEBUG: Cloudflare WS Domain: %v", wsDomain)
	wsPath := getWsPath(configIndex)

	// Strict Matched-Domain Fallback Logic
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0"{
		CloudflareIP = wsDomain	
	}
	
	return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "vless",
		"settings": {
			"vnext": [{
				"address": "%s",
				"port": %d,
				"users": [{
					"id": "%s",
					"encryption": "none",
					"flow": ""
				}]
			}]
		},
		"streamSettings": {
			"network": "ws",
			"security": "tls",
			"tlsSettings": {
				"serverName": "%s",
				"alpn": ["http/1.1"]
			},
			"wsSettings": {
				"path": "%s",
				"headers": {
					"Host": "%s"
				}
			}
		}
	}`, CloudflareIP, serverPort, uuid, wsDomain, wsPath, wsDomain)
}

// =====================================================================
// VLESS HTTP-UPGRADE OUTBOUND
// =====================================================================
func buildXrayVlessHttpUpgradeOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIp string, targetCDN string) string {

	CloudflareIP := runtimeVlessIp
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0" {
		// CloudflareIP = getCdnFallbackIP(configIndex, targetCDN)
		CloudflareIP = GetTargetIP(configIndex, "vless-httpupgrade", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIp)
	}

	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	serverName := getHttpupgradeServerName(configIndex)
	httpUpgradeDomain := getHttpupgradeDomain(configIndex)
	httpUpgradePath := getHttpupgradePath(configIndex)

	// Strict Matched-Domain Fallback Logic
	if CloudflareIP == "" || CloudflareIP == "0.0.0.0"{
		CloudflareIP = httpUpgradeDomain
	}
	
	return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "vless",
		"settings": {
			"vnext": [{
				"address": "%s",
				"port": %d,
				"users": [{
					"id": "%s",
					"encryption": "none",
					"flow": ""
				}]
			}]
		},
		"streamSettings": {
			"network": "httpupgrade",
			"security": "tls",
			"tlsSettings": {
				"serverName": "%s",
				"alpn": ["http/1.1"]
			},
			"httpupgradeSettings": {
				"path": "%s",
				"host": "%s"
			}
		}
	}`, CloudflareIP, serverPort, uuid, serverName, httpUpgradePath, httpUpgradeDomain)
}

// =====================================================================
// REALITY-XHTTP OUTBOUND
// =====================================================================
func buildXrayRealityXHTTPOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, fragment bool) string {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	serverPortStr := getXhttpPort(configIndex)
	serverPort, err := strconv.Atoi(serverPortStr)
	if err != nil || serverPort == 0 {
		serverPort = 2053
	}
	// Pulling standard Reality credentials (uuid/targetHost/keys)
	uuid := getRealityUUID(configIndex)
	xhttpPath := getXhttpPath(configIndex)
	targetHost := getRealityDomain(configIndex)
	publicKey := getRealityPubKey(configIndex)
	shortId := getRealityShortId(configIndex)

	sockoptJSON := ""
	if fragment {
		sockoptJSON = `,
			"sockopt": {
				"dialerProxy": "fragment"
			}`
	}
	
	return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "vless",
		"settings": {
			"vnext": [{
				"address": "%s",
				"port": %d,
				"users": [{
					"id": "%s",
					"encryption": "none",
					"flow": ""
				}]
			}]
		},
		"streamSettings": {
			"network": "xhttp",
			"xhttpSettings": {
				"path": "%s"
			},
			"security": "reality"%s,
			"realitySettings": {
				"fingerprint": "chrome",
				"serverName": "%s",
				"publicKey": "%s",
				"shortId": "%s",
				"spiderX": "/"
			}
		}
	}`, serverIP, serverPort, uuid, xhttpPath, sockoptJSON, targetHost, publicKey, shortId)
	
	/*return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "vless",
		"settings": {
			"vnext": [{
				"address": "%s",
				"port": %d,
				"users": [{
					"id": "%s",
					"encryption": "none",
					"flow": ""
				}]
			}]
		},
		"streamSettings": {
			"network": "xhttp",
			"sockopt": {
				"dialerProxy": "fragment"
			},			
			"xhttpSettings": {
				"path": "%s"
			},
			"security": "reality",
			"realitySettings": {
				"fingerprint": "chrome",
				"serverName": "%s",
				"publicKey": "%s",
				"shortId": "%s",
				"spiderX": "/"
			}
		}
	}`, serverIP, serverPort, uuid, xhttpPath, targetHost, publicKey, shortId)*/
}

// =====================================================================
// REALITY-TCP OUTBOUND
// =====================================================================
func buildXrayRealityTCPOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, fragment bool) string {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)

	// Ensure we fetch the raw port string and convert it
	serverPortStr := getRealityServerPortRaw(configIndex)
	serverPort, err := strconv.Atoi(serverPortStr)
	if err != nil || serverPort == 0 {
		serverPort = 443 // Default Reality fallback port
	}

	uuid := getRealityUUID(configIndex)
	targetHost := getRealityDomain(configIndex)
	publicKey := getRealityPubKey(configIndex)
	shortId := getRealityShortId(configIndex)

	sockoptJSON := ""
	if fragment {
		sockoptJSON = `,
			"sockopt": {
				"dialerProxy": "fragment"
			}`
	}
	
	return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "vless",
		"settings": {
			"vnext": [{
				"address": "%s",
				"port": %d,
				"users": [{
					"id": "%s",
					"encryption": "none",
					"flow": "xtls-rprx-vision"
				}]
			}]
		},
		"streamSettings": {
			"network": "tcp",
			"security": "reality"%s,
			"realitySettings": {
				"fingerprint": "chrome",
				"serverName": "%s",
				"publicKey": "%s",
				"shortId": "%s",
				"spiderX": "/"
			}
		}
	}`, serverIP, serverPort, uuid, sockoptJSON, targetHost, publicKey, shortId)
		
	// Note the addition of "xtls-rprx-vision" - this is Xray's flagship optimization for Reality-TCP!
	/*return fmt.Sprintf(`{
		"tag": "proxy",
		"protocol": "vless",
		"settings": {
			"vnext": [{
				"address": "%s",
				"port": %d,
				"users": [{
					"id": "%s",
					"encryption": "none",
					"flow": "xtls-rprx-vision"
				}]
			}]
		},
		"streamSettings": {
			"network": "tcp",
			"security": "reality",
			"realitySettings": {
				"fingerprint": "chrome",
				"serverName": "%s",
				"publicKey": "%s",
				"shortId": "%s",
				"spiderX": "/"
			}
		}
	}`, serverIP, serverPort, uuid, targetHost, publicKey, shortId)*/
	
}

// StopXrayEngine safely terminates the active Xray instance.
func StopXrayEngine() {
	if activeXrayServer != nil {
		activeXrayServer.Close()
		activeXrayServer = nil
		log.Printf("VAY_DEBUG: Shutting down Xray core...")
	}
}
