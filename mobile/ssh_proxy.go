package mobile

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"time"

	"golang.org/x/crypto/ssh"
)

// SSHProxyManager holds the active SSH connection and local SOCKS5 listener
type SSHProxyManager struct {
	sshClient     *ssh.Client
	socksListener net.Listener
	ProxyPort     int // The port tun2socks will connect to
	cancelFunc    context.CancelFunc
}

// StartSSHClient connects to the VayDNS bridge, performs the SSH handshake, and opens the local SOCKS5 proxy
func StartSSHClient(bridgeAddr, user, pass string, isPrivateKey bool) (*SSHProxyManager, error) {
	var authMethod ssh.AuthMethod

	if isPrivateKey {
		signer, err := ssh.ParsePrivateKey([]byte(pass))
		if err != nil {
			return nil, fmt.Errorf("invalid SSH private key: %v", err)
		}
		authMethod = ssh.PublicKeys(signer)
		log.Printf("VAY_DEBUG: SSH configured to use Private Key authentication")
	} else {
		authMethod = ssh.Password(pass)
		log.Printf("VAY_DEBUG: SSH configured to use Password authentication")
	}

	config := &ssh.ClientConfig{
		User:            user,
		Auth:            []ssh.AuthMethod{authMethod},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         15 * time.Second,
	}

	time.Sleep(1 * time.Second)

	conn, err := net.DialTimeout("tcp", bridgeAddr, 15*time.Second)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to VayDNS bridge: %v", err)
	}

	sshConn, chans, reqs, err := ssh.NewClientConn(conn, bridgeAddr, config)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("SSH handshake failed: %v", err)
	}

	client := ssh.NewClient(sshConn, chans, reqs)
	log.Printf("VAY_DEBUG: SSH Handshake successful! Session established.")

	manager := &SSHProxyManager{
		sshClient: client,
	}

	err = manager.startLocalSocks()
	if err != nil {
		client.Close()
		return nil, fmt.Errorf("failed to start local SOCKS5 translator: %v", err)
	}

	return manager, nil
}

// startLocalSocks spins up a lightweight SOCKS5 server that tunnels traffic through SSH
func (m *SSHProxyManager) startLocalSocks() error {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	m.socksListener = l
	m.ProxyPort = l.Addr().(*net.TCPAddr).Port

	log.Printf("VAY_DEBUG: Internal SOCKS5 Translator listening on 127.0.0.1:%d", m.ProxyPort)

	go func() {
		for {
			conn, err := l.Accept()
			if err != nil {
				return // Listener was closed
			}
			go m.handleSocksClient(conn)
		}
	}()

	return nil
}

// handleSocksClient processes tun2socks traffic and pushes it through SSH
func (m *SSHProxyManager) handleSocksClient(conn net.Conn) {
	defer conn.Close()
	buf := make([]byte, 256)

	// 1. SOCKS5 Greeting
	if _, err := io.ReadFull(conn, buf[:2]); err != nil || buf[0] != 0x05 {
		return
	}
	numMethods := int(buf[1])
	if _, err := io.ReadFull(conn, buf[:numMethods]); err != nil {
		return
	}
	conn.Write([]byte{0x05, 0x00}) // No Auth Required

	// 2. Read Connection Request
	if _, err := io.ReadFull(conn, buf[:4]); err != nil || buf[0] != 0x05 {
		return
	}

	cmd := buf[1]

	// 🟢 THE MAGIC TRICK: Handle UDP ASSOCIATE for DNS Interception
	if cmd == 0x03 {
		m.handleUDPAssociate(conn)
		return
	}

	if cmd != 0x01 { // If not TCP CONNECT or UDP ASSOCIATE, reject.
		conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// 3. Handle Standard TCP Traffic
	addrType := buf[3]
	var destAddr string
	switch addrType {
	case 0x01: // IPv4
		if _, err := io.ReadFull(conn, buf[4:8]); err != nil {
			return
		}
		destAddr = net.IP(buf[4:8]).String()
	case 0x03: // Domain
		if _, err := io.ReadFull(conn, buf[4:5]); err != nil {
			return
		}
		domainLen := int(buf[4])
		if _, err := io.ReadFull(conn, buf[5:5+domainLen]); err != nil {
			return
		}
		destAddr = string(buf[5 : 5+domainLen])
	case 0x04: // IPv6
		if _, err := io.ReadFull(conn, buf[4:20]); err != nil {
			return
		}
		destAddr = net.IP(buf[4:20]).String()
	default:
		return
	}

	if _, err := io.ReadFull(conn, buf[:2]); err != nil {
		return
	}
	destPort := binary.BigEndian.Uint16(buf[:2])
	dest := net.JoinHostPort(destAddr, strconv.Itoa(int(destPort)))

	sshConn, err := m.sshClient.Dial("tcp", dest)
	if err != nil {
		conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer sshConn.Close()

	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	go io.Copy(sshConn, conn)
	io.Copy(conn, sshConn)
}

// 🟢 NEW: handleUDPAssociate intercepts tun2socks's attempt to open a UDP socket
func (m *SSHProxyManager) handleUDPAssociate(tcpConn net.Conn) {
	// 1. Open a local UDP listener just for this tun2socks session
	udpAddr, _ := net.ResolveUDPAddr("udp", "127.0.0.1:0")
	udpConn, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		tcpConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // General failure
		return
	}
	defer udpConn.Close()

	boundPort := udpConn.LocalAddr().(*net.UDPAddr).Port

	// 2. Tell tun2socks: "Success! Send your UDP packets to 127.0.0.1 on this port"
	reply := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, byte(boundPort >> 8), byte(boundPort & 0xff)}
	tcpConn.Write(reply)

	// 3. Start reading the UDP packets tun2socks sends us
	go m.processUDPPackets(udpConn)

	// 4. RFC 1928: The UDP binding stays alive as long as the TCP connection is open.
	// Block here until the TCP connection drops.
	io.Copy(io.Discard, tcpConn)
}

