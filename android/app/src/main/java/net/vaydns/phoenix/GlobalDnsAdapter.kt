package net.vaydns.phoenix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

data class GlobalDnsResult(
    val providerName: String,
    val address: String,
    val mode: String,
    val latencyMs: Long
)

class GlobalDnsAdapter(private val results: List<GlobalDnsResult>) :
    RecyclerView.Adapter<GlobalDnsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_dns_name)
        val tvIp: TextView = view.findViewById(R.id.tv_ip_address)
        val tvLatency: TextView = view.findViewById(R.id.tv_latency)
        val btnSetDns: ImageView = view.findViewById(R.id.btn_set_dns)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_global_dns, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]

        holder.tvName.text = result.providerName
        holder.tvIp.text = result.address

        // Colorize latency based on speed
        if (result.latencyMs > 0) {
            holder.tvLatency.text = "${result.latencyMs} ms"
            when {
                result.latencyMs <= 150 -> holder.tvLatency.setTextColor(Color.parseColor("#00C853")) // Fast (Green)
                result.latencyMs <= 400 -> holder.tvLatency.setTextColor(Color.parseColor("#FFB300")) // Med (Orange)
                else -> holder.tvLatency.setTextColor(Color.parseColor("#F44336")) // Slow (Red)
            }
        } else {
            // Server is blocked by ISP or timed out
            holder.tvLatency.text = "Timeout"
            holder.tvLatency.setTextColor(Color.parseColor("#B0BEC5")) // Greyed out
        }

        holder.btnSetDns.setOnClickListener {
            // Only allow setting it if it actually passed the ping test
            if (result.latencyMs > 0) {
                val prefs = it.context.getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("global_dns_server", result.address).apply()

                Toast.makeText(it.context, "Set Global DNS to: ${result.address}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(it.context, "Cannot set a timed-out server.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount() = results.size
}