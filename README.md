# VayDNS VPN for Android

[English] | [**فارسی**](README.fa.md)

VayDNS is a high-performance DNS-based tunneling solution. Originally developed for Linux environments to facilitate robust bypassing of internet filtering, this project adapts the core technology specifically for Android devices. This mobile implementation integrates three powerful Go-based technologies to provide a full-device VPN experience even in highly restrictive network environments.

- **vaydns**: The "Tunnel" layer. It encapsulates data into DNS queries (DoH, DoT, or UDP) to bypass firewalls and deep packet inspection (DPI).
- **Tun2Socks**: The "VPN" layer. It captures all IP traffic from the Android TUN interface and transparently forwards it through the VayDNS tunnel.
- **f35**: The E2E resolver scanner. To proble and rapidly measuring the latency and reliability of DNS resolvers across a network.

## Key Features
- **Encrypted Configuration:** Supports pre-configured "Default Servers" that are protected by a native C++ security layer, preventing the leakage of private infrastructure details in public builds.

- **CI/CD Ready:** Automated build pipeline via GitHub Actions that injects server configurations through encrypted secrets.

- **Real-time Latency Scanning:** Built-in scanner to identify the fastest local DNS resolvers for optimal tunnel performance.

- **Multi-Architecture Support:** Native binaries optimized for both arm64-v8a and armeabi-v7a devices.

## Prerequisites

|      Tool          |Version                          |Purpose                         |
|----------------|-------------------------------|-----------------------------|
|Go|`1.25.8+`            |Core logic and cross-compilation            |
|Android SDK          |`API 24+`            |Android system integration           |
|Android NDK          |r27d |Compiling Go for ARM64|
|Java (JDK)          |`17`|Required for Gradle / Android Studio|

##  1. Go Installation (Linux/AMD64)
### Step 1: Download and installation

```
# Download and install the official Go binary:
wget https://go.dev/dl/go1.25.8.linux-amd64.tar.gz 
# Remove old versions and extract (requires sudo) 
sudo rm -rf /usr/local/go
rm -rf ~/go
sudo tar -C /usr/local -xzf go1.25.8.linux-amd64.tar.gz
```
To ensure compatibility with the mobile binding tools, install the latest Go binary distribution.

### Step 2: Update Environment

Add these lines to your `~/.bashrc`:
```
export PATH=$PATH:/usr/local/go/bin
export GOPATH=$HOME/go
export PATH=$PATH:$GOPATH/bin
```
Apply changes: `source ~/.bashrc`

## 2. Android Environment Setup
### Step 1: Install SDK & NDK
If you are on a headless server, use the `sdkmanager`. Otherwise I recommend to use Android Studio
```
# Install specific platform and NDK version 
sdkmanager "platforms;android-24" "build-tools;34.0.0" "ndk;27.2.12479018"
```
### Android Studio Setup
* In Android Studio:

- Go to **Settings > Languages & Frameworks > Android SDK**.

- Select the **SDK Tools** tab.

- Check **Show Package Details** (bottom right).

- Under **NDK (Side by side)**, ensure you have a version installed (e.g., 27.2.x for LTS or the latest available).

- Under **SDK Platforms**, ensure **Android 7.0 (API 24)** or higher is installed.

### Step 2: Set Android Variables
Add these to your `~/.bashrc`:
```
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH
```
## 3. Dependency & Toolchain Initialization

To prevent "package not found" errors during the build, we "pin" the mobile tools using a `tools.go` file.
### Step 1: Create `tools.go`
Navigate to the mobile directory and ensure the toolchain is pinned.
Create a file named 'mobile/tools.go' to ensure the Go compiler caches the mobile binding tools:
```
// +build tools 
package mobile

import (
        _ "golang.org/x/mobile/bind"
        _ "golang.org/x/mobile/cmd/gobind"
        _ "golang.org/x/mobile/cmd/gomobile"
)
```
```
# Fetch core libraries
cd vaydns-vpn/mobile
# Sync dependencies
go mod tidy

# Install tools to $GOPATH/bin
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

# Initialize the cross-compilation environment 
gomobile init
```
## 4. Building the Project
### Generate the Android Library (.aar)
```
# Build for ARM64
cd mobile
gomobile bind -v \
    -target=android/arm64 \
    -androidapi 24 \
    -ldflags="-s -w" \
    -trimpath \
    -o ../vaydns-arm64.aar \
    .

# Move the library to the Android project libs folder
cp vaydns-arm64.aar android/app/libs/
```
### Build the Final APK (CLI Method)
You may use Android Studio or gradlew
```
cd android
./gradlew assembleRelease
```

## Troubleshooting
* Missing Bind Package: If you get an error saying golang.org/x/mobile/bind is not found, ensure you have run go mod tidy inside the mobile/ folder after creating tools.go.
* NDK Not Found: Ensure ANDROID_NDK_HOME points to the specific version folder (e.g., ndk/android-ndk-r27d) rather than just the root ndk folder.

## Acknowledgments

This project would not be possible without the incredible work of the following open-source repositories:

-   **[vaydns](https://github.com/net2share/vaydns)**: For the core DNS tunneling engine and sophisticated transport layers.
    
-   **[tun2socks](https://github.com/xjasonlyu/tun2socks)**: For the high-performance implementation of TUN-to-SOCKS conversion, enabling system-wide VPN functionality.

-   **[f35](https://github.com/nxdp/f35)**: For the End-to-End DNS Resolver Scanner

## Disclaimer & License

### Disclaimer

This software is provided "as is", without warranty of any kind. The authors are not responsible for any misuse, data loss, or legal consequences resulting from the use of this software. Users are responsible for complying with local laws and regulations regarding VPN and tunneling usage.
### License

This project is licensed under the **MIT License**.

