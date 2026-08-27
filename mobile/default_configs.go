package mobile

import (
    "io"
    "net"
    "crypto/rand"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strconv"
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
	ConfigType      string `json:"config_type"`
	Name            string `json:"name"`
	Clouds          []string `json:"clouds"`
	Domain          string `json:"domain"`
	Pubkey          string `json:"pubkey"`
	RecordType      string `json:"recordType"`
	IdleTimeout     string `json:"idleTimeout"`
	KeepAlive       string `json:"keepAlive"`
	ClientIdSize    int    `json:"clientIdSize"`
	DnsttCompatible bool   `json:"dnsttCompatible"`
	Protocol        string `json:"protocol"`
	Proxy           string `json:"proxy"`
	UseSshKey       bool   `json:"useSshKey"`
	SSMethod        string `json:"method"`
	User            string `json:"user"`
	Pass            string `json:"pass"`
	FreeScanner     bool   `json:"freeScanner"`
	ServerDomain    string `json:"server_domain"`
	
	// --- Protocol Specific Embedded Structs ---
	// Go will seamlessly unmarshal flat JSON into this!
	HysteriaConfig
	RealityConfig
	VlessConfig
	XhttpRealityConfig
	WireguardConfig
}

type CDNSettings struct {
	Name      string   `json:"name"`
	Code      string   `json:"code"`
	Protocols []string `json:"protocols"`
	Ports     []int    `json:"ports"`
	VlessWsIP string   `json:"vless_ws_ip"`
}

type ConfigWrapper struct {
	Version      int             `json:"version"`
	Release      string          `json:"release"`
	ServerURLs   []string        `json:"serverURLs"`
	AppSecretKey string          `json:"appSecretKey"`
	CDN          map[string]CDNSettings `json:"cdn"`
	SniPool      []string               `json:"sni_pool"`
	Configs      []DefaultConfig `json:"configs"`
}

var (
	defaultConfigs    []DefaultConfig
	currentVersion    int
	currentRelease    string
	currentServerURLs []string
	currentSecretKey  string
	currentCDN        map[string]CDNSettings
	cdnNames          []string
	sniPool           []string 
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
		currentRelease = wrapper.Release
		currentServerURLs = wrapper.ServerURLs
		currentSecretKey = wrapper.AppSecretKey
		currentCDN = wrapper.CDN // Directly assign the map
		sniPool = wrapper.SniPool

		// Extract and sort CDN names so Android UI indexing remains consistent (Amazon, Cloudflare, etc.)
		cdnNames = make([]string, 0, len(currentCDN))
		for name := range currentCDN {
			cdnNames = append(cdnNames, name)
		}
		sort.Strings(cdnNames)

	} else {
		// Bare minimum fallback just in case the file is completely broken
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
	sniPool = nil
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

func GetDefaultConfigDomainCount(index int64) int64 {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return 0
	}
	parts := strings.Split(defaultConfigs[index].Domain, ",")
	count := 0
	for _, p := range parts {
		if strings.TrimSpace(p) != "" {
			count++
		}
	}
	return int64(count)
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

func GetUpdateServerURLsExported() string {
	ensureParsed()
	return strings.Join(currentServerURLs, ",")
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
	
/*	
	if int(index) >= 0 && int(index) < len(defaultConfigs) {
		cfg := defaultConfigs[index]
		
		// LOGGING FOR DEBUGGING
		// This will show up in logcat under the "GoLog" or "Go" tag
		fmt.Printf("VAY_DEBUG_GO: Checking Index %d | Name: %s | isFreeScanner: %v\n", 
			index, cfg.Name, cfg.FreeScanner)

		return cfg.FreeScanner
	}
		
	fmt.Printf("VAY_DEBUG_GO: Index %d is OUT OF BOUNDS (Size: %d)\n", index, len(defaultConfigs))
*/	
		
	// Ensure we are checking the defaultConfigs slice, not the display map
	if int(index) >= 0 && int(index) < len(defaultConfigs) {
		return defaultConfigs[index].FreeScanner
	}
	return false
}

func GetDefaultConfigProxy(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "socks"
	}
	if defaultConfigs[index].Proxy == "" {
		return "socks" // Default internally
	}
	return strings.ToLower(defaultConfigs[index].Proxy)
}

// GetDefaultConfigType returns "vaydns" or "direct" so the UI knows how to route.
func GetDefaultConfigType(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "vaydns" // Safe fallback
	}
	if defaultConfigs[index].ConfigType == "" {
		return "vaydns" // Backward compatibility for old JSONs
	}
	return defaultConfigs[index].ConfigType
}

// IsOfficialBuild returns true if the app was compiled with CI/CD injected keys.
// It returns false for community builds.
func IsOfficialBuild() bool {
	return InjectedConfigs != "" && InjectedPrivateKey != ""
}

func getServerDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if defaultConfigs[index].ServerDomain != "" {
		return defaultConfigs[index].ServerDomain
	}

	return ""
}

