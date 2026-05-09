// Package client provides a reusable DNS tunnel client library.
//
// It provides configuration options for VayDNS features (DoH/DoT transports,
// per-query UDP, forged response filtering, rate limiting, dnstt wire
// compatibility, etc.).
//
// Basic usage (xray-core compatible):
//
//	r, _ := client.NewResolver(client.ResolverTypeUDP, "8.8.8.8:53")
//	ts, _ := client.NewTunnelServer("t.example.com", "pubkey-hex")
//	t, _ := client.NewTunnel(r, ts)
//	t.InitiateResolverConnection()
//	t.InitiateDNSPacketConn(ts.Addr)
//	t.InitiateKCPConn(ts.MTU)
//	t.InitiateNoiseChannel()
//	t.InitiateSmuxSession()
//	stream, _ := t.OpenStream() // returns net.Conn
//	defer t.Close()
package client

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sync"
	"syscall"
	"time"
	"strings"
	"sync/atomic"

	"github.com/net2share/vaydns/dns"
	"github.com/net2share/vaydns/noise"
	"github.com/net2share/vaydns/turbotunnel"
	utls "github.com/refraction-networking/utls"
	log "github.com/sirupsen/logrus"
	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

// Default timeouts for VayDNS mode.
const (
	DefaultIdleTimeout          = 10 * time.Second
	DefaultKeepAlive            = 2 * time.Second
	DefaultOpenStreamTimeout    = 10 * time.Second
	DefaultReconnectDelay       = 1 * time.Second
	DefaultReconnectMaxDelay    = 30 * time.Second
	DefaultSessionCheckInterval = 500 * time.Millisecond
	DefaultUDPResponseTimeout   = 500 * time.Millisecond
	DefaultUDPWorkers           = 100
	DefaultMaxStreams           = 0 // unlimited
	DefaultHandshakeTimeout     = 15 * time.Second
)

// Default timeouts for dnstt compatibility mode.
const (
	DnsttIdleTimeout = 2 * time.Minute
	DnsttKeepAlive   = 10 * time.Second
)

// ResolverType identifies the DNS transport to use.
type ResolverType string

const (
	ResolverTypeUDP ResolverType = "udp"
	ResolverTypeTCP ResolverType = "tcp"
	ResolverTypeDOT ResolverType = "dot"
	ResolverTypeDOH ResolverType = "doh"
)

// Resolver holds DNS resolver configuration.
type Resolver struct {
	ResolverType ResolverType
//	ResolverAddr string // UDP: "1.1.1.1:53", DoT: "resolver:853", DoH: "https://resolver/dns-query"
	ResolverAddrs []string
	// UTLSClientHelloID sets the uTLS fingerprint for DoH/DoT connections.
	// nil means no uTLS (plain TLS).
	UTLSClientHelloID *utls.ClientHelloID

	// RoundTripper overrides the HTTP transport for DoH. If set,
	// UTLSClientHelloID is ignored for DoH.
	RoundTripper http.RoundTripper

	// DialerControl is an optional callback for setting socket options
	// (SO_MARK, SO_BINDTODEVICE, etc.) on UDP sockets.
	DialerControl func(network, address string, c syscall.RawConn) error

	// UDP transport settings (only apply to ResolverTypeUDP).
	UDPWorkers      int           // concurrent UDP workers (0 = DefaultUDPWorkers)
	UDPSharedSocket bool          // use single shared socket instead of per-query
	UDPTimeout      time.Duration // per-query response timeout (0 = DefaultUDPResponseTimeout)
	UDPAcceptErrors bool          // pass through non-NOERROR responses (default: filter)
}

// --- PROXY STATS TRACKING ---

var (
	ProxyRxBytes uint64
	ProxyTxBytes uint64
)

// StatsConn wraps a net.Conn to count bytes as they flow through.
type StatsConn struct {
	net.Conn
}

// Read counts bytes going OUT to the internet (Upload / TX)
func (c *StatsConn) Read(b []byte) (n int, err error) {
	n, err = c.Conn.Read(b)
	if n > 0 {
		atomic.AddUint64(&ProxyTxBytes, uint64(n))
	}
	return
}

// Write counts bytes coming IN from the internet (Download / RX)
func (c *StatsConn) Write(b []byte) (n int, err error) {
	n, err = c.Conn.Write(b)
	if n > 0 {
		atomic.AddUint64(&ProxyRxBytes, uint64(n))
	}
	return
}


