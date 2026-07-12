package net.vaydns.phoenix

import java.util.UUID

data class Config(
    val transport:  String = "vaydns",
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val domain: String,
    val pubkey: String,
    val dnsAddress: String,
    val mode: String,   // "udp", "tcp", "dot", or "doh"
    val recordType: String = "TXT",
    val idleTimeout: String = "10s",
    val keepAlive: String = "2s",
    val clientIdSize: Long = 2,
    val mtu: Long = 0,
    val dnsttCompatible: Boolean = false,
    val useAuth: Boolean = false,
    val useSshKey: Boolean = false,
    val protocol: String = "socks",
    val authProtocol: String = "socks",
    val user: String = "",
    val pass: String = "",
    val ssMethod: String = "chacha20-ietf-poly1305",
    val isDefault: Boolean = false,
    val freeScanner: Boolean = false,
    val useMultiDomains: Boolean = false,
    val tunnelProtocol: String = "vaydns",
    val vlessIp: String = "",
    val domainIndex: Int = 0,
    var lastLatency: Long = -1L
)