func getServerIpAddress(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].ServerIP 
}

func GetReleaseType() string {
	ensureParsed()
	if currentRelease == "" {
		return "community" // Fallback safety
	}
	return currentRelease
}

func GetPrimaryUpdateServer() string {
	ensureParsed()
	if len(currentServerURLs) > 0 {
		return strings.TrimRight(currentServerURLs[0], "/")
	}
	return ""
}

func GetAppSecretKeyExported() string {
	ensureParsed()
	return currentSecretKey
}

func GetCdnCount() int64 {
	ensureParsed()
	return int64(len(cdnNames))
}

func GetCdnName(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(cdnNames)) {
		return ""
	}
	return cdnNames[index]
}

func GetCdnVlessWsIP(cdnName string) string {
	ensureParsed()
	if currentCDN == nil {
		return ""
	}
	if settings, ok := currentCDN[cdnName]; ok {
		return settings.VlessWsIP
	}
	return ""
}

// GetCdnVendorName safely returns the internal matching name for the selected CDN
func getCdnVendorName(cdnName string) string {
	ensureParsed()
	if currentCDN == nil {
		return ""
	}
	if settings, ok := currentCDN[cdnName]; ok {
		return settings.Name
	}
	return ""
}

// GetCdnMatchCode safely returns the exact HTTP response validation code for the selected CDN
func getCdnMatchCode(cdnName string) string {
	ensureParsed()
	if currentCDN == nil {
		return ""
	}
	if settings, ok := currentCDN[cdnName]; ok {
		return settings.Code
	}
	return ""
}

// GetDefaultConfigClouds returns a comma-separated string of supported CDNs for a specific config
func GetDefaultConfigClouds(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	if len(defaultConfigs[index].Clouds) == 0 {
		return ""
	}
	return strings.Join(defaultConfigs[index].Clouds, ",")
}

// CdnSupportsProtocol checks if a specific CDN supports the requested protocol mode
func CdnSupportsProtocol(cdnName string, protocol string) bool {
	ensureParsed()
	if currentCDN == nil {
		return true // Fallback to true if config hasn't loaded yet
	}
	settings, ok := currentCDN[cdnName]
	if !ok {
		return true
	}
	if len(settings.Protocols) == 0 {
		return true
	}
	protoLower := strings.ToLower(strings.TrimSpace(protocol))
	for _, p := range settings.Protocols {
		if strings.ToLower(strings.TrimSpace(p)) == protoLower {
			return true
		}
	}
	return false
}

// GetCdnPortsCount returns the number of supported ports for a given CDN.
func GetCdnPortsCount(cdnName string) int64 {
	ensureParsed()
	if currentCDN == nil {
		return 0
	}
	if settings, ok := currentCDN[cdnName]; ok {
		return int64(len(settings.Ports))
	}
	return 0
}

// GetCdnPort fetches a specific port by index for Kotlin JNI iteration.
func GetCdnPort(cdnName string, index int64) int64 {
	ensureParsed()
	if currentCDN == nil {
		return 443
	}
	if settings, ok := currentCDN[cdnName]; ok {
		if index >= 0 && index < int64(len(settings.Ports)) {
			return int64(settings.Ports[index])
		}
	}
	return 443
}

// CdnSupportsPort checks if a specific CDN supports a given target port.
func CdnSupportsPort(cdnName string, port int) bool {
	ensureParsed()
	if currentCDN == nil {
		return true // Fallback to true if config hasn't loaded yet
	}
	settings, ok := currentCDN[cdnName]
	if !ok {
		return true
	}
	if len(settings.Ports) == 0 {
		return true
	}
	for _, p := range settings.Ports {
		if p == port {
			return true
		}
	}
	return false
}

// GetCdnPortsCsv returns a comma-separated string of ports (e.g. "443,2053,2083") for Kotlin UI spinners/dialogs.
func GetCdnPortsCsv(cdnName string) string {
	ensureParsed()
	if currentCDN == nil {
		return "443"
	}
	settings, ok := currentCDN[cdnName]
	if !ok || len(settings.Ports) == 0 {
		return "443"
	}
	var ports []string
	for _, p := range settings.Ports {
		ports = append(ports, strconv.Itoa(p))
	}
	return strings.Join(ports, ",")
}

// GetSniPoolCount returns the number of SNIs in the pool to Android JNI
func GetSniPoolCount() int64 {
	ensureParsed()
	configMu.Lock()
	defer configMu.Unlock()
	return int64(len(sniPool))
}

// getSniFromPool fetches the actual SNI string by index (internal to Go)
func getSniFromPool(index int64) string {
	ensureParsed()
	configMu.Lock()
	defer configMu.Unlock()
	if index < 0 || index >= int64(len(sniPool)) {
		return ""
	}
	return sniPool[index]
}