// NewResolver creates a Resolver that handles comma-separated multipath addresses.
func NewResolver(resolverType ResolverType, resolverAddrs string) (Resolver, error) {
	switch resolverType {
	case ResolverTypeUDP, ResolverTypeTCP, ResolverTypeDOT, ResolverTypeDOH:
	default:
		return Resolver{}, fmt.Errorf("unsupported resolver type: %s", resolverType)
	}

	// Split the comma-separated list provided by mobile.go
	parts := strings.Split(resolverAddrs, ",")
	trimmed := make([]string, 0, len(parts))
	for _, p := range parts {
		if t := strings.TrimSpace(p); t != "" {
			trimmed = append(trimmed, t)
		}
	}

	if len(trimmed) == 0 {
		return Resolver{}, errors.New("no resolver addresses provided")
	}

	return Resolver{
		ResolverType:  resolverType,
		ResolverAddrs: trimmed,
	}, nil
}

// TunnelServer holds tunnel server configuration (domain + public key).
type TunnelServer struct {
	Addr               dns.Name
	PubKey             string
	MTU                int // auto-computed if 0 when InitiateKCPConn is called
	decodedNoisePubKey []byte

	// DnsttCompat enables the original dnstt wire format (8-byte ClientID,
	// padding prefixes). When true, ClientIDSize is forced to 8.
	DnsttCompat bool

	// ClientIDSize is the ClientID size in bytes (default: 2).
	// Ignored when DnsttCompat is true.
	ClientIDSize int

	// MaxQnameLen is the maximum QNAME wire length (default: 101, or 253 with DnsttCompat).
	MaxQnameLen int

	// MaxNumLabels is the maximum number of data labels (default: 0 = unlimited).
	MaxNumLabels int

	// RPS limits outgoing DNS queries per second (default: 0 = unlimited).
	RPS float64

	// RecordType selects the DNS record type for downstream data.
	// Supported values: "txt" (default), "cname", "a", "aaaa", "mx", "ns", "srv".
	RecordType string
}

// NewTunnelServer creates a TunnelServer from a domain string and hex-encoded
// public key.
func NewTunnelServer(addr string, pubKeyString string) (TunnelServer, error) {
	domain, err := dns.ParseName(addr)
	if err != nil {
		return TunnelServer{}, fmt.Errorf("invalid domain %+q: %w", addr, err)
	}

	pubkey, err := noise.DecodeKey(pubKeyString)
	if err != nil {
		return TunnelServer{}, fmt.Errorf("pubkey format error: %w", err)
	}

	return TunnelServer{
		Addr:               domain,
		PubKey:             pubKeyString,
		decodedNoisePubKey: pubkey,
	}, nil
}

// wireConfig returns the WireConfig derived from the TunnelServer settings.
func (ts *TunnelServer) wireConfig() turbotunnel.WireConfig {
	if ts.DnsttCompat {
		return turbotunnel.WireConfig{ClientIDSize: 8, Compat: true}
	}
	size := ts.ClientIDSize
	if size <= 0 {
		size = 2
	}
	return turbotunnel.WireConfig{ClientIDSize: size}
}

// effectiveRRType returns the DNS RR type for downstream data.
func (ts *TunnelServer) effectiveRRType() uint16 {
	rt, err := dns.ParseRecordType(ts.RecordType)
	if err != nil {
		return dns.RRTypeTXT
	}
	return rt
}

// effectiveMaxQnameLen returns the max QNAME length, applying dnstt defaults.
func (ts *TunnelServer) effectiveMaxQnameLen() int {
	if ts.MaxQnameLen > 0 {
		return ts.MaxQnameLen
	}
	if ts.DnsttCompat {
		return 253
	}
	return 101
}

// Tunnel represents a DNS tunnel connection. Create with NewTunnel, then
// either call the step-by-step Initiate* methods (for embedding in frameworks
// like xray-core) or call ListenAndServe for a fully managed session.
type Tunnel struct {
	Resolver     Resolver
	TunnelServer TunnelServer

	// Session configuration. Zero values use defaults.
	IdleTimeout          time.Duration                 // default: 10s (2m with DnsttCompat)
	KeepAlive            time.Duration                 // default: 2s (10s with DnsttCompat)
	OpenStreamTimeout    time.Duration                 // default: 10s
	MaxStreams           int                           // default: 0 (0 = unlimited)
	ReconnectMinDelay    time.Duration                 // default: 1s
	ReconnectMaxDelay    time.Duration                 // default: 30s
	SessionCheckInterval time.Duration                 // default: 500ms
	HandshakeTimeout     time.Duration                 // default: 15s
	PacketQueueSize      int                           // default: QueueSize (512)
	KCPWindowSize        int                           // default: PacketQueueSize/2
	QueueOverflowMode    turbotunnel.QueueOverflowMode // default: drop

	mu            sync.Mutex
	// internal state
	wireConfig    turbotunnel.WireConfig
	forgedStats   *ForgedStats
	resolverConn  net.PacketConn
	dnsPacketConn *DNSPacketConn
	kcpConn       *kcp.UDPSession
	noiseChannel  io.ReadWriteCloser
	smuxSession   *smux.Session
	remoteAddr    net.Addr
}

