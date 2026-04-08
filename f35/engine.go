package f35

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
)

type EngineSpec struct {
	DefaultBinary        string
	DefaultArgs          []string
	InsertArgsBeforeTail bool
}

var engineSpecs = map[string]EngineSpec{
	"dnstt": {
		DefaultBinary: "dnstt-client",
		DefaultArgs: []string{
			"-udp", "{resolver}",
			"{domain}",
			"{listen}",
		},
		InsertArgsBeforeTail: true,
	},
	"slipstream": {
		DefaultBinary: "slipstream-client",
		DefaultArgs: []string{
			"--tcp-listen-host", "{listen_host}",
			"--tcp-listen-port", "{listen_port}",
			"--resolver", "{resolver}",
			"--domain", "{domain}",
			"--keep-alive-interval", "200",
		},
	},
	"vaydns": {
		DefaultBinary: "vaydns-client",
		DefaultArgs: []string{
			"-domain", "{domain}",
			"-listen", "{listen}",
			"-udp", "{resolver}",
		},
	},
}

func SupportedEngines() []string {
	names := make([]string, 0, len(engineSpecs))
	for name := range engineSpecs {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func buildEngineArgs(cfg *runtimeConfig, resolver string, port int) ([]string, error) {
	spec := engineSpecs[cfg.Engine]
	listenAddr := strings.Join([]string{"127.0.0.1", strconv.Itoa(port)}, ":")
	extraArgs := cfg.ExtraArgs

	args := make([]string, 0, len(spec.DefaultArgs)+len(extraArgs))
	if spec.InsertArgsBeforeTail {
		tailSize := 2
		if len(spec.DefaultArgs) < tailSize {
			return nil, fmt.Errorf("invalid engine configuration")
		}
		args = append(args, spec.DefaultArgs[:len(spec.DefaultArgs)-tailSize]...)
		args = append(args, extraArgs...)
		args = append(args, spec.DefaultArgs[len(spec.DefaultArgs)-tailSize:]...)
	} else {
		args = append(args, spec.DefaultArgs...)
		args = append(args, extraArgs...)
	}
	return expandPlaceholders(args, placeholderValues(cfg, resolver, port, listenAddr))
}

func placeholderValues(cfg *runtimeConfig, resolver string, port int, listenAddr string) map[string]string {
	return map[string]string{
		"{resolver}":    resolver,
		"{domain}":      cfg.Domain,
		"{listen}":      listenAddr,
		"{listen_host}": "127.0.0.1",
		"{listen_port}": strconv.Itoa(port),
	}
}

func expandPlaceholders(args []string, values map[string]string) ([]string, error) {
	expanded := make([]string, 0, len(args))
	for _, arg := range args {
		current := arg
		for key, value := range values {
			current = strings.ReplaceAll(current, key, value)
		}
		if strings.Contains(current, "{") && strings.Contains(current, "}") {
			return nil, fmt.Errorf("unknown placeholder in argument %q", arg)
		}
		expanded = append(expanded, current)
	}
	return expanded, nil
}
