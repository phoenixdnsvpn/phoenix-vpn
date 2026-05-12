package mobile

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
//	"fmt"
)

// =====================================================================
// VAYDNS NATIVE VAULT
// =====================================================================
// Notice for Open-Source Community: 
// Official server configurations and infrastructure keys are intentionally
// left blank in this source code to protect production infrastructure.
// 
// For official builds, these variables are injected at compile-time 
// via CI/CD using -ldflags. For community builds, the app will 
// gracefully fall back to "Custom Configs Only" mode.
// =====================================================================

var InjectedConfigs string
var InjectedResolvers string
var InjectedPrivateKey string

var RuntimeConfigs []byte
var RuntimeResolvers []byte

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
	SSMethod        string `json:"method"`
	User            string `json:"user"`
	Pass            string `json:"pass"`
	FreeScanner     bool   `json:"freeScanner"`
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

// --- NEW: AES-GCM Decryption Helper ---
func decryptAESGCM(data []byte, hexKey string) ([]byte, error) {
	key, err := hex.DecodeString(hexKey)
	if err != nil {
		return nil, err
	}
	if len(key) != 32 {
		return nil, errors.New("AES-256 requires a 32-byte key")
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonceSize := gcm.NonceSize()
	if len(data) < nonceSize {
		return nil, errors.New("ciphertext too short")
	}

	// Split the prepended nonce from the ciphertext
	nonce, ciphertext := data[:nonceSize], data[nonceSize:]

	// Open will automatically authenticate and decrypt
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, err
	}

	return plaintext, nil
}
// ---------------------------------------

func ensureParsed() {
	configMu.Lock()
	defer configMu.Unlock()

	if len(defaultConfigs) > 0 {
		return
	}

	var data []byte
	var err error

	// 1. Try reading directly from Android's internal storage
	if vaultStorageDir != "" {
		filePath := filepath.Join(vaultStorageDir, "cached_default_configs.bin")
		data, err = os.ReadFile(filePath)
	}

	// 2. Fallback to injected CI build
	if err != nil || len(data) == 0 {
		if InjectedConfigs != "" {
			data, _ = base64.StdEncoding.DecodeString(InjectedConfigs)
		} else {
			return // Community build: No data injected, remain empty
		}
	}

	// 3. Decrypt the payload
	if len(data) > 0 && InjectedPrivateKey != "" {
		decrypted, err := decryptAESGCM(data, InjectedPrivateKey)
		if err == nil {
			data = decrypted
		} else {
			return // Decryption failed, abort loading
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

	if defaultDisplayResolvers != nil {
		return
	}

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

	// Decrypt the payload
	if len(data) > 0 && InjectedPrivateKey != "" {
		decrypted, err := decryptAESGCM(data, InjectedPrivateKey)
		if err == nil {
			data = decrypted
		} else {
			return // Decryption failed
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
					if fakeClean == "" || realClean == "" {
						continue
					}

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
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}

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
	return defaultConfigs[index].SSMethod
}

// --- SAFE PUBLIC UI HELPERS ---
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
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Domain
}

func getDefaultConfigPubkey(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Pubkey
}

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

func ImportResolversManual(data []byte) string {
	if len(data) == 0 {
		return "ERROR|Received 0 bytes from Android."
	}

	safeData := make([]byte, len(data))
	copy(safeData, data)

	tempData := make([]byte, len(safeData))
	copy(tempData, safeData)

	if len(tempData) > 0 && InjectedPrivateKey != "" {
		decrypted, err := decryptAESGCM(tempData, InjectedPrivateKey)
		if err != nil {
			return "ERROR|Decryption failed. Please ensure you selected a valid VayDNS resolvers file."
		}
		tempData = decrypted
	}

	var entries []struct {
		Name         string `json:"name"`
		Resolver     string `json:"resolver"`
		RandResolver string `json:"rand_resolver"`
	}

	if err := json.Unmarshal(tempData, &entries); err != nil {
		return "ERROR|Invalid file structure."
	}

	validCount := 0
	for _, entry := range entries {
		if entry.Resolver != "" && entry.RandResolver != "" {
			validCount++
		}
	}
	if validCount == 0 {
		return "ERROR|Invalid file format. Did you accidentally upload the configs file?"
	}

	configMu.Lock()
	dir := vaultStorageDir
	configMu.Unlock()

	if dir != "" {
		savePath := filepath.Join(dir, "cached_default_resolvers.bin")
		os.WriteFile(savePath, safeData, 0644)
	}

	resolverMu.Lock()
	RuntimeResolvers = make([]byte, len(safeData))
	copy(RuntimeResolvers, safeData)
	defaultDisplayResolvers = nil
	realIpMap = nil
	resolverMu.Unlock()

	return "SUCCESS|Resolvers upload successful!"
}

func ImportConfigsManual(data []byte) string {
	if len(data) == 0 {
		return "ERROR|Received 0 bytes from Android."
	}

	ensureParsed()

	safeData := make([]byte, len(data))
	copy(safeData, data)

	tempData := make([]byte, len(safeData))
	copy(tempData, safeData)

	if len(tempData) > 0 && InjectedPrivateKey != "" {
		decrypted, err := decryptAESGCM(tempData, InjectedPrivateKey)
		if err != nil {
			return "ERROR|Decryption failed. Please ensure you selected a valid VayDNS configs file."
		}
		tempData = decrypted
	}

	var tempWrapper ConfigWrapper
	if err := json.Unmarshal(tempData, &tempWrapper); err != nil {
		return "ERROR|Invalid file structure."
	}

	if len(tempWrapper.Configs) == 0 {
		return "ERROR|Invalid file format. Did you accidentally upload the resolvers file?"
	}

	configMu.Lock()
	activeVersion := currentVersion
	configMu.Unlock()

	if tempWrapper.Version <= activeVersion {
		return "UP_TO_DATE|Configs are already updated."
	}

	configMu.Lock()
	dir := vaultStorageDir
	configMu.Unlock()

	if dir != "" {
		savePath := filepath.Join(dir, "cached_default_configs.bin")
		os.WriteFile(savePath, safeData, 0644)
	}

	configMu.Lock()
	RuntimeConfigs = make([]byte, len(safeData))
	copy(RuntimeConfigs, safeData)
	defaultConfigs = nil
	configMu.Unlock()

	return "SUCCESS|Config upload successful!"
}

func GetDefaultConfigIsFreeScanner(index int64) bool {

	ensureParsed()
	configMu.Lock()
	defer configMu.Unlock()
		
	// Ensure we are checking the defaultConfigs slice, not the display map
	if int(index) >= 0 && int(index) < len(defaultConfigs) {
		return defaultConfigs[index].FreeScanner
	}
	return false
}