// NewTunnel creates a Tunnel with the given resolver and server configuration.
// Zero-value fields use sensible defaults.
func NewTunnel(resolver Resolver, tunnelServer TunnelServer) (*Tunnel, error) {
	t := &Tunnel{
		Resolver:     resolver,
		TunnelServer: tunnelServer,
	}
	t.wireConfig = tunnelServer.wireConfig()
	return t, nil
}

func (t *Tunnel) applyDefaults() {
	isDnstt := t.TunnelServer.DnsttCompat

	if t.IdleTimeout == 0 {
		if isDnstt {
			t.IdleTimeout = DnsttIdleTimeout
		} else {
			t.IdleTimeout = DefaultIdleTimeout
		}
	}
	if t.KeepAlive == 0 {
		if isDnstt {
			t.KeepAlive = DnsttKeepAlive
		} else {
			t.KeepAlive = DefaultKeepAlive
		}
	}
	if t.OpenStreamTimeout == 0 {
		t.OpenStreamTimeout = DefaultOpenStreamTimeout
	}
	if t.MaxStreams == 0 {
		t.MaxStreams = DefaultMaxStreams
	}
	if t.ReconnectMinDelay == 0 {
		t.ReconnectMinDelay = DefaultReconnectDelay
	}
	if t.ReconnectMaxDelay == 0 {
		t.ReconnectMaxDelay = DefaultReconnectMaxDelay
	}
	if t.SessionCheckInterval == 0 {
		t.SessionCheckInterval = DefaultSessionCheckInterval
	}
	if t.HandshakeTimeout == 0 {
		t.HandshakeTimeout = DefaultHandshakeTimeout
	}
}


func (t *Tunnel) effectivePacketQueueSize() int {
	if t.PacketQueueSize > 0 {
		return t.PacketQueueSize
	}

	if t.Resolver.ResolverType != ResolverTypeUDP {
		return 2048 
	}

	return turbotunnel.QueueSize
}

func (t *Tunnel) effectiveQueueOverflowMode() turbotunnel.QueueOverflowMode {
	if t.QueueOverflowMode != "" {
		return t.QueueOverflowMode
	}
	return turbotunnel.DefaultQueueOverflowMode
}

func (t *Tunnel) effectiveKCPWindowSize() int {
	if t.KCPWindowSize > 0 {
		return t.KCPWindowSize
	}
	ws := t.effectivePacketQueueSize() / 2
	if ws < 1 {
		ws = 1
	}
	return ws
}

