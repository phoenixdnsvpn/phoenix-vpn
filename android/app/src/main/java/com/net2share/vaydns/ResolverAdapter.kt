package com.net2share.vaydns

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ResolverAdapter(private val results: List<ResolverResult>) :
    RecyclerView.Adapter<ResolverAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIp: TextView = view.findViewById(R.id.tv_ip)
        val tvLatency: TextView = view.findViewById(R.id.tv_latency)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_resolver_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = results[position]

        holder.tvIp.text = item.ip
        holder.tvLatency.text = "${item.latencyMs} ms"

        // Color coding
        when {
            item.latencyMs <= 2000 -> {
                holder.tvLatency.setTextColor(Color.parseColor("#00C853")) // Green
            }
            item.latencyMs <= 6000 -> {
                holder.tvLatency.setTextColor(Color.parseColor("#FFB300")) // Yellow/Orange
            }
            else -> {
                holder.tvLatency.setTextColor(Color.parseColor("#F44336")) // Red
            }
        }
    }

    override fun getItemCount() = results.size
}