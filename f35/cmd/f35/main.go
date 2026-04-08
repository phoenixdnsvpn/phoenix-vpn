package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	f35 "github.com/nxdp/f35"
	pflag "github.com/spf13/pflag"
)

const defaultProgressUpdateInterval = time.Second

type cliOptions struct {
	resolversFile string
	args          string
	json          bool
	quiet         bool
	short         bool
	colorize      bool
	logColorize   bool
}

type statusUI struct {
	mu           sync.Mutex
	colorize     bool
	liveProgress bool
	progress     f35.Progress
	progressSeen bool
	startedAt    time.Time
}

func main() {
	if err := run(); err != nil {
		logf(fileIsTerminal(os.Stderr), "ERR", "%v", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, opts, err := parseFlags()
	if err != nil {
		if err == pflag.ErrHelp {
			printUsage()
			return nil
		}
		printUsage()
		return err
	}

	resolvers, err := f35.LoadResolvers(opts.resolversFile)
	if err != nil {
		return err
	}
	cfg.Resolvers = resolvers

	if err := f35.ValidateConfig(cfg); err != nil {
		printUsage()
		return err
	}

	startedAt := time.Now()
	ui := newStatusUI(opts, startedAt, len(cfg.Resolvers))
	ui.UpdateProgress(f35.Progress{Total: len(cfg.Resolvers)})

	if !opts.quiet {
		ui.Log("INFO", "starting | resolvers=%d | workers=%d | engine=%s", len(cfg.Resolvers), cfg.Workers, cfg.Engine)
	}

	var stopProgress func()
	if !opts.quiet && ui.liveProgress {
		stopProgress = startProgressReporter(ui)
	}

	err = f35.Scan(cfg, f35.Hooks{
		OnProgress: func(progress f35.Progress) {
			if !opts.quiet {
				ui.UpdateProgress(progress)
			}
		},
		OnResult: func(result f35.Result) {
			ui.PrintResult(result, opts)
		},
	})
	if stopProgress != nil {
		stopProgress()
	}
	if err != nil {
		return err
	}

	if !opts.quiet {
		progress := ui.Progress()
		level := "INFO"
		if progress.Healthy == 0 {
			level = "WARN"
		}
		ui.Log(level, "completed | %d/%d | healthy=%d | failed=%d | elapsed=%s", progress.Processed, progress.Total, progress.Healthy, progress.Failed, formatElapsed(time.Since(startedAt)))
	}

	return nil
}

func newStatusUI(opts cliOptions, startedAt time.Time, total int) *statusUI {
	return &statusUI{
		colorize:     opts.logColorize,
		liveProgress: opts.logColorize && !opts.quiet,
		progress: f35.Progress{
			Total: total,
		},
		startedAt: startedAt,
	}
}

func startProgressReporter(ui *statusUI) func() {
	stop := make(chan struct{})
	done := make(chan struct{})

	go func() {
		defer close(done)

		ui.RenderProgress()
		ticker := time.NewTicker(defaultProgressUpdateInterval)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				ui.RenderProgress()
			case <-stop:
				return
			}
		}
	}()

	return func() {
		close(stop)
		<-done
		ui.StopLiveProgress()
	}
}

func (ui *statusUI) UpdateProgress(progress f35.Progress) {
	ui.mu.Lock()
	defer ui.mu.Unlock()
	ui.progress = progress
}

func (ui *statusUI) Progress() f35.Progress {
	ui.mu.Lock()
	defer ui.mu.Unlock()
	return ui.progress
}

func (ui *statusUI) Log(level string, format string, args ...any) {
	ui.mu.Lock()
	defer ui.mu.Unlock()

	ui.clearProgressLocked()
	logf(ui.colorize, level, format, args...)
	ui.renderProgressLocked()
}

func (ui *statusUI) PrintResult(result f35.Result, opts cliOptions) {
	ui.mu.Lock()
	defer ui.mu.Unlock()

	ui.clearProgressLocked()
	switch {
	case opts.json:
		_ = json.NewEncoder(os.Stdout).Encode(result)
	case opts.short:
		fmt.Println(formatShortResult(result, opts.colorize))
	default:
		fmt.Println(formatPlainTextResult(result, opts.colorize))
	}
	ui.renderProgressLocked()
}

func (ui *statusUI) RenderProgress() {
	ui.mu.Lock()
	defer ui.mu.Unlock()
	ui.renderProgressLocked()
}

