package f35

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"time"
)

const whoisURL = "https://api.ipiz.net"

type whoisResponse struct {
	OrgName string `json:"org_name"`
	Country string `json:"country"`
	Status  string `json:"status"`
}

// doHTTPCheck now accepts a parent 'ctx' to support immediate termination.
func doHTTPCheck(ctx context.Context, client *http.Client, targetURL string, timeout time.Duration, drainBody bool) (int64, bool) {
	// We create a child context that dies if the timeout hits OR the main scan is cancelled.
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(probeCtx, http.MethodGet, targetURL, nil)
	if err != nil {
		return 0, false
	}
	req.Header.Set("Connection", "close")

	startedAt := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, false
	}
	defer resp.Body.Close()

	if drainBody {
		if _, err := io.Copy(io.Discard, resp.Body); err != nil {
			return 0, false
		}
	}

	return time.Since(startedAt).Milliseconds(), true
}

// doUploadCheck now accepts a parent 'ctx' to support immediate termination.
func doUploadCheck(ctx context.Context, client *http.Client, targetURL string, timeout time.Duration, payload []byte) (int64, bool) {
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(probeCtx, http.MethodPost, targetURL, bytes.NewReader(payload))
	if err != nil {
		return 0, false
	}
	req.Header.Set("Connection", "close")
	req.Header.Set("Content-Type", "application/octet-stream")

	startedAt := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, false
	}
	defer resp.Body.Close()

	return time.Since(startedAt).Milliseconds(), true
}

// lookupResolverInfo now accepts a parent 'ctx' to support immediate termination.
func lookupResolverInfo(ctx context.Context, client *http.Client, resolverHost string, timeout time.Duration) (int64, string, string, bool) {
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(probeCtx, http.MethodGet, whoisURL+"/"+resolverHost, nil)
	if err != nil {
		return 0, "unknown", "unknown", false
	}
	req.Header.Set("Connection", "close")

	startedAt := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, "unknown", "unknown", false
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, "unknown", "unknown", false
	}

	var data whoisResponse
	if err := json.Unmarshal(body, &data); err != nil {
		return 0, "unknown", "unknown", false
	}

	return time.Since(startedAt).Milliseconds(), data.OrgName, data.Country, true
}
