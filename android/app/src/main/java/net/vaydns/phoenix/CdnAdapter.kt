package net.vaydns.phoenix

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView


class CdnAdapter(
    private val results: List<ResolverResult>
) : RecyclerView.Adapter<CdnAdapter.ViewHolder>() {

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

        val displayIp = mobile.Mobile.encryptIP(currentResult.ip)

        holder.tvIp.text = displayIp
        holder.tvLatency.text = "${currentResult.latencyMs} ms"

        // Optional status styling if applicable
        if (currentResult.latencyMs < 500) {
            holder.tvLatency.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green for fast
        } else if (currentResult.latencyMs < 1500) {
            holder.tvLatency.setTextColor(android.graphics.Color.parseColor("#FF9800")) // Orange for moderate
        } else {
            holder.tvLatency.setTextColor(android.graphics.Color.parseColor("#F44336")) // Red for slow
        }
    }

    override fun getItemCount() = results.size
}