// --- NEW: AES-GCM Encryption Helper ---
func encryptAESGCM(data []byte, hexKey string) ([]byte, error) {
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

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}

	// Seal appends the ciphertext and prepends/handles the nonce structure
	ciphertext := gcm.Seal(nonce, nonce, data, nil)
	return ciphertext, nil
}

// --- EXPORTED JNI WRAPPERS FOR KOTLIN ---

// EncryptText encrypts sensitive data using the native InjectedPrivateKey
func EncryptText(plaintext string) string {
	configMu.Lock()
	key := InjectedPrivateKey
	configMu.Unlock()

	// Fallback if no private key exists (Community build mode)
	if key == "" || plaintext == "" {
		return plaintext
	}

	encrypted, err := encryptAESGCM([]byte(plaintext), key)
	if err != nil {
		return plaintext
	}
	return base64.StdEncoding.EncodeToString(encrypted)
}

// DecryptText decrypts sensitive data using the native InjectedPrivateKey
func DecryptText(ciphertext string) string {
	configMu.Lock()
	key := InjectedPrivateKey
	configMu.Unlock()

	// Fallback if no private key exists or text is raw/empty
	if key == "" || ciphertext == "" {
		return ciphertext
	}

	data, err := base64.StdEncoding.DecodeString(ciphertext)
	if err != nil {
		return ciphertext // Might be plaintext already (migration/fallback)
	}

	decrypted, err := decryptAESGCM(data, key)
	if err != nil {
		return ciphertext
	}
	return string(decrypted)
}

// --- NATIVE AES-256 FORMAT-PRESERVING ENCRYPTION (FPE) FOR IPs ---

// fpeRoundFunction acts as the pseudo-random function for the Feistel network
func fpeRoundFunction(block cipher.Block, right16 uint16, roundIndex uint8) uint16 {
	src := make([]byte, 16)
	binary.BigEndian.PutUint16(src[0:2], right16)
	src[2] = roundIndex

	dst := make([]byte, 16)
	block.Encrypt(dst, src)

	return binary.BigEndian.Uint16(dst[0:2])
}

// EncryptIP scrambles a real IPv4 address into a fake, mapped IPv4 address
func EncryptIP(ipStr string) string {
	configMu.Lock()
	hexKey := InjectedPrivateKey
	configMu.Unlock()

	ip := net.ParseIP(strings.TrimSpace(ipStr))
	// Fallback to raw string if it is not an IPv4 address, or if it is a community build
	if ip == nil || ip.To4() == nil || hexKey == "" {
		return ipStr 
	}

	key, err := hex.DecodeString(hexKey)
	if err != nil || len(key) != 32 {
		return ipStr
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return ipStr
	}

	ip32 := binary.BigEndian.Uint32(ip.To4())
	left := uint16(ip32 >> 16)
	right := uint16(ip32 & 0xFFFF)

	// Forward Feistel Network Execution (6 Rounds)
	for i := uint8(0); i < 6; i++ {
		nextLeft := right
		nextRight := left ^ fpeRoundFunction(block, right, i)
		left = nextLeft
		right = nextRight
	}

	encryptedIP := make(net.IP, 4)
	binary.BigEndian.PutUint32(encryptedIP, (uint32(left)<<16)|uint32(right))
	return encryptedIP.String()
}

// DecryptIP perfectly restores a fake IPv4 address back to the real IPv4 address
func DecryptIP(ipStr string) string {
	configMu.Lock()
	hexKey := InjectedPrivateKey
	configMu.Unlock()

	ip := net.ParseIP(strings.TrimSpace(ipStr))
	if ip == nil || ip.To4() == nil || hexKey == "" {
		return ipStr 
	}

	key, err := hex.DecodeString(hexKey)
	if err != nil || len(key) != 32 {
		return ipStr
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return ipStr
	}

	ip32 := binary.BigEndian.Uint32(ip.To4())
	left := uint16(ip32 >> 16)
	right := uint16(ip32 & 0xFFFF)

	// Reverse Feistel Network Execution (6 Rounds backwards)
	for i := int8(5); i >= 0; i-- {
		prevRight := left
		prevLeft := right ^ fpeRoundFunction(block, left, uint8(i))
		left = prevLeft
		right = prevRight
	}

	decryptedIP := make(net.IP, 4)
	binary.BigEndian.PutUint32(decryptedIP, (uint32(left)<<16)|uint32(right))
	return decryptedIP.String()
}

func getWgPublicKey(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].ServerPublicKey
}

func getWgSecretKey(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].ClientPrivateKey
}

func getWgLocalAddress(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "10.0.0.2/32"
	}
	if defaultConfigs[index].InternalIP == "" {
		return "10.0.0.2/32" // WireGuard requires a local internal IP
	}
	return defaultConfigs[index].InternalIP
}

func getWgPort(index int64) int {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return 51820
	}
	portStr := defaultConfigs[index].WgPort
	if portStr == "" {
		// Fallback: If wg_port is empty, reuse the Reality/Vless port!
		return getRealityTcpPort(index) 
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return 51820
	}
	return port
}

