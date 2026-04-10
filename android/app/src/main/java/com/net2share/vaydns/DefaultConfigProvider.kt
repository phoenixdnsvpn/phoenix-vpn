package com.net2share.vaydns

import android.content.Context
import android.util.Log
import mobile.Mobile

object DefaultConfigProvider {

    fun getDefaultConfigs(context: Context): List<Config> {
        // 1. Try to "Prime" the bridge from assets if it's currently empty
        // We check the count first to see if CI already injected it
        if (mobile.Mobile.getDefaultConfigCount() == 0L) {
            try {
                val jsonStr = context.assets.open("default_configs.json").bufferedReader().use { it.readText() }
                mobile.Mobile.setDefaultConfigs(jsonStr)
                Log.d("VayDNS_Dev", "Bridge primed with local assets.")
            } catch (e: Exception) {
                Log.e("VayDNS_Dev", "No local asset found, waiting for CI injection.")
            }
        }

        // 2. Now get the count (it should be > 0 now)
        val count = mobile.Mobile.getDefaultConfigCount()
        Log.d("VayDNS_Go", "Final count for UI: $count")

        if (count == 0L) {
            return emptyList() // This triggers "None found"
        }

        val defaultList = mutableListOf<Config>()
        val prefs = context.getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)

        for (i in 0L until count) {
            val id = "default_$i"
            val config = Config(
                id = id,
                name = mobile.Mobile.getDefaultConfigName(i),
                // Masking logic remains the same
                domain = "-".repeat(mobile.Mobile.getDefaultConfigDomain(i).length),
                pubkey = "-".repeat(mobile.Mobile.getDefaultConfigPubkey(i).length),
                dnsAddress = prefs.getString("${id}_dns", "8.8.8.8:53")!!,
                mode = prefs.getString("${id}_mode", "udp")!!,
                isDefault = true
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
            pubkey = Mobile.getDefaultConfigPubkey(index)
        )
    }
}