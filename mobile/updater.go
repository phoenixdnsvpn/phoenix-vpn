package mobile

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// fetchSecure handles the authenticated HTTP request natively in Go
func fetchSecure(fullURL string) ([]byte, error) {
	req, err := http.NewRequest("GET", fullURL, nil)
	if err != nil {
		return nil, err
	}

	// NATIVE VAULT: Go securely grabs the token from its own memory!
	req.Header.Set("X-VayDNS-Token", getAppSecretKey())
	req.Header.Set("Accept-Encoding", "identity")

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("server returned %d", resp.StatusCode)
	}

	return io.ReadAll(resp.Body)
}

// NEW: Iterate through the array of URLs until one succeeds
func fetchWithFailover(path string) ([]byte, error) {
	urls := getUpdateServerURLs()
	if len(urls) == 0 {
		return nil, fmt.Errorf("no update server URLs configured")
	}

	var lastErr error
	for _, baseURL := range urls {
		cleanBase := strings.TrimRight(baseURL, "/")
		if cleanBase == "" {
			continue
		}

		fullURL := fmt.Sprintf("%s%s", cleanBase, path)
//	    fmt.Printf("VAY_DEBUG_NET: Trying failover URL: %s\n", fullURL)

		data, err := fetchSecure(fullURL)
		if err == nil && len(data) > 0 {
			return data, nil // Success! Stop checking the rest.
		}
		
		lastErr = err
//		fmt.Printf("VAY_DEBUG_NET: Failed %s - Error: %v\n", fullURL, err)
	}

	return nil, fmt.Errorf("all failover URLs failed. Last error: %v", lastErr)
}

func SyncConfigs() string {
	// 1. Fetch Version using Failover
	versionData, err := fetchWithFailover("/config/version.txt")
	if err != nil {
		return "Error: Failed to check version - " + err.Error()
	}

	latestVersion, err := strconv.Atoi(strings.TrimSpace(string(versionData)))
	if err != nil {
		return "Error: Invalid version format received"
	}

	ensureParsed()
	if latestVersion <= currentVersion {
		return "Configurations are already up to date."
	}

	// 2. Download Configs using Failover
	configPath := fmt.Sprintf("/config/configs.bin?t=%d", time.Now().UnixNano())
	configData, err := fetchWithFailover(configPath)
	if err != nil || len(configData) == 0 {
		return "Error: Failed to download configs - " + err.Error()
	}

	// 3. Save directly to Android internal storage via Go
	configMu.Lock()
	dir := vaultStorageDir
	configMu.Unlock()

	if dir == "" {
		return "Error: Vault storage directory not initialized"
	}

	savePath := filepath.Join(dir, "cached_default_configs.bin")
	if err := os.WriteFile(savePath, configData, 0644); err != nil {
		return "Error: Failed to save to disk - " + err.Error()
	}

	// 4. Update Engine Memory
	parseConfigData(configData)
	return fmt.Sprintf("Success: Configurations updated to v%d!", latestVersion)
}

func SyncResolvers() string {
	resolverPath := fmt.Sprintf("/config/resolvers.bin?t=%d", time.Now().UnixNano())
	resolverData, err := fetchWithFailover(resolverPath)
	if err != nil || len(resolverData) == 0 {
		return "Error: Failed to download resolvers - " + err.Error()
	}

	configMu.Lock()
	dir := vaultStorageDir
	configMu.Unlock()

	if dir == "" {
		return "Error: Vault storage directory not initialized"
	}

	savePath := filepath.Join(dir, "cached_default_resolvers.bin")
	if err := os.WriteFile(savePath, resolverData, 0644); err != nil {
		return "Error: Failed to save resolvers to disk"
	}

	ClearCaches()
	return "Success: Resolvers updated successfully!"
}

func CheckForAppUpdate(currentVersionStr string) string {
	versionData, err := fetchWithFailover("/config/app_version.txt")
	if err != nil {
		return "Error: " + err.Error()
	}

	fetchedVersion := strings.TrimSpace(string(versionData))

	if isNewerAppVersion(currentVersionStr, fetchedVersion) {
		return "UPDATE_AVAILABLE|" + fetchedVersion
	}

	return "NO_UPDATE"
}

// Helper to compare "1.7.0" with "v1.7.1"
func isNewerAppVersion(current, fetched string) bool {
	// Strip non-numeric characters (keep dots)
	cleaner := func(r rune) rune {
		if (r >= '0' && r <= '9') || r == '.' {
			return r
		}
		return -1
	}
	cleanCurrent := strings.Split(strings.Map(cleaner, current), ".")
	cleanFetched := strings.Split(strings.Map(cleaner, fetched), ".")

	length := len(cleanCurrent)
	if len(cleanFetched) > length {
		length = len(cleanFetched)
	}

	for i := 0; i < length; i++ {
		c := 0
		if i < len(cleanCurrent) {
			c, _ = strconv.Atoi(cleanCurrent[i])
		}
		f := 0
		if i < len(cleanFetched) {
			f, _ = strconv.Atoi(cleanFetched[i])
		}

		if f > c {
			return true
		}
		if f < c {
			return false
		}
	}
	return false
}

