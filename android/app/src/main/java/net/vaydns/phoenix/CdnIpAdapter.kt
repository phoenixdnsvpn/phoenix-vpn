package net.vaydns.phoenix

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

class CdnIpAdapter(
    private val entries: List<CdnIpManagerActivity.CfIpEntry>,
    private val onStatusChanged: () -> Unit
) : RecyclerView.Adapter<CdnIpAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.cb_cf_ip)
        val editText: EditText = view.findViewById(R.id.et_cf_ip_address)
        val tvLatency: TextView = view.findViewById(R.id.tv_cf_latency)
        var textWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cdn_ip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]

        holder.textWatcher?.let { holder.editText.removeTextChangedListener(it) }
        val displayIp = if (entry.address.isNotBlank()) mobile.Mobile.encryptIP(entry.address) else ""

        holder.editText.setText(displayIp)
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = entry.isChecked

        // Handle Latency Display
        if (entry.latencyMs > 0) {
            holder.tvLatency.text = "${entry.latencyMs} ms"
            when {
                entry.latencyMs < 500 -> holder.tvLatency.setTextColor(Color.parseColor("#4CAF50")) // Green for fast
                entry.latencyMs < 1500 -> holder.tvLatency.setTextColor(Color.parseColor("#FF9800")) // Orange for moderate
                else -> holder.tvLatency.setTextColor(Color.parseColor("#F44336")) // Red for slow
            }
        } else {
            holder.tvLatency.text = "---"
            holder.tvLatency.setTextColor(Color.GRAY)
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val input = s.toString().trim()
                if (input.isNotEmpty()) {
                    entry.address = mobile.Mobile.decryptIP(input)
                } else {
                    entry.address = ""
                }
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