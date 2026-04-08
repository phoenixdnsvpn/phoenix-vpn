package bridge

import (
	"context"
	"fmt"
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

	parseDur := func(input string, fallback time.Duration) time.Duration {
		if input == "" || input == "null" {
			return fallback
		}
		d, err := time.ParseDuration(input)
		if err != nil {
			return fallback
		}
		return d
	}

	// 2. Parse Durations
	idleTimeout := parseDur(c.IdleTimeout, 10*time.Second)
	keepAlive := parseDur(c.KeepAlive, 2*time.Second)
	reconnectMin := parseDur(c.ReconnectMin, 2*time.Second)
	reconnectMax := parseDur(c.ReconnectMax, 10*time.Second)
	sessionCheck := parseDur(c.SessionCheck, 500*time.Millisecond)
	openStreamTimeout := parseDur(c.OpenStreamTimeout, 10*time.Second)
	udpTimeout := parseDur(c.UDPTimeout, 30*time.Second)

	// 3. Apply Dnstt Compatibility logic
	if c.CompatDnstt {
		c.RecordType = "txt"
		idleTimeout = client.DnsttIdleTimeout
		keepAlive = client.DnsttKeepAlive
		c.MaxQnameLen = 253
	}

	if keepAlive >= idleTimeout {
		keepAlive = idleTimeout / 3
	}

	// 4. Build Resolver
	var rType client.ResolverType
	if c.DohURL != "" {
		rType = client.ResolverTypeDOH
	} else if c.DotAddr != "" {
		rType = client.ResolverTypeDOT
	} else if c.UdpAddr != "" {
		rType = client.ResolverTypeUDP
	} else {
		return fmt.Errorf("at least one resolver must be specified")
	}

	rAddr := c.DohURL
	if rType == client.ResolverTypeDOT { rAddr = c.DotAddr }
	if rType == client.ResolverTypeUDP { rAddr = c.UdpAddr }

	resolver, err := client.NewResolver(rType, rAddr)
	if err != nil {
		return err
	}

/*
	// NEW: Use DialerControl instead of resolver.Dialer
	// This provides the "Bypass" logic for the Android VPN in v0.2.6
	resolver.DialerControl = func(network, address string, rawConn syscall.RawConn) error {
		return rawConn.Control(func(fd uintptr) {
			if c.Protector != nil {
				log.Infof("VAY_DEBUG: Protecting socket fd: %d", fd)
				c.Protector.Protect(int(fd))
			} else {
				log.Errorf("VAY_DEBUG: PROTECTOR IS NULL! Bypass will fail.")
			}
		})
	}
*/
	resolver.DialerControl = func(network, address string, rawConn syscall.RawConn) error {
	    // Only intercept the dialer if we actually have a protector to use.
	    // If Protector is nil, we let Android handle the routing normally.
	    if c.Protector == nil {
	        return nil 
	    }

	    return rawConn.Control(func(fd uintptr) {
	        log.Infof("VAY_DEBUG: Protecting socket fd: %d", fd)
	        c.Protector.Protect(int(fd))
	    })
	}

	utlsID, _ := client.SampleUTLSDistribution(c.UtlsDistribution)
	resolver.UTLSClientHelloID = utlsID
	resolver.UDPTimeout = udpTimeout

	// 5. Build Tunnel Server
	ts, err := client.NewTunnelServer(c.Domain, c.PubkeyHex)
	if err != nil {
		return err
	}
	ts.DnsttCompat = c.CompatDnstt
	ts.RecordType = strings.ToLower(c.RecordType)
	ts.RPS = c.RpsLimit
	ts.MaxQnameLen = 253
	ts.MaxNumLabels = 45
	ts.ClientIDSize = 2

	// 6. Assemble the final Tunnel
	tunnel, err := client.NewTunnel(resolver, ts)
	if err != nil {
		return err
	}
	
	// Apply settings to the Tunnel struct
	tunnel.IdleTimeout = idleTimeout
	tunnel.KeepAlive = keepAlive
	tunnel.ReconnectMinDelay = reconnectMin
	tunnel.ReconnectMaxDelay = reconnectMax
	tunnel.SessionCheckInterval = sessionCheck
	tunnel.OpenStreamTimeout = openStreamTimeout
	tunnel.MaxStreams = c.MaxStreams

	// Context cancellation handler
	go func() {
		<-ctx.Done()
		log.Info("VAY_DEBUG: Context cancelled - shutting down tunnel")
		tunnel.Close()
	}()

	if c.ListenAddr == "" {
		c.ListenAddr = "127.0.0.1:10808"
	}

	log.Infof("VAY_DEBUG: VayDNS starting on %s", c.ListenAddr)
	return tunnel.ListenAndServe(c.ListenAddr)
}
