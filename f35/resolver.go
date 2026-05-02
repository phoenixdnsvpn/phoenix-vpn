package f35

import (
	"bufio"
	"fmt"
	"net"
	"os"
//	"strconv"
	"strings"
)

type parsedResolver struct {
	addr string
	ip   net.IP
	port uint16
}

func LoadResolvers(path string, mode string) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	var raw []string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		raw = append(raw, scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}

	resolvers := normalizeResolvers(raw, mode)
	if len(resolvers) == 0 {
		return nil, fmt.Errorf("no valid resolvers found")
	}
	return resolvers, nil
}

func normalizeResolvers(input []string, mode string) []string {
	return resolverAddrs(parseResolvers(input, mode))
}

func parseResolvers(input []string, mode string) []parsedResolver {
	out := make([]parsedResolver, 0, len(input))
	seen := make(map[string]bool)
	for _, line := range input {
		resolver, ok := parseResolver(strings.TrimSpace(line), mode)
		if ok && !seen[resolver.addr] {
			seen[resolver.addr] = true
			out = append(out, resolver)
		}
	}
	return out
}

func resolverAddrs(resolvers []parsedResolver) []string {
	out := make([]string, 0, len(resolvers))
	for _, resolver := range resolvers {
		out = append(out, resolver.addr)
	}
	return out
}

func parseResolver(line string, mode string) (parsedResolver, bool) {
	if line == "" {
		return parsedResolver{}, false
	}
	
	if mode == "doh" {
		return parsedResolver{addr: line}, true
	}

	defaultPort := "53"
	if mode == "dot" {
		defaultPort = "853"
	}

	host, port, err := net.SplitHostPort(line)
	if err != nil {
		host = line
		port = defaultPort
	}
	
	ip := net.ParseIP(host) // Might be nil for DoT domains, which is fine
	
	return parsedResolver{
		addr: net.JoinHostPort(host, port),
		ip:   ip,
	}, true
}
