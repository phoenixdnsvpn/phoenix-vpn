# VayDNS VPN for Android

VayDNS is a high-performance DNS-based tunneling solution. While originally developed for Linux server environments to facilitate robust network monitoring and connectivity, this project adapts the core technology specifically for Android devices. This mobile implementation integrates two powerful Go-based technologies to provide a full-device VPN experience even in highly restrictive network environments

- **VayDNS**: The "Tunnel" layer. It encapsulates data into DNS queries (DoH, DoT, or UDP) to bypass firewalls and deep packet inspection (DPI).
- **Tun2Socks**: The "VPN" layer. It captures all IP traffic from the Android TUN interface and transparently forwards it through the VayDNS tunnel.

## Prerequisites

|      Tool          |Version                          |Purpose                         |
|----------------|-------------------------------|-----------------------------|
|Single backticks|`1.25.8+`            |Core logic and cross-compilation            |
|Android SDK          |`API 24+`            |Android system integration           |
|Android NDK          |r26b (26.1.10909125)|Compiling Go for ARM64|
|Java (JDK)          |`17`|Required for Gradle / Android Studio|

##  1. Go Installation (Linux/AMD64)
```
# Download the official binary 
wget https://go.dev/dl/go1.25.8.linux-amd64.tar.gz 
# Remove old versions and extract (requires sudo) 
sudo rm -rf /usr/local/go && sudo tar -C /usr/local -xzf go1.25.8.linux-amd64.tar.gz
```
To ensure compatibility with the mobile binding tools, install the latest Go binary distribution.

## Step 2: Update Environment

Add these lines to your `~/.bashrc`:
```
export GOROOT=/usr/local/go
export GOPATH=$HOME/go
export PATH=$PATH:$GOROOT/bin:$GOPATH/bin
```
Apply changes: `source ~/.bashrc`

## 2. Android Environment Setup
### Step 1: Install SDK & NDK
If you are on a headless server, use the `sdkmanager`.
```
# Install specific platform and NDK version 
sdkmanager "platforms;android-24" "build-tools;34.0.0" "ndk;26.1.10909125"
```
### Step 2: Set Android Variables
Add these to your `~/.bashrc`:
```
export ANDROID_HOME=$HOME/Android/Sdk 
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125 
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```
## 3. Dependency & Toolchain Initialization

To prevent "package not found" errors during the build, we "pin" the mobile tools using a `tools.go` file.
### Step 1: Create `tools.go`
Create a file named `tools.go` in your project root:
```
// +build tools 
package tools 
import ( 
_ "golang.org/x/mobile/bind" 
_ "golang.org/x/mobile/cmd/gobind" 
_ "golang.org/x/mobile/cmd/gomobile" 
)
```
```
# Fetch core libraries 
go get github.com/net2share/vaydns@v0.2.6 
go get github.com/xjasonlyu/tun2socks/v2@v2.6.0 
go get golang.org/x/mobile/bind 

# Build binaries to your GOPATH 
go build -o $(go env GOPATH)/bin/gobind golang.org/x/mobile/cmd/gobind 
go build -o $(go env GOPATH)/bin/gomobile golang.org/x/mobile/cmd/gomobile 
# Initialize the cross-compilation environment 
gomobile init
```
## 4. Building the Project
### Generate the Android Library (.aar)
```
cd android
./gradlew assembleRelease
```
### Build the Final APK (CLI Method)
```
cd android
./gradlew assembleRelease
```

## Disclaimer & License

### Disclaimer

This software is provided "as is", without warranty of any kind. The authors are not responsible for any misuse, data loss, or legal consequences resulting from the use of this software. Users are responsible for complying with local laws and regulations regarding VPN and tunneling usage.
### License

This project is licensed under the **MIT License**.

## Acknowledgments

This project would not be possible without the incredible work of the following open-source repositories:

-   **[VayDNS](https://github.com/net2share/vaydns)**: For the core DNS tunneling engine and sophisticated transport layers.
    
-   **[tun2socks](https://github.com/xjasonlyu/tun2socks)**: For the high-performance implementation of TUN-to-SOCKS conversion, enabling system-wide VPN functionality.