// InitiateResolverConnection creates the underlying transport connection.
// It implements a "Sliding Window" multipath: it races up to 3 resolvers at a time.
// If one fails, it immediately pulls the next one from the pool to take its place.
func (t *Tunnel) InitiateResolverConnection() error {
	r := t.Resolver
	if len(r.ResolverAddrs) == 0 {
		return errors.New("multipath error: no resolver addresses available")
	}

	t.mu.Lock()
	qSize := t.effectivePacketQueueSize()
	oMode := t.effectiveQueueOverflowMode()
	t.mu.Unlock()

	type raceResult struct {
		conn        net.PacketConn
		addr        net.Addr
		forgedStats *ForgedStats
		err         error
		target      string
	}

	resultCh := make(chan raceResult, len(r.ResolverAddrs))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel() // Ensures all background workers die instantly when this function returns

	//  The Semaphore: Strictly limits us to 3 active attempts at any given time
	sem := make(chan struct{}, 3)

	// Spawner Goroutine: Feeds addresses from the pool into the worker slots
	go func() {
		for _, addrStr := range r.ResolverAddrs {
			// Try to claim a slot (blocks if 3 are currently running)
			select {
			case sem <- struct{}{}: 
			case <-ctx.Done():
				return // Abort spawning if we already found a winner
			}

			// Slot claimed! Launch the dialer
			go func(target string) {
				defer func() { <-sem }() //  Instantly free the slot for the next IP when finished

				var res raceResult
				res.target = target

				switch r.ResolverType {
				case ResolverTypeUDP:
					uAddr, err := net.ResolveUDPAddr("udp", target)
					if err != nil {
						res.err = err
					} else {
						res.addr = uAddr
						if r.UDPSharedSocket {
							lc := net.ListenConfig{Control: r.DialerControl}
							conn, err := lc.ListenPacket(ctx, "udp", ":0")
							res.conn, res.err = conn, err
						} else {
							workers := r.UDPWorkers
							if workers <= 0 { workers = DefaultUDPWorkers }
							timeout := r.UDPTimeout
							if timeout <= 0 { timeout = DefaultUDPResponseTimeout }
							conn, fs, err := NewUDPPacketConn(uAddr, r.DialerControl, workers, timeout, !r.UDPAcceptErrors, qSize, oMode)
							res.conn, res.forgedStats, res.err = conn, fs, err
						}
					}

				case ResolverTypeTCP:
					res.addr = turbotunnel.DummyAddr{}
					dialTCP := func(ctx context.Context, n, a string) (net.Conn, error) {
						return (&net.Dialer{Control: r.DialerControl}).DialContext(ctx, n, a)
					}
					res.conn, res.err = NewTLSPacketConn(target, dialTCP, qSize, oMode)

				case ResolverTypeDOH:
					res.addr = turbotunnel.DummyAddr{}
					var rt http.RoundTripper

					reqURL := target
					if !strings.HasPrefix(reqURL, "http://") && !strings.HasPrefix(reqURL, "https://") {
						reqURL = "https://" + reqURL
					}
					
					// Use Go's native URL parser to safely check if a path exists
					u, err := url.Parse(reqURL)
					if err == nil {
						// If the user didn't specify a path (e.g., "8.8.8.8" or "dns.google")
						if u.Path == "" || u.Path == "/" {
							u.Path = "/dns-query"
							reqURL = u.String()
						}
					}

					if r.UTLSClientHelloID != nil {
						rt = NewUTLSRoundTripper(nil, r.UTLSClientHelloID, r.DialerControl) 
					} else {
						tr := http.DefaultTransport.(*http.Transport).Clone()
						tr.DialContext = (&net.Dialer{Control: r.DialerControl}).DialContext
						rt = tr
					}

					// Pass the safely parsed URL and the 100 workers!
					res.conn, res.err = NewHTTPPacketConn(rt, reqURL, 32, qSize, oMode)

				case ResolverTypeDOT:
					res.addr = turbotunnel.DummyAddr{}
					dialTLS := func(ctx context.Context, n, a string) (net.Conn, error) {
						dialer := &net.Dialer{Control: r.DialerControl}
						if r.UTLSClientHelloID != nil {
							return UTLSDialContext(ctx, n, a, nil, r.UTLSClientHelloID, r.DialerControl)
						}
						return tls.DialWithDialer(dialer, n, a, nil)
					}
					res.conn, res.err = NewTLSPacketConn(target, dialTLS, qSize, oMode)
				}

				// Report result safely
				select {
				case resultCh <- res:
					if res.err == nil && ctx.Err() != nil {
						res.conn.Close() // Edge case cleanup
					}
				case <-ctx.Done():
					if res.err == nil && res.conn != nil {
						res.conn.Close() // Cleanup if a winner was already found
					}
				}
			}(addrStr)
		}
	}()

	// Main thread: Listen for results as they come in
	var lastErr error
	failures := 0
	timeout := time.After(t.HandshakeTimeout)

	for failures < len(r.ResolverAddrs) {
		select {
		case res := <-resultCh:
			if res.err == nil {
				// WINNER FOUND!
				cancel() // Instantly kills all other active dialers and stops the spawner
				
				t.mu.Lock()
				t.resolverConn = res.conn
				t.remoteAddr = res.addr
				t.forgedStats = res.forgedStats
				t.mu.Unlock()
				
				log.Infof("VAY_DEBUG: [Multipath] Winner established: %s", res.target)
				return nil
			}
			
			// If it failed, record it. The worker defer func() will release the slot, 
			// triggering the spawner to instantly launch the next IP.
			lastErr = res.err
			failures++
			log.Warnf("VAY_DEBUG: [Multipath] Path failed: %s (%v)", res.target, res.err)

		case <-timeout:
			cancel()
			return fmt.Errorf("multipath connection timeout after %v", t.HandshakeTimeout)
		}
	}

	return fmt.Errorf("all %d multipath resolvers failed. last error: %v", len(r.ResolverAddrs), lastErr)
}

// InitiateDNSPacketConn wraps the resolver connection with DNS encoding.
func (t *Tunnel) InitiateDNSPacketConn(domain dns.Name) error {
	var rateLimiter *RateLimiter
	if t.TunnelServer.RPS > 0 {
		rateLimiter = NewRateLimiter(t.TunnelServer.RPS)
	}
	maxQnameLen := t.TunnelServer.effectiveMaxQnameLen()
	rrType := t.TunnelServer.effectiveRRType()
	t.dnsPacketConn = NewDNSPacketConn(t.resolverConn, t.remoteAddr, domain, rateLimiter, maxQnameLen, t.TunnelServer.MaxNumLabels, t.wireConfig, t.forgedStats, rrType, t.effectivePacketQueueSize(), t.effectiveQueueOverflowMode())
	return nil
}

