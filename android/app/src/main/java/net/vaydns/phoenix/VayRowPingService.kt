package net.vaydns.phoenix

import android.content.Context
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class VayRowPingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        mobile.Mobile.initVault(filesDir.absolutePath)

        val configId = intent.getStringExtra("CONFIG_ID") ?: return START_NOT_STICKY
        val configType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"
        val tunnelProtocol = intent.getStringExtra("TUNNEL_PROTOCOL") ?: "vaydns"
        val localProxyProtocol = intent.getStringExtra("LOCAL_PROXY_PROTOCOL") ?: "socks5"
        val authProtocol = intent.getStringExtra("AUTH_PROTOCOL") ?: "socks"

        val activeProtocol = tunnelProtocol.lowercase()
        val isWireguardMode = activeProtocol == "wireguard"
        val isMasqueMode = activeProtocol == "masque"
        val isWarpPlusMode = activeProtocol == "warp" // Added WARP+ Support

        // ARCHITECTURAL FORK: Check if it is a direct connection by verifying the active protocol string
        val isDirectMode = !configType.lowercase().contains("vaydns") ||
                tunnelProtocol.lowercase() in listOf("hysteria2", "reality-tcp", "reality-xhttp", "vless-ws", "vless-httpupgrade", "vless-grpc", "vless-xhttp", "amneziawg")

        // Group Standard WARP and WARP+ together
        if (isWireguardMode || isWarpPlusMode) {
            // =========================================================
            // DEDICATED WIREGUARD/WARP+ PING (Using Scanner Engine)
            // =========================================================
            Thread {
                var latency = -1L
                try {
                    val warpPrefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)
                    val mode = warpPrefs.getString("connection_mode", "endpoint") ?: "endpoint"
                    val endpoint = warpPrefs.getString("endpoint", "engage.cloudflareclient.com") ?: "engage.cloudflareclient.com"
                    val customIp = warpPrefs.getString("custom_ip", "") ?: ""
                    val engineType = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE).getString("tun_engine", "xray") ?: "xray"

                    // Determine which target to ping based on the active Radio toggle
                    val targetIp = if (mode == "ip" && customIp.isNotEmpty()) customIp else endpoint
                    val port = warpPrefs.getString("port", "2408")?.toIntOrNull() ?: 2408

                    // CRITICAL FIX: Read keys directly from JSON since UI no longer saves them to Prefs
                    var privKey = ""
                    var pubKey = ""
                    var reservedBytesStr = "[0, 0, 0]"

                    val file = File(filesDir, "warp_keys.json")
                    if (file.exists()) {
                        try {
                            val decrypted = mobile.Mobile.decryptText(file.readText())
                            val json = JSONObject(decrypted)
                            privKey = json.optString("private_key", "")
                            pubKey = json.optString("public_key", json.optString("server_public_key", ""))
                            val resArr = json.optJSONArray("reserved")
                            if (resArr != null) {
                                reservedBytesStr = resArr.toString()
                            } else if (json.has("reserved_bytes")) {
                                reservedBytesStr = json.optString("reserved_bytes", "[0, 0, 0]")
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }

                    var r1 = 0L; var r2 = 0L; var r3 = 0L
                    try {
                        val cleanArr = reservedBytesStr.replace("[", "").replace("]", "").split(",")
                        if (cleanArr.size >= 3) {
                            r1 = cleanArr[0].trim().toLongOrNull() ?: 0L
                            r2 = cleanArr[1].trim().toLongOrNull() ?: 0L
                            r3 = cleanArr[2].trim().toLongOrNull() ?: 0L
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    if (privKey.isNotEmpty() && pubKey.isNotEmpty() && targetIp.isNotEmpty()) {
                        val resultJson = mobile.Mobile.runWarpScanner(
                            1L,
                            targetIp,
                            port.toLong(),
                            false,
                            privKey,
                            pubKey,
                            r1,
                            r2,
                            r3,
                            engineType,
                            1L
                        )

                        val safeJson = if (resultJson.isNullOrBlank() || resultJson == "null") "[]" else resultJson
                        val resultsArray = JSONArray(safeJson)

                        if (resultsArray.length() > 0) {
                            latency = resultsArray.getJSONObject(0).getLong("latency_ms")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                broadcastResult(configId, latency)
            }.start()

        } else if (isMasqueMode) {
            // =========================================================
            // DEDICATED MASQUE PING (Using Native QUIC Scanner)
            // =========================================================
            Thread {
                var latency = -1L
                try {
                    val uPrefs = getSharedPreferences("UsqueProfilePrefs", Context.MODE_PRIVATE)
                    val mode = uPrefs.getString("connection_mode", "endpoint") ?: "endpoint"
                    val endpoint = uPrefs.getString("endpoint", "162.159.198.2") ?: "162.159.198.2"
                    val customIp = uPrefs.getString("custom_ip", "") ?: ""
                    val port = uPrefs.getString("port", "443")?.toIntOrNull() ?: 443

                    val targetIp = if (mode == "ip" && customIp.isNotEmpty()) customIp else endpoint

                    if (targetIp.isNotEmpty()) {
                        val resultJson = mobile.Mobile.runNativeMasqueScanner(
                            1L,
                            targetIp,
                            port.toLong(),
                            false,
                            1L,
                            1000L,
                            2500L
                        )

                        val safeJson = if (resultJson.isNullOrBlank() || resultJson == "null") "[]" else resultJson
                        val resultsArray = JSONArray(safeJson)

                        if (resultsArray.length() > 0) {
                            latency = resultsArray.getJSONObject(0).getLong("latency_ms")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                broadcastResult(configId, latency)
            }.start()

        } else if (isDirectMode) {
            // =========================================================
            // SECURE NATIVE PING (Executes entirely inside Go)
            // =========================================================
            val isDefault = intent.getBooleanExtra("IS_DEFAULT", false)
            val configIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
            val serverIp = intent.getStringExtra("SERVER_IP") ?: "" // Only used for custom configs
            //val protocol = intent.getStringExtra("PROTOCOL") ?: ""
            var vlessWsIp = intent.getStringExtra("VLESS_WS_IP") ?: ""
            val domain = intent.getStringExtra("DOMAIN") ?: "" // Extract for SNI

            if (vlessWsIp.isEmpty()) {
                if (isDefault) {
                    val defPrefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                    vlessWsIp = defPrefs.getString("${configId}_vlessIp", "") ?: ""
                } else {
                    try {
                        val currentConfigs = net.vaydns.phoenix.ConfigEditorActivity.loadAllConfigs(this)
                        val userConfig = currentConfigs.find { it.id == configId }
                        vlessWsIp = userConfig?.vlessIp ?: ""
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
            val useLayer7 = tunnelPrefs.getBoolean("use_layer7_ping", true)
            var globalDnsServer = tunnelPrefs.getString("global_dns_server", "")?.trim() ?: ""
            if (globalDnsServer.isEmpty()) {
                globalDnsServer = "1.1.1.1"
            }

            val getServerIpFromDomain = tunnelPrefs.getBoolean("get_server_ip_from_domain", false)
            val globalOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
            val globalCdn = tunnelPrefs.getString("selected_cdn", "CloudX") ?: "CloudX"

            val useSniPool = tunnelPrefs.getBoolean("use_sni_pool", false)
            val selectedSniIndex = tunnelPrefs.getInt("selected_sni_index", -1)
            val sniIndex = if (useSniPool) selectedSniIndex.toLong() else -1L

            val targetCdn = if (globalOverride) {
                globalCdn
            } else if (isDefault) {
                val defPrefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                defPrefs.getString("${configId}_cdn", "CloudX") ?: "CloudX"
            } else {
                val appPrefs = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
                appPrefs.getString("${configId}_cdn", "CloudX") ?: "CloudX"
            }

            vlessWsIp = CryptoHelper.decrypt(vlessWsIp)

            Thread {
                val latency = if (useLayer7) {
                    Mobile.pingDirectServerLayer7(
                        isDefault,
                        configIndex,
                        serverIp,
                        tunnelProtocol.lowercase(),
                        domain,
                        "/",
                        globalDnsServer,
                        getServerIpFromDomain,
                        targetCdn,
                        vlessWsIp,
                        sniIndex
                    )
                } else {
                    Mobile.pingDirectServer(
                        isDefault,
                        configIndex,
                        serverIp,
                        tunnelProtocol.lowercase(),
                        globalDnsServer,
                        getServerIpFromDomain,
                        targetCdn,
                        vlessWsIp
                    )
                }

                broadcastResult(configId, latency)
            }.start()

        } else {
            // =========================================================
            // HEAVY GO SCANNER FOR VAYDNS TUNNELS
            // =========================================================
            val isDefault = intent.getBooleanExtra("IS_DEFAULT", false)
            val configIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
            val mode = intent.getStringExtra("MODE") ?: ""
            val domain = intent.getStringExtra("DOMAIN") ?: ""
            val domainIndex = intent.getIntExtra("DOMAIN_INDEX", 0)
            val pubkey = intent.getStringExtra("PUBKEY") ?: ""
            val multipathDnsList = intent.getStringExtra("MULTIPATH_DNS") ?: ""
            val baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
            val proxyType = intent.getStringExtra("PROXY_TYPE") ?: "socks5h"
            //val protocol = intent.getStringExtra("PROTOCOL") ?: ""
            val user = intent.getStringExtra("USER") ?: "none"
            val pass = intent.getStringExtra("PASS") ?: "none"
            val ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
            val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
            val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
            val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
            val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
            val mtu = intent.getLongExtra("MTU", 0L)
            val workers = intent.getLongExtra("WORKERS", 20L)
            val tunnelWait = intent.getLongExtra("TUNNEL_WAIT", 3000L)
            val udpTimeout = intent.getLongExtra("UDP_TIMEOUT", 1000L)
            val probeTimeout = intent.getLongExtra("PROBE_TIMEOUT", 15000L)
            val retries = intent.getLongExtra("RETRIES", 0L)
            val lightE2E = intent.getBooleanExtra("LIGHT_E2E", false)

            Thread {
                val bestLatency = Mobile.pingMultipleServers(
                    isDefault,
                    configIndex,
                    domainIndex.toLong(),
                    mode,
                    domain,
                    pubkey,
                    multipathDnsList,
                    baseDohUrl,
                    proxyType,
                    authProtocol,
                    user,
                    pass,
                    ssMethod,
                    recordType,
                    idleTimeout,
                    keepAlive,
                    clientIdSize,
                    mtu,
                    workers,
                    tunnelWait,
                    udpTimeout,
                    probeTimeout,
                    retries,
                    lightE2E,
                    false
                )
                broadcastResult(configId, bestLatency)
            }.start()
        }

        return START_NOT_STICKY
    }

    private fun broadcastResult(configId: String, latency: Long) {
        val broadcastIntent = Intent("ROW_PING_FINISHED").setPackage(packageName).apply {
            putExtra("CONFIG_ID", configId)
            putExtra("LATENCY", latency)
        }
        sendBroadcast(broadcastIntent)

        Handler(Looper.getMainLooper()).post {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Thread {
            Mobile.stopRowPing()
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }
}