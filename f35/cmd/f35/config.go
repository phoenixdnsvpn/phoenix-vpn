package main

import (
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	f35 "github.com/nxdp/f35"
	pflag "github.com/spf13/pflag"
	"github.com/spf13/viper"
)

type configBinding struct {
	key  string
	flag string
}

var configBindings = []configBinding{
	{key: "resolvers_file", flag: "resolvers"},
	{key: "engine", flag: "engine"},
	{key: "client_path", flag: "client-path"},
	{key: "domain", flag: "domain"},
	{key: "args", flag: "args"},
	{key: "json", flag: "json"},
	{key: "quiet", flag: "quiet"},
	{key: "short", flag: "short"},
	{key: "probe_url", flag: "probe-url"},
	{key: "probe", flag: "probe"},
	{key: "download", flag: "download"},
	{key: "download_url", flag: "download-url"},
	{key: "upload", flag: "upload"},
	{key: "upload_url", flag: "upload-url"},
	{key: "upload_bytes", flag: "upload-bytes"},
	{key: "whois", flag: "whois"},
	{key: "proxy", flag: "proxy"},
	{key: "proxy_user", flag: "proxy-user"},
	{key: "proxy_pass", flag: "proxy-pass"},
	{key: "workers", flag: "workers"},
	{key: "retries", flag: "retries"},
	{key: "wait", flag: "wait"},
	{key: "probe_timeout", flag: "probe-timeout"},
	{key: "download_timeout", flag: "download-timeout"},
	{key: "upload_timeout", flag: "upload-timeout"},
	{key: "whois_timeout", flag: "whois-timeout"},
	{key: "start_port", flag: "start-port"},
}

var allowedConfigKeys = func() map[string]struct{} {
	keys := make(map[string]struct{}, len(configBindings))
	for _, binding := range configBindings {
		keys[binding.key] = struct{}{}
	}
	return keys
}()

func parseFlags() (f35.Config, cliOptions, error) {
	defaults := f35.DefaultConfig()
	fs := newFlagSet(defaults)
	if err := fs.Parse(os.Args[1:]); err != nil {
		return f35.Config{}, cliOptions{}, err
	}

	v, err := newConfig(fs, defaults)
	if err != nil {
		return f35.Config{}, cliOptions{}, err
	}

	cfg, opts, err := buildConfig(v)
	if err != nil {
		return f35.Config{}, cliOptions{}, err
	}

	opts.colorize = !opts.json && fileIsTerminal(os.Stdout)
	opts.logColorize = fileIsTerminal(os.Stderr)
	return cfg, opts, nil
}

func newConfig(fs *pflag.FlagSet, defaults f35.Config) (*viper.Viper, error) {
	v := viper.New()
	setConfigDefaults(v, defaults)

	configPath, err := fs.GetString("config")
	if err != nil {
		return nil, fmt.Errorf("read --config: %w", err)
	}
	configPath = strings.TrimSpace(configPath)
	if configPath != "" {
		v.SetConfigFile(configPath)
		v.SetConfigType("toml")
		if err := v.ReadInConfig(); err != nil {
			return nil, fmt.Errorf("read config file: %w", err)
		}
		if err := validateConfigKeys(v); err != nil {
			return nil, err
		}
	}

	for _, binding := range configBindings {
		if err := v.BindPFlag(binding.key, fs.Lookup(binding.flag)); err != nil {
			return nil, fmt.Errorf("bind flag %s: %w", binding.flag, err)
		}
	}

	return v, nil
}

func validateConfigKeys(v *viper.Viper) error {
	for _, key := range v.AllKeys() {
		if _, ok := allowedConfigKeys[strings.ToLower(key)]; !ok {
			return fmt.Errorf("unknown config key: %s", key)
		}
	}
	return nil
}

func setConfigDefaults(v *viper.Viper, defaults f35.Config) {
	v.SetDefault("resolvers_file", "")
	v.SetDefault("engine", defaults.Engine)
	v.SetDefault("client_path", defaults.ClientPath)
	v.SetDefault("domain", defaults.Domain)
	v.SetDefault("args", "")
	v.SetDefault("json", false)
	v.SetDefault("quiet", false)
	v.SetDefault("short", false)
	v.SetDefault("probe_url", defaults.ProbeURL)
	v.SetDefault("probe", defaults.Probe)
	v.SetDefault("download", defaults.Download)
	v.SetDefault("download_url", defaults.DownloadURL)
	v.SetDefault("upload", defaults.Upload)
	v.SetDefault("upload_url", defaults.UploadURL)
	v.SetDefault("upload_bytes", defaults.UploadBytes)
	v.SetDefault("whois", defaults.Whois)
	v.SetDefault("proxy", defaults.Proxy)
	v.SetDefault("proxy_user", defaults.ProxyUser)
	v.SetDefault("proxy_pass", defaults.ProxyPass)
	v.SetDefault("workers", defaults.Workers)
	v.SetDefault("retries", defaults.Retries)
	v.SetDefault("wait", int(defaults.TunnelWait/time.Millisecond))
	v.SetDefault("probe_timeout", int(defaults.ProbeTimeout/time.Second))
	v.SetDefault("download_timeout", int(defaults.DownloadTimeout/time.Second))
	v.SetDefault("upload_timeout", int(defaults.UploadTimeout/time.Second))
	v.SetDefault("whois_timeout", int(defaults.WhoisTimeout/time.Second))
	v.SetDefault("start_port", defaults.StartPort)
}