// InitiateKCPConn opens a KCP connection over the DNS packet connection.
// If mtu is 0, it is auto-computed from the domain and QNAME constraints.
func (t *Tunnel) InitiateKCPConn(mtu int) error {
	if mtu <= 0 {
		maxQnameLen := t.TunnelServer.effectiveMaxQnameLen()
		mtu = DNSNameCapacity(t.TunnelServer.Addr, maxQnameLen, t.TunnelServer.MaxNumLabels) - t.wireConfig.DataOverhead()
	}
	if mtu < 25 {
		return fmt.Errorf("MTU %d is too small (minimum 25); try increasing -max-qname-len (currently %d), increasing -max-num-labels (currently %d), using a shorter domain, or decreasing -clientid-size (currently %d)",
			mtu, t.TunnelServer.effectiveMaxQnameLen(), t.TunnelServer.MaxNumLabels, t.wireConfig.ClientIDSize)
	}
	t.TunnelServer.MTU = mtu
	log.Infof("effective MTU %d", mtu)

	conn, err := kcp.NewConn2(t.remoteAddr, nil, 0, 0, t.dnsPacketConn)
	if err != nil {
		return fmt.Errorf("opening KCP conn: %v", err)
	}
	log.Infof("session %08x ready", conn.GetConv())
	conn.SetStreamMode(true)

	if t.Resolver.ResolverType == ResolverTypeDOT || t.Resolver.ResolverType == ResolverTypeTCP {
		// giving the single TCP stream time to recover from firewall packet loss.
		// nodelay=1, interval=50ms
		// resend=3 (Aggressive retransmit to overcome high latency/loss)
		// nc=1 (Disable congestion window so speed doesn't drop on lag)
		// nc=0 (0 means Congestion Control is ON)		
		conn.SetNoDelay(1, 50, 2, 1)
	} else{
		conn.SetNoDelay(0, 0, 0, 1)
	}
						
	conn.SetWindowSize(t.effectiveKCPWindowSize(), t.effectiveKCPWindowSize())
	if rc := conn.SetMtu(mtu); !rc {
		conn.Close()
		return fmt.Errorf("failed to set KCP MTU to %d", mtu)
	}

	t.kcpConn = conn
	return nil
}

// InitiateNoiseChannel performs the Noise protocol handshake with a timeout.
// The timeout is controlled by HandshakeTimeout (default 30s).
func (t *Tunnel) InitiateNoiseChannel() error {
	t.applyDefaults()
	rw, err := noiseHandshake(t.kcpConn, t.TunnelServer.decodedNoisePubKey, t.HandshakeTimeout)
	if err != nil {
		return err
	}
	t.noiseChannel = rw
	return nil
}

// noiseHandshake performs the Noise handshake on conn with a deadline.
// It sets a deadline before the handshake and clears it after, so the
// deadline does not affect subsequent reads/writes on the connection.
func noiseHandshake(conn *kcp.UDPSession, pubkey []byte, timeout time.Duration) (io.ReadWriteCloser, error) {
	conn.SetDeadline(time.Now().Add(timeout))
	rw, err := noise.NewClient(conn, pubkey)
	conn.SetDeadline(time.Time{}) // clear deadline
	if err != nil {
		return nil, fmt.Errorf("noise handshake: %v", err)
	}
	return rw, nil
}

// InitiateSmuxSession establishes a multiplexed session over the Noise channel.
func (t *Tunnel) InitiateSmuxSession() error {
	t.applyDefaults()

	smuxConfig := smux.DefaultConfig()
	smuxConfig.Version = 2
	smuxConfig.KeepAliveInterval = t.KeepAlive
	smuxConfig.KeepAliveTimeout = t.IdleTimeout
	smuxConfig.MaxStreamBuffer = 1 * 1024 * 1024
	sess, err := smux.Client(t.noiseChannel, smuxConfig)
	if err != nil {
		return fmt.Errorf("opening smux session: %v", err)
	}
	t.smuxSession = sess
	return nil
}

// openStreamWithTimeout opens an smux stream with a timeout. If the open
// succeeds after the timeout, the late stream is closed to avoid leaking
// capacity.
func openStreamWithTimeout(conv uint32, timeout time.Duration, open func() (*smux.Stream, error)) (*smux.Stream, error) {
	type result struct {
		stream *smux.Stream
		err    error
	}
	ch := make(chan result, 1)
	go func() {
		s, err := open()
		ch <- result{s, err}
	}()

	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case r := <-ch:
		if r.err != nil {
			return nil, fmt.Errorf("session %08x opening stream: %v", conv, r.err)
		}
		return r.stream, nil
	case <-timer.C:
		go func() {
			if r, ok := <-ch; ok && r.stream != nil {
				r.stream.Close()
			}
		}()
		return nil, fmt.Errorf("session %08x opening stream: timed out after %v", conv, timeout)
	}
}

