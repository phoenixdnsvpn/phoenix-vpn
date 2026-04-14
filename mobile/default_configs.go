package mobile

import (
"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"sync"
)

// InjectedConfigs will be populated via -ldflags in CI or SetDefaultConfigs locally
var InjectedConfigs string

type DefaultConfig struct {
	Name   string `json:"name"`
	Domain string `json:"domain"`
	Pubkey string `json:"pubkey"`
    RecordType   string `json:"recordType"`   
	IdleTimeout  string `json:"idleTimeout"`  
	KeepAlive    string `json:"keepAlive"`    	
	ClientIdSize    int    `json:"clientIdSize"`
	DnsttCompatible bool   `json:"dnsttCompatible"`
	Protocol        string `json:"protocol"` 
	UseSshKey       bool   `json:"useSshKey"`
	User            string `json:"user"`
	Pass            string `json:"pass"`
}

type ConfigWrapper struct {
	Version int             `json:"version"`
	ServerURL string        `json:"serverURL"`
	AppSecretKey string     `json:"appSecretKey"`
	Configs []DefaultConfig `json:"configs"`
}

var (
	defaultConfigs   []DefaultConfig
	currentVersion   int
	currentServerURL string
	currentSecretKey string
	configMu         sync.Mutex
)

// ensureParsed checks if the slice needs to be populated.
// This is called automatically by every getter.
func ensureParsed() {
	configMu.Lock()
	defer configMu.Unlock()

	if len(defaultConfigs) == 0 && InjectedConfigs != "" {
		data, err := base64.StdEncoding.DecodeString(InjectedConfigs)
		if err != nil {
			// Local raw JSON fallback
			if json.Valid([]byte(InjectedConfigs)) {
				var wrapper ConfigWrapper
				if err := json.Unmarshal([]byte(InjectedConfigs), &wrapper); err == nil && len(wrapper.Configs) > 0 {
					defaultConfigs = wrapper.Configs
					currentVersion = wrapper.Version
					currentServerURL = wrapper.ServerURL
					currentSecretKey = wrapper.AppSecretKey // Extract here!
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
			currentSecretKey = wrapper.AppSecretKey // Extract here!
		} else {
			_ = json.Unmarshal(data, &defaultConfigs)
			currentVersion = 0
			currentServerURL = ""
			currentSecretKey = ""
		}
	}
}

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
	defaultConfigs = nil // Resetting to nil forces ensureParsed to re-run
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
		return "TXT" // Default fallback
	}
	if defaultConfigs[index].RecordType == "" {
		return "TXT"
	}
	return defaultConfigs[index].RecordType
}

// GetDefaultConfigIdleTimeout returns the IdleTimeout for the given index
func GetDefaultConfigIdleTimeout(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "10s" // Default fallback
	}
	if defaultConfigs[index].IdleTimeout == "" {
		return "10s"
	}
	return defaultConfigs[index].IdleTimeout
}

// GetDefaultConfigKeepAlive returns the KeepAlive for the given index
func GetDefaultConfigKeepAlive(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return "2s" // Default fallback
	}
	if defaultConfigs[index].KeepAlive == "" {
		return "2s"
	}
	return defaultConfigs[index].KeepAlive
}

// GetDefaultConfigClientIdSize returns the Client ID Size (int)
func GetDefaultConfigClientIdSize(index int64) int64 {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return 2 // Default fallback
	}
	if defaultConfigs[index].ClientIdSize == 0 {
		return 2
	}
	return int64(defaultConfigs[index].ClientIdSize)
}

// GetDefaultConfigDnsttCompatible returns the DNSTT Compatibility (bool)
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
		return "socks" // Default fallback
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

// GetDefaultConfigUser returns the User string
func GetDefaultConfigUser(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].User
}

// GetDefaultConfigPass returns the Password string
func GetDefaultConfigPass(index int64) string {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return ""
	}
	return defaultConfigs[index].Pass
}
