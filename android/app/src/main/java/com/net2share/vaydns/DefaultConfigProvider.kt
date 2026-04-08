package com.net2share.vaydns

import android.content.Context
import android.util.Log
object DefaultConfigProvider {

    fun getDefaultConfigs(context: Context): List<Config> {
        DefaultConfigs.loadConfigs(context.assets)
        Log.d("VayDNS_Native", "Calling loadConfigs...")
        val count = DefaultConfigs.getConfigCount()
        Log.d("VayDNS_Native", "Native Count received: $count")

        if (count == 0) {
            // Display a brief notification to the user
            /*android.widget.Toast.makeText(
                context,
                "No default configurations found.",
                android.widget.Toast.LENGTH_SHORT
            ).show()*/

            Log.i("VayDNS_Native", "Returning empty list (CI/Template mode active)")
            return emptyList()
        }
        val defaultList = mutableListOf<Config>()

        // Access overrides for DNS and Mode
        val prefs = context.getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)

        for (i in 0 until count) {
            val id = "default_$i"

            // Check if user has saved a custom DNS or Mode for this specific default config
            val savedDns = prefs.getString("${id}_dns", "8.8.8.8:53")
            val savedMode = prefs.getString("${id}_mode", "udp")

            val config = Config(
                id = id,
                name = DefaultConfigs.getConfigName(i),
                // These remain masked in the Config objects used by the UI/Adapter
                domain = "-".repeat(DefaultConfigs.getConfigDomain(i).length),
                pubkey = "-".repeat(DefaultConfigs.getConfigPubkey(i).length),
                dnsAddress = savedDns!!,
                mode = savedMode!!,
                isDefault = true // Ensure your Config data class has this field
            )
            defaultList.add(config)
        }
        Log.d("VayDNS_Native", "Returning ${defaultList.size} configs to UI")
        return defaultList
    }

    /**
     * Helper to get the REAL data only when starting the tunnel or scanner
     */
    fun getActualConfig(context: Context, maskedConfig: Config): Config {
        if (!maskedConfig.isDefault) return maskedConfig

        val index = maskedConfig.id.removePrefix("default_").toInt()

        // Return a copy with the REAL strings from the native library
        return maskedConfig.copy(
            domain = DefaultConfigs.getConfigDomain(index),
            pubkey = DefaultConfigs.getConfigPubkey(index)
        )
    }
}