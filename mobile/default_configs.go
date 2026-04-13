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

var (
	defaultConfigs []DefaultConfig
	configMu       sync.Mutex // Use a Mutex instead of sync.Once to allow re-parsing
)

// ensureParsed checks if the slice needs to be populated.
// This is called automatically by every getter.
func ensureParsed() {
	configMu.Lock()
	defer configMu.Unlock()

	// Only attempt to parse if the slice is empty and we have the necessary data
	if len(defaultConfigs) == 0 && InjectedConfigs != "" {
		
		// 1. Decode the Base64 InjectedConfigs
		data, err := base64.StdEncoding.DecodeString(InjectedConfigs)
		if err != nil {
			// If Base64 decode fails, check if it's raw JSON (for local dev)
			if json.Valid([]byte(InjectedConfigs)) {
				_ = json.Unmarshal([]byte(InjectedConfigs), &defaultConfigs)
			}
			return
		}

		// 2. Prepare the Masking Key (InjectedPrivateKey)
		// We decode the hex string into raw bytes to use for XOR
		mask, err := hex.DecodeString(InjectedPrivateKey)
		
		// 3. XOR Decode if a valid mask exists
		if err == nil && len(mask) > 0 {
			for i := 0; i < len(data); i++ {
				// XOR each byte of data with a byte from the private key
				data[i] ^= mask[i%len(mask)]
			}
		}

		// 4. Unmarshal the resulting JSON
		_ = json.Unmarshal(data, &defaultConfigs)
	}
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
