package mobile

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"math/rand"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var (
/*	cfCIDRs = []string{
		"104.16.0.0/12", "104.24.0.0/14", "172.64.0.0/13",
		"188.114.96.0/20", "198.41.128.0/17",
	}*/
	cfCIDRs = []string{
		"103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
		"104.16.0.0/13", "104.24.0.0/14", "108.162.192.0/18",
		"131.0.72.0/22", "141.101.64.0/18", "162.158.0.0/15",
		"172.64.0.0/13", "173.245.48.0/20", "188.114.96.0/20",
		"190.93.240.0/20", "197.234.240.0/22", "198.41.128.0/17",				
	}
	
	cfScanCancel context.CancelFunc
	cfScanMu     sync.Mutex
)

type CFScannerCallback interface {
	OnUpdate(result string)
}

func getRandomIP(cidr string) (string, error) {
	_, ipv4Net, err := net.ParseCIDR(cidr)
	if err != nil {
		return "", err
	}
	ip := ipv4Net.IP.To4()
	if ip == nil {
		return "", fmt.Errorf("not an ipv4 address")
	}

	ones, bits := ipv4Net.Mask.Size()
	hostBits := bits - ones
	hostNum := rand.Intn(1<<hostBits - 2) + 1

	resultIP := make(net.IP, 4)
	copy(resultIP, ip)

	for i := 3; i >= 0; i-- {
		resultIP[i] += byte(hostNum >> ((3 - i) * 8))
	}
	return resultIP.String(), nil
}

func generateCloudflareIPs(count int) []string {
	ips := make(map[string]bool)
	var result []string

	for len(result) < count {
		cidr := cfCIDRs[rand.Intn(len(cfCIDRs))]
		ip, err := getRandomIP(cidr)
		if err == nil && !ips[ip] {
			ips[ip] = true
			result = append(result, ip)
		}
	}
	return result
}

func StopCloudflareScanner() {
	cfScanMu.Lock()
	defer cfScanMu.Unlock()
	if cfScanCancel != nil {
		cfScanCancel()
		cfScanCancel = nil
	}
}

func RunCloudflareScanner(isDefault bool, configIndex int64, requestedCount int64, cb CFScannerCallback) string {
	cfScanMu.Lock()
	if cfScanCancel != nil {
		cfScanCancel() 
	}

	if requestedCount <= 0 {
		requestedCount = 512
	}

	ctx, cancel := context.WithCancel(context.Background())
	cfScanCancel = cancel
	cfScanMu.Unlock()

	defer StopCloudflareScanner()

	// 1. Resolve SNI
	domainToUse := ""
	pathToUse := "/vayws"

	if isDefault {
		if d := getVlessDomain(configIndex); d != "" {
			domainToUse = d
		}
		if p := getVlessPath(configIndex); p != "" {
			pathToUse = p
		}
	}

	if domainToUse == "" {
		return ""
	}

	if strings.Contains(domainToUse, ":") {
		domainToUse = strings.Split(domainToUse, ":")[0]
	}

	// 2. Setup Batch Parameters
	testIPs := generateCloudflareIPs(int(requestedCount))
	
	// INCREASED: Now processes 512 IPs before pushing an update
	batchSize := 1024

	type scanRes struct {
		ip      string
		latency int64
	}
	
	type CFResult struct {
		IP      string `json:"ip"`
		Latency int64  `json:"latency"`
	}

	resultsChan := make(chan scanRes, requestedCount)
	var scannedCount int32 = 0
	var foundCount int32 = 0
	
	var validResults []CFResult 

	// 3. Start Batch Loop
	for i := 0; i < len(testIPs); i += batchSize {
		end := i + batchSize
		if end > len(testIPs) {
			end = len(testIPs)
		}
		batchIPs := testIPs[i:end]

		var wg sync.WaitGroup
		sem := make(chan struct{}, 20)

		for _, ip := range batchIPs {
			wg.Add(1)
			sem <- struct{}{}

			go func(targetIP string) {
				defer wg.Done()
				defer func() { <-sem }()

				select {
				case <-ctx.Done():
					return
				default:
				}

				atomic.AddInt32(&scannedCount, 1)
				address := net.JoinHostPort(targetIP, "443")
				start := time.Now()

				dialCtx, dialCancel := context.WithTimeout(ctx, 1500*time.Millisecond)
				defer dialCancel()

				var d net.Dialer
				conn, err := d.DialContext(dialCtx, "tcp", address)
				if err != nil || conn == nil {
					return
				}

				tlsConfig := &tls.Config{
					ServerName:         domainToUse,
					InsecureSkipVerify: false,
				}
				tlsConn := tls.Client(conn, tlsConfig)
				err = tlsConn.HandshakeContext(dialCtx)
				if err != nil {
					tlsConn.Close()
					return
				}

				reqStr := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: Mozilla/5.0\r\n\r\n", pathToUse, domainToUse)
				_, err = tlsConn.Write([]byte(reqStr))

				if err == nil {
					tlsConn.SetReadDeadline(time.Now().Add(800 * time.Millisecond))
					buf := make([]byte, 1024)
					n, readErr := tlsConn.Read(buf)

					if readErr == nil && n > 0 {
						resp := string(buf[:n])
						if strings.Contains(resp, "HTTP/1.1 400") || strings.Contains(resp, "HTTP/1.1 101") {
							lat := time.Since(start).Milliseconds()
							atomic.AddInt32(&foundCount, 1)
							resultsChan <- scanRes{ip: targetIP, latency: lat}
						}
					}
				}
				tlsConn.Close()
			}(ip)
		}

		// Wait for batch to finish
		wg.Wait()

		// 4. Drain, Sort, and Mask Results
	drainLoop:
		for {
			select {
			case res := <-resultsChan:
				validResults = append(validResults, CFResult{IP: res.ip, Latency: res.latency})
			default:
				break drainLoop 
			}
		}

		sort.Slice(validResults, func(x, y int) bool {
			return validResults[x].Latency < validResults[y].Latency
		})

		// ANTI-CENSORSHIP: Create a masked copy for the UI
		maskedDisplay := make([]CFResult, len(validResults))
		for i, v := range validResults {
			if i == 0 {
				maskedDisplay[i] = v
			} else {
				parts := strings.Split(v.IP, ".")
				if len(parts) == 4 {
					maskedDisplay[i] = CFResult{IP: fmt.Sprintf("%s.%s.%s.%s", parts[0], parts[1], parts[2], parts[3]), Latency: v.Latency}
					// maskedDisplay[i] = CFResult{IP: fmt.Sprintf("%s.*.*.*", parts[0]), Latency: v.Latency}
				} else {
					maskedDisplay[i] = CFResult{IP: "***.***.***.***", Latency: v.Latency}
				}
			}
		}

		resBytes, _ := json.Marshal(maskedDisplay)
		currentResultStr := fmt.Sprintf("%s|%d|%d", string(resBytes), atomic.LoadInt32(&scannedCount), atomic.LoadInt32(&foundCount))

		// 5. Push Update and Pause NAT
		if end < len(testIPs) {
			if cb != nil {
				cb.OnUpdate(currentResultStr)
			}
			
			// INCREASED: Wait 30 seconds for Router NAT table to flush completely
			select {
			case <-ctx.Done():
				return currentResultStr 
			case <-time.After(30 * time.Second):
			}
		} else {
			return currentResultStr
		}
	}

	return ""
}
