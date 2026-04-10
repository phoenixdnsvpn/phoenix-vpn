package mobile

import (
	"encoding/json"
	"sync"
)

// InjectedConfigs will be populated via -ldflags in CI or SetDefaultConfigs locally
var InjectedConfigs string

type DefaultConfig struct {
	Name   string `json:"name"`
	Domain string `json:"domain"`
	Pubkey string `json:"pubkey"`
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

	// If the slice is empty but we have a JSON string, parse it.
	if len(defaultConfigs) == 0 && InjectedConfigs != "" {
		_ = json.Unmarshal([]byte(InjectedConfigs), &defaultConfigs)
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