// 🟢 NEW: processUDPPackets dissects the SOCKS5 UDP header and filters for DNS
func (m *SSHProxyManager) processUDPPackets(udpConn *net.UDPConn) {
	buf := make([]byte, 4096)
	for {
		n, clientAddr, err := udpConn.ReadFromUDP(buf)
		if err != nil {
			return
		}

		// SOCKS5 UDP Header Parsing
		if n < 10 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
			continue // Invalid header or fragmentation (unsupported)
		}

		atyp := buf[3]
		headerLen := 4
		var destIP string

		if atyp == 0x01 { // IPv4
			destIP = net.IP(buf[4:8]).String()
			headerLen += 4
		} else if atyp == 0x03 { // Domain
			dlen := int(buf[4])
			destIP = string(buf[5 : 5+dlen])
			headerLen += 1 + dlen
		} else if atyp == 0x04 { // IPv6
			destIP = net.IP(buf[4:20]).String()
			headerLen += 16
		} else {
			continue
		}

		destPort := binary.BigEndian.Uint16(buf[headerLen : headerLen+2])
		headerLen += 2
		payload := buf[headerLen:n]
		socksHeader := buf[:headerLen]

		// 🟢 THE INTERCEPT: If it's a DNS request (Port 53), convert it to TCP
		if destPort == 53 {
			go m.resolveDNSoverTCP(udpConn, clientAddr, socksHeader, destIP, payload)
		}
		// Notice: If it's UDP traffic for port 443 (like QUIC), we drop it.
		// Browsers/Apps will immediately fall back to standard TCP, which works over SSH.
	}
}

// 🟢 NEW: resolveDNSoverTCP wraps the raw DNS UDP packet in a TCP envelope
func (m *SSHProxyManager) resolveDNSoverTCP(udpConn *net.UDPConn, clientAddr *net.UDPAddr, socksHeader []byte, destIP string, dnsPayload []byte) {
	// 1. Dial the target DNS server (e.g., 8.8.8.8:53) through the SSH Tunnel using TCP
	sshConn, err := m.sshClient.Dial("tcp", fmt.Sprintf("%s:53", destIP))
	if err != nil {
		return
	}
	defer sshConn.Close()

	// 2. DNS-over-TCP specification requires a 2-byte length prefix before the payload
	lengthPrefix := make([]byte, 2)
	binary.BigEndian.PutUint16(lengthPrefix, uint16(len(dnsPayload)))

	// 3. Send length + payload through SSH
	sshConn.Write(lengthPrefix)
	sshConn.Write(dnsPayload)

	// 4. Read the 2-byte response length from the SSH tunnel
	respLenBuf := make([]byte, 2)
	if _, err := io.ReadFull(sshConn, respLenBuf); err != nil {
		return
	}
	respLen := binary.BigEndian.Uint16(respLenBuf)

	// 5. Read the actual DNS response based on that length
	respPayload := make([]byte, respLen)
	if _, err := io.ReadFull(sshConn, respPayload); err != nil {
		return
	}

	// 6. Re-attach the exact SOCKS5 UDP header tun2socks sent us, and send the payload back as UDP!
	finalPacket := append(socksHeader, respPayload...)
	udpConn.WriteToUDP(finalPacket, clientAddr)
}

// Stop safely closes the translator and SSH session
func (m *SSHProxyManager) Stop() {
	if m.cancelFunc != nil {
		m.cancelFunc()
	}
	if m.socksListener != nil {
		m.socksListener.Close()
	}
	if m.sshClient != nil {
		m.sshClient.Close()
	}
	log.Printf("VAY_DEBUG: SSH Proxy Manager stopped.")
}
