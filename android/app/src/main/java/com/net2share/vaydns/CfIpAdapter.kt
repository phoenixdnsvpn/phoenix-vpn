package com.net2share.vaydns

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CfIpAdapter(
    private val entries: List<CfIpManagerActivity.CfIpEntry>,
    private val onStatusChanged: () -> Unit
) : RecyclerView.Adapter<CfIpAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.cb_cf_ip)
        val editText: EditText = view.findViewById(R.id.et_cf_ip_address)
        val tvLatency: TextView = view.findViewById(R.id.tv_cf_latency)
        var textWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cf_ip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]

        holder.textWatcher?.let { holder.editText.removeTextChangedListener(it) }

        holder.editText.setText(entry.address)
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = entry.isChecked

        // Handle Latency Display
        if (entry.latencyMs > 0) {
            holder.tvLatency.text = "${entry.latencyMs} ms"
            when {
                entry.latencyMs <= 2000 -> holder.tvLatency.setTextColor(Color.parseColor("#00C853"))
                entry.latencyMs <= 6000 -> holder.tvLatency.setTextColor(Color.parseColor("#FFB300"))
                else -> holder.tvLatency.setTextColor(Color.parseColor("#F44336"))
            }
        } else {
            holder.tvLatency.text = "---"
            holder.tvLatency.setTextColor(Color.GRAY)
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                entry.address = s.toString()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        holder.editText.addTextChangedListener(watcher)
        holder.textWatcher = watcher

        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            entry.isChecked = isChecked
            onStatusChanged()
        }
    }

    override fun getItemCount() = entries.size
}