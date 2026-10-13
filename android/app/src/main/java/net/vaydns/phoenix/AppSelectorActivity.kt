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
    private var masterAppList = listOf<net.vaydns.phoenix.AppListItem>() // Holds ALL apps permanently
    private var fullAppList = listOf<net.vaydns.phoenix.AppListItem>()
    private var appAdapter: net.vaydns.phoenix.AppAdapter? = null
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar

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
        toolbar = findViewById(R.id.toolbar_app_selector)
        toolbar.setNavigationOnClickListener {
            finish() // Closes this activity and safely returns to MainActivity
        }

        // SET INITIAL COUNT ON LOAD
        val currentSelectedCount = getSelectedApps().size
        toolbar.title = "Select Apps $currentSelectedCount"

        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.Default) {
            val appList = mutableListOf<net.vaydns.phoenix.AppListItem>()

            // 1. ORIGINAL LOGIC: Find all apps with a Home Screen Icon (Chrome, YouTube, Gemini, User Apps)
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherPackages = packageManager.queryIntentActivities(mainIntent, 0)
                .map { it.activityInfo.packageName }
                .toSet()

            // 2. NEW LOGIC: Fetch absolutely everything installed on the device
            val allInstalledApps = packageManager.getInstalledApplications(0)

            for (appInfo in allInstalledApps) {
                val pkgName = appInfo.packageName

                // Skip Phoenix itself
                if (pkgName == packageName) continue

                // HYBRID CLASSIFICATION:
                // If it has a launcher icon, it's a Normal App (isSystemApp = false).
                // If it does NOT have a launcher icon, it's a Headless System App (isSystemApp = true).
                val isSystem = !launcherPackages.contains(pkgName)

                val appName = appInfo.loadLabel(packageManager).toString()
                val icon = appInfo.loadIcon(packageManager)

                appList.add(
                    net.vaydns.phoenix.AppListItem(
                        name = appName,
                        packageName = pkgName,
                        icon = icon,
                        isSystemApp = isSystem
                    )
                )
            }

            masterAppList = appList.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                val btnToggleAll = findViewById<Button>(R.id.btn_toggle_all_apps)
                val swSystemApps = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_display_system_apps)

                if (getSelectedApps().isEmpty()) {
                    btnToggleAll.text = "Allow All Applications"
                } else {
                    btnToggleAll.text = "Disallow All Applications"
                }

                updateVisibleAppList(swSystemApps.isChecked)

                appAdapter = net.vaydns.phoenix.AppAdapter(
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

                    toolbar.title = "Select Apps ${currentSet.size}"

                    if (currentSet.isEmpty()) {
                        btnToggleAll.text = "Allow All Applications"
                    } else {
                        btnToggleAll.text = "Disallow All Applications"
                    }
                }

                swSystemApps.setOnCheckedChangeListener { _, isChecked ->
                    updateVisibleAppList(isChecked)
                }

                btnToggleAll.setOnClickListener {
                    if (btnToggleAll.text.toString() == "Allow All Applications") {
                        val allPkgs = fullAppList.map { it.packageName }.toSet()
                        saveSelectedApps(allPkgs)
                        appAdapter?.updateSelectedApps(allPkgs)
                        btnToggleAll.text = "Disallow All Applications"
                        toolbar.title = "Select Apps ${allPkgs.size}"
                    } else {
                        saveSelectedApps(emptySet())
                        appAdapter?.updateSelectedApps(emptySet())
                        btnToggleAll.text = "Allow All Applications"
                        toolbar.title = "Select Apps 0"
                    }
                }

                val recyclerView = findViewById<RecyclerView>(R.id.app_recycler_view)
                recyclerView.layoutManager = LinearLayoutManager(this@AppSelectorActivity)
                recyclerView.adapter = appAdapter

                setupSearchListener()
            }
        }
    }

    private fun updateVisibleAppList(showSystem: Boolean) {
        fullAppList = if (showSystem) {
            masterAppList
        } else {
            masterAppList.filter { !it.isSystemApp }
        }

        // Re-apply any active search text over the newly populated list
        val etSearch = findViewById<EditText>(R.id.et_search_apps)
        filterApps(etSearch?.text?.toString() ?: "")
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
        val sharedPref = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putStringSet("allowed_apps", selectedPackages)
            apply()
        }
    }

    private fun getSelectedApps(): Set<String> {
        val sharedPref = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
        return sharedPref.getStringSet("allowed_apps", emptySet()) ?: emptySet()
    }
}