func buildConfig(v *viper.Viper) (f35.Config, cliOptions, error) {
	cfg := f35.Config{
		Engine:          v.GetString("engine"),
		ClientPath:      v.GetString("client_path"),
		Domain:          v.GetString("domain"),
		ProbeURL:        v.GetString("probe_url"),
		DownloadURL:     v.GetString("download_url"),
		UploadURL:       v.GetString("upload_url"),
		Probe:           v.GetBool("probe"),
		Download:        v.GetBool("download"),
		Upload:          v.GetBool("upload"),
		Whois:           v.GetBool("whois"),
		Proxy:           v.GetString("proxy"),
		ProxyUser:       v.GetString("proxy_user"),
		ProxyPass:       v.GetString("proxy_pass"),
		Workers:         v.GetInt("workers"),
		Retries:         v.GetInt("retries"),
		TunnelWait:      time.Duration(v.GetInt("wait")) * time.Millisecond,
		ProbeTimeout:    time.Duration(v.GetInt("probe_timeout")) * time.Second,
		DownloadTimeout: time.Duration(v.GetInt("download_timeout")) * time.Second,
		UploadTimeout:   time.Duration(v.GetInt("upload_timeout")) * time.Second,
		UploadBytes:     v.GetInt("upload_bytes"),
		WhoisTimeout:    time.Duration(v.GetInt("whois_timeout")) * time.Second,
		StartPort:       v.GetInt("start_port"),
	}

	opts := cliOptions{
		resolversFile: strings.TrimSpace(v.GetString("resolvers_file")),
		args:          v.GetString("args"),
		json:          v.GetBool("json"),
		quiet:         v.GetBool("quiet"),
		short:         v.GetBool("short"),
	}

	if opts.resolversFile == "" || strings.TrimSpace(cfg.Domain) == "" {
		return f35.Config{}, cliOptions{}, fmt.Errorf("--resolvers and --domain are required")
	}

	if opts.args != "" {
		extraArgs, err := splitCommandLine(opts.args)
		if err != nil {
			return f35.Config{}, cliOptions{}, fmt.Errorf("invalid --args: %w", err)
		}
		cfg.ExtraArgs = extraArgs
	}

	return cfg, opts, nil
}

func newFlagSet(defaults f35.Config) *pflag.FlagSet {
	fs := pflag.NewFlagSet(os.Args[0], pflag.ContinueOnError)
	fs.SetOutput(io.Discard)

	fs.String("resolvers", "", "Path to file containing resolvers (IP or IP:PORT per line)")
	fs.String("engine", defaults.Engine, fmt.Sprintf("Tunnel engine to use: %s", strings.Join(f35.SupportedEngines(), "|")))
	fs.String("client-path", defaults.ClientPath, "Explicit path to client binary (optional)")
	fs.StringP("config", "c", "", "Path to TOML config file")
	fs.String("domain", defaults.Domain, "Tunnel domain (e.g., ns.example.com)")
	fs.String("args", "", "Extra engine CLI args; supports placeholders like {resolver}, {domain}, {listen}")
	fs.Bool("json", false, "Print one JSON object per result line")
	fs.Bool("quiet", false, "Suppress startup, progress, and completion logs")
	fs.Bool("short", false, "Print only IP:PORT and latency in plain text output")
	fs.String("probe-url", defaults.ProbeURL, "HTTP URL used for the probe request through the tunnel")
	fs.Bool("probe", defaults.Probe, "Run a quick connectivity probe through the tunnel")
	fs.Bool("download", defaults.Download, "Run a real download test through the tunnel")
	fs.String("download-url", defaults.DownloadURL, "HTTP URL used for the download test")
	fs.Bool("upload", defaults.Upload, "Run a real upload test through the tunnel")
	fs.String("upload-url", defaults.UploadURL, "HTTP URL used for the upload test")
	fs.Int("upload-bytes", defaults.UploadBytes, "Number of bytes to send for the upload test")
	fs.Bool("whois", defaults.Whois, "Lookup resolver owner info and print organization and country")
	fs.String("proxy", defaults.Proxy, "Protocol to use when sending request through the tunnel: http|https|socks5|socks5h")
	fs.String("proxy-user", defaults.ProxyUser, "Proxy username (if the tunnel exit requires auth)")
	fs.String("proxy-pass", defaults.ProxyPass, "Proxy password (if the tunnel exit requires auth)")
	fs.Int("workers", defaults.Workers, "Number of concurrent scanning workers")
	fs.Int("retries", defaults.Retries, "Number of retries per resolver after the first failure")
	fs.Int("wait", int(defaults.TunnelWait/time.Millisecond), "Time to wait (ms) for tunnel establishment before testing HTTP")
	fs.Int("probe-timeout", int(defaults.ProbeTimeout/time.Second), "Probe request timeout in seconds")
	fs.Int("download-timeout", int(defaults.DownloadTimeout/time.Second), "Download request timeout in seconds")
	fs.Int("upload-timeout", int(defaults.UploadTimeout/time.Second), "Upload request timeout in seconds")
	fs.Int("whois-timeout", int(defaults.WhoisTimeout/time.Second), "WHOIS lookup timeout in seconds")
	fs.Int("start-port", defaults.StartPort, "Starting local port for tunnel listeners")

	return fs
}

func printUsage() {
	fs := newFlagSet(f35.DefaultConfig())
	fs.SetOutput(os.Stderr)
	_, _ = fmt.Fprintf(os.Stderr, "Usage of %s:\n", os.Args[0])
	fs.PrintDefaults()
}
