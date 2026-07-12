package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
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
    private var fullAppList = listOf<net.vaydns.phoenix.AppListItem>()
    private var appAdapter: net.vaydns.phoenix.AppAdapter? = null

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
            val appList = mutableListOf<net.vaydns.phoenix.AppListItem>()
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            // Fetch all system launchable application details
            val resolvedInfos = packageManager.queryIntentActivities(mainIntent, 0)

            for (info in resolvedInfos) {
                val appName = info.loadLabel(packageManager).toString()
                val pkgName = info.activityInfo.packageName
                val icon = info.loadIcon(packageManager)

                // Skip Phoenix itself so we don't tunnel the tunnel loop
                if (pkgName == packageName) continue

                appList.add(
                    _root_ide_package_.net.vaydns.phoenix.AppListItem(
                        appName,
                        pkgName,
                        icon
                    )
                )
            }

            // Pre-compile full alphabetical fallback cache array
            fullAppList = appList.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                val btnToggleAll = findViewById<Button>(R.id.btn_toggle_all_apps)

                // SMART DEFAULT: If no apps are currently selected, set action to "Allow All"
                if (getSelectedApps().isEmpty()) {
                    btnToggleAll.text = "Allow All Applications"
                } else {
                    btnToggleAll.text = "Disallow All Applications"
                }

                appAdapter = _root_ide_package_.net.vaydns.phoenix.AppAdapter(
                    this@AppSelectorActivity,
                    fullAppList
                ) { pkgName, isChecked ->
                    val currentSet = getSelectedApps().toMutableSet()
                    if (isChecked) {
                        currentSet.add(pkgName)
                    } else {
                        currentSet.remove(pkgName)
                    }
                    saveSelectedApps(currentSet)

                    // Dynamically update the button text if the user manually checks/unchecks everything
                    if (currentSet.isEmpty()) {
                        btnToggleAll.text = "Allow All Applications"
                    } else {
                        btnToggleAll.text = "Disallow All Applications"
                    }
                }

                // TOGGLE BUTTON CLICK LOGIC
                btnToggleAll.setOnClickListener {
                    if (btnToggleAll.text.toString() == "Allow All Applications") {
                        // Action: Allow All Apps
                        val allPkgs = fullAppList.map { it.packageName }.toSet()
                        saveSelectedApps(allPkgs)
                        appAdapter?.updateSelectedApps(allPkgs)
                        btnToggleAll.text = "Disallow All Applications"
                    } else {
                        // Action: Disallow All Apps
                        saveSelectedApps(emptySet())
                        appAdapter?.updateSelectedApps(emptySet())
                        btnToggleAll.text = "Allow All Applications"
                    }
                }

                val recyclerView = findViewById<RecyclerView>(R.id.app_recycler_view)
                recyclerView.layoutManager = LinearLayoutManager(this@AppSelectorActivity)
                recyclerView.adapter = appAdapter

                // ATTACH REAL-TIME INPUT OBSERVER LOGIC
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