package net.vaydns.phoenix

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import mobile.Mobile


class VayAutoConnectService : Service() {
    companion object {
        const val NOTIF_ID = 3
    }
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "ACTION_STOP_VPN") {
            isRunning = false
            try { Mobile.stopVpn() } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NOTIF_ID)
            nm.cancel(2)
            stopSelf()
            Log.i("PhoenixAuto", "Sandbox process exiting")
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "VAY_CHANNEL_AUTO_CONNECT")
            .setContentTitle("Phoenix Auto-Connect")
            .setContentText("Finding the fastest available server...")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .build()

        // Android 14+ Crash Prevention
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        isRunning = true

        Thread {
            // Extract the original parameters passed from MainActivity
            val originalIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
            val isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT_CONFIG", false)
            val configType = intent.getStringExtra("CONFIG_TYPE") ?: "vaydns"
            val useMultiDomains = intent.getBooleanExtra("USE_MULTI_DOMAINS", false)
            val domainIndex = intent.getIntExtra("DOMAIN_INDEX", 0)
            val domain = intent.getStringExtra("DOMAIN") ?: ""
            val pubkey = (intent.getStringExtra("PUBKEY") ?: "").replace("\\s".toRegex(), "")
            val baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
            val dnsAddress = intent.getStringExtra("UDP") ?: "8.8.8.8:53"
            val mode = intent.getStringExtra("MODE") ?: "udp"
            val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
            val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
            val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
            val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
            val mtu = intent.getLongExtra("MTU", 0L)
            val dnsttCompatible = intent.getBooleanExtra("DNSTT_COMPATIBLE", false)
            val useAuth = intent.getBooleanExtra("USE_AUTH", false)
            val tunnelProtocol = intent.getStringExtra("TUNNEL_PROTOCOL") ?: "vaydns"
            val localProxyProtocol = intent.getStringExtra("LOCAL_PROXY_PROTOCOL") ?: "socks5"
            val authProtocol = intent.getStringExtra("AUTH_PROTOCOL") ?: "socks"
            val ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
            val masterDnsMethod = intent.getStringExtra("MASTERDNS_METHOD") ?: "XOR"
            val user = intent.getStringExtra("USER") ?: ""
            val pass = intent.getStringExtra("PASS") ?: ""
            val engineType = intent.getStringExtra("ENGINE_TYPE") ?: "sing-box"
            val vlessWsIp = intent.getStringExtra("VLESS_WS_IP") ?: ""
            val targetCdn = intent.getStringExtra("TARGET_CDN") ?: "CloudX"
            val fragment = intent.getBooleanExtra("USE_FRAGMENTATION", false)
            val blockQuic = intent.getBooleanExtra("BLOCK_QUIC", true)
            val getServerIpFromDomain = intent.getBooleanExtra("GET_SERVER_IP_FROM_DOMAIN", false)
            val sniIndex = intent.getLongExtra("SNI_INDEX", -1L)
            val useHysteriaCore = intent.getBooleanExtra("USE_HYSTERIA_CORE", false)
            val isProxyMode = intent.getBooleanExtra("IS_PROXY_MODE", false)

            val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
            val globalDnsServer = tunnelPrefs.getString("global_dns_server", "1.1.1.1") ?: "1.1.1.1"
            val isDebugEnabled = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE).getBoolean("debug_logs_enabled", false)

            val slipstreamCongestion = intent.getStringExtra("SLIPSTREAM_CONGESTION") ?: "BBR"
            val slipstreamAuthoritative = intent.getBooleanExtra("SLIPSTREAM_AUTHORITATIVE", false)
            val slipstreamGso = intent.getBooleanExtra("SLIPSTREAM_GSO", false)

            var udp = ""; var tcp = ""; var doh = ""; var dot = ""
            when (mode.lowercase()) {
                "udp" -> udp = dnsAddress
                "tcp" -> tcp = dnsAddress
                "doh" -> doh = dnsAddress
                "dot" -> dot = dnsAddress
            }

            val dns_mode = intent.getStringExtra("DNS_MODE") ?: when ((intent.getStringExtra("MODE") ?: "udp").lowercase()) {
                "tcp" -> "TCP"
                "dot" -> "DoT"
                "doh" -> "DoH"
                else -> "UDP"
            }

            // =========================================================================
            // 1. FETCH SHUFFLED ELIGIBLE CONFIGS
            // =========================================================================
            // Get the fully shuffled list of all eligible auto-connect indices from Go
            val indicesCsv = Mobile.getRandomizedConfigIndices()
            val candidateIndices = indicesCsv.split(",").mapNotNull { it.trim().toLongOrNull() }

            // Failsafe: If no configs were marked "randomize" in the JSON, fallback to the original tapped index
            val fallbackList = if (candidateIndices.isNotEmpty()) candidateIndices else listOf(originalIndex)

            val maxAttempts = fallbackList.size
            var connected = false

            sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                putExtra("status", "CONNECTING")
                setPackage(packageName)
            })

            // =========================================================================
            // 2. ITERATE DETERMINISTICALLY
            // =========================================================================
            for ((attemptIndex, candidateIndex) in fallbackList.withIndex()) {
                if (!isRunning) break
                val currentAttempt = attemptIndex + 1

                Log.i("PhoenixAuto", "Attempt $currentAttempt/$maxAttempts: Testing Config Index $candidateIndex with REAL Proxy Engine")

                if (tunnelProtocol.lowercase() == "slipstream") {
                    val slipstreamPath = applicationInfo.nativeLibraryDir + "/libslipstream.so"
                    mobile.Mobile.setSlipstreamBinaryPath(slipstreamPath)
                }

                // Start the lightweight Proxy engine using the current candidate index
                val proxyResult = Mobile.startProxy(
                    engineType, isDefaultConfig, true, candidateIndex, configType, useMultiDomains, domainIndex.toLong(),
                    udp, tcp, doh, dot, baseDohUrl, domain, pubkey, recordType, idleTimeout, keepAlive,
                    clientIdSize, mtu, dnsttCompatible, useAuth, tunnelProtocol, localProxyProtocol,
                    authProtocol, ssMethod, masterDnsMethod, user, pass, 35000L, vlessWsIp, targetCdn, globalDnsServer,
                    isDebugEnabled, fragment, blockQuic, getServerIpFromDomain, sniIndex, useHysteriaCore, dns_mode,
                    slipstreamCongestion, slipstreamAuthoritative, slipstreamGso,
                )

                if (proxyResult.contains("Success")) {
                    Log.i("PhoenixAuto", "Proxy Engine mounted locally. Firing E2E HTTP payload to test DPI...")

                    // Wait exactly 1.5s for the native core to stabilize its routing
                    Thread.sleep(1500)

                    val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                    val maxAttempts = tunnelPrefs.getLong("max_verification_attempts", 2L)

                    // Send REAL HTTP traffic through the tunnel to prove DPI isn't dropping the connection
                    val verifyResult = Mobile.verifyTunnel(tunnelProtocol, maxAttempts)

                    if (verifyResult.contains("Success")) {
                        Log.i("PhoenixAuto", "SUCCESS! Config $candidateIndex defeated DPI. Handing off to Main VPN...")

                        sendBroadcast(
                            Intent(this@VayAutoConnectService, AutoconnectHandoffReceiver::class.java)
                                .putExtra("CONFIG_INDEX", candidateIndex)
                                .putExtra("TUNNEL_PROTOCOL", tunnelProtocol)
                        )
                        isRunning = false
                        connected = true

                        Handler(Looper.getMainLooper()).post {
                            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                            nm.cancel(NOTIF_ID)
                            nm.cancel(2)
                            Log.i("PhoenixAuto", "Sandbox process exiting")
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }

                        break

                    } else {
                        Log.w("PhoenixAuto", "Config $candidateIndex blocked by DPI during payload transfer. Trying next...")
                    }
                } else {
                    Log.w("PhoenixAuto", "Config $candidateIndex failed to mount proxy: $proxyResult")
                }

                // Clean up the dead engine before trying the next one in the list
                Mobile.stopVpn()
                Thread.sleep(500)
            }

            if (!connected && isRunning) {
                Log.e("PhoenixAuto", "Exhausted all $maxAttempts attempts. No working servers found.")
                sendBroadcast(Intent("VPN_STATE_CHANGED").apply {
                    putExtra("status", "ERROR")
                    putExtra("message", "Auto-Connect Failed: All eligible servers are blocked by DPI.")
                    setPackage(packageName)
                })
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

        }.start()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("VAY_CHANNEL_AUTO_CONNECT", "Auto Connect Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}