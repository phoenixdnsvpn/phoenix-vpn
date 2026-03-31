package bridge

import (
	"context"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"

	log "github.com/sirupsen/logrus"
	"github.com/net2share/vaydns/client"
)

// SocketProtector is an interface that Android will implement
// to allow Go to bypass the VPN tunnel.
type SocketProtector interface {
	Protect(fd int) bool
}

type TunnelConfig struct {
	DohURL, DotAddr, UdpAddr, Domain, ListenAddr string
	PubkeyHex, UtlsDistribution, RecordType      string
	LogLevel                                     string
	MaxQnameLen, MaxNumLabels, MaxStreams        int
	RpsLimit                                     float64
	IdleTimeout, KeepAlive, ReconnectMin         string
	ReconnectMax, SessionCheck, OpenStreamTimeout string
	UDPTimeout                                   string
	CompatDnstt                                  bool
	ClientIDSize                                 int
	Protector SocketProtector
}

var tunnelCancel context.CancelFunc

func RunTunnel(ctx context.Context, c TunnelConfig) error {
	// 1. Setup Logging

	if c.LogLevel == "" {
		c.LogLevel = "info"
	}

	level, _ := log.ParseLevel(c.LogLevel)
	log.SetLevel(level)

// Helper to parse duration with a fallback default
	parseDur := func(input string, fallback time.Duration) time.Duration {
		if input == "" || input == "null" {
			return fallback
		}
		d, err := time.ParseDuration(input)
		if err != nil {
			log.Warnf("VAY_DEBUG: Invalid duration '%s', using %v", input, fallback)
			return fallback
		}
		return d
	}

	// 2. Parse all Durations with original error checking

	idleTimeout := parseDur(c.IdleTimeout, 60*time.Second)
	keepAlive := parseDur(c.KeepAlive, 20*time.Second)
	reconnectMin := parseDur(c.ReconnectMin, 2*time.Second)
	reconnectMax := parseDur(c.ReconnectMax, 10*time.Second)
	sessionCheck := parseDur(c.SessionCheck, 30*time.Second)
	openStreamTimeout := parseDur(c.OpenStreamTimeout, 10*time.Second)
	udpTimeout := parseDur(c.UDPTimeout, 30*time.Second)

	// 3. Apply the original 'dnstt-compat' logic blocks
	if c.CompatDnstt {
		c.RecordType = "txt"
		idleTimeout = client.DnsttIdleTimeout
		keepAlive = client.DnsttKeepAlive
		c.MaxQnameLen = 253
	}

	if idleTimeout == 0 {
		idleTimeout = 60 * time.Second
	}
	if keepAlive == 0 {
		keepAlive = 20 * time.Second
	}

// Ensure the Rule: KeepAlive < IdleTimeout
	if keepAlive >= idleTimeout {
		log.Warnf("VAY_DEBUG: KeepAlive %v >= Idle %v. Adjusting...", keepAlive, idleTimeout)
		// Force KeepAlive to be 1/3 of IdleTimeout to satisfy the library requirement
		keepAlive = idleTimeout / 3
	}

	if c.UtlsDistribution == "" {
		c.UtlsDistribution = "chrome"
	}

	// 5. Build Resolver with uTLS
	utlsID, err := client.SampleUTLSDistribution(c.UtlsDistribution)
	if err != nil {
		// This is likely where "unexpected end of string" is coming from
		log.Errorf("VAY_DEBUG: uTLS Error: %v. Falling back to Chrome.", err)
		utlsID, _ = client.SampleUTLSDistribution("chrome")
	}

// Setup the DialContext with Socket Protection
	// This is the "Bypass" logic for the Android VPN

// Setup the DialContext with Socket Protection
	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
		Control: func(network, address string, rawConn syscall.RawConn) error {
			return rawConn.Control(func(fd uintptr) {
				// Accessing 'c' from the RunTunnel(ctx, c TunnelConfig) arguments
				if c.Protector != nil {
//					log.Debugf("Protecting socket fd: %d", fd)
					log.Infof("VAY_DEBUG: Protecting socket fd: %d", fd)
					c.Protector.Protect(int(fd))
				}else {
    				log.Errorf("VAY_DEBUG: PROTECTOR IS NULL! Bypass will fail.")
				}				
			})
		},
	}

	var rType client.ResolverType
	var rAddr string

	if c.DohURL != "" {
		rType, rAddr = client.ResolverTypeDOH, c.DohURL
	} else if c.DotAddr != "" {
		rType, rAddr = client.ResolverTypeDOT, c.DotAddr
	} else if c.UdpAddr != "" {
		rType, rAddr = client.ResolverTypeUDP, c.UdpAddr
	}else {
		return fmt.Errorf("at least one resolver (-udp, -doh, or -dot) must be specified")
	}

	resolver, err := client.NewResolver(rType, rAddr)
	if err != nil {
		return err
	}

	resolver.Dialer = dialer
	resolver.UTLSClientHelloID = utlsID
	resolver.UDPTimeout = udpTimeout

	// 6. Build Tunnel Server (handles encryption/handshake)
	ts, err := client.NewTunnelServer(c.Domain, c.PubkeyHex)
	if err != nil {
		return err
	}
	ts.DnsttCompat = c.CompatDnstt
//	ts.MaxQnameLen = c.MaxQnameLen
	ts.RecordType = strings.ToLower(c.RecordType)
	ts.RPS = c.RpsLimit

	ts.MaxQnameLen = 253
	ts.MaxNumLabels = 45 // This is the key for Base64 efficiency
	ts.DnsttCompat = false
    ts.ClientIDSize = 2

	resolver.UDPTimeout = 2 * time.Second
	
// If not in compat mode, force 2-byte ClientID and Base64 encoding
	if !c.CompatDnstt {
		log.Info("VAY_DEBUG: Using VayDNS optimized wire format (Base64 + 2-byte ClientID)")
		ts.ClientIDSize = 2 
		// Note: Most VayDNS 'client' packages use URLEncoding by default 
		// if DnsttCompat is false. Ensure your client library supports this.
	}

	// 7. Assemble the final Tunnel
	tunnel, err := client.NewTunnel(resolver, ts)
	if err != nil {
		return err
	}
	defer tunnel.Close()  // extra safety

	tunnel.KeepAlive = 5 * time.Second

	// Monitor the Context for the Stop signal
	go func() {
		<-ctx.Done()
		log.Info("VAY_DEBUG: Context cancelled - shutting down tunnel")
		time.Sleep(400 * time.Millisecond)
		if tunnel != nil {
			tunnel.Close()
		}		
		
	}()

//	log.Infof("VayDNS listening on %s", c.ListenAddr)    
	

	tunnel.IdleTimeout = idleTimeout
	tunnel.KeepAlive = keepAlive
	tunnel.ReconnectMinDelay = reconnectMin
	tunnel.ReconnectMaxDelay = reconnectMax
	tunnel.SessionCheckInterval = sessionCheck
	tunnel.OpenStreamTimeout = openStreamTimeout
	tunnel.MaxStreams = c.MaxStreams

	if c.ListenAddr == "" {
        c.ListenAddr = "127.0.0.1:10808"
    }

	log.Infof("VAY_DEBUG: VayDNS starting ListenAndServe on %s", c.ListenAddr)

	err = tunnel.ListenAndServe(c.ListenAddr)
	if err != nil {
		log.Errorf("VAY_DEBUG: ListenAndServe Error: %v", err)
	}
	log.Info("VAY_DEBUG: RunTunnel exiting")
	return err

//	return tunnel.ListenAndServe(c.ListenAddr)
}