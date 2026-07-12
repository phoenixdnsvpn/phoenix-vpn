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
	XhttpPort   string `json:"xhttp_port"` 
	XhttpPath   string `json:"xhttp_path"`   
}

func getXhttpPort(index int64) string {
	ensureParsed()

	return defaultConfigs[index].XhttpPort
}

func getXhttpPath(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "/vayxhttp"
	}
	if defaultConfigs[index].XhttpPath != "" {
		return defaultConfigs[index].XhttpPath
	}
	return "/vayxhttp"
}

// StartXrayEngine generates the Xray JSON config and boots the core for either VPN or Proxy mode.
func StartXrayEngine(configIndex int64, globalDnsServer string, isProxyMode bool, localPort int, vpnMtu int, protocol string) error {
	
	// Ensure no orphaned instance is running
	StopXrayEngine()

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
				"destOverride": ["http", "tls", "quic"]
			}
		}`, localPort)
	} else {
		inboundsJSON = fmt.Sprintf(`{
			"protocol": "tun",
			"settings": {
				"mtu": %d
			},
			"sniffing": {
				"enabled": true,
				"destOverride": ["http", "tls", "quic"]
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
		outboundJSON = buildXrayRealityXHTTPOutbound(configIndex, globalDnsServer)
	case "reality-tcp":
		outboundJSON = buildXrayRealityTCPOutbound(configIndex, globalDnsServer)
	case "vless-ws":
		outboundJSON = buildXrayVlessWsOutbound(configIndex)
	case "vless-httpupgrade":
		outboundJSON = buildXrayVlessHttpUpgradeOutbound(configIndex)	
	case "hysteria2":
		outboundJSON = buildXrayHysteria2Outbound(configIndex, globalDnsServer)			
	default:
		return fmt.Errorf("unsupported Xray protocol: %s", protocol)
	}

	// 3. Build the final unified JSON config
	rawConfig := fmt.Sprintf(`{
		"log": {
			"loglevel": "warning"
		},
		"inbounds": [
			%s
		],
		"outbounds": [
			%s
		]
	}`, inboundsJSON, outboundJSON)

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
// HYSTERIA2 OUTBOUND
// =====================================================================
// =====================================================================
// HYSTERIA2 OUTBOUND
// =====================================================================
func buildXrayHysteria2Outbound(configIndex int64, globalDnsServer string) string {
	serverIP := getHysteriaServerIP(configIndex, globalDnsServer)
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
// VLESS WEBSOCKET OUTBOUND
// =====================================================================
func buildXrayVlessWsOutbound(configIndex int64) string {
	// Uses the exact same fallback logic for Cloudflare Anycast IP as Sing-box
	serverIP := getCloudflareIP(configIndex)
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	wsDomain := getWsDomain(configIndex)
	wsPath := getWsPath(configIndex)

	return fmt.Sprintf(`{
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
	}`, serverIP, serverPort, uuid, wsDomain, wsPath, wsDomain)
}

// =====================================================================
// VLESS HTTP-UPGRADE OUTBOUND
// =====================================================================
func buildXrayVlessHttpUpgradeOutbound(configIndex int64) string {
	serverIP := getCloudflareIP(configIndex)
	serverPort := getVlessServerPort(configIndex)
	uuid := getVlessUUID(configIndex)
	serverName := getHttpupgradeServerName(configIndex)
	httpUpgradeDomain := getHttpupgradeDomain(configIndex)
	httpUpgradePath := getHttpupgradePath(configIndex)

	return fmt.Sprintf(`{
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
	}`, serverIP, serverPort, uuid, serverName, httpUpgradePath, httpUpgradeDomain)
}

// =====================================================================
// REALITY-XHTTP OUTBOUND
// =====================================================================

func buildXrayRealityXHTTPOutbound(configIndex int64, globalDnsServer string) string {
	serverIP := getRealityServerIP(configIndex, globalDnsServer)
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

	return fmt.Sprintf(`{
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
			"security": "reality",
			"realitySettings": {
				"fingerprint": "chrome",
				"serverName": "%s",
				"publicKey": "%s",
				"shortId": "%s",
				"spiderX": "/"
			}
		}
	}`, serverIP, serverPort, uuid, xhttpPath, targetHost, publicKey, shortId)
}

// =====================================================================
// REALITY-TCP OUTBOUND
// =====================================================================
func buildXrayRealityTCPOutbound(configIndex int64, globalDnsServer string) string {
	serverIP := getRealityServerIP(configIndex, globalDnsServer)
	
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

	// Note the addition of "xtls-rprx-vision" - this is Xray's flagship optimization for Reality-TCP!
	return fmt.Sprintf(`{
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
	}`, serverIP, serverPort, uuid, targetHost, publicKey, shortId)
}
// StartXrayEngine generates the Xray JSON config and boots the core.
func StartXrayEngine_socks(listenPort int, configIndex int64, globalDnsServer string) error {
	
	// Ensure no orphaned instance is running
	StopXrayEngine()
		
	serverIP := getRealityServerIP(configIndex, globalDnsServer)
	serverPortStr := getXhttpPort(configIndex)
	serverPort, err := strconv.Atoi(serverPortStr)
	if err != nil || serverPort == 0 {
		serverPort = 2053 // Safe fallback if the JSON is empty or invalid
	}

	if globalDnsServer != "0.0.0.0" && globalDnsServer != "" {
		// 1. Get the domain name of your actual server		
		serverDomain := getServerDomain(configIndex)
		// 2. Resolve the IP silently via DoH using the Global BootstrapDns variable
		serverIP := resolveDomainOverDoH(serverDomain, globalDnsServer)
		// 3. Fallbacks just in case the encrypted DNS lookup fails
		if serverIP == "" {
			serverIP = getRealityServerIP(configIndex, globalDnsServer)
		}
		if serverIP == "" {
			serverIP = serverDomain // Send the raw domain to Xray/Sing-box as a last resort
		}
	}
	
	uuid := getRealityUUID(configIndex)
	targetHost := getRealityDomain(configIndex)
	shortId := getRealityShortId(configIndex) 
	publicKey := getRealityPubKey(configIndex)
	xhttpPath := getXhttpPath(configIndex)
//	xhttpPath := "/vayxhttp"
		
	// Build the strict Xray-compatible JSON configuration
	rawConfig := fmt.Sprintf(`{
		"log": {
			"loglevel": "warning"
		},
		"inbounds": [
			{
				"listen": "127.0.0.1",
				"port": %d,
				"protocol": "socks",
				"settings": {
					"auth": "noauth",
					"udp": true
				},
				"sniffing": {
					"enabled": true,
					"destOverride": ["http", "tls", "quic"]
				}
			}
		],
		"outbounds": [
			{
				"protocol": "vless",
				"settings": {
					"vnext": [
						{
							"address": "%s",
							"port": %d,
							"users": [
								{
									"id": "%s",
									"encryption": "none",
									"flow": ""
								}
							]
						}
					]
				},
				"streamSettings": {
					"network": "xhttp",
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
			}
		]
	}`, listenPort, serverIP, serverPort, uuid, xhttpPath, targetHost, publicKey, shortId)

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
	log.Printf("VAY_DEBUG: Xray Engine booted successfully on port %d with xhttp+REALITY", listenPort)

	return nil
}

// StopXrayEngine safely terminates the active Xray instance.
func StopXrayEngine() {
	if activeXrayServer != nil {
		activeXrayServer.Close()
		activeXrayServer = nil
		log.Printf("VAY_DEBUG: Shutting down Xray core...")		
	}
}