func (ui *statusUI) ClearProgress() {
	ui.mu.Lock()
	defer ui.mu.Unlock()
	ui.clearProgressLocked()
}

func (ui *statusUI) StopLiveProgress() {
	ui.mu.Lock()
	defer ui.mu.Unlock()
	ui.clearProgressLocked()
	ui.liveProgress = false
}

func (ui *statusUI) clearProgressLocked() {
	if !ui.liveProgress || !ui.progressSeen {
		return
	}
	_, _ = io.WriteString(os.Stderr, "\r\033[K")
	ui.progressSeen = false
}

func (ui *statusUI) renderProgressLocked() {
	if !ui.liveProgress {
		return
	}
	_, _ = fmt.Fprintf(
		os.Stderr,
		"\r\033[K%s %d/%d | healthy=%d | failed=%d | elapsed=%s",
		formatLogLevel("INFO", ui.colorize),
		ui.progress.Processed,
		ui.progress.Total,
		ui.progress.Healthy,
		ui.progress.Failed,
		formatElapsed(time.Since(ui.startedAt)),
	)
	ui.progressSeen = true
}

func formatPlainTextResult(result f35.Result, colorize bool) string {
	line := fmt.Sprintf("%s %s", result.Resolver, formatLatency(result.LatencyMS, colorize))
	parts := []string{line}
	parts = append(parts, "download="+strconvQuote(result.Download))
	parts = append(parts, "upload="+strconvQuote(result.Upload))
	parts = append(parts, "whois="+strconvQuote(result.Whois))
	parts = append(parts, "probe="+strconvQuote(result.Probe))
	if result.Whois != "off" {
		parts = append(parts, "org="+strconvQuote(result.Org))
		parts = append(parts, "country="+strconvQuote(result.Country))
	}
	return strings.Join(parts, " ")
}

func formatShortResult(result f35.Result, colorize bool) string {
	return fmt.Sprintf("%s %s", result.Resolver, formatLatency(result.LatencyMS, colorize))
}

func formatLatency(latencyMs int64, colorize bool) string {
	latency := fmt.Sprintf("%dms", latencyMs)
	if !colorize {
		return latency
	}
	switch {
	case latencyMs <= 2000:
		return "\033[32m" + latency + "\033[0m"
	case latencyMs <= 6000:
		return "\033[33m" + latency + "\033[0m"
	default:
		return "\033[31m" + latency + "\033[0m"
	}
}

func fileIsTerminal(file *os.File) bool {
	info, err := file.Stat()
	if err != nil {
		return false
	}
	return (info.Mode() & os.ModeCharDevice) != 0
}

func formatElapsed(duration time.Duration) string {
	if duration < time.Second {
		return "0s"
	}
	return duration.Truncate(time.Second).String()
}

func logf(colorize bool, level string, format string, args ...any) {
	fmt.Fprintf(os.Stderr, "%s %s\n", formatLogLevel(level, colorize), fmt.Sprintf(format, args...))
}

func formatLogLevel(level string, colorize bool) string {
	tag := "[" + level + "]"
	if !colorize {
		return tag
	}
	switch level {
	case "INFO":
		return "\033[32m" + tag + "\033[0m"
	case "WARN":
		return "\033[33m" + tag + "\033[0m"
	case "ERR":
		return "\033[31m" + tag + "\033[0m"
	default:
		return tag
	}
}

func strconvQuote(value string) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return `""`
	}
	return string(encoded)
}

func splitCommandLine(input string) ([]string, error) {
	var args []string
	var current strings.Builder
	var quote rune
	escaped := false

	flush := func() {
		if current.Len() > 0 {
			args = append(args, current.String())
			current.Reset()
		}
	}

	for _, r := range input {
		switch {
		case escaped:
			current.WriteRune(r)
			escaped = false
		case r == '\\':
			escaped = true
		case quote != 0:
			if r == quote {
				quote = 0
			} else {
				current.WriteRune(r)
			}
		case r == '\'' || r == '"':
			quote = r
		case r == ' ' || r == '\t' || r == '\n':
			flush()
		default:
			current.WriteRune(r)
		}
	}

	if escaped {
		return nil, errors.New("unfinished escape sequence")
	}
	if quote != 0 {
		return nil, errors.New("unterminated quote")
	}

	flush()
	return args, nil
}
