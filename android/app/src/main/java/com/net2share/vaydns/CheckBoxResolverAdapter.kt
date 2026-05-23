package com.net2share.vaydns

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class CheckBoxResolverAdapter(
    private val entries: List<ConfigEditorActivity.ResolverEntry>,
    private val tunnelMode: String,
    private val onStatusChanged: () -> Unit,
    private val sanitizePointer: (String, String) -> String?
) : RecyclerView.Adapter<CheckBoxResolverAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.cb_resolver)
        val editText: EditText = view.findViewById(R.id.et_resolver_address)
        val tvLatency: TextView = view.findViewById(R.id.tv_resolver_latency)
        var textWatcher: TextWatcher? = null // Keeps references clean
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_resolver, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        // Unbind existing TextWatcher before text injection to prevent recycling overwrite bugs!
        holder.textWatcher?.let { holder.editText.removeTextChangedListener(it) }

        holder.editText.setText(entry.address)
        holder.editText.hint = if (entry.isManual) "Enter Manual IP..." else ""

        holder.editText.isFocusable = entry.isManual
        holder.editText.isFocusableInTouchMode = entry.isManual
        holder.editText.isCursorVisible = entry.isManual

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = entry.isChecked

        // Handle Latency Display
        if (!entry.isManual && entry.latency.isNotEmpty()) {
            holder.tvLatency.visibility = View.VISIBLE
            holder.tvLatency.text = "${entry.latency} ms"
            val lat = entry.latency.toIntOrNull() ?: 0
            holder.tvLatency.setTextColor(when {
                lat <= 2000 -> Color.parseColor("#00C853")
                lat <= 6000 -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#F44336")
            })
        } else {
            holder.tvLatency.visibility = View.GONE
        }

        // Field Editor Tracking
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val txt = s.toString().trim()
                if (entry.address != txt) {
                    entry.address = txt
                    if (holder.editText.hasFocus() && holder.checkBox.isChecked) {
                        holder.checkBox.isChecked = false
                        entry.isChecked = false
                        onStatusChanged()
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        holder.editText.addTextChangedListener(watcher)
        holder.textWatcher = watcher

        // Checkbox State Switching Logic
        holder.checkBox.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                if (entry.address.isBlank()) {
                    buttonView.isChecked = false
                    Toast.makeText(holder.itemView.context, "Please enter an IP address first", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }

                val clean = sanitizePointer(entry.address, tunnelMode)
                if (clean != null) {
                    if (clean != entry.address) {
                        entry.address = clean
                        holder.editText.setText(clean)
                    }
                    entry.isChecked = true
                } else {
                    buttonView.isChecked = false
                    Toast.makeText(holder.itemView.context, "Invalid IP for ${tunnelMode.uppercase()} mode.", Toast.LENGTH_SHORT).show()
                }
            } else {
                entry.isChecked = false
            }
            onStatusChanged()
        }
    }

    override fun getItemCount() = entries.size
}