package com.net2share.vaydns

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
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
            holder.latency.text = "Ping..."

            CoroutineScope(Dispatchers.IO).launch {
                val addressToPing = config.dnsAddress.split(",").firstOrNull()?.trim() ?: ""
                val configIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else 0L

                val prefs = holder.itemView.context.getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                val proxyType = prefs.getString("proxy_type", "socks5h") ?: "socks5h"
                val lightE2E = prefs.getBoolean("light_e2e", false)
                val workers = prefs.getInt("workers", 20).toLong()
                val tWait = prefs.getInt("tunnel_wait", 3000).toLong()
                val pTimeout = prefs.getInt("probe_timeout", 15000).toLong()
                val uTimeout = prefs.getInt("udp_timeout", 1000).toLong()
                val retries = prefs.getInt("retries", 0).toLong()

                val latencyMs = mobile.Mobile.pingServer(
                    config.isDefault, configIndex, addressToPing, config.mode,
                    config.domain, config.pubkey, "https://cloudflare-dns.com/dns-query",
                    proxyType, config.authProtocol, config.user, config.pass, config.ssMethod,
                    config.recordType, config.idleTimeout, config.keepAlive, config.clientIdSize,
                    lightE2E, workers, tWait, pTimeout, uTimeout, retries
                )

                withContext(Dispatchers.Main) {
                    // SAVE TO CENTRAL CACHE
                    pingCache[config.id] = if (latencyMs > 0 && latencyMs < 99999) latencyMs else -2L

                    val currentPos = holder.bindingAdapterPosition
                    if (currentPos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(currentPos)
                    }
                }
            }
        }
    }

    override fun getItemCount() = configs.size
}