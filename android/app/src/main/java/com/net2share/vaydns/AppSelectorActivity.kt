package com.net2share.vaydns

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import com.net2share.vaydns.AppAdapter
import com.net2share.vaydns.AppListItem

class AppSelectorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_app_selector)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // --- START OF NEW LOGIC ---
        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.Default) {
            val appList = mutableListOf<AppListItem>()
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            // Get all launchable apps
            val resolvedInfos = packageManager.queryIntentActivities(mainIntent, 0)

            for (info in resolvedInfos) {
                val appName = info.loadLabel(packageManager).toString()
                val pkgName = info.activityInfo.packageName
                val icon = info.loadIcon(packageManager)

                // Skip VayDNS itself so we don't tunnel the tunnel!
                if (pkgName == packageName) continue

                appList.add(AppListItem(appName, pkgName, icon))
            }

            // Sort alphabetically
            val sortedList = appList.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                // Initialize the adapter with the sorted list and the "Save" logic
                val adapter =
                    AppAdapter(this@AppSelectorActivity, sortedList) { pkgName, isChecked ->
                        // This block runs every time a checkbox is clicked in the list
                        val currentSet = getSelectedApps().toMutableSet()
                        if (isChecked) {
                            currentSet.add(pkgName)
                        } else {
                            currentSet.remove(pkgName)
                        }
                        saveSelectedApps(currentSet)
                    }

                // Find the RecyclerView from your XML and attach the adapter
                val recyclerView = findViewById<RecyclerView>(R.id.app_recycler_view)
                recyclerView.layoutManager = LinearLayoutManager(this@AppSelectorActivity)
                recyclerView.adapter = adapter
            }
        }
    }

    // --- SAVE SELECTION ---
    private fun saveSelectedApps(selectedPackages: Set<String>) {
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putStringSet("allowed_apps", selectedPackages)
            apply()
        }
    }

    // --- LOAD SELECTION (To show which boxes are already checked) ---
    private fun getSelectedApps(): Set<String> {
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        return sharedPref.getStringSet("allowed_apps", emptySet()) ?: emptySet()
    }

}