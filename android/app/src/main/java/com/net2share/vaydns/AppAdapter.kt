package com.net2share.vaydns

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.net2share.vaydns.AppInfo
import com.net2share.vaydns.R

class AppAdapter(
    private val context: Context,
    private var apps: List<AppListItem>,
    private val onAppSelected: (String, Boolean) -> Unit // This is the "Trigger" callback
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    // Load the already saved apps once when the adapter starts
    private val savedApps = context.getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        .getStringSet("allowed_apps", emptySet())?.toMutableSet() ?: mutableSetOf()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView = view.findViewById(R.id.app_icon)
        val appName: TextView = view.findViewById(R.id.app_name)
        val checkbox: CheckBox = view.findViewById(R.id.app_checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.appName.text = app.name
        holder.appIcon.setImageDrawable(app.icon)

        // Set the checkbox state based on saved data
        holder.checkbox.setOnCheckedChangeListener(null) // Prevent recursive trigger
        holder.checkbox.isChecked = savedApps.contains(app.packageName)

        // THIS IS THE TRIGGER:
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                savedApps.add(app.packageName)
            } else {
                savedApps.remove(app.packageName)
            }
            // Notify the Activity to save the new set
            onAppSelected(app.packageName, isChecked)
        }
    }

    override fun getItemCount() = apps.size
}