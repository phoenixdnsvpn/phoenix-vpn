package com.net2share.vaydns

import java.util.UUID

data class Config(
    val transport:  String = "vaydns",
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val domain: String,
    val pubkey: String,
    val dnsAddress: String,
    val mode: String,   // "udp", "dot", or "doh"
    val recordType: String = "TXT",
    val idleTimeout: String = "10s",
    val keepAlive: String = "2s",
    val clientIdSize: Long = 2,
    val dnsttCompatible: Boolean = false,
    val useAuth: Boolean = false,
    val useSshKey: Boolean = false,
    val protocol: String = "socks",
    val user: String = "",
    val pass: String = "",
    val isDefault: Boolean = false // Crucial for your toggle logicval
)