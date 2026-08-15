package mobile

import (

//	"crypto/tls"
	"net"
	"strings"
	"strconv"
	"context"
	"fmt"
	"io"
	"log"
	
	hyClient "github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/extras/v2/obfs"
	"github.com/armon/go-socks5"	
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

// tcpConnWrapper mocks the network address types to prevent SOCKS5 library panics.
// It embeds the native Hysteria QUIC stream so Read/Write methods pass through seamlessly.
type tcpConnWrapper struct {
	net.Conn
}

func (w *tcpConnWrapper) LocalAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0}
}

func (w *tcpConnWrapper) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0}
}

// hysteriaConnFactory intercepts the connection creation to apply Salamander obfuscation to the raw UDP sockets.
type hysteriaConnFactory struct {
	ObfsPass string
}

func (f *hysteriaConnFactory) New(addr net.Addr) (net.PacketConn, error) {
	// Open a standard UDP socket
	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, err
	}
	
	// If an obfuscation password is provided, wrap the socket using Salamander
	if f.ObfsPass != "" {
		wrapped, err := obfs.WrapPacketConnSalamander(conn, []byte(f.ObfsPass))
		if err != nil {
			_ = conn.Close()
			return nil, err
		}
		return wrapped, nil
	}
	
	return conn, nil
}

// =====================================================================
// HYSTERIA2 SECURE INTERNAL GETTERS
// =====================================================================

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
func buildHysteriaOutbound2(configIndex int64, globalDnsServer string, getServerIpFromDomain bool) map[string]interface{} {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
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

// buildHysteriaOutbound securely constructs the sing-box Hysteria2 JSON object.
func buildHysteriaOutbound(configIndex int64, globalDnsServer string, getServerIpFromDomain bool) map[string]interface{} {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	rawPort := getHysteriaServerPortRaw(configIndex)
	network := getHysteriaNetwork(configIndex)
	upMbps := getHysteriaUpMbps(configIndex)
	downMbps := getHysteriaDownMbps(configIndex)
	obfsPass := getHysteriaObfsPass(configIndex)
	
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
		"up_mbps":   upMbps,
		"down_mbps": downMbps,
		"obfs":      obfsObj,
		"tls":       tlsObj,
	}

	// FIX: Only inject "network" if it was explicitly defined in config
	if network != "" {
		outbound["network"] = network
	}

	// =========================================================
	// DYNAMIC PORT INJECTION (Sing-Box Syntax Transformation)
	// =========================================================
	cleanPortStr := strings.ReplaceAll(rawPort, " ", "")
	cleanPortStr = strings.ReplaceAll(cleanPortStr, "\r", "")
	cleanPortStr = strings.ReplaceAll(cleanPortStr, "\n", "")
	
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

// startHysteriaNativeEngine boots the official Apernet Hysteria v2 client
// and mounts a local SOCKS5 proxy server for the system tunnel to consume.
func startHysteriaNativeEngine(listenPort int, configIndex int64, globalDnsServer string, getServerIpFromDomain bool) error {
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	
	// Dynamically extract the primary port from the configuration
	rawPort := getHysteriaServerPortRaw(configIndex)
	portStr := strings.Split(strings.ReplaceAll(rawPort, "-", ","), ",")[0]

	// 1. Resolve the raw string into a proper net.UDPAddr
	udpAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(serverIP, portStr))
	if err != nil {
		return fmt.Errorf("failed to resolve hysteria server address: %w", err)
	}

	// Fetch security and connection credentials
	sniDomain := getHysteriaDomain(configIndex)
	authPass := getHysteriaAuthPass(configIndex)
	obfsPass := getHysteriaObfsPass(configIndex)
	upMbps := uint64(getHysteriaUpMbps(configIndex) * 1024 * 1024 / 8) // Convert to Bytes
	downMbps := uint64(getHysteriaDownMbps(configIndex) * 1024 * 1024 / 8) // Convert to Bytes

	// 2. Map strictly to Hysteria's native client config structure
	hyConfig := &hyClient.Config{
		ServerAddr: udpAddr,
		Auth:       authPass,
		TLSConfig: hyClient.TLSConfig{
			ServerName:         sniDomain,
			InsecureSkipVerify: true, 
		},
		BandwidthConfig: hyClient.BandwidthConfig{
			MaxTx: upMbps,
			MaxRx: downMbps,
		},
		// Inject our custom factory to handle the UDP Obfuscation routing
		ConnFactory: &hysteriaConnFactory{
			ObfsPass: obfsPass,
		},
	}

	// 3. Initialize the Core Client. 
	// NOTE: NewClient blocks and performs the handshake. If err == nil, we are connected!
	client, _, err := hyClient.NewClient(hyConfig)
	if err != nil {
		return fmt.Errorf("failed to create native hysteria client: %w", err)
	}

	// 4. Start Local SOCKS5 Proxy mapped to Hysteria
	conf := &socks5.Config{
		Logger: log.New(io.Discard, "", 0), // <--- Add this line to silence the logs
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			// Route TCP traffic natively through the Hysteria QUIC client
			if strings.HasPrefix(network, "tcp") {
				conn, err := client.TCP(addr)
				if err != nil {
					return nil, err
				}
				// Wrap the connection to safely mock the TCPAddr interfaces
				return &tcpConnWrapper{Conn: conn}, nil
			}
			return nil, fmt.Errorf("unsupported network mapped for SOCKS5: %s", network)
		},
	}
	
	server, err := socks5.New(conf)
	if err != nil {
		client.Close()
		return fmt.Errorf("failed to create go-socks5 server: %w", err)
	}

	// Spin up the SOCKS5 listener in a goroutine
	activeWg.Add(1)
	go func() {
		defer activeWg.Done()
		log.Printf("VAY_DEBUG: Native Hysteria Core SOCKS5 listening on port %d", listenPort)
		
		if err := server.ListenAndServe("tcp", fmt.Sprintf("127.0.0.1:%d", listenPort)); err != nil {
			log.Printf("VAY_DEBUG: Hysteria SOCKS5 server stopped: %v", err)
		}
	}()

	// Watchdog to safely close the client when VPN context is cancelled
	activeWg.Add(1)
	go func() {
		defer activeWg.Done()
		<-activeCtx.Done()
		log.Printf("VAY_DEBUG: Shutting down Hysteria Core...")
		client.Close() 
	}()

	return nil
}
