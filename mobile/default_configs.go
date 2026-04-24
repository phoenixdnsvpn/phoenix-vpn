package mobile

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"sync"
	"fmt"
)

// Injected via -ldflags in CI (Compile-time Base64 Strings from .bin files)
var InjectedConfigs string
var InjectedResolvers string 

// Downloaded OTA via Kotlin (Runtime Raw Bytes from .dat files)
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
	ssMethod        string `json:"method"`
	User            string `json:"user"`
	Pass            string `json:"pass"`
}

type ConfigWrapper struct {
	Version      int             `json:"version"`
	ServerURL    string          `json:"serverURL"`
	AppSecretKey string          `json:"appSecretKey"`
	Configs      []DefaultConfig `json:"configs"`
}

var (
	defaultConfigs   []DefaultConfig
	currentVersion   int
	currentServerURL string
	currentSecretKey string
	configMu         sync.Mutex

	// Resolver specific state
	defaultDisplayResolvers map[string]string // Maps Config Name -> Fake IPs (for UI)
	realIpMap               map[string]string // Maps Fake IP -> Real IP (for VPN backend)
	resolverMu              sync.Mutex
)


// ensureParsed checks if the configs slice needs to be populated.

func ensureParsed() {
	configMu.Lock()
	defer configMu.Unlock()

	if len(defaultConfigs) > 0 { return }

	var data []byte
	if len(RuntimeConfigs) > 0 {
		data = make([]byte, len(RuntimeConfigs))
		copy(data, RuntimeConfigs)
        // DEBUG: See exactly what Go received from Kotlin
//       	fmt.Printf("VAY_DEBUG: Raw Input String: %s\n", string(data[:10]))
//       	fmt.Printf("VAY_DEBUG: Raw Input String: %s\n", string(data))       	
//        fmt.Printf("VAY_DEBUG: Raw Input from Android (Hex): %x %x %x %x\n", data[0], data[1], data[2], data[3])

	} else if InjectedConfigs != "" {
		decoded, err := base64.StdEncoding.DecodeString(InjectedConfigs)
		if err != nil { return }
		data = decoded
	} else { return }

	mask, _ := hex.DecodeString(InjectedPrivateKey)
	for i := 0; i < len(data); i++ {
		data[i] ^= mask[i%len(mask)]
	}
    
//    fmt.Printf("VAY_DEBUG: XOR Result (Hex): %x %x %x %x\n", data[0], data[1], data[2], data[3])
	parseConfigData(data)
}


// Helper to handle the JSON unmarshaling cleanly
func parseConfigData(data []byte) {
	var wrapper ConfigWrapper
	if err := json.Unmarshal(data, &wrapper); err == nil && len(wrapper.Configs) > 0 {
		defaultConfigs = wrapper.Configs
		currentVersion = wrapper.Version
		currentServerURL = wrapper.ServerURL
		currentSecretKey = wrapper.AppSecretKey
	} else {
		fmt.Println("VAY_DEBUG: Primary JSON Parse Failed:", err)
		
		err2 := json.Unmarshal(data, &defaultConfigs)
		if err2 != nil {
			fmt.Println("VAY_DEBUG: Fallback JSON Parse Failed:", err2)
			
			// Print a snippet of what Go actually sees after decryption
			snippet := string(data)
			if len(snippet) > 100 {
				snippet = snippet[:100] + "..."
			}
			fmt.Printf("VAY_DEBUG: Decrypted payload snippet: \n%s\n", snippet)
		} else {
			currentVersion = 0
			currentServerURL = ""
			currentSecretKey = ""
		}
	}
}

