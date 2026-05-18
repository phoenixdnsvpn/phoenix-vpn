package com.net2share.vaydns

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectorActivity : AppCompatActivity() {

    // Persistent storage arrays for tracking application queries safely across threads
    private var fullAppList = listOf<AppListItem>()
    private var appAdapter: AppAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_app_selector)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // BIND TOOLBAR AND SET NAVIGATION CLICK LISTENER
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_app_selector)
        toolbar.setNavigationOnClickListener {
            finish() // Closes this activity and safely returns to MainActivity
        }

        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.Default) {
            val appList = mutableListOf<AppListItem>()
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            // Fetch all system launchable application details
            val resolvedInfos = packageManager.queryIntentActivities(mainIntent, 0)

            for (info in resolvedInfos) {
                val appName = info.loadLabel(packageManager).toString()
                val pkgName = info.activityInfo.packageName
                val icon = info.loadIcon(packageManager)

                // Skip VayDNS itself so we don't tunnel the tunnel loop
                if (pkgName == packageName) continue

                appList.add(AppListItem(appName, pkgName, icon))
            }

            // Pre-compile full alphabetical fallback cache array
            fullAppList = appList.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                appAdapter = AppAdapter(this@AppSelectorActivity, fullAppList) { pkgName, isChecked ->
                    val currentSet = getSelectedApps().toMutableSet()
                    if (isChecked) {
                        currentSet.add(pkgName)
                    } else {
                        currentSet.remove(pkgName)
                    }
                    saveSelectedApps(currentSet)
                }

                val recyclerView = findViewById<RecyclerView>(R.id.app_recycler_view)
                recyclerView.layoutManager = LinearLayoutManager(this@AppSelectorActivity)
                recyclerView.adapter = appAdapter

                // 🟢 ATTACH REAL-TIME INPUT OBSERVER LOGIC
                setupSearchListener()
            }
        }
    }

    private fun setupSearchListener() {
        val etSearch = findViewById<EditText>(R.id.et_search_apps)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun filterApps(query: String) {
        val cleanQuery = query.trim().lowercase()

        if (cleanQuery.isEmpty()) {
            // Restore clean full cache view array if input cleared
            appAdapter?.updateList(fullAppList)
        } else {
            // High-speed dual sub-string filter check on localized variables
            val filtered = fullAppList.filter {
                it.name.lowercase().contains(cleanQuery) ||
                        it.packageName.lowercase().contains(cleanQuery)
            }
            appAdapter?.updateList(filtered)
        }
    }

    private fun saveSelectedApps(selectedPackages: Set<String>) {
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putStringSet("allowed_apps", selectedPackages)
            apply()
        }
    }

    private fun getSelectedApps(): Set<String> {
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        return sharedPref.getStringSet("allowed_apps", emptySet()) ?: emptySet()
    }
}