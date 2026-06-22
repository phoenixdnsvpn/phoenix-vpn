package com.net2share.vaydns

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ConfigAdapter(
    private val configs: List<Config>,
    private var selectedId: String?,
    private val onConfigSelected: (Config) -> Unit,
    private val onEditClicked: (Config) -> Unit,
    private val onDeleteClicked: (Config) -> Unit,
    private val onExportClicked: (Config) -> Unit
) : RecyclerView.Adapter<ConfigAdapter.ViewHolder>() {

    // CENTRAL THREAD-SAFE CACHE: Stores latencies by unique config ID
    companion object {
        val pingCache = ConcurrentHashMap<String, Long>()
        val activePings = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }

    fun updateSelectedId(newId: String?) {
        this.selectedId = newId
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view.findViewById(R.id.config_card_layout)
        val name: TextView = view.findViewById(R.id.tv_config_name)
        //val domain: TextView = view.findViewById(R.id.tv_domain)
        val edit: ImageView = view.findViewById(R.id.btn_edit)
        val delete: ImageView = view.findViewById(R.id.btn_delete)
        val export: ImageView = view.findViewById(R.id.btn_export)
        val latency: TextView = view.findViewById(R.id.tv_latency)
        val pingBtn: ImageView = view.findViewById(R.id.btn_ping)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]

        holder.name.text = config.name
        val isSelected = config.id == selectedId

        // Extract Active Protocol and Colorize Config Name
        val context = holder.itemView.context
        val isDefault = config.isDefault
        val configIndex = if (isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L
        val rawConfigType = if (isDefault) mobile.Mobile.getDefaultConfigType(configIndex) else "vaydns"

        val tunnelPrefs = context.getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        val globalOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
        val globalProtocol = tunnelPrefs.getString("global_protocol_selected", "vaydns") ?: "vaydns"

        var activeProtocol = if (isDefault && globalOverride) {
            globalProtocol
        } else if (isDefault) {
            context.getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${config.id}_tunnelProtocol", null)
                ?: rawConfigType.split(",").firstOrNull { it.isNotBlank() } ?: "vaydns"
        } else {
            // STRICT GUARDRAIL: Custom configs bypass the global override and strictly use their own protocol
            config.tunnelProtocol
        }

        if (isDefault) {
            val supportedProtocols = rawConfigType.lowercase().split(",").map { it.trim() }
            if (!supportedProtocols.contains(activeProtocol.lowercase())) {
                activeProtocol = supportedProtocols.firstOrNull { it.isNotEmpty() } ?: "vaydns"
            }
        }

        // Fetch the native default text color (Supports Light & Dark themes automatically)
        val defaultTextColor = com.google.android.material.color.MaterialColors.getColor(
            holder.itemView,
            com.google.android.material.R.attr.colorOnSurface,
            android.graphics.Color.BLACK
        )

        // Assign striking Material colors based on the protocol
        val protocolColor = when (activeProtocol.lowercase().trim()) {
            "hysteria", "hysteria2" -> android.graphics.Color.parseColor("#00897B") // Teal
            "reality"               -> android.graphics.Color.parseColor("#E64A19") // Deep Orange
            "vless-ws", "vless"     -> android.graphics.Color.parseColor("#3949AB") // Indigo
            else                    -> defaultTextColor // VayDNS (Native Black/White)
        }

        holder.name.setTextColor(protocolColor)

        holder.itemView.isSelected = isSelected

        holder.itemView.setOnClickListener {
            onConfigSelected(config)
        }

        // =========================================================
        // VISIBILITY AND CLICK LOGIC
        // =========================================================
        val nativeIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L
        val configType = if (config.isDefault) mobile.Mobile.getDefaultConfigType(nativeIndex) else "vaydns"
        val canEdit = configType.lowercase().contains("vaydns") || configType.trim().isEmpty()

        if (config.isDefault) {
            // Hide the icons completely for official configs
            holder.export.visibility = View.GONE
            holder.delete.visibility = View.GONE
        } else {
            // Show them for custom configs and apply the selection alpha
            holder.export.visibility = View.VISIBLE
            holder.delete.visibility = View.VISIBLE

            val iconAlpha = if (isSelected) 0.5f else 1.0f
            holder.export.alpha = iconAlpha
            holder.delete.alpha = iconAlpha
        }

        holder.edit.alpha = 1.0f

        holder.export.setOnClickListener {
            if (!config.isDefault) {
                onExportClicked(config)
            }
        }

        holder.edit.setOnClickListener {
            onEditClicked(config)
        }

        holder.delete.setOnClickListener {
            if (!config.isDefault) {
                this.onDeleteClicked(config)
            }
        }

        // READ FROM ID-BASED CACHE
        val cachedLatency = pingCache[config.id] ?: -1L

        if (cachedLatency > 0 && cachedLatency < 99999) {
            holder.latency.text = "${cachedLatency}ms"
            holder.latency.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green
        } else if (cachedLatency == -2L) {
            holder.latency.text = "Dead"
            holder.latency.setTextColor(android.graphics.Color.parseColor("#F44336")) // Red
        } else {
            holder.latency.text = "-- ms"
            val defaultColor = com.google.android.material.color.MaterialColors.getColor(
                holder.itemView,
                com.google.android.material.R.attr.colorOnSurface,
                android.graphics.Color.GRAY
            )
            holder.latency.setTextColor(defaultColor)
        }

        holder.pingBtn.setOnClickListener {

            val context = holder.itemView.context

            if (activePings.contains(config.id)) {
                Toast.makeText(context, "Ping already in progress for this server...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (context is MainActivity && context.isPingRunning) {
                Toast.makeText(context, "Bulk ping is running. Please wait...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activePings.add(config.id)
            holder.latency.text = "Ping..."

            // Guarantees the UI will never permanently lock up if the Go backend hangs or crashes.
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(12000) // Wait 12 seconds max
                if (activePings.contains(config.id)) {
                    android.util.Log.e("VAY_DEBUG", "UI Safety Net: Auto-unlocking ping for ${config.id}")
                    activePings.remove(config.id)
                    pingCache[config.id] = -2L // Mark as Dead/Timeout
                    notifyDataSetChanged() // Refresh the UI row
                }
            }

            val appPrefs = context.getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
            val useAllResolvers = appPrefs.getBoolean("use_all_resolvers_for_ping", false)
            val mainActivity = context as MainActivity
            val finalConfig = if (config.isDefault) DefaultConfigProvider.getActualConfig(mainActivity, config) else config

            val multipathDnsList = if (useAllResolvers) {
                mainActivity.getMultipathResolvers(config.id, finalConfig.dnsAddress, finalConfig.mode)
            } else {
                finalConfig.dnsAddress.split(",").firstOrNull()?.trim() ?: ""
            }

            val configIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L

            val prefs = context.getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
            val proxyType = prefs.getString("proxy_type", "socks5h") ?: "socks5h"
            val activeProtocol = prefs.getString("active_protocol", "vaydns") ?: "vaydns"
            val lightE2E = prefs.getBoolean("light_e2e", false)
            val workers = prefs.getInt("workers", 20)
            val tWait = prefs.getInt("tunnel_wait", 3000)
            val uTimeout = prefs.getInt("udp_timeout", 1000)
            val retries = prefs.getInt("retries", 0)
            var pTimeout = prefs.getInt("probe_timeout", 15000).toLong()

            val configVlessIp = if (finalConfig.isDefault) {
                prefs.getString("${finalConfig.id}_vlessIp", "") ?: ""
            } else {
                finalConfig.vlessIp
            }
            val globalVlessIp = context.getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                .getString("vless_ws_ip", "") ?: ""

            val finalVlessIp = if (configVlessIp.isNotEmpty()) configVlessIp else globalVlessIp

            val currentMode = config.mode.lowercase()
            if (!lightE2E && (currentMode == "udp" || currentMode == "tcp")) {
                if (pTimeout > 10000L) {
                    pTimeout = 10000L
                }
            }

            val safeWorkers = if (currentMode == "udp" && workers > 20) {
                20
            } else {
                workers
            }

            val domainIndex = if (finalConfig.isDefault) {
                context.getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE).getInt("${finalConfig.id}_domainIndex", 0)
            } else {
                finalConfig.domainIndex
            }

            val serviceIntent = Intent(context, VayRowPingService::class.java).apply {

                val rawConfigType = if (finalConfig.isDefault) mobile.Mobile.getDefaultConfigType(configIndex) else "vaydns"

                val tunnelPrefs = context.getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                val globalOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
                val globalProtocol = tunnelPrefs.getString("global_protocol_selected", "vaydns") ?: "vaydns"

                var activeProtocol = if (globalOverride) {
                    globalProtocol
                } else if (finalConfig.isDefault) {
                    context.getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                        .getString("${finalConfig.id}_tunnelProtocol", null)
                        ?: rawConfigType.split(",").firstOrNull { it.isNotBlank() } ?: "vaydns"
                } else {
                    finalConfig.tunnelProtocol
                }

                if (finalConfig.isDefault) {
                    val supportedProtocols = rawConfigType.lowercase().split(",").map { it.trim() }
                    if (!supportedProtocols.contains(activeProtocol.lowercase())) {
                        activeProtocol = supportedProtocols.firstOrNull { it.isNotEmpty() } ?: "vaydns"
                    }
                }

                // 2. SANITIZE ROUTING FOR BACKEND
                val isDirectMode = activeProtocol.lowercase() != "vaydns"
                val cleanConfigType = if (isDirectMode) "direct" else "vaydns"
                val cleanProtocol = if (isDirectMode) activeProtocol else finalConfig.protocol

                // ONLY pass the IP if it's a custom config (which is already public to the user)
                val serverIp = if (isDirectMode && !finalConfig.isDefault) {
                    finalConfig.dnsAddress
                } else {
                    "" // Send nothing for official configs to protect OPSEC!
                }

                putExtra("CONFIG_TYPE", cleanConfigType)
                putExtra("SERVER_IP", serverIp)

                putExtra("CONFIG_ID", finalConfig.id)
                putExtra("IS_DEFAULT", finalConfig.isDefault)
                putExtra("CONFIG_INDEX", configIndex)
                putExtra("MODE", finalConfig.mode)
                putExtra("DOMAIN_INDEX", domainIndex)
                // putExtra("DOMAIN", finalConfig.domain.split(",").firstOrNull()?.trim() ?: finalConfig.domain)
                putExtra("DOMAIN", finalConfig.domain)
                putExtra("PUBKEY", finalConfig.pubkey)
                putExtra("MULTIPATH_DNS", multipathDnsList)
                putExtra("BASE_DOH_URL", if (finalConfig.mode.lowercase() == "doh") finalConfig.dnsAddress else "")
                putExtra("PROXY_TYPE", proxyType)
                putExtra("PROTOCOL", cleanProtocol)
                putExtra("VLESS_WS_IP", finalVlessIp)
                val finalUser = if (finalConfig.isDefault) "" else finalConfig.user
                val finalPass = if (finalConfig.isDefault) "" else finalConfig.pass
                putExtra("USER", finalUser)
                putExtra("PASS", finalPass)
                putExtra("SS_METHOD", finalConfig.ssMethod)
                putExtra("RECORD_TYPE", finalConfig.recordType)
                putExtra("IDLE_TIMEOUT", finalConfig.idleTimeout)
                putExtra("KEEP_ALIVE", finalConfig.keepAlive)
                putExtra("CLIENT_ID_SIZE", finalConfig.clientIdSize)
                putExtra("MTU", finalConfig.mtu)
                putExtra("WORKERS", safeWorkers.toLong())
                putExtra("TUNNEL_WAIT", tWait.toLong())
                putExtra("UDP_TIMEOUT", uTimeout.toLong())
                putExtra("PROBE_TIMEOUT", pTimeout)
                putExtra("RETRIES", retries.toLong())
                putExtra("LIGHT_E2E", lightE2E)
            }
            context.startService(serviceIntent)
        }

    }

    override fun getItemCount() = configs.size
}