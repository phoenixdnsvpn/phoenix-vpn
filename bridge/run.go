package bridge

import (
	"context"
	"fmt"
	"strings"
	"syscall"
	"time"

	log "github.com/sirupsen/logrus"
	"github.com/Starling226/vaydns-vpn/vaydns/client"
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
//	reconnectMax := parseDur(c.ReconnectMax, 30*time.Second)
	reconnectMax := parseDur(c.ReconnectMax, 10*time.Second)
	sessionCheck := parseDur(c.SessionCheck, 500*time.Millisecond)
	openStreamTimeout := parseDur(c.OpenStreamTimeout, 10*time.Second)
	udpTimeout := parseDur(c.UDPTimeout, 10*time.Second)

	// 3. Apply Dnstt Compatibility logic
	if c.CompatDnstt {
		c.RecordType = "txt"
		idleTimeout = client.DnsttIdleTimeout
		keepAlive = client.DnsttKeepAlive
		c.MaxQnameLen = 253
	}

	if keepAlive > idleTimeout / 5 {
		idleTimeout = keepAlive * 5
	}
	
//	if keepAlive >= idleTimeout {
//		keepAlive = idleTimeout / 3
//	}

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

	resolver.DialerControl = func(network, address string, rawConn syscall.RawConn) error {
    	// Check if we are already shutting down
	    if err := ctx.Err(); err != nil {
    	    // Return context.Canceled directly. 
    	    // Most Go network libraries treat this as a "stop everything" signal.
    	    return err 
	    }

	    if c.Protector == nil {
	        return nil 
	    }

	    return rawConn.Control(func(fd uintptr) {
	        select {
	        case <-ctx.Done():
	            return 
	        default:
	            c.Protector.Protect(int(fd))
	        }
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
//	ts.ClientIDSize = 2
    ts.ClientIDSize = c.ClientIDSize


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
		if tunnel != nil {
			tunnel.Close()
		}
	}()

	if c.ListenAddr == "" {
		c.ListenAddr = "127.0.0.1:10808"
	}

	log.Infof("VAY_DEBUG: VayDNS starting on %s", c.ListenAddr)
	return tunnel.ListenAndServe(c.ListenAddr)
}
