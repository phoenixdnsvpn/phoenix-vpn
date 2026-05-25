package com.net2share.vaydns

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ResolverTransferAdapter(
    private val items: List<TransferItem>,
    private val onSelected: (String, Boolean) -> Unit
) : RecyclerView.Adapter<ResolverTransferAdapter.ViewHolder>() {

    // Simple data class to hold the config and the count from the JSON/Device
    data class TransferItem(val config: Config, val resolverCount: Int)

    private val checkedMap = mutableMapOf<String, Boolean>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cb: CheckBox = view.findViewById(R.id.cb_config)
        val name: TextView = view.findViewById(R.id.tv_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_resolver_transfer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = "${item.config.name} (${item.resolverCount} resolvers)"

        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = checkedMap[item.config.id] ?: false

        holder.cb.setOnCheckedChangeListener { _, isChecked ->
            checkedMap[item.config.id] = isChecked
            onSelected(item.config.id, isChecked)
        }
    }

    fun setAllChecked(checked: Boolean) {
        items.forEach { checkedMap[it.config.id] = checked }
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
}