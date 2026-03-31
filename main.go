package main

import (
	"encoding/hex"
	"flag"
	"fmt"
	"os"
	"strings"

	log "github.com/sirupsen/logrus"
	"github.com/net2share/vaydns/bridge"
	"github.com/net2share/vaydns/client"
	"github.com/net2share/vaydns/noise"
)

func main() {
	var c bridge.TunnelConfig
	var pubkeyFile string

	// Flag definitions (Keeping the original defaults)
	flag.StringVar(&c.DohURL, "doh", "", "URL of DoH resolver")
	flag.StringVar(&c.DotAddr, "dot", "", "address of DoT resolver")
	flag.StringVar(&c.UdpAddr, "udp", "", "address of UDP DNS resolver")
	flag.StringVar(&c.Domain, "domain", "", "tunnel domain")
	flag.StringVar(&c.ListenAddr, "listen", "", "local TCP address")
	flag.StringVar(&c.PubkeyHex, "pubkey", "", "server public key (hex)")
	flag.StringVar(&pubkeyFile, "pubkey-file", "", "read pubkey from file")
	flag.StringVar(&c.UtlsDistribution, "utls", "4*random,3*Firefox_120,3*Chrome_120", "uTLS fingerprints")
	flag.StringVar(&c.RecordType, "record-type", "txt", "DNS record type")
	flag.StringVar(&c.LogLevel, "log-level", "info", "log level")
	flag.IntVar(&c.MaxQnameLen, "max-qname-len", 101, "max QNAME length")
	flag.Float64Var(&c.RpsLimit, "rps", 0, "queries per second limit")
	flag.IntVar(&c.MaxStreams, "max-streams", 64, "max concurrent streams")
	flag.BoolVar(&c.CompatDnstt, "dnstt-compat", false, "dnstt compatibility")
	
	// Durations as strings for the bridge to parse
	flag.StringVar(&c.IdleTimeout, "idle-timeout", "1m", "idle timeout")
	flag.StringVar(&c.KeepAlive, "keepalive", "5s", "keepalive interval")
	flag.StringVar(&c.ReconnectMin, "reconnect-min", "500ms", "min reconnect delay")
	flag.StringVar(&c.ReconnectMax, "reconnect-max", "10s", "max reconnect delay")
	flag.StringVar(&c.SessionCheck, "session-check-interval", "100ms", "session check interval")
	flag.StringVar(&c.OpenStreamTimeout, "open-stream-timeout", "2s", "open stream timeout")
	flag.StringVar(&c.UDPTimeout, "udp-timeout", "2s", "UDP timeout")

	// Custom Usage (Restore the uTLS list printing from original)
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "Usage: %s [options]\n\nKnown uTLS fingerprints:\n", os.Args[0])
		for _, entry := range client.UTLSClientHelloIDMap() {
			fmt.Fprintf(os.Stderr, "  %s\n", entry.Label)
		}
		flag.PrintDefaults()
	}

	flag.Parse()

	// Handle Pubkey File Logic
	if pubkeyFile != "" {
		f, err := os.Open(pubkeyFile)
		if err != nil {
			log.Fatalf("Pubkey file error: %v", err)
		}
		key, _ := noise.ReadKey(f)
		c.PubkeyHex = hex.EncodeToString(key)
		f.Close()
	}

	if c.Domain == "" || c.ListenAddr == "" || c.PubkeyHex == "" {
		flag.Usage()
		os.Exit(1)
	}

	if err := bridge.RunTunnel(c); err != nil {
		log.Fatalf("Fatal: %v", err)
	}
}