// shouldLogCopyError returns true if the error from io.Copy is worth logging.
// Expected close/EOF/timeout errors are filtered out.
func shouldLogCopyError(err error) bool {
	if err == nil || err == io.EOF || errors.Is(err, io.ErrClosedPipe) || errors.Is(err, net.ErrClosed) {
		return false
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return false
	}
	return true
}

// OpenStream opens a new multiplexed stream. Returns a net.Conn.
func (t *Tunnel) OpenStream() (net.Conn, error) {
	if t.smuxSession == nil {
		return nil, fmt.Errorf("smux session is not initialized")
	}

	timeout := t.OpenStreamTimeout
	if timeout <= 0 {
		timeout = DefaultOpenStreamTimeout
	}

	var conv uint32
	if t.kcpConn != nil {
		conv = t.kcpConn.GetConv()
	}

	stream, err := openStreamWithTimeout(conv, timeout, t.smuxSession.OpenStream)
	if err != nil {
		return nil, err
	}
	log.Debugf("stream %08x:%d ready", conv, stream.ID())
	return stream, nil
}

// Handle forwards data between a local TCP connection and a tunnel stream.
func (t *Tunnel) Handle(lconn *net.TCPConn) error {
	stream, err := t.OpenStream()
	if err != nil {
		return err
	}
	defer stream.Close()

	// --- NEW: Wrap the local connection ---
	statsLocal := &StatsConn{Conn: lconn}
	
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		//_, err := io.Copy(stream, lconn)
		_, err := io.Copy(stream, statsLocal)
		if shouldLogCopyError(err) {
			log.Warnf("copy stream←local: %v", err)
		}
		lconn.CloseRead()
		stream.Close()
	}()
	go func() {
		defer wg.Done()
		//_, err := io.Copy(lconn, stream)
		_, err := io.Copy(statsLocal, stream)
		if shouldLogCopyError(err) {
			log.Warnf("copy local←stream: %v", err)
		}
		lconn.CloseWrite()
		lconn.CloseRead()
	}()
	wg.Wait()

	return nil
}

// Close tears down the tunnel and all its layers.
func (t *Tunnel) Close() error {
	if t.smuxSession != nil {
		t.smuxSession.Close()
		t.smuxSession = nil
	}
	if t.noiseChannel != nil {
		t.noiseChannel.Close()
		t.noiseChannel = nil
	}
	if t.kcpConn != nil {
		log.Debugf("session %08x closed", t.kcpConn.GetConv())
		t.kcpConn.Close()
		t.kcpConn = nil
	}
	t.closeTransportLayers()
	return nil
}

// closeTransportLayers tears down the DNS and resolver transport layers.
// Safe to call multiple times.
func (t *Tunnel) closeTransportLayers() {
	if t.dnsPacketConn != nil {
		t.dnsPacketConn.Close()
		t.dnsPacketConn = nil
	}
	if t.resolverConn != nil {
		t.resolverConn.Close()
		t.resolverConn = nil
	}
	t.forgedStats = nil
}

// resetTransportLayers tears down existing transport layers and creates fresh
// ones. Used during reconnect to ensure a clean transport stack.
func (t *Tunnel) resetTransportLayers() error {
	t.closeTransportLayers()
	if err := t.InitiateResolverConnection(); err != nil {
		return fmt.Errorf("resolver connection: %w", err)
	}
	if err := t.InitiateDNSPacketConn(t.TunnelServer.Addr); err != nil {
		t.closeTransportLayers()
		return fmt.Errorf("DNS packet conn: %w", err)
	}
	return nil
}

