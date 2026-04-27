package mobile

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"sync"
//	"fmt"
	"os"
	"path/filepath"
)

// =====================================================================
// VAYDNS NATIVE VAULT
// =====================================================================
// Notice for Open-Source Community: 
// Official server configurations and infrastructure keys are intentionally
// left blank in this source code to protect production infrastructure.
// 
// For official builds, these variables are injected at compile-time 
// via CI/CD using -ldflags. For local community builds, the app will 
// gracefully fall back to "Custom Configs Only" mode.
// =====================================================================

var InjectedConfigs string
var InjectedResolvers string 
var InjectedPrivateKey string

type DefaultConfig struct {
	Name            string `json:"name"`
	Domain          string `json:"domain"`
	Pubkey          string `json:"pubkey"`
	RecordType      string `json:"recordType"`
	IdleTimeout     string `json:"idleTimeout"`
	KeepAlive       string `json:"keepAlive"`
	ClientIdSize    int    `json:"clientIdSize"`
	DnsttCompatible bool   `json:"dnsttCompatible"`
	Protocol        string `json:"protocol"`
	UseSshKey       bool   `json:"useSshKey"`
	ssMethod        string `json:"method"`
	User            string `json:"user"`
	Pass            string `json:"pass"`
}

type ConfigWrapper struct {
	Version      int             `json:"version"`
	ServerURLs   []string        `json:"serverURLs"`
	AppSecretKey string          `json:"appSecretKey"`
	Configs      []DefaultConfig `json:"configs"`
}

var (
	defaultConfigs    []DefaultConfig
	currentVersion    int
	currentServerURLs []string
	currentSecretKey  string
	configMu          sync.Mutex

	defaultDisplayResolvers map[string]string 
	realIpMap               map[string]string 
	resolverMu              sync.Mutex

	vaultStorageDir string
)

// InitVault tells Go where to look for the downloaded .bin files
func InitVault(storageDir string) {
	configMu.Lock()
	vaultStorageDir = storageDir
	configMu.Unlock()
}

func ensureParsed() {
	configMu.Lock()
	defer configMu.Unlock()

	if len(defaultConfigs) > 0 { return }
    
	var data []byte
	var err error

	// 1. Try reading directly from Android's internal storage
	if vaultStorageDir != "" {
		filePath := filepath.Join(vaultStorageDir, "cached_default_configs.bin")
		data, err = os.ReadFile(filePath)		
//		fmt.Printf("VAY_DEBUG_VAULT: Read from disk. Err: %v, Bytes: %d\n", err, len(data))
	}

	// 2. Fallback to injected CI build
	if err != nil || len(data) == 0 {
		if InjectedConfigs != "" {
			data, _ = base64.StdEncoding.DecodeString(InjectedConfigs)
//			fmt.Printf("VAY_DEBUG_VAULT: Read from InjectedConfigs. Bytes: %d\n", len(data))
		} else {
//			fmt.Printf("VAY_DEBUG_VAULT: No data found anywhere!\n")
			return // Community build: No data injected, remain empty
		}
	}

	// Decrypt the payload
	mask, _ := hex.DecodeString(InjectedPrivateKey)

	if len(mask) > 0 && len(data) > 0 {
		for i := 0; i < len(data); i++ {
			data[i] ^= mask[i%len(mask)]
		}
	}

	parseConfigData(data)

}

func parseConfigData(data []byte) {
	var wrapper ConfigWrapper	
	err := json.Unmarshal(data, &wrapper)
		
	if err == nil && len(wrapper.Configs) > 0 {
		defaultConfigs = wrapper.Configs
		currentVersion = wrapper.Version
		currentServerURLs = wrapper.ServerURLs
		currentSecretKey = wrapper.AppSecretKey
					
	} else {
		// Fallback for older JSON formats
		json.Unmarshal(data, &defaultConfigs)
	}
}

