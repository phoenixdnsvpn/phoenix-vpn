module github.com/Starling226/vaydns-vpn

go 1.25.8

require (
	github.com/net2share/vaydns v0.2.6
	github.com/sirupsen/logrus v1.9.4
	github.com/xjasonlyu/tun2socks/v2 v2.6.0
	golang.org/x/mobile v0.0.0-20260312152759-81488f6aeb60
)

// The replace is still necessary for your custom KCP logic
replace github.com/xtaci/kcp-go/v5 => github.com/net2share/kcp-go/v5 v5.0.0-20260325165956-416ba9d3856d
