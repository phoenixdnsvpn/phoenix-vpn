package com.net2share.vaydns

import android.content.Context
import android.util.Log
import mobile.Mobile

object DefaultConfigProvider {

    fun getDefaultConfigs(context: Context): List<Config> {
        // 1. Check for Over-the-Air updates first.
        // We now load the raw binary files directly from internal storage.

        /*try {
            val configFile = java.io.File(context.filesDir, "cached_default_configs.bin")
            if (configFile.exists()) {
                val cachedBytes = configFile.readBytes()
                if (cachedBytes.isNotEmpty()) {
                    mobile.Mobile.setDefaultConfigs(cachedBytes)
                }
            }
        } catch (e: Exception) {
            Log.e("VayDNS_Init", "Error loading cached configs file:", e)
        }*/
        val configFile = java.io.File(context.filesDir, "cached_default_configs.bin")
        try {
            //val configFile = java.io.File(context.filesDir, "cached_default_configs.bin")
            if (configFile.exists()) {
                val bytes = configFile.readBytes()
                if (bytes.isNotEmpty()) {
                    mobile.Mobile.setDefaultConfigs(bytes)
                }
            }

            /*val resolversFile = java.io.File(context.filesDir, "cached_default_resolvers.bin")
            if (resolversFile.exists()) {
                val resBytes = resolversFile.readBytes()
                if (resBytes.isNotEmpty()) {
                    mobile.Mobile.setDefaultResolvers(resBytes)
                }
            }*/
        } catch (e: Exception) { e.printStackTrace() }
        /**} catch (e: Exception) {
            Log.e("VayDNS_Init", "Failed to load cached binary configs: ${e.message}")
        }*/
        try {
            val resolversFile = java.io.File(context.filesDir, "cached_default_resolvers.bin")
            if (resolversFile.exists()) {
                val cachedResolverBytes = resolversFile.readBytes()
                if (cachedResolverBytes.isNotEmpty()) {
                    mobile.Mobile.setDefaultResolvers(cachedResolverBytes)
                }
            }
        } catch (e: Exception) {
            Log.e("VayDNS_Init", "Error loading cached resolvers file:", e)
        }

        // 2. Fetch the count from Go.
        // This triggers ensureParsed() inside the Go library automatically.
        var count = mobile.Mobile.getDefaultConfigCount()
        if (count <= 0 && configFile.exists()) {
            android.util.Log.w("VayDNS", "Corrupt disk cache detected. Deleting...")
            configFile.delete()
            // Now Go will automatically fall back to the working AAR injection
            count = mobile.Mobile.getDefaultConfigCount()
        }
        //val count = mobile.Mobile.getDefaultConfigCount()
        /*Log.d("VayDNS_Go", "Official configs loaded: $count")
        if (count <= 0) {
            // If count is 0, the file on disk was corrupted or invalid.
            // DELETE the bad file so the app can fall back to the embedded AAR config.
            java.io.File(context.filesDir, "cached_default_configs.bin").delete()

            // Re-check count (Go will now fall back to InjectedConfigs)
            count = mobile.Mobile.getDefaultConfigCount()
        }*/
        //if (count == 0L) return emptyList()

        val defaultList = mutableListOf<Config>()
        val overrides = context.getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)

        for (i in 0L until count) {
            val id = "default_$i"

            // Check if this specific default config has credentials
            val hasAuth = mobile.Mobile.getDefaultConfigUser(i).isNotEmpty() ||
                    mobile.Mobile.getDefaultConfigPass(i).isNotEmpty()

            val config = Config(
                id = id,
                isDefault = true,

                // Name: Fallback if name is empty in JSON
                name = mobile.Mobile.getDefaultConfigName(i).ifEmpty { "Official Server ${i + 1}" },

                // UI Masking: Don't leak the actual values in the main list
//                domain = "----------",
//                pubkey = "----------",

                domain = mobile.Mobile.getDefaultConfigDomain(i),
                pubkey = mobile.Mobile.getDefaultConfigPubkey(i),

                // User-adjustable fields (Local persistence for DNS/Mode)
                dnsAddress = overrides.getString("${id}_dns", "8.8.8.8:53")!!,
                mode = overrides.getString("${id}_mode", "udp")!!,

                // Parameters from Go Binary
                recordType = mobile.Mobile.getDefaultConfigRecordType(i),
                idleTimeout = mobile.Mobile.getDefaultConfigIdleTimeout(i),
                keepAlive = mobile.Mobile.getDefaultConfigKeepAlive(i),
                clientIdSize = mobile.Mobile.getDefaultConfigClientIdSize(i),
                dnsttCompatible = mobile.Mobile.getDefaultConfigDnsttCompatible(i),
                ssMethod = mobile.Mobile.getDefaultConfigMethod(i),
                protocol = mobile.Mobile.getDefaultConfigProtocol(i),
                useSshKey = mobile.Mobile.getDefaultConfigUseSshKey(i),

                // Auth Masking
                useAuth = hasAuth,
                user = if (hasAuth) "********" else "",
                pass = if (hasAuth) "********" else ""
            )
            defaultList.add(config)
        }
        return defaultList
    }

    fun getActualConfig(context: Context, maskedConfig: Config): Config {
        if (!maskedConfig.isDefault) return maskedConfig

        val index = maskedConfig.id.removePrefix("default_").toLongOrNull() ?: 0L

        // Swap masked values with real values from Go
        return maskedConfig.copy(
            domain = Mobile.getDefaultConfigDomain(index),
            pubkey = Mobile.getDefaultConfigPubkey(index),
            recordType = Mobile.getDefaultConfigRecordType(index),
            idleTimeout = Mobile.getDefaultConfigIdleTimeout(index),
            keepAlive = Mobile.getDefaultConfigKeepAlive(index),
            clientIdSize = mobile.Mobile.getDefaultConfigClientIdSize(index),
            dnsttCompatible = mobile.Mobile.getDefaultConfigDnsttCompatible(index),
            ssMethod = mobile.Mobile.getDefaultConfigMethod(index),
            user = mobile.Mobile.getDefaultConfigUser(index),
            pass = mobile.Mobile.getDefaultConfigPass(index)
        )
    }
}