func ensureResolversParsed() {
	resolverMu.Lock()
	defer resolverMu.Unlock()

	if defaultDisplayResolvers != nil { return }

	var data []byte
	var err error

	if vaultStorageDir != "" {
		filePath := filepath.Join(vaultStorageDir, "cached_default_resolvers.bin")
		data, err = os.ReadFile(filePath)
	}

	if err != nil || len(data) == 0 {
		if InjectedResolvers != "" {
			data, _ = base64.StdEncoding.DecodeString(InjectedResolvers)
		} else {
			return // Community build
		}
	}

	mask, _ := hex.DecodeString(InjectedPrivateKey)
	if len(mask) > 0 && len(data) > 0 {
		for i := 0; i < len(data); i++ {
			data[i] ^= mask[i%len(mask)]
		}
	}

	var entries []struct {
		Name         string `json:"name"`
		Resolver     string `json:"resolver"`
		RandResolver string `json:"rand_resolver"`
	}

	defaultDisplayResolvers = make(map[string]string)
	realIpMap = make(map[string]string)

	if err := json.Unmarshal(data, &entries); err == nil {
		for _, entry := range entries {
			if entry.Resolver != "" && entry.RandResolver != "" {
				realIps := strings.Split(entry.Resolver, ",")
				fakeIps := strings.Split(entry.RandResolver, ",")
				var displayFake []string

				for i := 0; i < len(fakeIps) && i < len(realIps); i++ {
					fakeClean := strings.TrimSpace(fakeIps[i])
					realClean := strings.TrimSpace(realIps[i])
					if fakeClean == "" || realClean == "" { continue }

					realIpMap[fakeClean] = realClean
					displayFake = append(displayFake, fakeClean+":53")
				}
				defaultDisplayResolvers[entry.Name] = strings.Join(displayFake, ",")
			}
		}
	}
}

func ClearCaches() {
	configMu.Lock()
	defaultConfigs = nil
	configMu.Unlock()

	resolverMu.Lock()
	defaultDisplayResolvers = nil
	realIpMap = nil
	resolverMu.Unlock()
}

func GetDefaultConfigDisplayResolvers(index int64) string {
	ensureParsed()
	ensureResolversParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) { return "" }
	
	configName := defaultConfigs[index].Name

	resolverMu.Lock()
	defer resolverMu.Unlock()
	if defaultDisplayResolvers != nil {
		if resolvers, exists := defaultDisplayResolvers[configName]; exists {
			return resolvers
		}
	}
	return ""
}

func GetRealResolver(inputIP string) string {
	ensureResolversParsed()
	resolverMu.Lock()
	defer resolverMu.Unlock()
	if realIpMap != nil {
		if realIP, exists := realIpMap[inputIP]; exists {
			return realIP
		}
	}
	return inputIP
}

// =====================================================================
// PUBLIC GETTERS (Safe UI Data Only)
// =====================================================================

func GetDefaultConfigVersion() int64 {
	ensureParsed()
	return int64(currentVersion)
}

func GetDefaultConfigCount() int64 {
	ensureParsed()
	return int64(len(defaultConfigs))
}

func GetDefaultConfigName(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Name
}

func GetDefaultConfigRecordType(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "TXT"
	}
	if defaultConfigs[index].RecordType == "" {
		return "TXT"
	}
	return defaultConfigs[index].RecordType
}

func GetDefaultConfigIdleTimeout(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "10s"
	}
	if defaultConfigs[index].IdleTimeout == "" {
		return "10s"
	}
	return defaultConfigs[index].IdleTimeout
}

func GetDefaultConfigKeepAlive(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "2s"
	}
	if defaultConfigs[index].KeepAlive == "" {
		return "2s"
	}
	return defaultConfigs[index].KeepAlive
}

func GetDefaultConfigClientIdSize(index int64) int64 {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return 2
	}
	if defaultConfigs[index].ClientIdSize == 0 {
		return 2
	}
	return int64(defaultConfigs[index].ClientIdSize)
}

func GetDefaultConfigDnsttCompatible(index int64) bool {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return false
	}
	return defaultConfigs[index].DnsttCompatible
}

func GetDefaultConfigProtocol(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "socks"
	}
	if defaultConfigs[index].Protocol == "" {
		return "socks"
	}
	return strings.ToLower(defaultConfigs[index].Protocol)
}

func GetDefaultConfigUseSshKey(index int64) bool {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return false
	}
	return defaultConfigs[index].UseSshKey
}

func GetDefaultConfigMethod(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].ssMethod
}

// --- SAFE PUBLIC UI HELPERS ---
// This lets Kotlin know if it should mask the UI, without exposing the actual credentials.
func HasDefaultConfigAuth(index int64) bool {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return false
	}
	return defaultConfigs[index].User != "" || defaultConfigs[index].Pass != ""
}

// =====================================================================
// SECURE INTERNAL GETTERS (Lowercase = Hidden from Kotlin JNI)
// =====================================================================

func getAppSecretKey() string {
	ensureParsed()
	return currentSecretKey
}

func getUpdateServerURLs() []string {
	ensureParsed()
	return currentServerURLs
}

func getDefaultConfigDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) { return "" }
	return defaultConfigs[index].Domain
}

func getDefaultConfigPubkey(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) { return "" }
	return defaultConfigs[index].Pubkey
}

// HACKER BLOCKED: These are now lowercase. Kotlin cannot access them.
func getDefaultConfigUser(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].User
}

func getDefaultConfigPass(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Pass
}


