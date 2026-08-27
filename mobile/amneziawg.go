package mobile

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"sync/atomic"

	"github.com/phoenixdnsvpn/phoenix-vpn/vaydns/client"
	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// =====================================================================
// EXPANDED CONFIGURATION STRUCT
// =====================================================================
type WireguardConfig struct {
	ClientPrivateKey string `json:"client_private_key"`
	ServerPublicKey  string `json:"server_public_key"`
	InternalIP       string `json:"internal_ip"` 
	WgPort           string `json:"wg_port"`
	Reserved         []uint8 `json:"reserved"`  
	// NEW: Dynamic DPI Evasion Parameters
	Jc               int    `json:"jc"`
	Jmin             int    `json:"jmin"`
	Jmax             int    `json:"jmax"`
	S1               int    `json:"s1"`
	S2               int    `json:"s2"`
	H1               uint32 `json:"h1"`
	H2               uint32 `json:"h2"`
	H3               uint32 `json:"h3"`
	H4               uint32 `json:"h4"`
}

func getWgReservedBytes(index int64) []uint8 {
	ensureParsed()
	if index < 0 || index >= int64(len(defaultConfigs)) {
		return []uint8{0, 0, 0}
	}
	if len(defaultConfigs[index].Reserved) == 3 {
		return defaultConfigs[index].Reserved
	}
	// Default empty bytes if not a WARP profile
	return []uint8{0, 0, 0}
}

// Helper to extract Amnezia DPI parameters safely with defaults
func getAwgParams(index int64) (jc, jmin, jmax, s1, s2 int, h1, h2, h3, h4 uint32) {
	ensureParsed()
	
	// Standard Fallback Defaults
	jc, jmin, jmax = 4, 40, 70
	s1, s2 = 0, 0
	h1, h2, h3, h4 = 123456789, 987654321, 112233445, 556677889

	if index >= 0 && index < int64(len(defaultConfigs)) {
		cfg := defaultConfigs[index]
		if cfg.Jc != 0 { jc = cfg.Jc }
		if cfg.Jmin != 0 { jmin = cfg.Jmin }
		if cfg.Jmax != 0 { jmax = cfg.Jmax }
		if cfg.S1 != 0 { s1 = cfg.S1 }
		if cfg.S2 != 0 { s2 = cfg.S2 }
		if cfg.H1 != 0 { h1 = cfg.H1 }
		if cfg.H2 != 0 { h2 = cfg.H2 }
		if cfg.H3 != 0 { h3 = cfg.H3 }
		if cfg.H4 != 0 { h4 = cfg.H4 }
	}
	return
}

// =====================================================================
// DYNAMIC KEY OVERRIDE SYSTEM
// =====================================================================
type DynamicAWGKeys struct {
	ServerIP         string `json:"server_ip"`
	ServerPublicKey  string `json:"server_public_key"`
	ClientPrivateKey string `json:"client_private_key"`
	InternalIP       string `json:"internal_ip"`
	WgPort           string `json:"wg_port"`
}

// getDynamicAWGKeys checks the secure vault for dynamically downloaded API keys
func getDynamicAWGKeys() *DynamicAWGKeys {
	configMu.Lock()
	dir := vaultStorageDir
	configMu.Unlock()

	if dir == "" {
		return nil
	}

	path := filepath.Join(dir, "amneziawg_keys.json")
	data, err := os.ReadFile(path)
	if err != nil {
		return nil 
	}

	// Decrypt the payload before parsing (Safely falls back if plaintext)
	decryptedData := DecryptText(string(data))

	var keys DynamicAWGKeys
	if err := json.Unmarshal([]byte(decryptedData), &keys); err == nil {
		return &keys
	}
	return nil
}

// =====================================================================
// CUSTOM ANDROID TUN WRAPPER
// =====================================================================
type androidTun struct {
	file   *os.File
	mtu    int
	events chan tun.Event
}

func (t *androidTun) File() *os.File { return t.file }

func (t *androidTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 {
		return 0, nil
	}
	n, err := t.file.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	atomic.AddUint64(&client.ProxyTxBytes, uint64(n))
	return 1, nil
}

func (t *androidTun) Write(bufs [][]byte, offset int) (int, error) {
	for i, buf := range bufs {
		n, err := t.file.Write(buf[offset:])
		if err != nil {
			return i, err
		}
		atomic.AddUint64(&client.ProxyRxBytes, uint64(n))
	}
	return len(bufs), nil
}

func (t *androidTun) MTU() (int, error) { return t.mtu, nil }

func (t *androidTun) Name() (string, error) { return "tun0", nil }