// ListenAndServe starts a TCP listener and forwards connections through the
// tunnel with automatic session reconnection. This is the main entry point
// for the CLI.
func (t *Tunnel) ListenAndServe(listenAddr string) error {
	t.applyDefaults()

	localAddr, err := net.ResolveTCPAddr("tcp", listenAddr)
	if err != nil {
		return fmt.Errorf("invalid listen address: %v", err)
	}

	maxQnameLen := t.TunnelServer.effectiveMaxQnameLen()
	mtu := DNSNameCapacity(t.TunnelServer.Addr, maxQnameLen, t.TunnelServer.MaxNumLabels) - t.wireConfig.DataOverhead()
	if mtu < 25 {
		return fmt.Errorf("MTU %d is too small (minimum 25); try increasing -max-qname-len (currently %d), increasing -max-num-labels (currently %d), using a shorter domain, or decreasing -clientid-size (currently %d)",
			mtu, maxQnameLen, t.TunnelServer.MaxNumLabels, t.wireConfig.ClientIDSize)
	}
	log.Infof("VAY_DEBUG effective MTU %d", mtu)
    
	ln, err := net.ListenTCP("tcp", localAddr)
	if err != nil {
		return fmt.Errorf("opening local listener: %v", err)
	}
	defer ln.Close()
	defer t.closeTransportLayers()

	var sem chan struct{}
	if t.MaxStreams > 0 {
		sem = make(chan struct{}, t.MaxStreams)
	}

	for {
		// Rebuild the transport stack from the resolver upward.
		var transportErrCh <-chan error
		delay := t.ReconnectMinDelay
		for {
			if err := t.resetTransportLayers(); err != nil {
				log.Warnf("transport rebuild failed: %v; retrying in %v", err, delay)
				time.Sleep(delay)
				delay *= 2
				if delay > t.ReconnectMaxDelay {
					delay = t.ReconnectMaxDelay
				}
				continue
			}
			transportErrCh = t.dnsPacketConn.TransportErrors()
			break
		}

		// Create a new tunnel session with exponential backoff.
		var conn *kcp.UDPSession
		var sess *smux.Session
		delay = t.ReconnectMinDelay
		for {
			conn, sess, err = t.createSession(mtu)
			if err == nil {
				break
			}
			log.Warnf("session creation failed: %v; retrying in %v", err, delay)
			time.Sleep(delay)
			delay *= 2
			if delay > t.ReconnectMaxDelay {
				delay = t.ReconnectMaxDelay
			}
		}

		sessDone := sess.CloseChan()
		conv := conn.GetConv()

		sessionAlive := true
		for sessionAlive {
			ln.SetDeadline(time.Now().Add(t.SessionCheckInterval))
			local, err := ln.Accept()
			if err != nil {
				if ne, ok := err.(net.Error); ok && ne.Timeout() {
					select {
					case <-sessDone:
						sessionAlive = false
					case tErr := <-transportErrCh:
						log.Warnf("session %08x transport error: %v", conv, tErr)
						sessionAlive = false
					default:
					}
					continue
				}
				sess.Close()
				conn.Close()
				t.closeTransportLayers()
				return err
			}

			select {
			case <-sessDone:
				local.Close()
				sessionAlive = false
				continue
			case tErr := <-transportErrCh:
				log.Warnf("session %08x transport error: %v", conv, tErr)
				local.Close()
				sessionAlive = false
				continue
			default:
			}

			go func(sess *smux.Session, conv uint32) {
				if sem != nil {
					sem <- struct{}{}
					defer func() { <-sem }()
				}
				defer local.Close()
				err := t.handleConn(local.(*net.TCPConn), sess, conv)
				if err != nil {
					log.Warnf("handle: %v", err)
				}
			}(sess, conv)
		}

		log.Warnf("session %08x closed, reconnecting", conv)
		sess.Close()
		conn.Close()
		t.closeTransportLayers()
	}
}

// createSession creates a KCP+Noise+smux session (used by ListenAndServe).
func (t *Tunnel) createSession(mtu int) (*kcp.UDPSession, *smux.Session, error) {
	// Initialize the KCP connection over our DNS packet transport
	conn, err := kcp.NewConn2(t.remoteAddr, nil, 0, 0, t.dnsPacketConn)
	if err != nil {
		return nil, nil, fmt.Errorf("opening KCP conn: %v", err)
	}
	log.Infof("session %08x ready", conn.GetConv())
	
	// Enable Stream Mode for consistent data flow
	conn.SetStreamMode(true)

	if t.Resolver.ResolverType == ResolverTypeDOT || t.Resolver.ResolverType == ResolverTypeTCP {

		// giving the single TCP stream time to recover from firewall packet loss.
		// nodelay=1, interval=50ms
		// resend=3 (Aggressive retransmit to overcome high latency/loss)
		// nc=1 (Disable congestion window so speed doesn't drop on lag)
		// nc=0 (0 means Congestion Control is ON)
		conn.SetNoDelay(1, 50, 2, 1)
	} else{
		conn.SetNoDelay(0, 0, 0, 1)
	}
		
	// Calculate and set the window size based on our expanded queue
	// This will now correctly log 4096 (8192 / 2) for TCP modes
	ws := t.effectiveKCPWindowSize()
//	fmt.Printf("VAY_DEBUG: Setting KCP Window Size to %d\n", ws)
	conn.SetWindowSize(ws, ws)

	// Apply the MTU (usually 50-100 for DNS tunneling)
	if rc := conn.SetMtu(mtu); !rc {
		conn.Close()
		return nil, nil, fmt.Errorf("failed to set KCP MTU to %d", mtu)
	}

	// Perform the Noise Handshake for end-to-end encryption
	rw, err := noiseHandshake(conn, t.TunnelServer.decodedNoisePubKey, t.HandshakeTimeout)
	if err != nil {
		conn.Close()
		return nil, nil, err
	}

	// Setup Smux Multiplexing over the encrypted channel
	smuxConfig := smux.DefaultConfig()
	smuxConfig.Version = 2
	smuxConfig.KeepAliveInterval = t.KeepAlive
	smuxConfig.KeepAliveTimeout = t.IdleTimeout
	smuxConfig.MaxStreamBuffer = 1 * 1024 * 1024 // 1MB buffer for streams
	
	sess, err := smux.Client(rw, smuxConfig)
	if err != nil {
		conn.Close()
		return nil, nil, fmt.Errorf("opening smux session: %v", err)
	}

	return conn, sess, nil
}

