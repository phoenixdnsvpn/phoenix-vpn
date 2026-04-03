package com.net2share.vaydns

import java.util.UUID

data class Config(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val domain: String,
    val pubkey: String,
    val dnsAddress: String,
    val mode: String   // "udp", "dot", or "doh"
)