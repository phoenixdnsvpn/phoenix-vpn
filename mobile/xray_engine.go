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
//	VlessXhttpDomain string `json:"vless_xhttp_domain"`
//	XhttpCdnDomain string `json:"xhttp_cdn_domain"`
//	XhttpPath string `json:"xhttp_path"`
}

func getXhttpPort(index int64) int {
	ensureParsed()
	
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return 2053
	}
	
	portStr := defaultConfigs[index].XhttpPort
	if portStr == "" {
		return 2053
	}
	
	port, err := strconv.Atoi(portStr)
	if err != nil || port <= 0 {
		return 2053
	}
	
	return port
}

// StartXrayEngine generates the Xray JSON config and boots the core for either VPN or Proxy mode.
func StartXrayEngine(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, CdnIP string, targetCDN string, isProxyMode bool, localPort int, vpnMtu int, protocol string, debug bool, fragment bool, blockQuic bool, sniIndex int64) error {

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
		outboundJSON = buildXrayRealityXHTTPOutbound(configIndex, globalDnsServer, getServerIpFromDomain, fragment, sniIndex)
	case "vless-xhttp":
		outboundJSON = buildXrayVlessXhttpOutbound(configIndex, globalDnsServer, getServerIpFromDomain, CdnIP, targetCDN)
	case "reality-tcp":
		outboundJSON = buildXrayRealityTCPOutbound(configIndex, globalDnsServer, getServerIpFromDomain, fragment, sniIndex)
	case "vless-ws":
		outboundJSON = buildXrayVlessWsOutbound(configIndex, globalDnsServer, getServerIpFromDomain, CdnIP, targetCDN)
	case "vless-httpupgrade":
		outboundJSON = buildXrayVlessHttpUpgradeOutbound(configIndex, globalDnsServer, getServerIpFromDomain, CdnIP, targetCDN)
	case "vless-grpc":
		outboundJSON = buildXrayVlessGrpcOutbound(configIndex, globalDnsServer, getServerIpFromDomain, CdnIP, targetCDN)			
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

	rawConfig := fmt.Sprintf(`{
		"log": {
			"loglevel": "%s"
		},
		"routing": {
			"domainStrategy": "IPIfNonMatch",
			"rules": [%s
				{
					"type": "field",
					"ip": [
						"10.0.0.0/8",
						"172.16.0.0/12",
						"192.168.0.0/16",
						"127.0.0.0/8",
						"fc00::/7",
						"fe80::/10"
					],
					"outboundTag": "direct"
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
				"protocol": "freedom",
				"tag": "direct"
			},
			{
				"protocol": "blackhole",
				"tag": "block"
			}
		]
	}`, logLevel, quicBlockRuleJSON, inboundsJSON, outboundJSON, fragmentOutboundJSON)
	
			
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
// VLESS-XHTTP OUTBOUND (TLS)
// =====================================================================

func buildXrayVlessXhttpOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIp string, targetCDN string) string {
	
	CdnIP := runtimeVlessIp
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = GetTargetIP(configIndex, "vless-xhttp", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIp)
	}

	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	domain := GetVlessCdnDomain(configIndex, "vless-xhttp", targetCDN)		
	path := getXhttpPath(configIndex)

	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = domain	
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
			"security": "tls",
			"tlsSettings": {
				"serverName": "%s",
				"alpn": ["h2", "http/1.1"]
			},
			"xhttpSettings": {
				"path": "%s",
				"host": "%s",
				"mode": "auto"
			}
		}
	}`, CdnIP, serverPort, uuid, domain, path, domain)
}

// =====================================================================
// OUTBOUND BUILDER FOR VLESS gRPC (XRAY-CORE FORMAT)
// =====================================================================

func buildXrayVlessGrpcOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIp string, targetCDN string) string {

	CdnIP := runtimeVlessIp
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = GetTargetIP(configIndex, "vless-grpc", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIp)
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
	}`, CdnIP, serverPort, uuid, GrpcDomain, grpcServiceName)
}

		
// =====================================================================
// VLESS WEBSOCKET OUTBOUND
// =====================================================================

func buildXrayVlessWsOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIp string, targetCDN string) string {
	CdnIP := runtimeVlessIp
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = GetTargetIP(configIndex, "vless-ws", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIp)
	}
		
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	wsDomain := GetVlessCdnDomain(configIndex, "vless-ws", targetCDN)
	wsPath := getWsPath(configIndex)

	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = wsDomain	
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
	}`, CdnIP, serverPort, uuid, wsDomain, wsPath, wsDomain)
}

// =====================================================================
// VLESS HTTP-UPGRADE OUTBOUND
// =====================================================================

func buildXrayVlessHttpUpgradeOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, runtimeVlessIp string, targetCDN string) string {
	CdnIP := runtimeVlessIp
	if CdnIP == "" || CdnIP == "0.0.0.0" {
		CdnIP = GetTargetIP(configIndex, "vless-httpupgrade", globalDnsServer, getServerIpFromDomain, targetCDN, runtimeVlessIp)
	}

	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	serverName := getHttpupgradeServerName(configIndex)
	httpUpgradeDomain := GetVlessCdnDomain(configIndex, "vless-httpupgrade", targetCDN)
	httpUpgradePath := getHttpupgradePath(configIndex)

	if CdnIP == "" || CdnIP == "0.0.0.0"{
		CdnIP = httpUpgradeDomain
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
	}`, CdnIP, serverPort, uuid, serverName, httpUpgradePath, httpUpgradeDomain)
}

// =====================================================================
// REALITY-XHTTP OUTBOUND
// =====================================================================
func buildXrayRealityXHTTPOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, fragment bool, sniIndex int64) string {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	serverPort := getXhttpPort(configIndex)
	
	uuid := getRealityUUID(configIndex)
	xhttpPath := getXhttpPath(configIndex)
	targetHost := getRealityDomain(configIndex, sniIndex)
	
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
				"path": "%s",
				"host": "%s",
				"mode": "auto"
			},
			"security": "reality"%s,
			"realitySettings": {
				"fingerprint": "chrome",
				"serverName": "%s",
				"publicKey": "%s",
				"shortId": "%s",
				"spiderX": "/",
				"alpn": [
					"h2",
					"http/1.1"
				]
			}
		}
	}`, serverIP, serverPort, uuid, xhttpPath, targetHost, sockoptJSON, targetHost, publicKey, shortId)
}

// =====================================================================
// REALITY-TCP OUTBOUND
// =====================================================================
func buildXrayRealityTCPOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool, fragment bool, sniIndex int64) string {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)

	// Ensure we fetch the raw port string and convert it
	serverPort := getRealityTcpPort(configIndex)
	if serverPort <= 0 {
		serverPort = 443 // Safe fallback just in case
	}

	uuid := getRealityUUID(configIndex)
	targetHost := getRealityDomain(configIndex, sniIndex)
	publicKey := getRealityPubKey(configIndex)
	shortId := getRealityShortId(configIndex)

//	log.Printf("VAY_DEBUG: Reality TCP SNI %v %v %v",targetHost,sniIndex, serverPort)
	
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
		
	
}

// StopXrayEngine safely terminates the active Xray instance.
func StopXrayEngine() {
	if activeXrayServer != nil {
		activeXrayServer.Close()
		activeXrayServer = nil
		log.Printf("VAY_DEBUG: Shutting down Xray core...")
	}
}

// StartXrayFromConfig decodes a raw JSON configuration byte slice and starts the Xray core instance.
func StartXrayFromConfig(jsonConfig []byte) error {
	// Ensure no orphaned instance is running
	StopXrayEngine()

	jsonReader := bytes.NewReader(jsonConfig)
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

	// Store the instance globally so we can shut it down later via StopXrayEngine()
	activeXrayServer = server

	log.Printf("VAY_DEBUG: Xray Middleware core started successfully from config.")
	return nil
}
