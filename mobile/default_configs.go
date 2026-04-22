package mobile

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"sync"
//	"fmt"
)

// InjectedConfigs will be populated via -ldflags in CI or SetDefaultConfigs locally
var InjectedConfigs string

// InjectedResolvers will be populated via SetDefaultResolvers from Android
var InjectedResolvers string

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

// ensureParsed checks if the slice needs to be populated.
func ensureParsed() {
	configMu.Lock()
	defer configMu.Unlock()

	if len(defaultConfigs) == 0 && InjectedConfigs != "" {
		data, err := base64.StdEncoding.DecodeString(InjectedConfigs)
		if err != nil {
			if json.Valid([]byte(InjectedConfigs)) {
				var wrapper ConfigWrapper
				if err := json.Unmarshal([]byte(InjectedConfigs), &wrapper); err == nil && len(wrapper.Configs) > 0 {
					defaultConfigs = wrapper.Configs
					currentVersion = wrapper.Version
					currentServerURL = wrapper.ServerURL
					currentSecretKey = wrapper.AppSecretKey
				} else {
					_ = json.Unmarshal([]byte(InjectedConfigs), &defaultConfigs)
					currentVersion = 0
					currentServerURL = ""
					currentSecretKey = ""
				}
			}
			return
		}

		mask, err := hex.DecodeString(InjectedPrivateKey)
		if err == nil && len(mask) > 0 {
			for i := 0; i < len(data); i++ {
				data[i] ^= mask[i%len(mask)]
			}
		}

		var wrapper ConfigWrapper
		if err := json.Unmarshal(data, &wrapper); err == nil && len(wrapper.Configs) > 0 {
			defaultConfigs = wrapper.Configs
			currentVersion = wrapper.Version
			currentServerURL = wrapper.ServerURL
			currentSecretKey = wrapper.AppSecretKey
		} else {
			_ = json.Unmarshal(data, &defaultConfigs)
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
		
		if InjectedResolvers == "" {
//			fmt.Println("VAY_DEBUG_PARSE: [CRITICAL] InjectedResolvers string is EMPTY! Android did not pass the data.")
			return
		}

		//fmt.Printf("VAY_DEBUG_PARSE: Starting parse. Base64 length: %d\n", len(InjectedResolvers))

		data, err := base64.StdEncoding.DecodeString(InjectedResolvers)
		if err != nil {
//			fmt.Printf("VAY_DEBUG_PARSE: Base64 Decode Failed: %v. Falling back to raw string.\n", err)
			data = []byte(InjectedResolvers)
		} else {
			mask, err := hex.DecodeString(InjectedPrivateKey)
			if err == nil && len(mask) > 0 {
				for i := 0; i < len(data); i++ {
					data[i] ^= mask[i%len(mask)]
				}
				
				// --- DIAGNOSTIC DUMP: View the fully decrypted JSON text ---
				//fmt.Printf("VAY_DEBUG_PARSE: [SUCCESS] Decrypted Text:\n%s\n", string(data))
				
			} else {
//				fmt.Printf("VAY_DEBUG_PARSE: Mask Decode Error: %v\n", err)
			}
		}

		var entries []struct {
			Name         string `json:"name"`
			Resolver     string `json:"resolver"`
			RandResolver string `json:"rand_resolver"`
		}

		if err := json.Unmarshal(data, &entries); err == nil {
//			fmt.Printf("VAY_DEBUG_PARSE: [SUCCESS] JSON Parsed! Found %d configs.\n", len(entries))
			
			defaultDisplayResolvers = make(map[string]string)
			realIpMap = make(map[string]string)

			for _, entry := range entries {
				if entry.Resolver != "" && entry.RandResolver != "" {
					realIps := strings.Split(entry.Resolver, ",")
					fakeIps := strings.Split(entry.RandResolver, ",")

					// --- NEW: Array to hold the port-appended IPs for Android ---
					var displayFake []string

					for i := 0; i < len(fakeIps) && i < len(realIps); i++ {
						fakeClean := strings.TrimSpace(fakeIps[i])
						realClean := strings.TrimSpace(realIps[i])
						
						if fakeClean == "" || realClean == "" {
							continue
						}

						// 1. Build the secure dictionary using RAW IPs (No ports)
						realIpMap[fakeClean] = realClean

						// 2. Append :53 to the Fake IP for the Android UI
						displayFake = append(displayFake, fakeClean+":53")
					}
					
					// Save the formatted Fake IPs so Kotlin can fetch them
					defaultDisplayResolvers[entry.Name] = strings.Join(displayFake, ",")
				}
			}
			
//			fmt.Printf("VAY_DEBUG_PARSE: Built realIpMap with %d total IP translations.\n", len(realIpMap))
		} else {
//			fmt.Printf("VAY_DEBUG_PARSE: [ERROR] JSON Unmarshal Failed: %v\n", err)
			

//			snippet := string(data)
//			if len(snippet) > 100 {
//				snippet = snippet[:100] + "..."
//			}
//			fmt.Printf("VAY_DEBUG_PARSE: Data snippet: %s\n", snippet)

			// Initialize empty maps so it doesn't crash
			defaultDisplayResolvers = make(map[string]string)
			realIpMap = make(map[string]string)
		}
	}
}

// SetDefaultResolvers is called by Android to inject the downloaded binary
func SetDefaultResolvers(b64Str string) {
	resolverMu.Lock()
	InjectedResolvers = b64Str
	defaultDisplayResolvers = nil // Force re-parse
	realIpMap = nil
	resolverMu.Unlock()
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
		} else {
			// DIAGNOSTIC DUMP: Shows up in Logcat if a specific IP fails to map
//			fmt.Printf("VAY_DEBUG_SCAN: [ERROR] %s not in map. Map size is %d\n", inputIP, len(realIpMap))
		}
	} else {
		// CRITICAL DUMP: Shows up if the file wasn't downloaded or JSON parsing failed
//		fmt.Println("VAY_DEBUG_SCAN: [CRITICAL] realIpMap is completely EMPTY or NIL!")
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

func SetDefaultConfigs(jsonStr string) {
	configMu.Lock()
	InjectedConfigs = jsonStr
	defaultConfigs = nil
	configMu.Unlock()
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
