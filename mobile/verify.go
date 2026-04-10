package mobile

import (
	"encoding/hex"
	"strings"
	"golang.org/x/crypto/curve25519"
)

// InjectedPrivateKey is populated at build time via:
// -ldflags="-X 'github.com/Starling226/vaydns-vpn/mobile.InjectedPrivateKey=HEX_HASH_HERE'"
// This variable holds the 64-character hex string derived from your passphrase.
var InjectedPrivateKey string

/**
 * CheckVerification:
 * Compares a user-provided Public Key against the one derived from 
 * the hidden internal Private Key.
 */
func CheckVerification(pastedPubKey string) bool {
	// 1. If no key was injected, it's an unverified/community build
	if InjectedPrivateKey == "" {
		return false
	}

	// 2. Decode the injected Hex Private Key into bytes
	privBytes, err := hex.DecodeString(InjectedPrivateKey)
	if err != nil || len(privBytes) != 32 {
		// This handles cases where the injection was malformed
		return false
	}

	// 3. Derive the "Official" Public Key from the internal Private Key
	// Curve25519 math: PublicKey = PrivateKey * Basepoint
	officialPubKey, err := curve25519.X25519(privBytes, curve25519.Basepoint)
	if err != nil {
		return false
	}

	// 4. Convert the result back to Hex for comparison
	officialHex := hex.EncodeToString(officialPubKey)

	// 5. Cleanup the user input (trim spaces and force lowercase)
	cleanPasted := strings.TrimSpace(strings.ToLower(pastedPubKey))
	
	// 6. The Moment of Truth
	return cleanPasted == strings.ToLower(officialHex)
}

/**
 * GetBuildStatus:
 * Returns a simple string describing the build type.
 */
func GetBuildStatus() string {
	if InjectedPrivateKey == "" {
		return "Community Build"
	}
	return "Official Release"
}
