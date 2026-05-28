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
        /*holder.domain.text = config.domain
        if (config.isDefault) {
            holder.domain.text = "----------"
        } else {
            holder.domain.text = config.domain
        }*/

        holder.itemView.isSelected = isSelected

        holder.itemView.setOnClickListener {
            onConfigSelected(config)
        }

        val iconAlpha = if (isSelected) 0.5f else 1.0f
        holder.export.alpha = iconAlpha
        holder.delete.alpha = iconAlpha

        holder.export.setOnClickListener {
            if (config.isDefault) {
                Toast.makeText(holder.itemView.context, "Built-in configs cannot be exported.", Toast.LENGTH_SHORT).show()
            } else {
                onExportClicked(config)
            }
        }

        holder.edit.setOnClickListener { onEditClicked(config) }

        holder.delete.setOnClickListener {
            if (config.isDefault) {
                Toast.makeText(holder.itemView.context, "Built-in configs cannot be deleted.", Toast.LENGTH_SHORT).show()
            } else {
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
            val lightE2E = prefs.getBoolean("light_e2e", false)
            val workers = prefs.getInt("workers", 20)
            val tWait = prefs.getInt("tunnel_wait", 3000)
            val uTimeout = prefs.getInt("udp_timeout", 1000)
            val retries = prefs.getInt("retries", 0)
            var pTimeout = prefs.getInt("probe_timeout", 15000).toLong()

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

            val serviceIntent = Intent(context, VayRowPingService::class.java).apply {
                putExtra("CONFIG_ID", config.id)
                putExtra("IS_DEFAULT", config.isDefault)
                putExtra("CONFIG_INDEX", configIndex)
                putExtra("MODE", finalConfig.mode)
                //putExtra("DOMAIN", finalConfig.domain)
                putExtra("DOMAIN", finalConfig.domain.split(",").firstOrNull()?.trim() ?: finalConfig.domain)
                putExtra("PUBKEY", finalConfig.pubkey)
                putExtra("MULTIPATH_DNS", multipathDnsList)
                putExtra("BASE_DOH_URL", if (finalConfig.mode.lowercase() == "doh") finalConfig.dnsAddress else "")
                putExtra("PROXY_TYPE", proxyType)
                putExtra("PROTOCOL", finalConfig.protocol)
                putExtra("USER", finalConfig.user)
                putExtra("PASS", finalConfig.pass)
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