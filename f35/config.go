package f35

import (
	"bytes"
	"fmt"
	"strings"
	"time"
)

type Config struct {
	Engine          string
	ClientPath      string
	Domain          string
	Pubkey          string // Essential for bridge.RunTunnel in run.go
	Resolvers       []string
	ProbeURL        string
	DownloadURL     string
	UploadURL       string
	Probe           bool
	Download        bool
	Upload          bool
	Whois           bool
	Proxy           string
	ProxyUser       string
	ProxyPass       string
	ExtraArgs       []string
	Workers         int
	Retries         int
	TunnelWait      time.Duration
	ProbeTimeout    time.Duration
	DownloadTimeout time.Duration
	UploadTimeout   time.Duration
	UploadBytes     int
	WhoisTimeout    time.Duration
	StartPort       int
}

type Result struct {
	Resolver  string `json:"resolver"`
	LatencyMS int64  `json:"latency_ms"`
	Download  string `json:"download"`
	Upload    string `json:"upload"`
	Whois     string `json:"whois"`
	Probe     string `json:"probe"`
	Org       string `json:"org,omitempty"`
	Country   string `json:"country,omitempty"`
}

type Progress struct {
	Total     int
	Processed int
	Healthy   int
	Failed    int
}

type Hooks struct {
	OnResult   func(Result)
	OnProgress func(Progress)
}

type runtimeConfig struct {
	Config
	uploadPayload   []byte
	parsedResolvers []parsedResolver
}

func DefaultConfig() Config {
	return Config{
		Engine:          "vaydns",
		ProbeURL:        "https://www.google.com/gen_204",
		DownloadURL:     "https://speed.cloudflare.com/__down?bytes=100000",
		UploadURL:       "https://speed.cloudflare.com/__up",
		Probe:           true,
		Proxy:           "socks5h",
		Workers:         20,
		Retries:         0,
		TunnelWait:      3 * time.Second,
		ProbeTimeout:    15 * time.Second,
		DownloadTimeout: 15 * time.Second,
		UploadTimeout:   15 * time.Second,
		UploadBytes:     50000,
		WhoisTimeout:    10 * time.Second,
		StartPort:       40000,
	}
}

func ValidateConfig(cfg Config) error {
	_, _, _, err := normalizeAndValidateConfig(cfg)
	return err
}

func prepareConfig(cfg Config) (runtimeConfig, error) {
	cfg, _, resolvers, err := normalizeAndValidateConfig(cfg)
	if err != nil {
		return runtimeConfig{}, err
	}

	runtime := runtimeConfig{
		Config:          cfg,
		parsedResolvers: resolvers,		
	}
	if cfg.Upload {
		runtime.uploadPayload = bytes.Repeat([]byte("0"), cfg.UploadBytes)
	}
	return runtime, nil
}

func normalizeAndValidateConfig(cfg Config) (Config, EngineSpec, []parsedResolver, error) {
	defaults := DefaultConfig()

	if strings.TrimSpace(cfg.Engine) == "" {
		cfg.Engine = defaults.Engine
	}
	if strings.TrimSpace(cfg.ProbeURL) == "" {
		cfg.ProbeURL = defaults.ProbeURL
	}
	if strings.TrimSpace(cfg.DownloadURL) == "" {
		cfg.DownloadURL = defaults.DownloadURL
	}
	if strings.TrimSpace(cfg.UploadURL) == "" {
		cfg.UploadURL = defaults.UploadURL
	}
	if strings.TrimSpace(cfg.Proxy) == "" {
		cfg.Proxy = defaults.Proxy
	}
	if cfg.Workers == 0 {
		cfg.Workers = defaults.Workers
	}
	if cfg.ProbeTimeout == 0 {
		cfg.ProbeTimeout = defaults.ProbeTimeout
	}
	if cfg.DownloadTimeout == 0 {
		cfg.DownloadTimeout = defaults.DownloadTimeout
	}
	if cfg.UploadTimeout == 0 {
		cfg.UploadTimeout = defaults.UploadTimeout
	}
	if cfg.UploadBytes == 0 {
		cfg.UploadBytes = defaults.UploadBytes
	}
	if cfg.WhoisTimeout == 0 {
		cfg.WhoisTimeout = defaults.WhoisTimeout
	}
	if cfg.StartPort == 0 {
		cfg.StartPort = defaults.StartPort
	}

	cfg.Engine = strings.ToLower(strings.TrimSpace(cfg.Engine))
	cfg.Proxy = strings.ToLower(strings.TrimSpace(cfg.Proxy))
	cfg.Domain = strings.TrimSpace(cfg.Domain)
	resolvers := parseResolvers(cfg.Resolvers)
	cfg.Resolvers = resolverAddrs(resolvers)

	if cfg.Domain == "" || len(cfg.Resolvers) == 0 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("domain and at least one resolver are required")
	}

	if cfg.Engine == "vaydns" && cfg.Pubkey == "" {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("pubkey is required for vaydns engine")
	}

	spec, ok := engineSpecs[cfg.Engine]
	if !ok {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("unsupported engine: %s", cfg.Engine)
	}

	switch cfg.Proxy {
	case "http", "https", "socks5", "socks5h":
	default:
		return Config{}, EngineSpec{}, nil, fmt.Errorf("proxy must be one of: http, https, socks5, socks5h")
	}

	if cfg.ProxyPass != "" && cfg.ProxyUser == "" {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("proxy password requires proxy user")
	}
	if !cfg.Probe && !cfg.Download && !cfg.Upload && !cfg.Whois {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("at least one of probe, download, upload, or whois must be enabled")
	}

	if cfg.Workers < 1 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("workers must be >= 1")
	}
	if cfg.Retries < 0 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("retries must be >= 0")
	}
	if cfg.ProbeTimeout <= 0 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("probe timeout must be > 0")
	}
	if cfg.DownloadTimeout <= 0 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("download timeout must be > 0")
	}
	if cfg.UploadTimeout <= 0 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("upload timeout must be > 0")
	}
	if cfg.UploadBytes < 1 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("upload bytes must be >= 1")
	}
	if cfg.WhoisTimeout <= 0 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("whois timeout must be > 0")
	}
	if cfg.TunnelWait < 0 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("tunnel wait must be >= 0")
	}
	if cfg.StartPort < 1 || cfg.StartPort > 65535 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("start port must be between 1 and 65535")
	}
	if cfg.StartPort+cfg.Workers-1 > 65535 {
		return Config{}, EngineSpec{}, nil, fmt.Errorf("port range overflow (start port + workers exceeds 65535)")
	}

	return cfg, spec, resolvers, nil
}