// handleConn forwards a single TCP connection through the tunnel session.
func (t *Tunnel) handleConn(local *net.TCPConn, sess *smux.Session, conv uint32) error {
	stream, err := openStreamWithTimeout(conv, t.OpenStreamTimeout, sess.OpenStream)
	if err != nil {
		return err
	}

	defer func() {
		log.Debugf("stream %08x:%d closed", conv, stream.ID())
		stream.Close()
	}()
	log.Infof("stream %08x:%d ready", conv, stream.ID())

	// --- NEW: Wrap the local connection ---
	statsLocal := &StatsConn{Conn: local}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
//		_, err := io.Copy(stream, local)
		_, err := io.Copy(stream, statsLocal)
		if shouldLogCopyError(err) {
			log.Warnf("stream %08x:%d copy stream←local: %v", conv, stream.ID(), err)
		}
		local.CloseRead()
		stream.Close()
	}()
	go func() {
		defer wg.Done()
		//_, err := io.Copy(local, stream)
		_, err := io.Copy(statsLocal, stream)
		if shouldLogCopyError(err) {
			log.Warnf("stream %08x:%d copy local←stream: %v", conv, stream.ID(), err)
		}
		local.CloseWrite()
		local.CloseRead()
	}()
	wg.Wait()

	return nil
}

// DNSNameCapacity returns the number of raw bytes that can be encoded in a DNS
// query name, given the domain suffix and encoding constraints.
func DNSNameCapacity(domain dns.Name, maxQnameLen int, maxNumLabels int) int {
	const labelLen = 63

	if maxQnameLen <= 0 || maxQnameLen > 253 {
		maxQnameLen = 253
	}

	domainWireLen := 0
	for _, label := range domain {
		domainWireLen += 1 + len(label)
	}

	availableWireBytes := maxQnameLen - domainWireLen
	if availableWireBytes <= 0 {
		return 0
	}

	encodedCapacity := availableWireBytes * labelLen / (labelLen + 1)

	if maxNumLabels > 0 {
		maxEncoded := maxNumLabels * labelLen
		if encodedCapacity > maxEncoded {
			encodedCapacity = maxEncoded
		}
	}

	rawCapacity := encodedCapacity * 5 / 8
	return rawCapacity
}

// SampleUTLSDistribution parses a weighted distribution string (e.g.,
// "3*Firefox,2*Chrome,1*iOS") and randomly selects a ClientHelloID.
func SampleUTLSDistribution(spec string) (*utls.ClientHelloID, error) {
	weights, labels, err := parseWeightedList(spec)
	if err != nil {
		return nil, err
	}
	ids := make([]*utls.ClientHelloID, 0, len(labels))
	for _, label := range labels {
		var id *utls.ClientHelloID
		if label == "none" {
			id = nil
		} else {
			id = UTLSLookup(label)
			if id == nil {
				return nil, fmt.Errorf("unknown TLS fingerprint %q", label)
			}
		}
		ids = append(ids, id)
	}
	return ids[sampleWeighted(weights)], nil
}

// UTLSClientHelloIDMap returns the list of supported uTLS fingerprint labels.
func UTLSClientHelloIDMap() []struct {
	Label string
	ID    *utls.ClientHelloID
} {
	return utlsClientHelloIDMap
}

// Outbound provides a high-level API for creating tunnels from multiple
// resolvers and tunnel servers.
type Outbound struct {
	Resolvers     []Resolver
	TunnelServers []TunnelServer
	tunnels       []*Tunnel
}

// NewOutbound creates an Outbound with the given resolvers and tunnel servers.
func NewOutbound(resolvers []Resolver, tunnelServers []TunnelServer) *Outbound {
	return &Outbound{
		Resolvers:     resolvers,
		TunnelServers: tunnelServers,
	}
}

// Start begins accepting connections on bind and forwarding them through the
// first resolver/server pair.
func (o *Outbound) Start(bind string) error {
	resolver := o.Resolvers[0]
	tunnelServer := o.TunnelServers[0]

	tunnel, err := NewTunnel(resolver, tunnelServer)
	if err != nil {
		return fmt.Errorf("failed to create tunnel: %w", err)
	}
	o.tunnels = []*Tunnel{tunnel}

	return tunnel.ListenAndServe(bind)
}
