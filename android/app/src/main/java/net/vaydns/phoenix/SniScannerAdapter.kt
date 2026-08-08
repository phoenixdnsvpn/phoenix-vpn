package net.vaydns.phoenix

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SniResultItem(
    val sniName: String,
    val isSuccess: Boolean,
    val latencyMs: Long,
    val message: String
)

class SniScannerAdapter(private val results: List<SniResultItem>) :
    RecyclerView.Adapter<SniScannerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_sni_name)
        val tvStatus: TextView = view.findViewById(R.id.tv_sni_status)
        val tvLatency: TextView = view.findViewById(R.id.tv_sni_latency)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sni_scanner, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]

        holder.tvName.text = result.sniName

        if (result.isSuccess && result.latencyMs > 0) {
            holder.tvStatus.text = "Passed"
            holder.tvStatus.setTextColor(Color.parseColor("#00C853")) // Green
            holder.tvLatency.text = "${result.latencyMs} ms"

            when {
                result.latencyMs <= 150 -> holder.tvLatency.setTextColor(Color.parseColor("#00C853"))
                result.latencyMs <= 400 -> holder.tvLatency.setTextColor(Color.parseColor("#FFB300"))
                else -> holder.tvLatency.setTextColor(Color.parseColor("#F44336"))
            }
        } else {
            holder.tvStatus.text = "Failed: ${result.message}"
            holder.tvStatus.setTextColor(Color.parseColor("#F44336")) // Red
            holder.tvLatency.text = "Timeout"
            holder.tvLatency.setTextColor(Color.parseColor("#B0BEC5")) // Grey
        }
    }

    override fun getItemCount() = results.size
}