func (t *androidTun) Events() <-chan tun.Event { return t.events }

func (t *androidTun) Close() error {
	if t.events != nil {
		close(t.events)
	}
	return t.file.Close()
}

func (t *androidTun) BatchSize() int { return 1 }

var activeAwgDevice *device.Device

// =====================================================================

// startAmneziaWgNativeEngine binds AmneziaWG directly to the Android TUN File Descriptor
func startAmneziaWgNativeEngine(fd int, configIndex int64, globalDnsServer string, getServerIpFromDomain bool) error {

	// 1. Resolve Server IP and Port
	serverIP := getServerIP(configIndex, globalDnsServer, getServerIpFromDomain)
	serverPort := getWgPort(configIndex)

	// 2. Fetch Keys
	b64PrivKey := getWgSecretKey(configIndex)
	b64PubKey := getWgPublicKey(configIndex)

	// 3. Extract Dynamic DPI Evasion Parameters
	jc, jmin, jmax, s1, s2, h1, h2, h3, h4 := getAwgParams(configIndex)

	// 4. APPLY DYNAMIC OVERRIDES (Intercept API Keys)
	dynamicKeys := getDynamicAWGKeys()
	if dynamicKeys != nil {
		log.Println("VAY_DEBUG: Overriding AmneziaWG parameters with downloaded dynamic keys.")
		if dynamicKeys.ServerIP != "" {
			serverIP = dynamicKeys.ServerIP
		}
		if dynamicKeys.ServerPublicKey != "" {
			b64PubKey = dynamicKeys.ServerPublicKey
		}
		if dynamicKeys.ClientPrivateKey != "" {
			b64PrivKey = dynamicKeys.ClientPrivateKey
		}
		if dynamicKeys.WgPort != "" {
			if p, err := strconv.Atoi(dynamicKeys.WgPort); err == nil {
				serverPort = p
			}
		} else if dynamicKeys.ServerIP != "" {
			serverPort = 443 
		}
	}

	if b64PrivKey == "" || b64PubKey == "" {
		return fmt.Errorf("missing private or public key for AmneziaWG")
	}

	// AWG requires HEX encoded keys
	privHex, err := base64ToHex(b64PrivKey)
	if err != nil {
		return fmt.Errorf("invalid private key formatting: %v", err)
	}
	pubHex, err := base64ToHex(b64PubKey)
	if err != nil {
		return fmt.Errorf("invalid public key formatting: %v", err)
	}

	// 5. Wrap the Android TUN FD
	file := os.NewFile(uintptr(fd), "tun")
	tunDev := &androidTun{
		file:   file,
		mtu:    1280, 
		events: make(chan tun.Event, 1),
	}
	tunDev.events <- tun.EventUp

	// 6. Initialize Cryptographic Device
	logger := device.NewLogger(device.LogLevelError, "[AWG] ")
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)

	mu.Lock()
	activeAwgDevice = dev
	mu.Unlock()

	// 7. Construct the UAPI Configuration String Dynamically
	uapiConfig := fmt.Sprintf(`private_key=%s
listen_port=0
jc=%d
jmin=%d
jmax=%d
s1=%d
s2=%d
h1=%d
h2=%d
h3=%d
h4=%d
public_key=%s
endpoint=%s:%d
allowed_ip=0.0.0.0/0
allowed_ip=::/0
persistent_keepalive_interval=25
`, privHex, jc, jmin, jmax, s1, s2, h1, h2, h3, h4, pubHex, serverIP, serverPort)

	// 8. Apply Configuration
	err = dev.IpcSet(uapiConfig)
	if err != nil {
		dev.Close()
		return fmt.Errorf("failed to apply AWG UAPI config: %v", err)
	}

	// 9. Bring UP Interface
	err = dev.Up()
	if err != nil {
		dev.Close()
		return fmt.Errorf("failed to bring up AWG device: %v", err)
	}

	log.Printf("VAY_DEBUG: Native AmneziaWG TUN Engine started successfully on %s:%d.", serverIP, serverPort)

	// 10. Bind lifecycle shutdown
	go func() {
		<-activeCtx.Done()
		log.Printf("VAY_DEBUG: Shutting down native AmneziaWG Engine...")
		dev.Close()
	}()

	return nil
}

// Helper: Base64 -> Hex
func base64ToHex(b64 string) (string, error) {
	decoded, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(decoded), nil
}

// StopAmneziaWgEngine safely unbinds the TUN interface
func StopAmneziaWgEngine() {
	mu.Lock()
	if activeAwgDevice != nil {
		activeAwgDevice.Close()
		activeAwgDevice = nil
	}
	mu.Unlock()
}
