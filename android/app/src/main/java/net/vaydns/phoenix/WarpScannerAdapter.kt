package net.vaydns.phoenix

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Dedicated data class for WARP/MASQUE scanner results
data class WarpResult(
    val ip: String,
    val port: Int,
    val latencyMs: Int,
    val status: String = "ok"
) {
    val displayString: String
        get() = "$ip:$port"
}

class WarpScannerAdapter(
    private val results: List<WarpResult>
) : RecyclerView.Adapter<WarpScannerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIp: TextView = itemView.findViewById(R.id.tv_ip)
        val tvLatency: TextView = itemView.findViewById(R.id.tv_latency)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_resolver_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val currentResult = results[position]

        // Encrypt IP while preserving the port in plaintext
        //val encryptedIp = mobile.Mobile.encryptIP(currentResult.ip)
        val displayIp = if (currentResult.ip.contains(":")) currentResult.ip else "${currentResult.ip}:${currentResult.port}"

        holder.tvIp.text = displayIp
        holder.tvLatency.text = "${currentResult.latencyMs} ms"

        if (currentResult.latencyMs < 500) {
            holder.tvLatency.setTextColor(Color.parseColor("#4CAF50"))
        } else if (currentResult.latencyMs < 1500) {
            holder.tvLatency.setTextColor(Color.parseColor("#FF9800"))
        } else {
            holder.tvLatency.setTextColor(Color.parseColor("#F44336"))
        }
    }

    override fun getItemCount() = results.size
}