package com.net2share.vaydns

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ConfigAdapter(
    private val configs: List<Config>,
    private val selectedId: String?,
    private val onConfigSelected: (Config) -> Unit,
    private val onEditClicked: (Config) -> Unit,
    private val onDeleteClicked: (Config) -> Unit
) : RecyclerView.Adapter<ConfigAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_config_name)
        val domain: TextView = view.findViewById(R.id.tv_domain)     // ← Fixed
        val edit: ImageView = view.findViewById(R.id.btn_edit)       // ← Fixed
        val delete: ImageView = view.findViewById(R.id.btn_delete)   // ← Fixed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = configs[position]

        holder.name.text = config.name
        holder.domain.text = config.domain          // ← Just the domain, no label

        holder.name.setOnClickListener { onConfigSelected(config) }

        // Highlight selected config
        if (config.id == selectedId) {
            holder.itemView.setBackgroundColor(0xFFCCE5FF.toInt())
        } else {
            holder.itemView.setBackgroundColor(0xFFFFFFFF.toInt())
        }

        holder.edit.setOnClickListener { onEditClicked(config) }
        holder.delete.setOnClickListener { onDeleteClicked(config) }
    }

    override fun getItemCount() = configs.size
}