// ensureResolversParsed decrypts the binary and builds the Fake->Real IP dictionary
func ensureResolversParsed() {
	resolverMu.Lock()
	defer resolverMu.Unlock()

	// Only parse if the map is nil
	if defaultDisplayResolvers == nil {

		var data []byte

		// 1. Prioritize OTA updates (Raw bytes from Android .dat)
		if len(RuntimeResolvers) > 0 {
			data = make([]byte, len(RuntimeResolvers))
			copy(data, RuntimeResolvers)
		} else if InjectedResolvers != "" {
			// 2. Fallback to build-time injected Base64 string (.bin)
			decoded, err := base64.StdEncoding.DecodeString(InjectedResolvers)
			if err == nil {
				data = decoded
			} else {
				data = []byte(InjectedResolvers) // Raw string fallback
			}
		} else {
			return // Nothing to parse
		}

		// Decrypt the data
		mask, err := hex.DecodeString(InjectedPrivateKey)
		if err == nil && len(mask) > 0 {
			for i := 0; i < len(data); i++ {
				data[i] ^= mask[i%len(mask)]
			}
		}

		var entries []struct {
			Name         string `json:"name"`
			Resolver     string `json:"resolver"`
			RandResolver string `json:"rand_resolver"`
		}

		if err := json.Unmarshal(data, &entries); err == nil {
			defaultDisplayResolvers = make(map[string]string)
			realIpMap = make(map[string]string)

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

						// Build the secure dictionary using RAW IPs (No ports)
						realIpMap[fakeClean] = realClean

						// Append :53 to the Fake IP for the Android UI
						displayFake = append(displayFake, fakeClean+":53")
					}

					defaultDisplayResolvers[entry.Name] = strings.Join(displayFake, ",")
				}
			}
		} else {
			// Initialize empty maps so it doesn't crash on invalid JSON
			defaultDisplayResolvers = make(map[string]string)
			realIpMap = make(map[string]string)
		}
	}
}

func SetDefaultConfigs(data []byte) {
	configMu.Lock()
	defer configMu.Unlock()

	// 1. Memory Safety: Copy the data from the Java/Kotlin bridge
	// This ensures Go owns its own copy of the bits.
	RuntimeConfigs = make([]byte, len(data))
	copy(RuntimeConfigs, data)

	// 2. The Cache Buster: Clear the existing memory
	// This forces ensureParsed() to run the next time the app needs a config.
	defaultConfigs = nil 

//	if len(data) > 0 {
//		fmt.Printf("VAY_DEBUG: Kotlin just handed Go: %d bytes\n", len(data))
//	}
}

// SetDefaultConfigs is called by Android to inject the downloaded binary array
/*
func SetDefaultConfigs2(data []byte) {
	configMu.Lock()
	RuntimeConfigs = data
	defaultConfigs = nil // Force re-parse next time ensureParsed is called
	configMu.Unlock()
}
// SetDefaultResolvers is called by Android to inject the downloaded binary array
func SetDefaultResolvers2(data []byte) {
	resolverMu.Lock()
	RuntimeResolvers = data
	defaultDisplayResolvers = nil // Force re-parse
	realIpMap = nil
	resolverMu.Unlock()
}*/

func SetDefaultResolvers(data []byte) {
	resolverMu.Lock()
	defer resolverMu.Unlock()

	// 1. Deep Copy: Move data from Java memory to Go memory
	// This is the "Ghost Buster" that prevents memory corruption
	RuntimeResolvers = make([]byte, len(data))
	copy(RuntimeResolvers, data)

	// 2. Clear Caches: Force the engine to re-parse the new data
	defaultDisplayResolvers = nil 
	realIpMap = nil

/*	if len(data) > 0 {
		fmt.Printf("VAY_DEBUG: Resolvers updated. Received %d bytes\n", len(data))
.
		if len(data) >= 4 {
			fmt.Printf("VAY_DEBUG: Resolver Raw Hex: %x %x %x %x\n", 
                data[0], data[1], data[2], data[3])
		}
	}*/
}

// GetDefaultConfigDisplayResolvers returns the SAFE (fake) IPs for Android to display
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

// GetRealResolver takes any IP. If it is a fake 10.255.x.x IP, it returns the real one.
// If it is a manually typed IP (like 8.8.8.8), it just returns it unharmed.
func GetRealResolver(inputIP string) string {
	ensureResolversParsed()

	resolverMu.Lock()
	defer resolverMu.Unlock()

	if realIpMap != nil && len(realIpMap) > 0 {
		if realIP, exists := realIpMap[inputIP]; exists {
			return realIP
		}
	}

	// Fallback: If it's not in our fake map, just return what they gave us
	return inputIP
}

// --- The rest are your standard getters ---

func GetAppSecretKey() string {
	ensureParsed()
	return currentSecretKey
}

func GetUpdateServerURL() string {
	ensureParsed()
	return currentServerURL
}

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

func GetDefaultConfigDomain(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Domain
}

func GetDefaultConfigPubkey(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Pubkey
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

func GetDefaultConfigUser(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].User
}

func GetDefaultConfigPass(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Pass
}
