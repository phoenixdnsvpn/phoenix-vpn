# Announcement
We are excited to announce that our Android client, formerly known as VayDNS, has officially been rebranded as Phoenix VPN. This name change reflects our recent architectural expansion and our deep respect for the original developers. The "VayDNS" brand and technology are the proprietary creation of the net2share group (https://github.com/net2share/vaydns). Because our client has evolved to include extensive support for high-speed direct protocols—which fall outside the scope of the core VayDNS technology—we felt it was necessary to adopt a new identity. By transitioning to Phoenix VPN, we ensure that all credit and branding for VayDNS remain rightfully reserved for the net2share group. Moving forward, the highly resilient VayDNS protocol will proudly remain the backbone of Phoenix VPN, while we continue to improve our support for advanced direct protocols alongside it.

# Phoenix VPN Android Client

[English] | [**فارسی**](README.fa.md)

Copyright © 2026 The Phoenix (formerly VayDNS) VPN Project. Licensed under the **Phoenix VPN Source-Available License**.

Phoenix VPN is a high-performance hybrid DNS-based & direct protocol tunneling solution. VayDNS Originally developed for Linux environments to facilitate robust bypassing of internet filtering, this project adapts the core technology specifically for Android devices. This mobile implementation integrates five powerful Go-based technologies to provide a full-device VPN experience even in highly restrictive network environments.

- **vaydns**: The "DNS Tunnel" backbone. Serving as the foundational technology for extreme censorship evasion, it encapsulates data into DNS queries (DoH, DoT, TCP, or UDP) to seamlessly bypass strict firewalls and deep packet inspection (DPI) when standard internet access is entirely blocked.
- **Xray-core**: The "Native Direct" engine. Integrated for bleeding-edge protocol support, Xray handles our high-speed direct connections natively. By pulling raw IP traffic directly from the Android TUN interface, it powers next-generation connections like REALITY-TCP (with xtls-rprx-vision flow control) and XHTTP with zero fragmentation and maximum throughput.
- **sing-box**: The "Universal Protocol" engine. Acting as our highly versatile secondary core, sing-box provides unmatched routing logic and supports advanced censorship-resistant protocols like Hysteria2, Reality-tcp, VLESS-WS, and VLESS-HTTPUpgrade.
- **Tun2Socks**: The "Network Bridge" layer. Because vaydns and sing-box excel at complex proxy logic rather than raw device routing, Tun2Socks acts as a high-performance bridge. It captures all full-device IP traffic from the Android TUN interface and transparently feeds it into those engines.
- **f35**: The "E2E Scanner" layer. A highly concurrent network probing tool embedded directly in the client. It is used to rapidly measure the latency, TLS handshake validity, and true reliability of global DNS resolvers and direct edge nodes across a restricted network.

### Transparency & Purpose
Phoenix VPN is a transparent, source-available project dedicated to promoting digital freedom and providing secure internet access for users in countries facing heavy internet censorship. The primary objective of this software is to facilitate open communication and information access through DNS tunneling technology. This project is strictly educational and humanitarian in nature; it does not target, exploit, or attack any systems, networks, or infrastructure. The source code is made available for public audit to ensure transparency and trust within the community of users who rely on these tools for safe and restricted-free connectivity.

## Key Features
- **Dedicated Proxy Mode:** Features a built-in local SOCKS5 proxy, allowing users to tunnel specific applications (like Telegram) without routing their entire device through the Android VpnService. Includes accurate, application-isolated traffic monitoring.

- **REALITY-TCP (with Vision):** Integrated native Xray support for the REALITY protocol over TCP, utilizing flagship xtls-rprx-vision flow control. This perfectly masks your traffic as standard TLS handshakes, rendering it virtually indistinguishable to Deep Packet Inspection (DPI) firewalls.

- **XHTTP Multiplexing:** Unlocked bleeding-edge XHTTP protocol support through our native Xray pipeline. By multiplexing streams, it eliminates head-of-line blocking on high-latency routes, unleashing massive throughput on the most restricted networks.

- **Hysteria2 (QUIC/HTTP3):** ntroduced full support for Hysteria2. Powered by custom UDP/QUIC transport layers with "Salamander" obfuscation and precise bandwidth flow controls, it effortlessly punches through aggressively throttled networks for brute-force speed.

- **VLESS over WebSockets (WS):** Added seamless support for VLESS-WS, allowing traffic to be routed and hidden behind massive, trusted Edge networks and CDNs (like Cloudflare) to keep connections alive even when direct server IPs are heavily blacklisted.

- **VLESS HTTPUpgrade Transport:** Implemented the heavily optimized HTTPUpgrade transport for VLESS. This provides a revolutionary, low-latency alternative to standard WebSockets for CDN routing, explicitly pinning ALPN to HTTP/1.1 to bypass unnecessary handshake delays at the edge.

- **Standardized DNST URL Support:**  Now fully compliant with the DNST URL Scheme, enabling seamless configuration sharing across compatible applications.

- **Multi-Domain Server Core:** The `vaydns-server` can now natively listen and route traffic for multiple domains simultaneously on a single instance (Port 53), allowing for seamless domain migrations without dropping existing users.

- **TCP Protocol Support:** Introduced full end-to-end support for plain TCP tunneling and resolving, expanding your options for bypassing restrictive DPI firewalls.

- **Concurrent Multi-Resolver Engine:** The app now intelligently utilizes up to 3 selected resolvers concurrently, improving connection reliability and speed.

- **Light E2E Scanner:** Added a highly optimized "fast-fail" handshake scanner. This bypasses heavy payload checks to rapidly verify raw connectivity, delivering instant Alive/Dead results.

- **Quick DNS Scanner:** Built-in DNS scanner to scan thousands of IP addresses to identify local DNS resolvers as a pre selection IP's to scan with E2E scanner.

- **Cloudflare Scanner:** Integrated a native Application-Layer (Layer 7) IP Scanner to automatically discover and map "clean" Cloudflare front-end IPs. This is to ensure VLESS-WS connections remain completely stable even when server IPs are blocked by aggressive DPI.

- **Global DNS Scanner:** Integrated a native Application-Layer (Layer 7) DoH Scanner to automatically discover and route through "clean" raw IP DNS resolvers. This completely strips the domain name from the TLS SNI, ensuring the initial connection bootstrap remains completely invisible to Deep Packet Inspection (DPI) and national firewalls.

- **Multi-Worker Ping:** Implemented parallel worker processing for ping tests. This significantly increases scanning speed when testing multiple resolvers across various configurations.

- **CI/CD Ready:** Automated build pipeline via GitHub Actions that injects server configurations through encrypted secrets.

- **Multi-Architecture Support:** Native binaries optimized for both arm64-v8a and armeabi-v7a devices.

- **Encrypted Configuration:** Supports pre-configured 'Default Servers' integrated via a compiled Go native layer. This architecture secures private infrastructure details by ensuring they are not stored in plain-text configuration files.
- **Remote Update Configs:**  Users can seamlessly update default configurations over-the-air whenever new servers or optimizations become available, ensuring the app stays ahead of network restrictions.

- **Remote Update Resolvers:**  Users can seamlessly update default resolvers over-the-air whenever new updtates for resolvers become available, ensuring the app stays ahead of network restrictions.




## 🔒 App Verification
Official builds of Phoenix VPN can be verified using the in-app verification tool. 
**Official Public Key:** `4ce41a228fd340bdee5aa6bbd453ebd1b407a36349019a1d4e62fc33364aa534`


### Screenshot of the Phoenix VPN 
Here are the main window, main window with default configs, config manager, scanner resolver, and scanner resolver results windows:

<p align="center">
  <img src="screenshots/vaydns.webp" alt="Main Window">
</p>

**Screenshot showing Phoenix VPN features**

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
cd phoenix-vpn/mobile
# Sync dependencies
go mod tidy

# Install tools to $GOPATH/bin
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

# Initialize the cross-compilation environment 
gomobile init
```
### direct protocols (Xray and Sing-Box)
If you are planning to add Xray core to your project, follow the go.mod in mobile directory. Due to Google library gvisor version dependency issues in Xray and tune2socks, make sure to run these sequence of commands in mobile directory:

```
go get github.com/xtls/xray-core
go get github.com/xjasonlyu/tun2socks/v2@latest
go get github.com/xjasonlyu/tun2socks/v2@main
go mod tidy
```

## 4. Building the Project
### Generate the Android Library (.aar)
```
# Build for ARM64
cd mobile
export PING_DOMAIN="example.com"
# replace example.com with a high traffic url inside the country
gomobile bind -v \
    -target=android/arm64 \
    -androidapi 24 \
    -ldflags="-s -w -X 'github.com/Starling226/phoenix-vpn/f35.InjectedPingDomain=$PING_DOMAIN'" \
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

## How to Use Phoenix VPN

Follow these steps to set up and start your secure tunnel:

1.  **Select Apps to Tunnel**: Tap on **SELECT APPS TO TUNNEL** and choose a few specific apps (3–4 recommended) that you want to pass through the tunnel. Only selected apps will be routed; all other traffic will remain on your local network.
2.  **Add Your Configuration**: 
    * To use your own server: Tap the **Menu** (three dots) and select **Add Config**.
    * To use built-in servers: Toggle on **Use default configs** and select a server from the list.
3.  **Find a Usable Resolver**: To establish a tunnel, you must find a functional DNS Resolver for your current network.
    * Open the **Menu** and select **DNS Scanner**. 
    * There are several options to choose from. You may use the default parameters and tap **START SCAN**.
    * Once the scan finishes, look for a resolver with a latency lower than **6000 ms**.
    * Tap the **Set (Checkmark)** icon to apply the fastest resolver to your config, or the **Save** icon to store a list of fast resolvers for later use. 
    * *Note: If no usable resolvers are found, go back and start a new scan to get a fresh random list.*
4.  **Start the Tunnel**: Return to the main menu and tap **START TUNNEL**. It may take up to **20 seconds** to establish a stable connection.
5.  **Troubleshooting**: Different configurations use different DNS record types (TXT, NULL, etc.). A resolver that works for one config may not work for another. If you cannot connect, try switching to a different configuration or record type.
6.  **Performance Expectations**: Please note that DNS tunneling is inherently slower than traditional VPNs due to protocol overhead. Expect speeds ranging from **10 KB/sec to 200 KB/sec**, depending on your network conditions.
      
## How to add Multi Domain support to vaydns server
To enable multi-domain routing, pass a comma-separated list of domains to the -domain flag.

**Example Usage**:
```
./vaydns-server -udp :5300 -privkey-file server.key -domain t.example.com,s.example.com -upstream 127.0.0.1:8000
```
      
## Acknowledgments

This project would not be possible without the incredible work of the following open-source repositories:

-   **[vaydns](https://github.com/net2share/vaydns)**: For the core DNS tunneling engine and sophisticated transport layers.
    
-   **[tun2socks](https://github.com/xjasonlyu/tun2socks)**: For the high-performance implementation of TUN-to-SOCKS conversion, enabling system-wide VPN functionality.

-   **[sing-box](https://github.com/SagerNet/sing-box)**: For the highly optimized universal proxy core, powering our advanced direct protocol support and intelligent traffic routing.

-   **[f35](https://github.com/nxdp/f35)**: For the End-to-End DNS Resolver Scanner

-   **[dnst-url-spec](https://github.com/net2share/dnst-url-spec)**: A standard URL format for sharing DNS-tunneled proxy configurations across clients and apps.

-   **[Hysteria2](https://github.com/apernet/hysteria)**: For the powerful, UDP-optimized transport protocol that delivers incredible speeds over unreliable and heavily censored networks.

-   **[REALITY](https://github.com/XTLS/REALITY)**: For the innovative stealth protocol that flawlessly masks proxy traffic without requiring a dedicated domain or standard TLS certificate.

-   **[VLESS-WS & VLESS-HTTPUpgrad](https://github.com/XTLS/Xray-core)**: For pioneering the lightweight VLESS protocol and engineering advanced CDN-compatible transports like HTTPUpgrade to drastically reduce overhead and latency.

-   **[DNS XS](https://github.com/code3-dev/DNS-XS)**: For the comprehensive list of the public DNS servers in json format.

## License & Disclaimer

### License
This project is licensed under the **Phoenix VPN Source-Available License**. 

Please see the [LICENSE](LICENSE) file for the full legal text. For third-party components and their respective licenses, see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).

### Disclaimer
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND. Use of Phoenix VPN is at your own risk. The maintainers are not responsible for any misuse, data loss, or legal consequences. Users are solely responsible for complying with their local laws and regulations regarding the use of VPN and tunneling technologies.

