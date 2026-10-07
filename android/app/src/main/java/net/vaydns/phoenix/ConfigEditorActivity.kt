package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class ConfigEditorActivity : AppCompatActivity() {

    // Shared data class used by Multipath Resolver & Adapter
    data class ResolverEntry(
        var address: String,
        var isChecked: Boolean = false,
        val isManual: Boolean = false,
        val latency: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fallback / Legacy router for default configs if triggered directly
        val configId = intent.getStringExtra("CONFIG_ID")
        if (configId != null && configId.startsWith("default_")) {
            startActivity(Intent(this, DefaultConfigEditorActivity::class.java).putExtras(intent))
        } else {
            startActivity(Intent(this, CustomDnsConfigEditorActivity::class.java).putExtras(intent))
        }
        finish()
    }

    companion object {
        // Required by VayRowPingService, Scanners, and MainActivity to load custom profiles
        fun loadAllConfigs(context: Context): List<Config> {
            val sharedPref = context.getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
            val jsonStr = sharedPref.getString("configs", "[]") ?: "[]"
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Config>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Config(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        orderIndex = obj.optInt("orderIndex", 0),
                        domain = obj.optString("domain", ""),
                        pubkey = obj.optString("pubkey", ""),
                        dnsAddress = obj.optString("dnsAddress", ""),
                        mode = obj.optString("mode", ""),
                        recordType = obj.optString("recordType", "TXT"),
                        idleTimeout = obj.optString("idleTimeout", "10s"),
                        keepAlive = obj.optString("keepAlive", "2s"),
                        clientIdSize = obj.optLong("clientIdSize", 2),
                        mtu = obj.optLong("mtu", 0L),
                        dnsttCompatible = obj.optBoolean("dnsttCompatible", false),
                        useAuth = obj.optBoolean("useAuth", false),
                        useSshKey = obj.optBoolean("useSshKey", false),
                        localProxyProtocol = obj.optString("localProxyProtocol", "socks5"),
                        authProtocol = obj.optString("authProtocol", "socks"),
                        ssMethod = obj.optString("ssMethod", "chacha20-ietf-poly1305"),
                        masterDnsMethod = obj.optString("masterDnsMethod", "XOR"),
                        user = obj.optString("user", ""),
                        pass = obj.optString("pass", ""),
                        useMultiDomains = obj.optBoolean("useMultiDomains", false),
                        domainIndex = obj.optInt("domainIndex", 0),
                        tunnelProtocol = obj.optString("tunnelProtocol", "vaydns"),
                        vlessIp = CryptoHelper.decrypt(obj.optString("vlessIp", "")),
                        vlessPort = obj.optInt("vlessPort", 443),
                        isDefault = false,
                        slipstreamCongestion = obj.optString("slipCongestion", "BBR"),
                        slipstreamAuthoritative = obj.optBoolean("slipAuth", false),
                        slipstreamGso = obj.optBoolean("slipGso", false),
                    )
                )
            }
            return list
        }

        // Required by Scanners and MainActivity to save custom profile updates
        fun saveAllConfigs(context: Context, configs: List<Config>) {
            val sharedPref = context.getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
            val array = JSONArray()

            configs.forEach { config ->
                if (!config.isDefault) {
                    val obj = JSONObject().apply {
                        put("id", config.id)
                        put("name", config.name)
                        put("orderIndex", config.orderIndex)
                        put("domain", config.domain)
                        put("pubkey", config.pubkey)
                        put("dnsAddress", config.dnsAddress)
                        put("mode", config.mode)
                        put("recordType", config.recordType)
                        put("idleTimeout", config.idleTimeout)
                        put("keepAlive", config.keepAlive)
                        put("clientIdSize", config.clientIdSize)
                        put("mtu", config.mtu)
                        put("dnsttCompatible", config.dnsttCompatible)
                        put("useAuth", config.useAuth)
                        put("useSshKey", config.useSshKey)
                        put("localProxyProtocol", config.localProxyProtocol)
                        put("authProtocol", config.authProtocol)
                        put("ssMethod", config.ssMethod)
                        put("masterDnsMethod", config.masterDnsMethod)
                        put("user", config.user)
                        put("pass", config.pass)
                        put("useMultiDomains", config.useMultiDomains)
                        put("tunnelProtocol", config.tunnelProtocol)
                        put("vlessIp", CryptoHelper.encrypt(config.vlessIp))
                        put("vlessPort", config.vlessPort)
                        put("domainIndex", config.domainIndex)
                        put("slipCongestion", config.slipstreamCongestion)
                        put("slipAuth", config.slipstreamAuthoritative)
                        put("slipGso", config.slipstreamGso)
                    }
                    array.put(obj)
                }
            }
            sharedPref.edit().putString("configs", array.toString()).apply()
        }
    }
}