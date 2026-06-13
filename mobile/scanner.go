package mobile

// Provide the interface so Kotlin's Gomobile bindings can still compile
type CFScannerCallback interface {
	OnUpdate(result string)
}

func StopCloudflareScanner() {}

// Return a graceful error so the Android UI handles it cleanly if triggered
func RunCloudflareScanner(isDefault bool, configIndex int64, requestedCount int64, cb CFScannerCallback) string {
	return "ERROR|Cloudflare Scanner is only available in the official VayDNS release."
}
