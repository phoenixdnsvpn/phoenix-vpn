package net.vaydns.phoenix

import android.content.Context
import android.util.Log
import mobile.Mobile

object DefaultConfigProvider {
    fun getDefaultConfigs(context: Context): List<Config> {
        // NATIVE VAULT: Go handles the parsing internally.
        // We just ask for the count.
        val count = mobile.Mobile.getDefaultConfigCount()
        val status = mobile.Mobile.getBuildStatus() // Call the function here

        val defaultList = mutableListOf<Config>()
        val overrides = context.getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)

        for (i in 0L until count) {
            val id = "default_$i"
            val hasAuth = mobile.Mobile.hasDefaultConfigAuth(i)
            val isFreeScanner = mobile.Mobile.getDefaultConfigIsFreeScanner(i)
            val originalName = mobile.Mobile.getDefaultConfigName(i).ifEmpty { "Official Server ${i.toInt() + 1}" }
            val customName = overrides.getString("${id}_name", originalName) ?: originalName

            // Fetch Native Defaults
            val nativeProxy = mobile.Mobile.getDefaultConfigProxy(i)
            val nativeProto = mobile.Mobile.getDefaultConfigProtocol(i)
            val rawConfigType = mobile.Mobile.getDefaultConfigType(i)

            // Resolve Default Values
            val authProtoDefault = if (nativeProto == "ssh" || nativeProto == "shadowsocks") nativeProto else "socks"
            val localProxyProtoDefault = if (nativeProxy.lowercase() == "http") "http" else "socks5"
            val tunnelProtoDefault = rawConfigType.split(",").firstOrNull { it.isNotBlank() } ?: "vaydns"

            val config = Config(
                id = id,
                isDefault = true,
                freeScanner = isFreeScanner,
                name = customName,
                domain = "SECURE_NATIVE_VAULT",
                pubkey = "SECURE_NATIVE_VAULT",

                dnsAddress = overrides.getString("${id}_dns", "8.8.8.8:53")!!,
                mode = overrides.getString("${id}_mode", "udp")!!,

                recordType = mobile.Mobile.getDefaultConfigRecordType(i),
                idleTimeout = mobile.Mobile.getDefaultConfigIdleTimeout(i),
                keepAlive = mobile.Mobile.getDefaultConfigKeepAlive(i),
                clientIdSize = mobile.Mobile.getDefaultConfigClientIdSize(i),
                dnsttCompatible = mobile.Mobile.getDefaultConfigDnsttCompatible(i),
                ssMethod = mobile.Mobile.getDefaultConfigMethod(i),
                useSshKey = mobile.Mobile.getDefaultConfigUseSshKey(i),

                // =======================================================
                // THE 3 ISOLATED VARIABLES (WITH OVERRIDE SUPPORT)
                // =======================================================
                localProxyProtocol = overrides.getString("${id}_localProxyProtocol", localProxyProtoDefault) ?: localProxyProtoDefault,
                authProtocol = overrides.getString("${id}_authProtocol", authProtoDefault) ?: authProtoDefault,
                tunnelProtocol = overrides.getString("${id}_tunnelProtocol", tunnelProtoDefault) ?: tunnelProtoDefault,

                useAuth = hasAuth,
                user = "",
                pass = "",
                useMultiDomains = overrides.getBoolean("${id}_useMultiDomains", false),
                domainIndex = overrides.getInt("${id}_domainIndex", 0),

                // Pull VLESS Overrides so the UI reflects them instantly
                vlessIp = CryptoHelper.decrypt(overrides.getString("${id}_vlessIp", "") ?: ""),
                vlessPort = overrides.getInt("${id}_vlessPort", 443)
            )
            defaultList.add(config)
        }
        return defaultList
    }

    fun getActualConfig(context: Context, maskedConfig: Config): Config {
        // If it's a custom config, return it as-is
        if (!maskedConfig.isDefault) return maskedConfig

        val index = maskedConfig.id.removePrefix("default_").toLongOrNull() ?: 0L

        // NATIVE VAULT: We no longer swap in real secrets here.
        // We just return the masked config as it was loaded.
        // Go will intercept the "SECURE_NATIVE_VAULT" and "********" strings
        // internally when StartVpn() is called.
        return maskedConfig.copy(
            recordType = mobile.Mobile.getDefaultConfigRecordType(index),
            idleTimeout = mobile.Mobile.getDefaultConfigIdleTimeout(index),
            keepAlive = mobile.Mobile.getDefaultConfigKeepAlive(index),
            clientIdSize = mobile.Mobile.getDefaultConfigClientIdSize(index),
            dnsttCompatible = mobile.Mobile.getDefaultConfigDnsttCompatible(index),
            ssMethod = mobile.Mobile.getDefaultConfigMethod(index),
            useMultiDomains = maskedConfig.useMultiDomains
        )
    }
}