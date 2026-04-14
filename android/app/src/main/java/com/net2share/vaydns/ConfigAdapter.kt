package com.net2share.vaydns

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ConfigAdapter(
    private val configs: List<Config>,
    private var selectedId: String?,
    private val onConfigSelected: (Config) -> Unit,
    private val onEditClicked: (Config) -> Unit,
    private val onDeleteClicked: (Config) -> Unit,
    private val onExportClicked: (Config) -> Unit
) : RecyclerView.Adapter<ConfigAdapter.ViewHolder>() {

    fun updateSelectedId(newId: String?) {
        this.selectedId = newId
        notifyDataSetChanged()
    }
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_config_name)
        val domain: TextView = view.findViewById(R.id.tv_domain)
        val edit: ImageView = view.findViewById(R.id.btn_edit)
        val delete: ImageView = view.findViewById(R.id.btn_delete)
        val export: ImageView = view.findViewById(R.id.btn_export)
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

        val isSelected = config.id == selectedId
        if (config.isDefault) {
            holder.domain.text = "----------"

            // 2. Hide Export and Delete for official configs
            // Using GONE so the Edit button can shift or look centered
            // holder.export.visibility = View.GONE
            // holder.delete.visibility = View.GONE
        } else {
            holder.domain.text = config.domain

            // Ensure they are visible for custom user configs
            // holder.export.visibility = View.VISIBLE
            // holder.delete.visibility = View.VISIBLE
        }

        // Highlight selected config
        val cardView = holder.itemView as androidx.cardview.widget.CardView
        if (config.id == selectedId) {
            cardView.setCardBackgroundColor(0xFFCCE5FF.toInt())
        } else {
            cardView.setCardBackgroundColor(0xFFFFFFFF.toInt())
        }

        holder.itemView.setOnClickListener {
            onConfigSelected(config)
        }

        if (isSelected) {
            holder.export.alpha = 0.5f
            holder.delete.alpha = 0.5f
        } else {
            holder.export.alpha = 1.0f
            holder.delete.alpha = 1.0f
        }

        holder.export.setOnClickListener {
            if (isSelected) {
                Toast.makeText(holder.itemView.context, "Built-in configs cannot be exported.", Toast.LENGTH_SHORT).show()
            } else {
                onExportClicked(config)
            }
        }

        // holder.export.setOnClickListener { onExportClicked(config) }
        holder.edit.setOnClickListener { onEditClicked(config) }
        // holder.delete.setOnClickListener { onDeleteClicked(config) }

        holder.delete.setOnClickListener {
            if (config.isDefault) {
                Toast.makeText(holder.itemView.context, "Built-in configs cannot be deleted.", Toast.LENGTH_SHORT).show()
            } else {
                this.onDeleteClicked(config)
            }
        }
    }

    override fun getItemCount() = configs.size
}