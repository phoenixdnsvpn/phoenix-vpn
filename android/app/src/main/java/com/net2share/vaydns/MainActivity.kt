package com.net2share.vaydns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Color
import android.net.VpnService
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.EditText
import android.util.TypedValue
import android.util.Base64
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.net2share.vaydns.ConfigEditorActivity.Companion.loadAllConfigs
import com.net2share.vaydns.ConfigEditorActivity.Companion.saveAllConfigs
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.net.URL

private lateinit var rgMode: RadioGroup   // kept only for editor (we don't use it here anymore)
private lateinit var tvStatus: TextView
//private lateinit var btnStart: Button
//private lateinit var btnStop: Button
private lateinit var btnToggle: Button
private var isVpnConnected = false // Track state locally for the toggle logic
private lateinit var recyclerConfigs: RecyclerView
private lateinit var switchDefault: androidx.appcompat.widget.SwitchCompat
private lateinit var layoutNetworkStats: LinearLayout
private lateinit var tvSpeed: TextView
private lateinit var tvTotal: TextView
private var selectedConfigId: String? = null   // which config is active for START
private val configList = mutableListOf<Config>() // Class-level list to hold User + Default configs
private var isProxyMode = false
private lateinit var layoutVpnControls: LinearLayout
private lateinit var layoutProxyControls: LinearLayout
//private lateinit var tvProxyAddress: TextView
private lateinit var etProxyPort: EditText
private var selectedApps = mutableSetOf<String>()
private lateinit var tvSelectedAppsInfo: TextView
private var activePendingRx = 0L
private var activePendingTx = 0L
private var liveDailyRx = -1L
private var liveDailyTx = -1L
private var liveTrackingDate = ""
private lateinit var tvProxyIpLabel: TextView

private var liveDailyOsRx = -1L
private var liveDailyOsTx = -1L
private var activeLocalProxyPort: Int = 1080

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var navView: com.google.android.material.navigation.NavigationView
    private lateinit var toggle: androidx.appcompat.app.ActionBarDrawerToggle
    private var configAdapter: ConfigAdapter? = null
    private var hasRunStartupPing = false
    private var currentSortMode = SortMode.NONE
    var isPingRunning = false

    enum class SortMode {
        NONE, NAME_ASC, NAME_DESC, LATENCY_ASC, LATENCY_DESC
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action == "VPN_STATS_UPDATE") {
                if (!isVpnConnected) {
                    isVpnConnected = true
                    updateUIState(true)
                }
                val speed = intent.getStringExtra("speed") ?: ""
                val total = intent.getStringExtra("total") ?: ""
                tvSpeed.text = speed
                tvTotal.text = total
                liveDailyRx = intent.getLongExtra("liveDailyRx", -1L)
                liveDailyTx = intent.getLongExtra("liveDailyTx", -1L)
                liveDailyOsRx = intent.getLongExtra("liveDailyOsRx", -1L)
                liveDailyOsTx = intent.getLongExtra("liveDailyOsTx", -1L)
                liveTrackingDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                return
            }

            val status = intent?.getStringExtra("status")
            when (status) {
                "CONNECTED" -> {
                    isVpnConnected = true
                    val realAddr = intent.getStringExtra("proxy_address") ?: "127.0.0.1"
                    tvProxyIpLabel.text = "IP: ${realAddr.substringBefore(":")}"
                    activeLocalProxyPort = realAddr.substringAfter(":", "1080").toIntOrNull() ?: 1080
                    updateUIState(true)

                    // Lock the port field while running
                    if (isProxyMode && ::etProxyPort.isInitialized) {
                        etProxyPort.isEnabled = false
                    }
                }
                "ERROR" -> {
                    // 1. Wipe the active ID memory
                    getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE).edit().remove("connected_config_id").apply()

                    // 2. TELL SERVICES TO INITIATE GRACEFUL SELF-DESTRUCT
                    startService(Intent(this@MainActivity, VayVpnService::class.java).apply { action = "ACTION_STOP_VPN" })
                    startService(Intent(this@MainActivity, VayProxyService::class.java).apply { action = "ACTION_STOP_VPN" })

                    isVpnConnected = false
                    tvProxyIpLabel.text = "IP: 127.0.0.1"
                    updateUIState(false)

                    if (isProxyMode && ::etProxyPort.isInitialized) {
                        etProxyPort.isEnabled = true
                    }

                    val msg = intent.getStringExtra("message") ?: "Failed to start proxy"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
                "DISCONNECTED", "STOPPED" -> {
                    // 1. Wipe the active ID memory
                    getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE).edit().remove("connected_config_id").apply()

                    // 2. TELL SERVICES TO INITIATE GRACEFUL SELF-DESTRUCT
                    startService(Intent(this@MainActivity, VayVpnService::class.java).apply { action = "ACTION_STOP_VPN" })
                    startService(Intent(this@MainActivity, VayProxyService::class.java).apply { action = "ACTION_STOP_VPN" })

                    isVpnConnected = false
                    tvProxyIpLabel.text = "IP: 127.0.0.1"
                    updateUIState(false)

                    if (isProxyMode && ::etProxyPort.isInitialized) {
                        etProxyPort.isEnabled = true
                    }
                    activePendingRx = 0L
                    activePendingTx = 0L
                }
            }
        }
    }

    private val pingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "PING_ALL_FINISHED") {
                val resultsJson = intent.getStringExtra("RESULTS_JSON") ?: return

                try {
                    val resultsObj = org.json.JSONObject(resultsJson)
                    val keys = resultsObj.keys()
                    while (keys.hasNext()) {
                        val configId = keys.next()
                        val latency = resultsObj.getLong(configId)
                        ConfigAdapter.pingCache[configId] = latency
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VAY_DEBUG", "Failed to deserialize ping results: ${e.message}")
                }
                // 1. Refresh the UI with the new latencies
                configAdapter?.notifyDataSetChanged()

                // 2. UNLOCK THE UI
                isPingRunning = false

                // 3. Notify the user it's safe to ping again
                Toast.makeText(this@MainActivity, "Ping sequence complete.", Toast.LENGTH_SHORT).show()
            }

            if (intent?.action == "ROW_PING_FINISHED") {
                val configId = intent.getStringExtra("CONFIG_ID") ?: return
                val latency = intent.getLongExtra("LATENCY", -2L)

                // UNLOCK THE UI FOR THIS SPECIFIC ROW
                ConfigAdapter.activePings.remove(configId)

                // Update the thread-safe cache
                ConfigAdapter.pingCache[configId] = latency

                // Tell the adapter to refresh that specific row
                configAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun updateUIState(connected: Boolean) {
        runOnUiThread {
            if (connected) {
// Read the actual connected config, fallback to selected if missing
                val prefs = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
                val connectedId = prefs.getString("connected_config_id", selectedConfigId)

                // FIND THE ACTIVE CONFIGURATION NAME FROM THE CACHED LIST
                val activeConfig = configList.find { it.id == connectedId }
                if (activeConfig != null) {
                    supportActionBar?.title = "VayDNS - ${activeConfig.name}"
                } else {
                    supportActionBar?.title = "VayDNS"
                }

                tvStatus.text = "Status: Connected"
                tvStatus.setTextColor(Color.parseColor("#006400")) // Green text for status

                btnToggle.text = "STOP TUNNEL"
                // Sleek Red for Stop state
                btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4F7F84"))
                layoutNetworkStats.visibility = android.view.View.VISIBLE
            } else {
                // RESTORE DEFAULT APP HEADER WHEN DISCONNECTED
                supportActionBar?.title = "VayDNS"

                tvStatus.text = "Status: Disconnected"
                tvStatus.setTextColor(Color.parseColor("#2F4A6F")) // Original theme color

                btnToggle.text = "START TUNNEL"
                // Original Sleek Blue/Gray for Start state
                btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                layoutNetworkStats.visibility = android.view.View.GONE
                tvSpeed.text = "▼ 0 B/s  ▲ 0 B/s"
                tvTotal.text = "Total: 0 B ↓  0 B ↑"
            }
            btnToggle.isEnabled = true
        }
    }

    private fun initDefaultSettings() {
        val prefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)

        // 1. Existing Tunnel settings initialization
        if (!prefs.contains("enable_prescan")) {
            prefs.edit().apply {
                putBoolean("enable_prescan", true)
                putString("proxy_type", "socks5h")
                putBoolean("light_e2e", false)
                putInt("workers", 20)
                putInt("tunnel_wait", 3000)
                putInt("probe_timeout", 15000)
                putInt("udp_timeout", 1000)
                putInt("retries", 0)
                apply()
            }
            Log.i("VAY_DEBUG", "Default settings initialized on first launch.")
        }

        // 2. EXPLICIT UI TOGGLE DEFAULTS
        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        if (!appPrefs.contains("default_configs_at_start")) {
            appPrefs.edit().apply {
                // TRUE: Show official configs by default on startup
                putBoolean("default_configs_at_start", true)

                // FALSE: Hide legacy/pure VayDNS configs by default
                if (!appPrefs.contains("display_vaydns_configs")) {
                    putBoolean("display_vaydns_configs", true)
                }
                apply()
            }
        }
    }

    private val preferenceChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "default_to_vpn_mode") {
            runOnUiThread { updateModeUI() }
        }
        if (key == "display_vaydns_configs") {
            runOnUiThread { refreshConfigList() }
        }
    }

    private fun updateModeUI() {
        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        val startInVpnMode = appPrefs.getBoolean("default_to_vpn_mode", true)

        if (isVpnConnected) {
            Toast.makeText(this, "Please stop the tunnel before changing VPN/Proxy Mode.", Toast.LENGTH_LONG).show()
            // Silently revert the shared preference back so it matches the current running UI state
            appPrefs.edit().putBoolean("default_to_vpn_mode", !isProxyMode).apply()
            return
        }

        isProxyMode = !startInVpnMode

        if (isProxyMode) {
            layoutVpnControls.visibility = View.GONE
            layoutProxyControls.visibility = View.VISIBLE
            if (::etProxyPort.isInitialized) {
                etProxyPort.isEnabled = true
            }
        } else {
            layoutVpnControls.visibility = View.VISIBLE
            layoutProxyControls.visibility = View.GONE
        }

        // Invalidate the menu so the "Switch to..." title updates immediately
        invalidateOptionsMenu()
    }

    private fun loadSelectedApps() {
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        selectedApps = (sharedPref.getStringSet("allowed_apps", emptySet()) ?: emptySet()).toMutableSet()
        runOnUiThread {
            if (::tvSelectedAppsInfo.isInitialized) {
                tvSelectedAppsInfo.text = "Selected apps to use the DNS tunnel: ${selectedApps.size}"
            }
        }
    }
    private val configFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importConfigBinary(uri)
        }
    }
    private val resolverFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importResolverBinary(uri)
        }
    }
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService() else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initDefaultSettings()

        loadSelectedApps()

        mobile.Mobile.initVault(filesDir.absolutePath)

        window.statusBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isDarkMode = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            val decor = window.decorView
            if (!isDarkMode) {
                // Light Mode: Black Icons
                decor.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                // Dark Mode: White Icons (Clear the flag)
                decor.systemUiVisibility = 0
            }
        }

        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        val surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        drawerLayout.setStatusBarBackgroundColor(surfaceColor)

        navView = findViewById(R.id.nav_view)

        // This creates the 3-line hamburger icon and links it to opening the drawer
        toggle = androidx.appcompat.app.ActionBarDrawerToggle(
            this,
            drawerLayout,
            findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar), // <-- Bulletproof explicit lookup
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        // Handle clicks for the left menu items
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_select_apps -> {
                    startActivity(Intent(this, AppSelectorActivity::class.java))
                }
                R.id.action_daily_traffic -> {
                    showDailyTrafficDialog()
                }
                R.id.action_tunnel_settings -> {
                    startActivity(Intent(this, TunnelSettingsActivity::class.java))
                }

                R.id.action_settings -> {
                    startActivity(Intent(this, GlobalSettingsActivity::class.java))
                }

                R.id.action_check_app_update -> {
                    checkForAppUpdate()
                    true
                }

                R.id.action_how_to_use -> {
                    showHowToUseDialog()
                }

                R.id.action_verify -> {
                    showVerificationDialog()
                    true
                }

                R.id.action_about -> {
                    val version = try {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    } catch (e: Exception) {
                        "1.0"
                    }

                    // Use MaterialAlertDialogBuilder to respect your Day/Night themes
                    MaterialAlertDialogBuilder(this)
                        .setTitle("VayDNS")
                        .setMessage("""
            Version: $version

            DNS Tunneling app designed for heavily censored environments.

            Made with ❤️
            x.com/Starling226
            t.me/Starling226
            https://github.com/Starling226/vaydns-vpn
        """.trimIndent())
                        .setPositiveButton("Close", null)
                        .setIcon(R.mipmap.ic_launcher_round)
                        .show() // .show() handles create and show automatically

                    // DELETE the manual setTextColor line.
                    // The theme overlay we created earlier handles this now.
                    true
                }
            }
            // Close the drawer automatically after tapping an item
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            true
        }

        // Toolbar + menu
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "VayDNS"

        tvStatus = findViewById(R.id.tv_status)
        btnToggle = findViewById(R.id.btn_toggle)
        layoutNetworkStats = findViewById(R.id.layout_network_stats)
        tvSpeed = findViewById(R.id.tv_speed)
        tvTotal = findViewById(R.id.tv_total)
        tvSelectedAppsInfo = findViewById(R.id.tv_selected_apps_info)
        //btnStart = findViewById(R.id.btn_start)
        //btnStop = findViewById(R.id.btn_stop)
        recyclerConfigs = findViewById(R.id.recycler_configs)
        switchDefault = findViewById(R.id.switch_default_configs)
        layoutVpnControls = findViewById(R.id.layout_vpn_controls)
        layoutProxyControls = findViewById(R.id.layout_proxy_controls)
        //tvProxyAddress = findViewById(R.id.tv_proxy_address)
        etProxyPort = findViewById(R.id.et_proxy_port)
        tvProxyIpLabel = findViewById(R.id.tv_proxy_ip_label)
        // WIRE BUTTON INTERACTION: Click listener to display custom override options tool windows
        findViewById<LinearLayout>(R.id.btn_custom_override_ping).setOnClickListener {
            showCustomPingOverrideDialog()
        }
        val configCount = mobile.Mobile.getDefaultConfigCount()
        val buildStatus = mobile.Mobile.getBuildStatus()
        //val isOfficialBuild = buildStatus == "Official Release" || configCount > 0
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        val savedPort = sharedPref.getString("proxy_port", "1080")
        etProxyPort.setText(savedPort)

        // BIND PING ALL HEADER CONTROL TO DISPATCHER LOOP
        findViewById<LinearLayout>(R.id.btn_ping_all_header).setOnClickListener {
            if (isPingRunning) {
                Toast.makeText(this, "Ping sequence is currently running. Please wait...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener // Abort the click
            }

            if (configList.isNotEmpty()) {
                isPingRunning = true // Lock the UI
                triggerStartupPings(configList)
            } else {
                Toast.makeText(this, "No configurations available to ping.", Toast.LENGTH_SHORT).show()
            }
        }

        getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        updateModeUI()

        // ENFORCE COMMUNITY CLEANUP: Hide the switch entirely if there are no default configs
        if (configCount > 0) {
            switchDefault.visibility = android.view.View.VISIBLE
            switchDefault.isEnabled = true
            switchDefault.text = "Use default configs"
        } else {
            switchDefault.visibility = android.view.View.GONE
        }

        loadSelectedConfig()

        switchDefault.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("default_configs_at_start", isChecked)
                .apply()

            refreshConfigList()
        }

        // BIND SORTING CONTROLS ON THE HEADER BAR
        val btnSortName = findViewById<LinearLayout>(R.id.btn_sort_name)
        val btnSortLatency = findViewById<LinearLayout>(R.id.btn_sort_latency)
        val tvHeaderName = findViewById<TextView>(R.id.tv_header_name)
        val tvHeaderLatency = findViewById<TextView>(R.id.tv_header_latency)

        btnSortName.setOnClickListener {
            // Cycle through: NONE -> NAME_ASC -> NAME_DESC -> back to NONE
            currentSortMode = when (currentSortMode) {
                SortMode.NONE -> SortMode.NAME_ASC
                SortMode.NAME_ASC -> SortMode.NAME_DESC
                else -> SortMode.NONE
            }

            // Update the UI header label dynamically to show the active state
            tvHeaderName.text = when (currentSortMode) {
                SortMode.NAME_ASC -> "Config Name ▲"
                SortMode.NAME_DESC -> "Config Name ▼"
                else -> "Config Name" // No arrow means original layout order!
            }

            tvHeaderLatency.text = "Latency" // Reset the latency column label

            refreshConfigList()
        }

        btnSortLatency.setOnClickListener {
            currentSortMode = if (currentSortMode == SortMode.LATENCY_ASC) SortMode.LATENCY_DESC else SortMode.LATENCY_ASC

            // Render directional flags & reset alternate rows
            tvHeaderLatency.text = if (currentSortMode == SortMode.LATENCY_ASC) "Latency ▲" else "Latency ▼"
            tvHeaderName.text = "Config Name"

            refreshConfigList()
        }

        // BIND PING ALL HEADER CONTROL TO DISPATCHER LOOP
        findViewById<LinearLayout>(R.id.btn_ping_all_header).setOnClickListener {
            if (configList.isNotEmpty()) {
                //Toast.makeText(this, "Pinging all configurations...", Toast.LENGTH_SHORT).show()
                // Directly trigger your asynchronous multi-threaded test sequence
                triggerStartupPings(configList)
            } else {
                Toast.makeText(this, "No configurations available to ping.", Toast.LENGTH_SHORT).show()
            }
        }

        // RecyclerView for configs
        recyclerConfigs.layoutManager = LinearLayoutManager(this)
        refreshConfigList()

        btnToggle.setOnClickListener {
            if (isVpnConnected) {
                stopVpnService()
            } else {
                val config = configList.find { it.id == selectedConfigId }

                if (config != null) {
                    // Fetch the discriminator to see what we are dealing with
                    val nativeIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L
                    val configType = if (config.isDefault) mobile.Mobile.getDefaultConfigType(nativeIndex) else "vaydns"

                    // GUARDRAIL 1: Block Hysteria in Proxy Mode
                    //val isDirectMode = configType.lowercase() == "direct"
                    val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                    val activeProtocol = tunnelPrefs.getString("active_protocol", "vaydns") ?: "vaydns"
                    val configTypeLower = configType.lowercase()

                    val isSupported = when {
                        configTypeLower == "direct" -> activeProtocol != "vaydns"
                        configTypeLower == "" -> activeProtocol == "vaydns"
                        else -> configTypeLower.contains(activeProtocol.lowercase())
                    }
                    if (!isSupported) {
                        Toast.makeText(this@MainActivity, "This config does not support ${activeProtocol.uppercase()}.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener // Instantly abort the connection attempt
                    }

                    val isDirectMode = activeProtocol.lowercase() != "vaydns"

                    if (isDirectMode && isProxyMode) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Proxy Mode Unavailable")
                            .setMessage("Direct protocols (like Hysteria2, Reality, and Vless-WS) currently require VPN Mode to function properly.\n\nPlease switch to VPN Mode to use this server.\n\n---\n\nپروتکل‌های مستقیم (مانند Hysteria2، Reality و Vless-WS) در حال حاضر فقط در حالت VPN کار می‌کنند.\n\nلطفاً برای استفاده از این سرور به حالت VPN تغییر وضعیت دهید.")
                            .setPositiveButton("OK", null)
                            .show()
                        return@setOnClickListener // Block connection
                    }

                    // GUARDRAIL 2: Block HTTP in VPN Mode
                    if (config.protocol.lowercase() == "http" && !isProxyMode) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("VPN Mode Unavailable")
                            // ... your existing HTTP error message ...
                            .setPositiveButton("OK", null)
                            .show()
                        return@setOnClickListener // Block connection
                    }
                }
                if (!isProxyMode) {
                    // Safety check: Prevent starting VPN with no apps selected
                    if (selectedApps.isEmpty()) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("No Apps Selected / هیچ برنامه‌ای انتخاب نشده است")
                            .setMessage("""
                                Please select at least one app to tunnel before starting VPN mode. Alternatively, switch to Proxy Mode to use the tunnel with apps like Telegram.
                                
                                لطفاً قبل از شروع حالت VPN، حداقل یک برنامه را برای عبور از تونل انتخاب کنید. در غیر این صورت، می‌توانید برای استفاده از برنامه‌هایی مانند تلگرام، به حالت پروکسی (Proxy Mode) تغییر دهید.
                            """.trimIndent())
                            .setPositiveButton("SELECT APPS / انتخاب برنامه‌ها") { _, _ ->
                                // Navigate to your App Selector
                                startActivity(Intent(this, AppSelectorActivity::class.java))
                            }
                            .setNegativeButton("CANCEL / لغو", null)
                            .show()
                        return@setOnClickListener // Stop the process here
                    }
                }
                startVpnService()
            }
        }

        // App selector button stays the same
        //findViewById<Button>(R.id.btn_select_apps).setOnClickListener {
        //    startActivity(Intent(this, AppSelectorActivity::class.java))
        //}

        // Register receiver
        val filter = IntentFilter()
        filter.addAction("VPN_STATE_CHANGED")
        filter.addAction("VPN_STATS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }

        val pingFilter = IntentFilter().apply {
            addAction("PING_ALL_FINISHED")
            addAction("ROW_PING_FINISHED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pingReceiver, pingFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(pingReceiver, pingFilter)
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun applyCurrentSort() {
        when (currentSortMode) {
            SortMode.NAME_ASC -> configList.sortBy { it.name.lowercase() }
            SortMode.NAME_DESC -> configList.sortByDescending { it.name.lowercase() }
            SortMode.LATENCY_ASC -> {
                configList.sortWith(Comparator { c1, c2 ->
                    val lat1 = ConfigAdapter.pingCache[c1.id] ?: -1L
                    val lat2 = ConfigAdapter.pingCache[c2.id] ?: -1L
                    compareLatencies(lat1, lat2, asc = true)
                })
            }
            SortMode.LATENCY_DESC -> {
                configList.sortWith(Comparator { c1, c2 ->
                    val lat1 = ConfigAdapter.pingCache[c1.id] ?: -1L
                    val lat2 = ConfigAdapter.pingCache[c2.id] ?: -1L
                    compareLatencies(lat1, lat2, asc = false)
                })
            }
            SortMode.NONE -> { /* Leave as default file order */ }
        }
    }

    // 🟢 Helper validation function matching your DNS Resolver Scanner Window logic
    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255 // Checks numeric bounds securely without regular expressions
        }
    }

    // 🟢 Helper port parsing function matching your DNS Resolver Scanner Window logic
    private fun isValidIpv4WithOptionalPort(input: String): Boolean {
        if (input.contains(":")) {
            val parts = input.split(":")
            if (parts.size != 2) return false
            val port = parts[1].toIntOrNull()
            return isValidIpv4(parts[0]) && port != null && port in 1..65535
        }
        return isValidIpv4(input)
    }

    private fun showCustomPingOverrideDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padVertical = (24 * resources.displayMetrics.density).toInt()
            val padHorizontal = (16 * resources.displayMetrics.density).toInt()
            setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tvGuideLabel = TextView(this).apply {
            text = "Use Tunnel Settings to set tunnel parameters"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        container.addView(tvGuideLabel)
        // --- ROW 1: IP Address Label & Entry Input ---
        val tvIpLabel = TextView(this).apply {
            text = "IP Address:"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
        }
        val etIpInput = EditText(this).apply {
            hint = "e.g., 8.8.8.8"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(tvIpLabel)
        container.addView(etIpInput)

        // Spacer Element
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (16 * resources.displayMetrics.density).toInt())
        })

        // --- ROW 2: Tunnel Mode Selection Header & Options Grid ---
        val tvModeLabel = TextView(this).apply {
            text = "Tunnel Mode:"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
        }

        val rgModeOptions = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            weightSum = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val rbParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)

        val rbUdp = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "UDP"
            layoutParams = rbParams
        }
        val rbTcp = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "TCP"
            layoutParams = rbParams
        }
        val rbDot = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "DoT"
            layoutParams = rbParams
        }
        val rbDoh = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "DoH"
            layoutParams = rbParams
        }

        rgModeOptions.addView(rbUdp)
        rgModeOptions.addView(rbTcp)
        rgModeOptions.addView(rbDot)
        rgModeOptions.addView(rbDoh)
        rgModeOptions.check(rbUdp.id)

        container.addView(tvModeLabel)
        container.addView(rgModeOptions)

        // Spacer Element
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, (20 * resources.displayMetrics.density).toInt())
        })

        // --- ROW 3: Scan Button Control ---
        val btnScanAction = Button(this).apply {
            text = "PING / شروع پینگ"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(btnScanAction)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Single IP Ping to Servers")
            .setView(container)
            .create()

        btnScanAction.setOnClickListener {

            if (isPingRunning) {
                Toast.makeText(this, "A ping sequence is already running. Please wait...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener // Abort the click
            }

            val enteredTarget = etIpInput.text.toString().trim()

            val selectedMode = when (rgModeOptions.checkedRadioButtonId) {
                rbTcp.id -> "tcp"
                rbDot.id -> "dot"
                rbDoh.id -> "doh"
                else -> "udp"
            }

            // 🟢 CONDITIONAL VALIDATION: Uses protocol parsing approach for UDP/TCP/DoT, regex for DoH
            val isValid = if (selectedMode == "doh") {
                enteredTarget.isNotEmpty() && enteredTarget.matches(Regex("^[a-zA-Z0-9.:_-]+$"))
            } else {
                // 🚀 Completely removed regex! Reusing your robust split-and-bound verification protocols
                isValidIpv4WithOptionalPort(enteredTarget)
            }

            if (!isValid) {
                Toast.makeText(this, "Notification: Entered IP is incorrect. / آی‌پی وارد شده اشتباه است", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            isPingRunning = true
            triggerCustomOverridePings(enteredTarget, selectedMode)
        }

        dialog.show()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun compareLatencies(lat1: Long, lat2: Long, asc: Boolean): Int {
        // Normalize unpinged and dead codes to heavy ceiling values
        // to cleanly cluster them at the bottom of standard ascending checks
        val v1 = if (lat1 > 0 && lat1 < 99999) lat1 else if (lat1 == -2L) 100000L else 100001L
        val v2 = if (lat2 > 0 && lat2 < 99999) lat2 else if (lat2 == -2L) 100000L else 100001L

        return if (asc) v1.compareTo(v2) else v2.compareTo(v1)
    }

    private fun triggerCustomOverridePings(targetIp: String, targetMode: String) {
        Log.i("VAY_DEBUG", "Custom Override Bulk Ping Sequence Initiated against -> $targetIp [$targetMode]")

        if (configList.isEmpty()) return

        // 1. Force state updates instantly across layout row items on screen
        for (index in configList.indices) {
            val holder = recyclerConfigs.findViewHolderForAdapterPosition(index)
            holder?.itemView?.findViewById<TextView>(R.id.tv_latency)?.text = "Ping..."
        }

        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        val proxyType = tunnelPrefs.getString("proxy_type", "socks5h") ?: "socks5h"
        //val activeProtocol = tunnelPrefs.getString("active_protocol", "vaydns") ?: "vaydns"
        val lightE2E = tunnelPrefs.getBoolean("light_e2e", false)
        val workers = tunnelPrefs.getInt("workers", 20)
        val tWait = tunnelPrefs.getInt("tunnel_wait", 3000)
        val pTimeout = tunnelPrefs.getInt("probe_timeout", 15)
        val uTimeout = tunnelPrefs.getInt("udp_timeout", 1000)
        val retries = tunnelPrefs.getInt("retries", 0)

        val safeWorkers = if (targetMode.lowercase() == "udp" && workers > 20) {
            20
        } else {
            workers
        }

        // AUTOMATED PORT & SCHEMA FORMATTER LAYER
        val formattedResolver = when (targetMode.lowercase()) {
            "udp", "tcp" -> if (targetIp.contains(":")) targetIp else "$targetIp:53"
            "dot" -> if (targetIp.contains(":")) targetIp else "$targetIp:853"
            "doh" -> {
                var url = targetIp
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }

                // Calculate the scheme offset and check if a path slash exists using indexOf
                val schemeIndex = url.indexOf("://")
                val startIndex = if (schemeIndex != -1) schemeIndex + 3 else 0

                if (url.indexOf("/", startIndex) == -1) {
                    url = url.removeSuffix("/") + "/dns-query"
                }
                url
            }
            else -> targetIp
        }

        Log.i("VAY_DEBUG", "[Override Formatter] Remapped input '$targetIp' to safe endpoint target: '$formattedResolver'")

        var configProbeTimeout = pTimeout.toLong()

        val jsonArray = org.json.JSONArray()
        for (config in configList) {
            val finalConfig = if (config.isDefault) DefaultConfigProvider.getActualConfig(this@MainActivity, config) else config
            val nativeIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L
            val rawConfigType = if (config.isDefault) mobile.Mobile.getDefaultConfigType(nativeIndex) else "vaydns"

            val globalOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
            val globalProtocol = tunnelPrefs.getString("global_protocol_selected", "vaydns") ?: "vaydns"

            var activeProtocol = if (config.isDefault && globalOverride) {
                globalProtocol
            } else if (config.isDefault) {
                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                    .getString("${config.id}_tunnelProtocol", null)
                    ?: rawConfigType.split(",").firstOrNull { it.isNotBlank() } ?: "vaydns"
            } else {
                // STRICT GUARDRAIL: Custom configs bypass the global override and strictly use their own protocol
                finalConfig.tunnelProtocol
            }

            if (config.isDefault) {
                val supportedProtocols = rawConfigType.lowercase().split(",").map { it.trim() }
                if (!supportedProtocols.contains(activeProtocol.lowercase())) {
                    activeProtocol = supportedProtocols.firstOrNull { it.isNotEmpty() } ?: "vaydns"
                }
            }

            // =========================================================
            // GUARDRAIL: Skip Direct Configs for Custom Resolver Scans
            // =========================================================
            if (activeProtocol.lowercase() != "vaydns") {
                continue // Skip Hysteria/Reality configs entirely
            }

            val domainIndex = if (config.isDefault) {
                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE).getInt("${config.id}_domainIndex", 0)
            } else {
                finalConfig.domainIndex
            }

            val jsonTaskObj = org.json.JSONObject().apply {
                put("id", config.id)
                put("is_default", config.isDefault)
                put("config_index", nativeIndex)

                // NEW: Inject Direct Protocol parameters
                put("config_type", "vaydns")
                put("server_ip", targetIp) // The IP the user manually entered in the dialog

                // Pass the safely formatted target straight to the Go workers
                put("dns_mode", targetMode)
                put("resolvers", formattedResolver)
                put("base_doh_url", if (targetMode.lowercase() == "doh") formattedResolver else "")
                put("domain_index", domainIndex)
                // put("custom_domain", finalConfig.domain.split(",").firstOrNull()?.trim() ?: finalConfig.domain)
                put("custom_domain", finalConfig.domain)
                put("custom_pubkey", finalConfig.pubkey)
                put("proxy_type", proxyType)
                put("protocol", finalConfig.protocol)
                put("user", if (finalConfig.protocol == "shadowsocks") finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" } else if (finalConfig.useAuth) finalConfig.user.ifEmpty { "none" } else "none")
                put("pass", if (finalConfig.useAuth) finalConfig.pass.ifEmpty { "none" } else "none")
                put("ss_method", finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" })
                put("record_type", finalConfig.recordType)
                put("idle_timeout", finalConfig.idleTimeout)
                put("keep_alive", finalConfig.keepAlive)
                put("client_id_size", finalConfig.clientIdSize)
                put("mtu", finalConfig.mtu)
            }
            jsonArray.put(jsonTaskObj)
        }

        val serviceIntent = Intent(this, VayPingService::class.java).apply {
            putExtra("TASKS_JSON", jsonArray.toString())
            putExtra("WORKERS", safeWorkers.toLong())
            putExtra("TUNNEL_WAIT", tWait.toLong())
            putExtra("UDP_TIMEOUT", uTimeout.toLong())
            putExtra("PROBE_TIMEOUT", pTimeout.toLong())
            putExtra("RETRIES", retries.toLong())
            putExtra("LIGHT_E2E", lightE2E)
            putExtra("QUICK_SCAN", false) // or engineQuickScan
        }
        startService(serviceIntent)
    }

    private fun triggerStartupPings(configs: List<Config>) {
        Log.i("VAY_DEBUG", "Massive Multi-Config Parallel Engine Run Launched...")
        val safeConfigs = configs.toList()

        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)

        for (index in safeConfigs.indices) {
            val holder = recyclerConfigs.findViewHolderForAdapterPosition(index)
            holder?.itemView?.findViewById<TextView>(R.id.tv_latency)?.text = "Ping..."
        }

        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        val proxyType = tunnelPrefs.getString("proxy_type", "socks5h") ?: "socks5h"
        val activeProtocol = tunnelPrefs.getString("active_protocol", "vaydns") ?: "vaydns"
        val lightE2E = tunnelPrefs.getBoolean("light_e2e", false)
        val workers = tunnelPrefs.getInt("workers", 20)
        val tWait = tunnelPrefs.getInt("tunnel_wait", 3000)
        val pTimeout = tunnelPrefs.getInt("probe_timeout", 15)
        val uTimeout = tunnelPrefs.getInt("udp_timeout", 1000)
        val retries = tunnelPrefs.getInt("retries", 0)

        var hasUdp = false
        for (config in safeConfigs) {
            val finalConfig = if (config.isDefault) DefaultConfigProvider.getActualConfig(this@MainActivity, config) else config
            if (finalConfig.mode.lowercase() == "udp") {
                hasUdp = true
                break
            }
        }

        val safeWorkers = if (hasUdp && workers > 20) {
            20
        } else {
            workers
        }

        // 1. Pack configuration models cleanly for normal/custom servers
        val jsonArray = org.json.JSONArray()
        for (config in safeConfigs) {
            val finalConfig = if (config.isDefault) DefaultConfigProvider.getActualConfig(this@MainActivity, config) else config
            val multipathDnsList = getMultipathResolvers(config.id, finalConfig.dnsAddress, finalConfig.mode)

            // PER-CONFIG PROTOCOL EXTRACTION ---
            val nativeIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L
            val rawConfigType = if (config.isDefault) mobile.Mobile.getDefaultConfigType(nativeIndex) else "vaydns"

            val globalOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
            val globalProtocol = tunnelPrefs.getString("global_protocol_selected", "vaydns") ?: "vaydns"

            var activeProtocol = if (config.isDefault && globalOverride) {
                globalProtocol
            } else if (config.isDefault) {
                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                    .getString("${config.id}_tunnelProtocol", null)
                    ?: rawConfigType.split(",").firstOrNull { it.isNotBlank() } ?: "vaydns"
            } else {
                // STRICT GUARDRAIL: Custom configs bypass the global override and strictly use their own protocol
                finalConfig.tunnelProtocol
            }

            if (config.isDefault) {
                val supportedProtocols = rawConfigType.lowercase().split(",").map { it.trim() }
                if (!supportedProtocols.contains(activeProtocol.lowercase())) {
                    activeProtocol = supportedProtocols.firstOrNull { it.isNotEmpty() } ?: "vaydns"
                }
            }

            val configVlessIp = if (config.isDefault) {
                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                    .getString("${config.id}_vlessIp", "") ?: ""
            } else {
                finalConfig.vlessIp
            }

// Fetch the Vault JSON
            val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
            val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"
            var firstGlobalVlessIp = ""

            try {
                val jsonArray = org.json.JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    // We only care about the single checked IP for the VPN Override
                    if (obj.getBoolean("isChecked")) {
                        firstGlobalVlessIp = obj.getString("ip")
                        break
                    }
                }
                // Safety Fallback: If nothing was checked, grab the first available IP
                if (firstGlobalVlessIp.isEmpty() && jsonArray.length() > 0) {
                    firstGlobalVlessIp = jsonArray.getJSONObject(0).getString("ip")
                }
            } catch (e: Exception) { e.printStackTrace() }

            val isDirectMode = activeProtocol.lowercase() != "vaydns"
            val cleanConfigType = if (isDirectMode) "direct" else "vaydns"
            val cleanProtocol = if (isDirectMode) activeProtocol else finalConfig.protocol
            val serverIp = if (isDirectMode && !config.isDefault) finalConfig.dnsAddress else ""

            val domainIndex = if (config.isDefault) {
                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE).getInt("${config.id}_domainIndex", 0)
            } else {
                finalConfig.domainIndex
            }

            val jsonTaskObj = org.json.JSONObject().apply {
                put("id", config.id)
                put("is_default", config.isDefault)
                put("config_index", nativeIndex)

                // Inject Direct Protocol parameters
                put("config_type", cleanConfigType)
                put("server_ip", serverIp)
                put("protocol", cleanProtocol)
                put("vless_ws_ip", firstGlobalVlessIp)
                put("dns_mode", finalConfig.mode)
                // put("custom_domain", finalConfig.domain.split(",").firstOrNull()?.trim() ?: finalConfig.domain)
                put("custom_domain", finalConfig.domain)
                put("domain_index", domainIndex)
                put("custom_pubkey", finalConfig.pubkey)
                put("resolvers", multipathDnsList)
                put("base_doh_url", if (finalConfig.mode.lowercase() == "doh") finalConfig.dnsAddress else "")
                put("proxy_type", proxyType)

                // Mirror authentication parameter construction criteria explicitly
                put("user", if (finalConfig.protocol == "shadowsocks") finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" } else if (finalConfig.useAuth) finalConfig.user.ifEmpty { "none" } else "none")
                put("pass", if (finalConfig.useAuth) finalConfig.pass.ifEmpty { "none" } else "none")
                put("ss_method", finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" })
                put("record_type", finalConfig.recordType)
                put("idle_timeout", finalConfig.idleTimeout)
                put("keep_alive", finalConfig.keepAlive)
                put("client_id_size", finalConfig.clientIdSize)
                put("mtu", finalConfig.mtu)
            }
            jsonArray.put(jsonTaskObj)
        }

        val serviceIntent = Intent(this, VayPingService::class.java).apply {
            putExtra("TASKS_JSON", jsonArray.toString())
            putExtra("WORKERS", safeWorkers.toLong())
            putExtra("TUNNEL_WAIT", tWait.toLong())
            putExtra("UDP_TIMEOUT", uTimeout.toLong())
            putExtra("PROBE_TIMEOUT", pTimeout.toLong())
            putExtra("RETRIES", retries.toLong())
            putExtra("LIGHT_E2E", lightE2E)
            putExtra("QUICK_SCAN", false) // or engineQuickScan
        }
        startService(serviceIntent)
    }

    private fun loadSelectedConfig() {
        val prefs = getSharedPreferences("VayDNS_Settings", MODE_PRIVATE)
        selectedConfigId = prefs.getString("selected_config_id", null)
    }

    private fun saveSelectedConfigId(id: String?) {
        val prefs = getSharedPreferences("VayDNS_Settings", MODE_PRIVATE)
        prefs.edit().putString("selected_config_id", id).apply()
        selectedConfigId = id
    }

    private fun refreshConfigList() {
        configList.clear()
        val userConfigs = loadAllConfigs(this)

        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        val displayVaydnsConfigs = appPrefs.getBoolean("display_vaydns_configs", false)

        val defaultConfigs = if (switchDefault.isChecked) {
            val allDefaults = DefaultConfigProvider.getDefaultConfigs(this)

            // 2. Filter based on explicit visibility
            allDefaults.filter { config ->
                val nativeIndex = config.id.removePrefix("default_").toLongOrNull() ?: 0L
                val configType = mobile.Mobile.getDefaultConfigType(nativeIndex).lowercase().trim()

                // If the config is PURELY VayDNS (no other protocols attached)
                if (configType == "vaydns") {
                    displayVaydnsConfigs // Show only if the toggle is ON
                } else {
                    true // Always show multi-protocol/modern configs
                }
            }
        } else {
            emptyList()
        }

        // 3. COMBINE AND FILTER: Remove any config marked as freeScanner
        val filteredList = (userConfigs + defaultConfigs).filter { !it.freeScanner }
        configList.addAll(filteredList)

        applyCurrentSort()

        // Check if adapter already exists
        if (recyclerConfigs.adapter == null) {
            configAdapter = ConfigAdapter(
                configList,
                selectedConfigId,
                onConfigSelected = { config ->
                    saveSelectedConfigId(config.id)
                    // Instead of refreshConfigList(), we just update the ID and notify
                    configAdapter?.updateSelectedId(config.id)
                },
                onEditClicked = { config ->
                    // All configs can now be edited to select their protocol!
                    val intent = Intent(this, ConfigEditorActivity::class.java).apply {
                        putExtra("CONFIG_ID", config.id)
                    }
                    startActivity(intent)
                },
                onDeleteClicked = { config -> showDeleteConfirmation(config) },
                onExportClicked = { config ->
                    if (config.isDefault) {
                        Toast.makeText(this, "This config cannot be shared.", Toast.LENGTH_SHORT).show()
                    } else {
                        exportToVayDnsProfile(config)
                    }
                }
            )
            recyclerConfigs.adapter = configAdapter
        } else {
            // If it exists, just tell the adapter the data changed
            // without reassining the adapter itself.
            configAdapter?.notifyDataSetChanged()
        }
    }

    /**
     * Constructs the vaydns:// profile and copies it to clipboard
     */
    private fun generateHumanReadableUrl(config: Config): String {
        val uriBuilder = android.net.Uri.Builder()
            .scheme("dnst")
            .authority(config.domain)
            .appendPath("vaydns")
            .appendPath(config.protocol)
            .appendQueryParameter("pubkey", config.pubkey)
            .appendQueryParameter("record-type", config.recordType.lowercase())
            .appendQueryParameter("clientid-size", config.clientIdSize.toString())
            .appendQueryParameter("keepalive", config.keepAlive)
            .appendQueryParameter("idle-timeout", config.idleTimeout)

        if (config.dnsttCompatible) {
            uriBuilder.appendQueryParameter("dnstt-compat", "true")
        }

        if (config.useAuth) {
            uriBuilder.appendQueryParameter("user", config.user)
            if (config.useSshKey) {
                uriBuilder.appendQueryParameter("pk", config.pass)
            } else {
                uriBuilder.appendQueryParameter("password", config.pass)
            }
        }

        uriBuilder.fragment(config.name)
        return uriBuilder.build().toString()
    }

    private fun generateBase64Json(config: Config): String {
        val json = org.json.JSONObject()

        // Root Tag
        json.put("tag", config.name)

        // Transport Object
        val transport = org.json.JSONObject().apply {
            put("type", "vaydns")
            put("domain", config.domain)
            put("pubkey", config.pubkey)
            put("record_type", config.recordType.lowercase())
            put("dnstt_compat", config.dnsttCompatible)
            put("clientid_size", config.clientIdSize)
            put("idle_timeout", config.idleTimeout)
            put("keepalive", config.keepAlive)
        }
        json.put("transport", transport)

        // Backend Object
        val backend = org.json.JSONObject().apply {
            put("type", config.protocol)
            if (config.useAuth) {
                put("user", config.user)
                if (config.useSshKey) {
                    put("pk", config.pass)
                } else {
                    put("password", config.pass)
                }
                if (config.protocol == "shadowsocks") {
                    put("method", config.ssMethod)
                }
            }
        }
        json.put("backend", backend)

        // Encode to Base64URL
        val jsonString = json.toString()
        val encoded = android.util.Base64.encodeToString(
            jsonString.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )

        return "dnst://$encoded"
    }

    /**
     * Shows a choice dialog then exports the config in the chosen format
     */
    private fun exportToVayDnsProfile(config: Config) {
        val options = arrayOf("Human-readable URL (dnst://...)", "Base64-JSON (Compressed)")

        AlertDialog.Builder(this)
            .setTitle("Select Export Format")
            .setItems(options) { _, which ->
                val exportString = when (which) {
                    0 -> generateHumanReadableUrl(config)
                    else -> generateBase64Json(config)
                }

                // Share the resulting string
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, exportString)
                }
                startActivity(Intent.createChooser(shareIntent, "Share VayDNS Profile"))
            }
            .show()
    }

    private fun showDeleteConfirmation(config: Config) {
        if (config.isDefault) {
            Toast.makeText(this, "Default configs cannot be deleted", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Delete Config")
            .setMessage("Delete \"${config.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val configs = loadAllConfigs(this).toMutableList()
                configs.removeAll { it.id == config.id }
                saveAllConfigs(this, configs)

                // If we deleted the currently selected one, clear selection
                if (selectedConfigId == config.id) saveSelectedConfigId(null)

                refreshConfigList()
                Toast.makeText(this, "Config deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)

        // 1. Create the dialog object
        val dialog = builder.create()

        // 2. Display it
        dialog.show()

        // 3. Set the button colors to #2F4A6F
        val brandColor = 0xFF2F4A6F.toInt()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(brandColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(brandColor)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        val isOfficialBuild = mobile.Mobile.isOfficialBuild()
        menu?.findItem(R.id.action_cf_scanner)?.isVisible = isOfficialBuild
        return true
    }

    private fun showImportDialog() {
        val brandColor = Color.parseColor("#2F4A6F") // Your specific color
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
        }

        val label = TextView(this).apply {
            text = "Paste the config below"
            textSize = 18f
            val dynamicColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            setTextColor(dynamicColor)

            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }

        val etInput = EditText(this).apply {
            hint = "dnst://..."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(label)
        layout.addView(etInput)

        MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val input = etInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    processImport(input)
                }
            }
            .setNegativeButton("Cancel", null)
            .show() // .show() handles create() and show() in one go

    }

    private fun processImport(data: String) {
        try {
            if (!data.startsWith("dnst://")) {
                throw Exception("Invalid profile prefix. Must start with dnst://")
            }

            val content = data.removePrefix("dnst://")
            val newConfig: Config

            if (content.contains("/")) {
                // --- 1. Human-readable Form Parsing ---
                // Grammar: dnst://<domain>/<transport>/<backend>?<params>#<tag>
                val uri = android.net.Uri.parse(data)

                val domain = uri.host ?: throw Exception("Missing tunnel domain")
                val pathSegments = uri.pathSegments
                if (pathSegments.size < 2) throw Exception("Invalid path structure. Need /transport/backend")

                val transport = pathSegments[0] // e.g., "vaydns"

                if (transport != "vaydns") {
                    throw Exception("Unsupported transport: '$transport'. This app only supports 'vaydns'.")
                }
                val backend = pathSegments[1]    // e.g., "socks", "ssh"
                val tag = uri.fragment ?: "Imported"

                // Map Query Parameters to Config
                newConfig = Config(
                    name = getUniqueName(tag, loadAllConfigs(this)),
                    domain = domain,
                    transport = transport,
                    protocol = backend,
                    pubkey = uri.getQueryParameter("pubkey") ?: "",
                    dnsAddress = "8.8.8.8:53", // Default if not provided
                    mode = "udp", // Default mode
                    recordType = uri.getQueryParameter("record-type")?.uppercase() ?: "TXT",
                    dnsttCompatible = uri.getQueryParameter("dnstt-compat")?.toBoolean() ?: false,
                    clientIdSize = uri.getQueryParameter("clientid-size")?.toLongOrNull() ?: 2L,
                    idleTimeout = uri.getQueryParameter("idle-timeout") ?: "10s",
                    keepAlive = uri.getQueryParameter("keepalive") ?: "2s",
                    useAuth = uri.getQueryParameter("user") != null,
                    ssMethod = uri.getQueryParameter("method") ?: "chacha20-ietf-poly1305",
                    user = uri.getQueryParameter("user") ?: "",
                    pass = uri.getQueryParameter("pk") ?: uri.getQueryParameter("password") ?: "",
                    useSshKey = uri.getQueryParameter("pk") != null,
                    isDefault = false
                )
            } else {
                // --- 2. Base64-JSON Form Parsing ---
                val decodedBytes = android.util.Base64.decode(content, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
                val json = org.json.JSONObject(String(decodedBytes, Charsets.UTF_8))

                val transportObj = json.getJSONObject("transport")
                val transportType = transportObj.optString("type", "").lowercase()

                if (transportType != "vaydns") {
                    throw Exception("Unsupported transport: '$transportType'. This app only supports 'vaydns'.")
                }

                val backendObj = json.getJSONObject("backend")
                val tag = json.optString("tag", "Imported")

                newConfig = Config(
                    name = getUniqueName(tag, loadAllConfigs(this)),
                    domain = transportObj.getString("domain"),
                    pubkey = transportObj.optString("pubkey", ""),
                    recordType = transportObj.optString("record_type", "TXT").uppercase(),
                    dnsttCompatible = transportObj.optBoolean("dnstt_compat", false),
                    clientIdSize = transportObj.optLong("clientid_size", 2L),
                    idleTimeout = transportObj.optString("idle_timeout", "10s"),
                    keepAlive = transportObj.optString("keepalive", "2s"),
                    protocol = backendObj.getString("type"),
                    ssMethod = backendObj.optString("method", "chacha20-ietf-poly1305"),
                    user = backendObj.optString("user", ""),
                    pass = backendObj.optString("pk", backendObj.optString("password", "")),
                    useSshKey = backendObj.has("pk"),
                    useAuth = backendObj.has("user"),
                    isDefault = false,
                    dnsAddress = "8.8.8.8:53",
                    mode = "udp" // Standard default
                )
            }

            // Save to internal storage
            val currentConfigs = loadAllConfigs(this).toMutableList()
            currentConfigs.add(newConfig)
            saveAllConfigs(this, currentConfigs)

            refreshConfigList()
            Toast.makeText(this, "Imported: ${newConfig.name}", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Import Error")
                .setMessage(e.message ?: "Failed to parse dnst:// profile")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun getUniqueName(baseName: String, currentConfigs: List<Config>): String {
        var candidate = baseName
        var counter = 1
        val existingNames = currentConfigs.map { it.name }

        // If name exists, append _1, _2, etc.
        while (existingNames.contains(candidate)) {
            candidate = "${baseName}_$counter"
            counter++
        }
        return candidate
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        //val buildStatus = mobile.Mobile.getBuildStatus()
        val configCount = mobile.Mobile.getDefaultConfigCount()
        //val isOfficialBuild = buildStatus == "Official Release" || configCount > 0
        val isOfficialBuild = mobile.Mobile.isOfficialBuild()

        // Read user preferences (Default is false/invisible)
        val menuPrefs = getSharedPreferences("MenuSettings", Context.MODE_PRIVATE)
        // val showAppUpdate = menuPrefs.getBoolean("show_check_app_update", true)
        val showUpdateConfigs = menuPrefs.getBoolean("show_update_configs", false)
        val showUpdateResolvers = menuPrefs.getBoolean("show_update_resolvers", false)
        val showUploadConfigs = menuPrefs.getBoolean("show_upload_configs", false)
        val showUploadResolvers = menuPrefs.getBoolean("show_upload_resolvers", false)
        //val menuPrefs = getSharedPreferences("VayDNS_MenuPrefs", Context.MODE_PRIVATE)
        val showImport = menuPrefs.getBoolean("show_import_resolvers", false)
        val showExport = menuPrefs.getBoolean("show_export_resolvers", false)

        //menu.findItem(R.id.action_import_resolvers)?.isVisible = showImport
        //menu.findItem(R.id.action_export_resolvers)?.isVisible = showExport

        val itemImport = menu.findItem(R.id.action_import_resolvers)
        val itemExport = menu.findItem(R.id.action_export_resolvers)

        if (itemImport != null) itemImport.isVisible = showImport
        if (itemExport != null) itemExport.isVisible = showExport

        if (!isOfficialBuild) {
            // Hide all private infrastructure options for completely public community builds
            // menu.findItem(R.id.action_verify)?.isVisible = false
            // menu.findItem(R.id.action_check_app_update)?.isVisible = false
            menu.findItem(R.id.action_update_configs)?.isVisible = false
            menu.findItem(R.id.action_update_resolvers)?.isVisible = false
            menu.findItem(R.id.action_upload_resolvers)?.isVisible = false
            menu.findItem(R.id.action_upload_configs)?.isVisible = false
            menu.findItem(R.id.action_quick_scanner)?.isVisible = false
            menu.findItem(R.id.action_cf_scanner)?.isVisible = false
        } else {
            // HYBRID VAULT MODE: Keep App Verification visible, but filter out sync tools if configs are omitted
            // menu.findItem(R.id.action_verify)?.isVisible = true
            // menu.findItem(R.id.action_check_app_update)?.isVisible = showAppUpdate
            menu.findItem(R.id.action_cf_scanner)?.isVisible = true

            if (configCount == 0L) {
                // If official configs are entirely missing, hide all config/resolver sync & scanning mechanics
                menu.findItem(R.id.action_update_configs)?.isVisible = false
                menu.findItem(R.id.action_update_resolvers)?.isVisible = false
                menu.findItem(R.id.action_upload_configs)?.isVisible = false
                menu.findItem(R.id.action_upload_resolvers)?.isVisible = false
                menu.findItem(R.id.action_quick_scanner)?.isVisible = false
            } else {
                menu.findItem(R.id.action_quick_scanner)?.isVisible = true
                menu.findItem(R.id.action_update_configs)?.isVisible = showUpdateConfigs
                menu.findItem(R.id.action_update_resolvers)?.isVisible = showUpdateResolvers
                menu.findItem(R.id.action_upload_configs)?.isVisible = showUploadConfigs
                menu.findItem(R.id.action_upload_resolvers)?.isVisible = showUploadResolvers
            }

            // Verification Logic
            val prefs = getSharedPreferences("VayDNS_Prefs", Context.MODE_PRIVATE)
            val savedKey = prefs.getString("verified_public_key", null)
            val verifyItem = menu.findItem(R.id.action_verify)

            if (savedKey != null && verifyItem != null) {
                val stillValid = mobile.Mobile.checkVerification(savedKey)
                if (stillValid) {
                    verifyItem.setIcon(R.drawable.ic_check_modern)
                    verifyItem.title = "App Verified ✅"
                    val greenColor = android.graphics.Color.parseColor("#4CAF50")
                    verifyItem.icon?.mutate()?.setTint(greenColor)
                } else {
                    verifyItem.title = "App Not Verified ⚠️"
                    verifyItem.icon?.mutate()?.setTintList(null)
                    prefs.edit().remove("verified_public_key").apply()
                }
            }
        }
//        return true
        return super.onPrepareOptionsMenu(menu)
    }

    private fun checkAppVerificationState() {
        // Find the Left Drawer and the Verify Item
        val navView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.nav_view)
        val verifyItem = navView.menu.findItem(R.id.action_verify) ?: return

        // 🟢 FIX: Ensure the Verify App item is ALWAYS visible for all builds (Official & Community)
        verifyItem.isVisible = true

        // Check the saved key and update the UI
        val prefs = getSharedPreferences("VayDNS_Prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("verified_public_key", null)

        if (!savedKey.isNullOrEmpty() && mobile.Mobile.checkVerification(savedKey)) {
            verifyItem.setIcon(R.drawable.ic_check_modern)
            verifyItem.title = "Verified Successfully"
            val greenColor = android.graphics.Color.parseColor("#4CAF50")
            verifyItem.icon?.mutate()?.setTint(greenColor)
        } else {
            verifyItem.title = "Verify App"
            verifyItem.icon?.mutate()?.setTintList(null)

            // If the key was invalid/corrupted, clear it
            if (!savedKey.isNullOrEmpty()) {
                prefs.edit().remove("verified_public_key").apply()
            }
        }
    }

    private fun showVerificationDialog() {
        val prefs = getSharedPreferences("VayDNS_Prefs", Context.MODE_PRIVATE)
        val storedKey = prefs.getString("verified_public_key", "")

        // Resolve dynamic colors from your theme
// Use android.R.attr to point specifically to the theme attributes
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(this, android.R.attr.colorPrimary, Color.BLUE)
        val onSurfaceColor = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val secondaryTextColor = com.google.android.material.color.MaterialColors.getColor(this, android.R.attr.textColorSecondary, Color.GRAY)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val textView = TextView(this).apply {
            text = if (storedKey.isNullOrEmpty()) "Paste the public key below" else "App is verified with the key below"
            textSize = 14f
            setTextColor(onSurfaceColor) // Dynamic: Black in Day, White in Night
            setPadding(0, 0, 0, 20)
        }

        val input = EditText(this).apply {
            setText(storedKey)
            hint = "8278278f..."
            maxLines = 3
            gravity = Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            // Ensure the text inside the input is also theme-aware
            setTextColor(onSurfaceColor)
            setHintTextColor(secondaryTextColor)
        }

        val linkSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 0)
        }

        val linkLabel = TextView(this).apply {
            text = "Get the verification key from:"
            textSize = 12f
            setTextColor(secondaryTextColor) // Dynamic: Gray that works in both
        }

        val twitterLink = TextView(this).apply {
            text = "x.com/Starling226"
            textSize = 13f
            setTextColor(primaryColor) // Dynamic: Uses your Brand Blue (Lighter in Night)
            setPadding(0, 10, 0, 5)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://x.com/Starling226"))
                startActivity(intent)
            }
        }

        val githubLink = TextView(this).apply {
            text = "github.com/Starling226/vaydns-vpn"
            textSize = 13f
            setTextColor(primaryColor) // Dynamic: Uses your Brand Blue
            setPadding(0, 5, 0, 0)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Starling226/vaydns-vpn"))
                startActivity(intent)
            }
        }

        linkSection.addView(linkLabel)
        linkSection.addView(twitterLink)
        linkSection.addView(githubLink)

        container.addView(textView)
        container.addView(input)
        container.addView(linkSection)

        // Using MaterialAlertDialogBuilder to handle button colors automatically
        MaterialAlertDialogBuilder(this)
            .setTitle("App Verification")
            .setView(container)
            .setPositiveButton("Verify") { _, _ ->
                val pastedKey = input.text.toString().trim()
                if (pastedKey.isEmpty()) return@setPositiveButton

                val isVerified = mobile.Mobile.checkVerification(pastedKey)

                if (isVerified) {
                    prefs.edit().putString("verified_public_key", pastedKey).apply()
                    invalidateOptionsMenu()
                }

                showVerificationResult(isVerified)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVerificationResult(isVerified: Boolean) {
        val resultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 50, 0, 50)
        }

        val statusIcon = TextView(this).apply {
            text = if (isVerified) "✅" else "❌"
            textSize = 48f
            gravity = Gravity.CENTER
        }

        val statusText = TextView(this).apply {
            text = if (isVerified) "Verified Official Build" else "NOT VERIFIED\n(Modified or Unofficial)"
            textSize = 18f
            setTextColor(if (isVerified) Color.parseColor("#006400") else Color.RED)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        resultContainer.addView(statusIcon)
        resultContainer.addView(statusText)

        checkAppVerificationState()
        // =========================================================
        // Instantly update the Left Drawer Menu Checkmark
        // =========================================================

        val resultDialog = AlertDialog.Builder(this)
            .setView(resultContainer)
            .setPositiveButton("OK", null)
            .create()

        resultDialog.show()

        // Style OK button
        resultDialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setTextColor(Color.parseColor("#2F4A6F"))
            text = "OK"
        }
    }

    private fun showVerificationResult2(isVerified: Boolean) {
        val resultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 50, 0, 50)
        }

        val statusIcon = TextView(this).apply {
            text = if (isVerified) "✅" else "❌"
            textSize = 48f
            gravity = Gravity.CENTER
        }

        val statusText = TextView(this).apply {
            text = if (isVerified) "Verified Official Build" else "NOT VERIFIED\n(Modified or Unofficial)"
            textSize = 18f
            setTextColor(if (isVerified) Color.parseColor("#006400") else Color.RED)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        resultContainer.addView(statusIcon)
        resultContainer.addView(statusText)

        val resultDialog = AlertDialog.Builder(this)
            .setView(resultContainer)
            .setPositiveButton("OK", null)
            .create()

        resultDialog.show()

        // Style OK button
        resultDialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setTextColor(Color.parseColor("#2F4A6F"))
            text = "OK"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        if (::toggle.isInitialized && toggle.onOptionsItemSelected(item)) {
            return true
        }

        return when (item.itemId) {

            R.id.action_quick_scanner -> {
                // 1. Safety Check: Stop VPN before scanning
                if (isVpnConnected) {
                    Toast.makeText(this, "Please stop the VPN before running a scan.", Toast.LENGTH_LONG).show()
                    return true
                }

                // 2. Find the dedicated Free Scanner config from the native vault
                val allConfigs = DefaultConfigProvider.getDefaultConfigs(this)
                val freeConfigEntry = allConfigs.find { it.freeScanner }

                if (freeConfigEntry != null) {
                    // Retrieve real secrets (Domain/PubKey) for the scanner to build the tunnel
                    val config = DefaultConfigProvider.getActualConfig(this, freeConfigEntry)

                    val intent = Intent(this, DnsScannerActivity::class.java).apply {
                        // Core Identification
                        putExtra("CONFIG_ID", config.id)
                        putExtra("DNS_ADDRESS", config.dnsAddress)
                        putExtra("IS_QUICK_SCANNER", true)

                        // Tunnel Parameters for End-to-End Test
                        putExtra("DOMAIN", config.domain)
                        // putExtra("CONFIG_ID", config.id)
                        putExtra("DOMAIN_INDEX", config.domainIndex)
                        putExtra("PUBKEY", config.pubkey)
                        putExtra("RECORD_TYPE", config.recordType)
                        putExtra("IS_DEFAULT", config.isDefault)
                        putExtra("IDLE_TIMEOUT", config.idleTimeout)
                        putExtra("CLIENT_ID_SIZE", config.clientIdSize)
                        putExtra("MTU", config.mtu)
                        putExtra("MODE", config.mode)

                        // Protocol & Authentication
                        putExtra("PROTOCOL", config.protocol)
                        putExtra("SS_METHOD", config.ssMethod.ifEmpty { "chacha20-ietf-poly1305" })
                        putExtra("USE_AUTH", config.useAuth)

                        val finalUser = if (config.protocol == "shadowsocks") {
                            config.ssMethod.ifEmpty { "chacha20-ietf-poly1305" }
                        } else if (config.useAuth) {
                            config.user.ifEmpty { "none" }
                        } else {
                            "none"
                        }
                        val finalPass = if (config.useAuth) config.pass.ifEmpty { "none" } else "none"

                        putExtra("USER", finalUser)
                        putExtra("PASS", finalPass)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Quick Scanner configuration not found on this server.", Toast.LENGTH_SHORT).show()
                }
                true
            }

            R.id.action_dns_scanner -> {

                if (isVpnConnected) {
                    Toast.makeText(this, "Please stop the VPN before running a scan.", Toast.LENGTH_LONG).show()
                    return true
                }

                val rawConfig = configList.find { it.id == selectedConfigId }
                if (rawConfig == null) {
                    Toast.makeText(this, "Please select a config first", Toast.LENGTH_LONG).show()
                } else {
                    val config = DefaultConfigProvider.getActualConfig(this, rawConfig)

                    val domainIndex = if (config.isDefault) {
                        getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE).getInt("${config.id}_domainIndex", 0)
                    } else {
                        config.domainIndex
                    }

                    val intent = Intent(this, DnsScannerActivity::class.java).apply {
                        putExtra("CONFIG_ID", config.id)
                        putExtra("DNS_ADDRESS", config.dnsAddress)
                        putExtra("IS_QUICK_SCANNER", false)
                        putExtra("DOMAIN", config.domain)
                        putExtra("DOMAIN_INDEX", domainIndex)
                        putExtra("PUBKEY", config.pubkey)
                        putExtra("RECORD_TYPE", config.recordType)
                        putExtra("IS_DEFAULT", config.isDefault)
                        putExtra("IDLE_TIMEOUT", config.idleTimeout)
                        putExtra("CLIENT_ID_SIZE", config.clientIdSize)
                        putExtra("MTU", config.mtu)
                        putExtra("MODE", config.mode)

                        putExtra("PROTOCOL", config.protocol)
                        putExtra("SS_METHOD", config.ssMethod.ifEmpty { "chacha20-ietf-poly1305" })
                        putExtra("USE_AUTH", config.useAuth)

                        val finalUser = if (config.protocol == "shadowsocks") {
                            config.ssMethod.ifEmpty { "chacha20-ietf-poly1305" }
                        } else if (config.useAuth) {
                            config.user.ifEmpty { "none" }
                        } else {
                            "none"
                        }
                        val finalPass = if (config.useAuth) config.pass.ifEmpty { "none" } else "none"

                        putExtra("USER", finalUser)
                        putExtra("PASS", finalPass)
                    }
                    startActivity(intent)
                }
                true
            }

            R.id.action_cf_scanner -> {
                if (isVpnConnected) {
                    Toast.makeText(this, "Please stop the VPN before running a scan.", Toast.LENGTH_LONG).show()
                    return true
                }

                val rawConfig = configList.find { it.id == selectedConfigId }
                if (rawConfig == null) {
                    Toast.makeText(this, "Please select a config first. The scanner requires a domain to test.", Toast.LENGTH_LONG).show()
                    return true
                }

                val config = DefaultConfigProvider.getActualConfig(this, rawConfig)
                val nativeIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L

                val configTypeLower = (if (config.isDefault) mobile.Mobile.getDefaultConfigType(nativeIndex) else config.mode ?: "").lowercase()
                val supportsVless = configTypeLower.contains("vless-ws")

                if (!supportsVless) {
                    Toast.makeText(this, "Cloudflare Scanner requires a VLESS-WS configuration. This config does not support it.", Toast.LENGTH_LONG).show()
                    return true // Instantly abort
                }

                val intent = Intent(this, CloudflareScannerActivity::class.java).apply {
                    putExtra("IS_DEFAULT", config.isDefault)
                    putExtra("CONFIG_INDEX", nativeIndex)
                    putExtra("DOMAIN", config.domain)
                    putExtra("CONFIG_ID", config.id)
                }
                startActivity(intent)
                true
            }

            R.id.action_import -> {
                showImportDialog()
                true
            }

            R.id.action_add_config -> {
                startActivity(Intent(this, ConfigEditorActivity::class.java))
                true
            }

            R.id.action_update_configs -> {
                syncConfigsWithServer()
                true
            }

            R.id.action_update_resolvers -> {
                syncResolversWithServer()
                true
            }

            R.id.action_update_resolvers -> {
                syncResolversWithServer()
                true
            }

            R.id.action_upload_configs -> {
                configFilePickerLauncher.launch(arrayOf("*/*"))
                true
            }

            R.id.action_upload_resolvers -> {
                // Using "*/*" because Android's SAF can be extremely strict
                // about selecting custom extensions like .bin
                resolverFilePickerLauncher.launch(arrayOf("*/*"))
                true
            }

            R.id.action_my_dns -> {
                showCurrentDnsDialog()
                true
            }

            R.id.action_import_resolvers -> {
                startActivity(Intent(this, ResolverTransferActivity::class.java).putExtra("MODE", "IMPORT"))
                true
            }
            R.id.action_export_resolvers -> {
                startActivity(Intent(this, ResolverTransferActivity::class.java).putExtra("MODE", "EXPORT"))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.2f GB", gb)
    }

    private fun showDailyTrafficDialog() {
        val prefs = getSharedPreferences("VayDNS_Traffic", Context.MODE_PRIVATE)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val displayFormat = java.text.SimpleDateFormat("MMM dd", java.util.Locale.US)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        data class TrafficRecord(val label: String, val rx: Long, val tx: Long, val osRx: Long, val osTx: Long)

        val calendar = java.util.Calendar.getInstance()
        val daysData = mutableListOf<TrafficRecord>()
        var maxTraffic = 1L

        for (i in 0 until 30) {
            val dateStr = dateFormat.format(calendar.time)
            var rx = prefs.getLong("rx_$dateStr", 0L)
            var tx = prefs.getLong("tx_$dateStr", 0L)
            var osRx = prefs.getLong("os_rx_$dateStr", 0L)
            var osTx = prefs.getLong("os_tx_$dateStr", 0L)

            if (i == 0 && liveTrackingDate == dateStr) {
                if (liveDailyRx != -1L && liveDailyRx > rx) rx = liveDailyRx
                if (liveDailyTx != -1L && liveDailyTx > tx) tx = liveDailyTx
                if (liveDailyOsRx != -1L && liveDailyOsRx > osRx) osRx = liveDailyOsRx
                if (liveDailyOsTx != -1L && liveDailyOsTx > osTx) osTx = liveDailyOsTx
            }

            // Both charts are scaled against whichever value (Go or OS) was the absolute highest this month
            val totalGo = rx + tx
            val totalOs = osRx + osTx
            if (totalGo > maxTraffic) maxTraffic = totalGo
            if (totalOs > maxTraffic) maxTraffic = totalOs

            val label = if (i == 0) "Today" else displayFormat.format(calendar.time)
            daysData.add(TrafficRecord(label, rx, tx, osRx, osTx))

            calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }

        daysData.reverse()
        val onSurfaceColor = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)

        for (day in daysData) {
            val (label, rx, tx, osRx, osTx) = day

            if (rx == 0L && tx == 0L && osRx == 0L && osTx == 0L && label != "Today") continue

            val textRow = TextView(this).apply {
                // Break it into multiple lines so it doesn't get cut off on narrow screens
                text = "$label:\nTunnel (Go): ${formatBytes(rx)} ↓ / ${formatBytes(tx)} ↑\nAndroid (OS): ${formatBytes(osRx)} ↓ / ${formatBytes(osTx)} ↑"
                textSize = 13f // Slightly smaller to keep the list clean
                setTextColor(onSurfaceColor)
                setLineSpacing(0f, 1.2f) // Add line spacing for readability
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
            }
            container.addView(textRow)

            // --- 1. GO ENGINE BAR (Blue & Green) ---
            val barContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (10 * resources.displayMetrics.density).toInt()
                )
                setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
            }

            val weightRx = (rx.toFloat() / maxTraffic.toFloat()).coerceAtLeast(0.0001f)
            val weightTx = (tx.toFloat() / maxTraffic.toFloat()).coerceAtLeast(0.0001f)
            val weightEmpty = (1.0f - (weightRx + weightTx)).coerceAtLeast(0.0f)

            if (rx > 0) {
                barContainer.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weightRx)
                    setBackgroundColor(android.graphics.Color.parseColor("#2196F3")) // Blue
                })
            }
            if (tx > 0) {
                barContainer.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weightTx)
                    setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green
                })
            }
            barContainer.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weightEmpty)
            })
            container.addView(barContainer)

            // --- 2. ANDROID OS BAR (Purple & Orange) ---
            val osBarContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (6 * resources.displayMetrics.density).toInt()
                ).apply {
                    setMargins(0, (2 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
                }
                setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
            }

            val weightOsRx = (osRx.toFloat() / maxTraffic.toFloat()).coerceAtLeast(0.0001f)
            val weightOsTx = (osTx.toFloat() / maxTraffic.toFloat()).coerceAtLeast(0.0001f)
            val weightOsEmpty = (1.0f - (weightOsRx + weightOsTx)).coerceAtLeast(0.0f)

            if (osRx > 0) {
                osBarContainer.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weightOsRx)
                    setBackgroundColor(android.graphics.Color.parseColor("#9C27B0")) // Purple
                })
            }
            if (osTx > 0) {
                osBarContainer.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weightOsTx)
                    setBackgroundColor(android.graphics.Color.parseColor("#FF9800")) // Orange
                })
            }
            osBarContainer.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weightOsEmpty)
            })
            container.addView(osBarContainer)
        }

        val legendRow = TextView(this).apply {
            text = "Tunnel (Go): ■ Down (Blue) ■ Up (Green)\nAndroid (OS): ■ Down (Purple) ■ Up (Orange)"
            textSize = 12f
            setTextColor(onSurfaceColor)
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            gravity = android.view.Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        container.addView(legendRow)

        val scrollView = android.widget.ScrollView(this).apply { addView(container) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Daily Traffic (Last 30 Days)")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showCurrentDnsDialog() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)

        // Extract DNS and Local IPs
        val dnsServers = linkProperties?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        val localIps = linkProperties?.linkAddresses?.mapNotNull { it.address.hostAddress }
            ?.filter { !it.contains(":") } ?: emptyList() // Filter out long IPv6 for cleaner UI

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, (16 * resources.displayMetrics.density).toInt(), pad, 0)
        }

        val onSurfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)
        val primaryColor = MaterialColors.getColor(this, android.R.attr.colorPrimary, android.graphics.Color.BLUE)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        val header = TextView(this).apply {
            text = "Network Details / جزئیات شبکه\n(Tap an IP to copy / برای کپی روی IP تپ کنید)\n"
            textSize = 15f
            setTextColor(onSurfaceColor)
            setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
        }
        container.addView(header)

        // Helper function to maintain your exact Ripple Effect and styling for the new IP rows
        fun addClickableRow(label: String, initialValue: String, isFetching: Boolean = false): TextView {
            val titleView = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            container.addView(titleView)

            val valueView = TextView(this).apply {
                text = initialValue
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(primaryColor)

                val padVertical = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, padVertical, 0, (12 * resources.displayMetrics.density).toInt())

                // Apply ripple effect only if we aren't waiting for the background thread
                if (!isFetching) {
                    val outValue = TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        val clip = android.content.ClipData.newPlainText(label, text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this@MainActivity, "Copied: $text", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            container.addView(valueView)
            return valueView
        }

        // 1. Local IP
        if (localIps.isNotEmpty()) {
            addClickableRow("Local IP / آی‌پی داخلی", localIps.first())
        }

        // 2. Public IP (Starts as "Fetching...")
        val tvPublicIp = addClickableRow("Public IP / آی‌پی عمومی", "Fetching... / در حال دریافت", isFetching = true)

        // 3. DNS Servers
        val dnsTitle = TextView(this).apply {
            text = "DNS Servers / سرورهای دی‌ان‌اس"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        container.addView(dnsTitle)

        if (dnsServers.isNotEmpty()) {
            for (ip in dnsServers) {
                val tvIp = TextView(this).apply {
                    text = ip
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(primaryColor)

                    val padVertical = (8 * resources.displayMetrics.density).toInt()
                    setPadding(0, padVertical, 0, padVertical)

                    // Add native Android tap ripple effect
                    val outValue = TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        val clip = android.content.ClipData.newPlainText("DNS IP", ip)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this@MainActivity, "Copied: $ip", Toast.LENGTH_SHORT).show()
                    }
                }
                container.addView(tvIp)
            }
        } else {
            val noDns = TextView(this).apply {
                text = "None detected / یافت نشد"
                textSize = 16f
                setTextColor(onSurfaceColor)
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt())
            }
            container.addView(noDns)
        }

        // Wrap in a ScrollView to prevent UI cutoff on small screens
        val scrollView = android.widget.ScrollView(this).apply { addView(container) }

        MaterialAlertDialogBuilder(this)
            .setTitle("My Network")
            .setView(scrollView)
            .setPositiveButton("Close / بستن", null)
            .setIcon(R.mipmap.ic_launcher_round)
            .show()

        // 4. Fetch Public IP asynchronously via lightweight API
        Thread {
            try {
                val publicIp = java.net.URL("https://api.ipify.org").readText(Charsets.UTF_8)
                runOnUiThread {
                    tvPublicIp.text = publicIp

                    // Re-enable the ripple effect and click listener now that it has loaded
                    val outValue = TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    tvPublicIp.setBackgroundResource(outValue.resourceId)
                    tvPublicIp.isClickable = true
                    tvPublicIp.isFocusable = true

                    tvPublicIp.setOnClickListener {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Public IP", publicIp))
                        Toast.makeText(this@MainActivity, "Copied: $publicIp", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvPublicIp.text = "Unavailable / در دسترس نیست"
                }
            }
        }.start()
    }

    private fun showCurrentDnsDialog2() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)

        val dnsServers = linkProperties?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()

        if (dnsServers.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("My DNS Server")
                .setMessage("Could not determine your current DNS servers. Please check your network connection.\n\n" +
                        "امکان تشخیص سرورهای DNS فعلی شما وجود ندارد. لطفاً اتصال شبکه خود را بررسی کنید.")
                .setPositiveButton("Close / بستن", null)
                .setIcon(R.mipmap.ic_launcher_round)
                .show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, (16 * resources.displayMetrics.density).toInt(), pad, 0)
        }

        val onSurfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)
        val primaryColor = MaterialColors.getColor(this, android.R.attr.colorPrimary, android.graphics.Color.BLUE)

        val header = TextView(this).apply {
            text = "Your current active DNS servers are:\n(Tap an IP to copy)\n\n" +
                    "سرورهای DNS فعال و فعلی شما:\n(برای کپی روی IP تپ کنید)"
            textSize = 15f
            setTextColor(onSurfaceColor)
            setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
        }
        container.addView(header)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

        for (ip in dnsServers) {
            val tvIp = TextView(this).apply {
                text = ip
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(primaryColor)

                val padVertical = (12 * resources.displayMetrics.density).toInt()
                setPadding(0, padVertical, 0, padVertical)

                // Add native Android tap ripple effect
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    val clip = android.content.ClipData.newPlainText("DNS IP", ip)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "Copied: $ip", Toast.LENGTH_SHORT).show()
                }
            }
            container.addView(tvIp)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("My DNS Server")
            .setView(container)
            .setPositiveButton("Close / بستن", null)
            .setIcon(R.mipmap.ic_launcher_round)
            .show()
    }

    fun getMultipathResolvers(configId: String, defaultAddr: String, mode: String): String {

        // THE RUTHLESS FORMATTER: Strips everything and forces the correct mode
        fun forceCorrectPort(res: String): String {
            var cleanIp = res.trim()
            if (cleanIp.isEmpty()) return ""

            // 1. Strip HTTP/HTTPS protocols and paths if they accidentally left them
            if (cleanIp.startsWith("http://") || cleanIp.startsWith("https://")) {
                cleanIp = cleanIp.substringAfter("://").substringBefore("/")
            }

            // 2. Strip ANY existing port (e.g., "8.8.8.8:853" becomes "8.8.8.8")
            if (cleanIp.contains(":")) {
                cleanIp = cleanIp.substringBeforeLast(":")
            }

            // 3. Force the correct port/format based strictly on the selected Tunnel Mode
            return when (mode.lowercase()) {
                //"doh" -> "https://$cleanIp/dns-query"
                "doh" -> cleanIp
                "dot" -> "$cleanIp:853"
                else -> "$cleanIp:53" // TCP and UDP
            }
        }

        // Format the single default address just in case multipath is empty
        val formattedDefault = forceCorrectPort(defaultAddr)

        // Read the raw multipath IPs
        val rawSelections = try {
            val file = java.io.File(filesDir, "selected_multipath_$configId.txt")
            if (!file.exists()) {
                listOf(formattedDefault)
            } else {
                val lines = file.readLines().filter { it.isNotEmpty() }
                if (lines.isEmpty()) listOf(formattedDefault) else lines
            }
        } catch (e: Exception) {
            listOf(formattedDefault)
        }

        // Apply the formatter to EVERY IP right before sending to Go
        return rawSelections.map { forceCorrectPort(it) }.joinToString(",")
    }

    private fun showHowToUseDialog() {
        val instructions = """
            1. Select Apps to Tunnel: Tap on SELECT APPS TO TUNNEL and choose a few specific apps (3–4 recommended) that you want to pass through the tunnel. Only selected apps will be routed; all other traffic will remain on your local network.

            2. Add Your Configuration:
               • To use your own server: Tap the Menu (three dots) and select "Add Config" or "Import Config".
               • To use built-in servers: Toggle on "Use default configs" and select a server from the list.

            3. Find a Usable Resolver: To establish a tunnel, you must find a functional DNS Resolver for your network.
               • For new users the quickest way: From menu tap on Update Resolvers. Select a config and tap on pencil icon, then tap on "Use default resolvers" and select one from the list. Go to 4.
               • To scan a fresh resolvers:
                 - Open the Menu and select "DNS Scanner".
                 - Use the default parameters and tap "START SCAN".
                 - Look for a resolver with a latency lower than 6000 ms.
                 - Tap the Set (Checkmark) icon to apply the fastest resolver to your config, or the Save icon to store a list of fast resolvers.
                 - Note: If no usable resolvers are found, go back and start a new scan to get a fresh random list.

            4. Start the Tunnel: Return to the main menu and tap START TUNNEL. It may take up to 20 seconds to establish a stable connection.

            5. Troubleshooting: Different configurations use different DNS record types (TXT, NULL, etc.). A resolver that works for one config may not work for another. If you cannot connect, try switching to a different configuration or record type.

            6. Performance Expectations: DNS tunneling is inherently slower than traditional VPNs due to protocol overhead. Expect speeds ranging from 10 KB/sec to 200 KB/sec, depending on your network conditions.

            --------------------------------------------------
            
            ۱. انتخاب برنامه‌ها برای تونل (Split Tunneling): روی گزینه SELECT APPS TO TUNNEL تپ کنید و چند برنامه خاص (پیشنهاد می‌شود ۳ تا ۴ برنامه) را برای عبور از تونل انتخاب کنید. فقط ترافیک برنامه‌های انتخاب شده از تونل عبور می‌کند و بقیه برنامه‌ها از اینترنت عادی شما استفاده خواهند کرد.

            ۲. افزودن پیکربندی (Configuration):
               • برای استفاده از سرور شخصی خود: منو (سه نقطه) را باز کرده و "Add Config" و یا "Import Config" را انتخاب کنید.
               • برای استفاده از سرورهای پیش‌فرض: گزینه "Use default configs" را فعال کرده و یکی از سرورهای لیست را انتخاب کنید.

            ۳. یافتن یک DNS (Resolver) مناسب: برای برقراری اتصال، باید یک Resolver فعال که با شبکه شما سازگار باشد پیدا کنید.
               • سریع‌ترین روش برای کاربران جدید: از منو روی Update Resolvers تپ کنید. یک پیکربندی (Config) را انتخاب کرده و روی آیکون مداد تپ کنید، سپس گزینه "Use default resolvers" را بزنید و یکی را از لیست انتخاب کنید. به شماره ۴ بروید.
               • برای اسکن Resolverهای جدید:               
                 - از منو گزینه "DNS Scanner" را انتخاب کنید.
                 - از پارامترهای پیش‌فرض استفاده کنید و روی "START SCAN" تپ کنید.
                 - پس از اتمام اسکن، به دنبال موردی بگردید که تاخیر (Latency) آن کمتر از 6000 میلی‌ثانیه باشد.
                 - روی آیکون تأیید (Checkmark) تپ کنید تا سریع‌ترین Resolver مستقیماً روی تنظیمات شما اعمال شود، یا از آیکون ذخیره (Save) برای نگهداری لیست استفاده کنید.
                 - نکته: اگر هیچ Resolver مناسبی پیدا نشد، به عقب برگردید و اسکن جدیدی شروع کنید تا لیست تصادفی جدیدی دریافت کنید.

            ۴. شروع اتصال (Start Tunnel): به منوی اصلی برگردید و روی "START TUNNEL" تپ کنید. برقراری اتصال پایدار ممکن است تا ۲۰ ثانیه زمان ببرد.

            ۵. عیب‌یابی: پیکربندی‌های مختلف از انواع رکوردهای DNS (مانند TXT یا NULL) استفاده می‌کنند. ممکن است یک Resolver که برای یک سرور کار می‌کند، برای سرور دیگر مناسب نباشد. در صورت عدم اتصال، سرور یا نوع رکورد را تغییر دهید.

            ۶. انتظارات از عملکرد: لطفاً توجه داشته باشید که تونل‌سازی DNS ذاتا کندتر از VPNهای معمولی است. بسته به شرایط شبکه، انتظار سرعتی بین ۱۰ تا ۲۰۰ کیلوبایت بر ثانیه را داشته باشید.
        """.trimIndent()

        // Resolve dynamic text color for Day/Night modes
        val onSurfaceColor = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            android.graphics.Color.BLACK
        )

        val textView = TextView(this).apply {
            text = instructions
            textSize = 15f
            setTextColor(onSurfaceColor)

            // Convert dp to pixels for padding
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)

            // Enable line spacing for better readability
            setLineSpacing(0f, 1.3f)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("How to Use / راهنمای استفاده")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun importConfigBinary(uri: android.net.Uri) {
        Thread {
            try {
                contentResolver.openInputStream(uri)?.let { rawStream ->
                    java.io.BufferedInputStream(rawStream).use { bufferedStream ->
                        val bytes = bufferedStream.readBytes()

                        if (bytes.isEmpty()) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "Error: File is empty.", Toast.LENGTH_LONG).show() }
                            return@Thread
                        }

                        val maxSizeBytes = 2 * 1024 * 1024
                        if (bytes.size > maxSizeBytes) {
                            runOnUiThread { Toast.makeText(this@MainActivity, "Error: File is too large. Limit is 2 MB.", Toast.LENGTH_LONG).show() }
                            return@Thread
                        }

                        // NATIVE VAULT: Pass raw bytes directly to Go.
                        val result = mobile.Mobile.importConfigsManual(bytes)

                        runOnUiThread {
                            if (result.startsWith("SUCCESS")) {
                                // Will show "Config upload successful!"
                                Toast.makeText(this@MainActivity, result.substringAfter("|"), Toast.LENGTH_SHORT).show()
                                switchDefault.isChecked = true
                                refreshConfigList()
                            } else if (result.startsWith("UP_TO_DATE")) {
                                // Will show "Configs are already updated."
                                Toast.makeText(this@MainActivity, result.substringAfter("|"), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } ?: runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: Android could not open this file.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("VAY_DEBUG_kotlin", "Exception: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to import: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun importResolverBinary(uri: android.net.Uri) {
        Thread {
            try {
                contentResolver.openInputStream(uri)?.let { rawStream ->
                    java.io.BufferedInputStream(rawStream).use { bufferedStream ->
                        val bytes = bufferedStream.readBytes()

                        // 1. Check if empty
                        if (bytes.isEmpty()) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Error: File is empty.", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }

                        // 2. Prevent abuse: Restrict to 2 MB (2 * 1024 * 1024 bytes)
                        val maxSizeBytes = 2 * 1024 * 1024
                        if (bytes.size > maxSizeBytes) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Error: File is too large. Limit is 2 MB.", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }

                        // 3. NATIVE VAULT: Pass raw bytes directly to Go.
                        val result = mobile.Mobile.importResolversManual(bytes)

                        runOnUiThread {
                            // 4. Show success toast if Go accepted it
                            if (result.startsWith("SUCCESS")) {
                                Toast.makeText(this@MainActivity, "Upload successful!", Toast.LENGTH_SHORT).show()
                            } else {
                                // Show any error messages returned by Go
                                Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                            }
                            refreshConfigList()
                        }
                    }
                } ?: runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: Android could not open this file.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("VAY_DEBUG_kotlin", "Exception: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to import: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun syncConfigsWithServer() {
        runOnUiThread { Toast.makeText(this, "Checking for configs...", Toast.LENGTH_SHORT).show() }
        Thread {
            // NATIVE VAULT: Tell Go to handle the download, decryption, and saving natively.
            val result = mobile.Mobile.syncConfigs()

            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                if (result.startsWith("Success")) {
                    refreshConfigList()
                }
            }
        }.start()
    }

    private fun syncResolversWithServer() {
        runOnUiThread { Toast.makeText(this, "Updating resolvers...", Toast.LENGTH_SHORT).show() }
        Thread {
            // NATIVE VAULT: Tell Go to handle the download, decryption, and saving natively.
            val result = mobile.Mobile.syncResolvers()

            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun checkForAppUpdate() {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        runOnUiThread { Toast.makeText(this, "Checking for app updates...", Toast.LENGTH_SHORT).show() }

        Thread {
            try {
                // Connect directly to your public GitHub repository's latest release endpoint
                val url = URL("https://api.github.com/repos/Starling226/vaydns-vpn/releases/latest")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    // GitHub API requires a User-Agent string to prevent automated request blocking
                    setRequestProperty("User-Agent", "VayDNS-App-Updater")
                }

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = org.json.JSONObject(jsonResponse)

                    // Extract the release tag name (e.g., "v1.11.0" or "1.11.0")
                    val fetchedVersionRaw = jsonObject.getString("tag_name").trim()

                    // Strip the 'v' prefix if present for clean calculation comparison blocks
                    val fetchedVersion = if (fetchedVersionRaw.startsWith("v", ignoreCase = true)) {
                        fetchedVersionRaw.substring(1)
                    } else {
                        fetchedVersionRaw
                    }

                    // Normalize current version string to remove any unexpected character decorations
                    val cleanCurrent = if (currentVersion.startsWith("v", ignoreCase = true)) currentVersion.substring(1) else currentVersion

                    runOnUiThread {
                        if (isNewerAppVersionLocal(cleanCurrent, fetchedVersion)) {
                            showUpdateDialog(fetchedVersion)
                        } else {
                            Toast.makeText(this@MainActivity, "You are using the latest version ($currentVersion).", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Update check failed: Server returned ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to connect to GitHub update server.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // Native version comparison logic that mirrors your original Go parsing algorithm
    private fun isNewerAppVersionLocal(current: String, fetched: String): Boolean {
        val cleanCurrent = current.filter { it.isDigit() || it == '.' }.split(".")
        val cleanFetched = fetched.filter { it.isDigit() || it == '.' }.split(".")

        val maxLength = maxOf(cleanCurrent.size, cleanFetched.size)

        for (i in 0 until maxLength) {
            val c = cleanCurrent.getOrNull(i)?.toIntOrNull() ?: 0
            val f = cleanFetched.getOrNull(i)?.toIntOrNull() ?: 0

            if (f > c) return true
            if (f < c) return false
        }
        return false
    }

    private fun showUpdateDialog(newVersion: String) {
        runOnUiThread {
            // 1. Detect Android Architecture
            var arch = "arm64-v8a" // Fallback to 64-bit standard
            for (abi in Build.SUPPORTED_ABIS) {
                if (abi.contains("arm64-v8a")) {
                    arch = "arm64-v8a"
                    break
                } else if (abi.contains("armeabi-v7a")) {
                    arch = "armeabi-v7a"
                    break
                }
            }

            // 2. Format Version String (Ensure it starts with 'v' for the URL)
            val versionStr = if (newVersion.startsWith("v")) newVersion else "v$newVersion"

            // 3. Construct the GitHub Release URL
            val downloadUrl = "https://github.com/Starling226/vaydns-vpn/releases/download/$versionStr/VaydnsVpn-$versionStr-$arch.apk"

            // 4. Show the Dialog
            MaterialAlertDialogBuilder(this)
                .setTitle("App Update Available")
                .setMessage("A new version of VayDNS ($versionStr) is available.\n\n" +
//                        "Your device uses the $arch architecture.\n\n" +
                        "Note: Your browser may say 'File might be harmful' because this is a direct APK download. It is safe to tap 'Download anyway'. If file download progress was not displayed, please check your Downloads directory.\n\n" +
                        "توجه: از آنجا که این فایل مستقیماً دانلود می‌شود، ممکن است مرورگر هشدار «File might be harmful» را نمایش دهد. این فایل کاملاً امن است؛ با خیال راحت روی «Download anyway» تپ کنید. اگر پیشرفت دانلود نمایش داده نشد، لطفاً پوشه دانلودهای خود را بررسی کنید.")
                .setPositiveButton("Download") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(downloadUrl))
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .setIcon(R.mipmap.ic_launcher_round)
                .show()
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService()
    }

    private fun startVpnService() {

        if (selectedConfigId == null) {
            Toast.makeText(this, "Please select a config first", Toast.LENGTH_SHORT).show()
            return
        }
        val config = configList.find { it.id == selectedConfigId } ?: return

        if (config.domain.isEmpty()) {
            Toast.makeText(this, "Invalid configuration. Please select another.", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
            .edit()
            .putString("connected_config_id", config.id)
            .apply()

        val currentPort = etProxyPort.text.toString().trim()
        getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
            .edit()
            .putString("proxy_port", currentPort)
            .apply()

        // Disable button while processing
        btnToggle.isEnabled = false
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GRAY)

        val finalConfig = if (config.isDefault) {
            DefaultConfigProvider.getActualConfig(this@MainActivity, config)
        } else {
            config
        }

        val nativeIndex = if (config.isDefault) {
            config.id.removePrefix("default_").toLongOrNull() ?: 0L
        } else {
            -1L
        }

        // Fetch the discriminator to decide the routing path
        val configType = if (config.isDefault) {
            mobile.Mobile.getDefaultConfigType(nativeIndex)
        } else {
            "vaydns"
        }

        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        val globalOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
        val globalProtocol = tunnelPrefs.getString("global_protocol_selected", "vaydns") ?: "vaydns"

        var activeProtocol = if (config.isDefault && globalOverride) {
            globalProtocol
        } else if (config.isDefault) {
            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${config.id}_tunnelProtocol", null)
                ?: configType.split(",").firstOrNull { it.isNotBlank() } ?: "vaydns"
        } else {
            // STRICT GUARDRAIL: Custom configs bypass the global override and strictly use their own protocol
            finalConfig.tunnelProtocol
        }

        if (config.isDefault) {
            val supportedProtocols = configType.lowercase().split(",").map { it.trim() }
            if (!supportedProtocols.contains(activeProtocol.lowercase())) {

                // 1. Warn the user
                Toast.makeText(
                    this,
                    "Connection Failed: This server no longer supports '${activeProtocol}'.Please edit the config to select a valid protocol.",
                    Toast.LENGTH_LONG
                ).show()
                val btnToggle = findViewById<Button>(R.id.btn_toggle)
                val tvStatus = findViewById<TextView>(R.id.tv_status)

                btnToggle?.isEnabled = true
                btnToggle?.text = "START TUNNEL"
                tvStatus?.text = "Status: Disconnected"
                val primaryColor = com.google.android.material.color.MaterialColors.getColor(this, android.R.attr.colorPrimary, Color.BLUE)
                btnToggle?.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                // 2. Abort the VPN launch immediately
                return
            }
        }

        // 1. Fetch the specific IP for this config
        val configVlessIp = if (config.isDefault) {
            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${config.id}_vlessIp", "") ?: ""
        } else {
            finalConfig.vlessIp
        }

        // Fetch the Vault JSON
        val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"
        var firstGlobalVlessIp = ""

        try {
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                // We only care about the single checked IP for the VPN Override
                if (obj.getBoolean("isChecked")) {
                    firstGlobalVlessIp = obj.getString("ip")
                    break
                }
            }
            // Safety Fallback: If nothing was checked, grab the first available IP
            if (firstGlobalVlessIp.isEmpty() && jsonArray.length() > 0) {
                firstGlobalVlessIp = jsonArray.getJSONObject(0).getString("ip")
            }
        } catch (e: Exception) { e.printStackTrace() }

        val isDirectMode = activeProtocol.lowercase() != "vaydns"
        // =================================================================
        // ARCHITECTURAL FORK: DIRECT PROTOCOL (HYSTERIA)
        // =================================================================

        if (isDirectMode) {

            // If the JSON is cleanly marked "direct", use the Global Settings override.

            //val finalProtocol = if (configType.lowercase() == "direct") activeProtocol else configType.lowercase()
            val finalProtocol = if (activeProtocol != "vaydns") activeProtocol else configType.split(",").firstOrNull { it != "vaydns" } ?: "hysteria2"

            if (finalProtocol == "vaydns") {
                Toast.makeText(this@MainActivity, "This is a direct config. Please select Hysteria or Reality in Settings.", Toast.LENGTH_LONG).show()
                btnToggle.isEnabled = true
                btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2F4A6F"))
                return
            }

            // 1. Fetch the engine type first
//            val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
            var engineType = tunnelPrefs.getString("tun_engine", "sing-box")

           // 2. Silent Engine Guardrail: Direct protocols natively require Sing-box
            if (engineType != "sing-box") {
                engineType = "sing-box"
            }

            // 3. Proceed with Hysteria connection
            tvStatus.text = "Status: Connecting to Node..."
            tvStatus.setTextColor(Color.parseColor("#008080")) // Teal for Direct Connection
            btnToggle.text = "CONNECTING..."

            val intent = Intent(this@MainActivity, VayVpnService::class.java).apply {
                action = "ACTION_START_VPN"
                putStringArrayListExtra("ALLOWED_APPS_LIST", ArrayList(selectedApps))
                putExtra("IS_DEFAULT_CONFIG", config.isDefault)
                putExtra("CONFIG_ID", config.id)
                putExtra("CONFIG_INDEX", nativeIndex)
                putExtra("CONFIG_TYPE", "direct")
                putExtra("PROTOCOL", finalProtocol)
                putExtra("ENGINE_TYPE", engineType)
                putExtra("VLESS_WS_IP", firstGlobalVlessIp)
            }

            if (isProxyMode) {
                var proxyPort = etProxyPort.text.toString().toIntOrNull() ?: 1080
                if (proxyPort < 1024 || proxyPort > 65535) {
                    proxyPort = 1080
                    etProxyPort.setText("1080")
                    Toast.makeText(this@MainActivity, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show()
                }
                intent.putExtra("PROXY_PORT", proxyPort.toLong())
                intent.setClass(this@MainActivity, VayProxyService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                val vpnIntent = VpnService.prepare(this@MainActivity)
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }

            // =================================================================
            // ARCHITECTURAL FORK: DNS TUNNEL (VAYDNS)
            // =================================================================
        } else {
            tvStatus.text = "Status: Verifying Domains..."
            tvStatus.setTextColor(Color.parseColor("#FFA500")) // Orange for verifying
            btnToggle.text = "CONNECTING..."

            val multipathDns = getMultipathResolvers(config.id, finalConfig.dnsAddress, finalConfig.mode)

            CoroutineScope(Dispatchers.IO).launch {
                val testResolver = multipathDns.split(",").firstOrNull()?.trim() ?: "8.8.8.8:53"
                val tunnelPrefs = this@MainActivity.getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                val proxyType = tunnelPrefs.getString("proxy_type", "socks5h") ?: "socks5h"
                val lightE2E = false
                val tWait = tunnelPrefs.getInt("tunnel_wait", 3000)
                val uTimeout = tunnelPrefs.getInt("udp_timeout", 1000)
                val retries = tunnelPrefs.getInt("retries", 0)
                var pTimeout = tunnelPrefs.getInt("probe_timeout", 15000).toLong()

                val currentMode = finalConfig.mode.lowercase()
                if (!lightE2E && (currentMode == "udp" || currentMode == "tcp")) {
                    if (pTimeout > 10000L) pTimeout = 10000L
                }

                val safeWorkers = 5
                val baseDohUrl = if (finalConfig.mode.lowercase() == "doh") finalConfig.dnsAddress else ""
                val ssMethod = finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" }
                val user = if (finalConfig.protocol == "shadowsocks") ssMethod else if (finalConfig.useAuth) finalConfig.user.ifEmpty { "none" } else "none"
                val pass = if (finalConfig.useAuth) finalConfig.pass.ifEmpty { "none" } else "none"
                val engineQuickScan = false

                val domainIndex = if (config.isDefault) {
                    getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE).getInt("${config.id}_domainIndex", 0)
                } else {
                    finalConfig.domainIndex
                }

                val healthyDomains = if (finalConfig.useMultiDomains) {
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        val receiver = object : android.content.BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                val result = intent.getStringExtra("HEALTHY_DOMAINS") ?: ""
                                try { context.unregisterReceiver(this) } catch(e: Exception){}
                                if (continuation.isActive) continuation.resume(result)
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            this@MainActivity.registerReceiver(receiver, android.content.IntentFilter("ACTION_DOMAIN_SCAN_RESULT"), Context.RECEIVER_NOT_EXPORTED)
                        } else {
                            this@MainActivity.registerReceiver(receiver, android.content.IntentFilter("ACTION_DOMAIN_SCAN_RESULT"))
                        }

                        val scanIntent = Intent(this@MainActivity, VayDomainService::class.java).apply {
                            putExtra("USE_MULTI_DOMAINS", finalConfig.useMultiDomains)
                            putExtra("DOMAIN_INDEX", domainIndex)
                            putExtra("IS_DEFAULT", config.isDefault)
                            putExtra("CONFIG_INDEX", nativeIndex)
                            putExtra("DOMAINS", finalConfig.domain)
                            putExtra("RESOLVER_IP", testResolver)
                            putExtra("DNS_MODE", finalConfig.mode)
                            putExtra("PUBKEY", finalConfig.pubkey)
                            putExtra("BASE_DOH_URL", baseDohUrl)
                            putExtra("PROXY_TYPE", proxyType)
                            putExtra("TUNNEL_PROTOCOL", finalConfig.protocol)
                            putExtra("PROXY_USER", user)
                            putExtra("PROXY_PASS", pass)
                            putExtra("SS_METHOD", ssMethod)
                            putExtra("RECORD_TYPE", finalConfig.recordType)
                            putExtra("IDLE_TIMEOUT", finalConfig.idleTimeout)
                            putExtra("KEEP_ALIVE", finalConfig.keepAlive)
                            putExtra("CLIENT_ID_SIZE", finalConfig.clientIdSize)
                            putExtra("MTU", finalConfig.mtu)
                            putExtra("WORKERS", safeWorkers.toLong())
                            putExtra("TUNNEL_WAIT", tWait.toLong())
                            putExtra("UDP_TIMEOUT", uTimeout.toLong())
                            putExtra("PROBE_TIMEOUT", pTimeout)
                            putExtra("RETRIES", retries.toLong())
                            putExtra("LIGHT_E2E", lightE2E)
                            putExtra("QUICK_SCAN", engineQuickScan)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            this@MainActivity.startForegroundService(scanIntent)
                        } else {
                            this@MainActivity.startService(scanIntent)
                        }

                        continuation.invokeOnCancellation {
                            val stopIntent = Intent(this@MainActivity, VayDomainService::class.java).apply { action = "ACTION_STOP_DOMAIN_SCANNER" }
                            this@MainActivity.startService(stopIntent)
                            try { this@MainActivity.unregisterReceiver(receiver) } catch(e: Exception){}
                        }
                    }
                } else {
                    finalConfig.domain.split(",").firstOrNull()?.trim() ?: finalConfig.domain
                }

                withContext(Dispatchers.Main) {
                    if (finalConfig.useMultiDomains && healthyDomains.isEmpty()) {
                        Toast.makeText(this@MainActivity, "All tunnel domains are blocked by your ISP!", Toast.LENGTH_LONG).show()
                        isVpnConnected = false
                        btnToggle.isEnabled = true
                        btnToggle.text = "START TUNNEL"
                        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2F4A6F"))
                        tvStatus.text = "Status: Disconnected"
                        tvStatus.setTextColor(android.graphics.Color.parseColor("#424242"))
                        return@withContext
                    }

                    if (healthyDomains.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Invalid configuration. Domain is empty.", Toast.LENGTH_SHORT).show()
                        isVpnConnected = false
                        btnToggle.isEnabled = true
                        btnToggle.text = "START TUNNEL"
                        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2F4A6F"))
                        tvStatus.text = "Status: Disconnected"
                        tvStatus.setTextColor(android.graphics.Color.parseColor("#424242"))
                        return@withContext
                    }

                    tvStatus.text = "Status: Connecting..."

                    val intent = Intent(this@MainActivity, VayVpnService::class.java).apply {
                        action = "ACTION_START_VPN"
                        putStringArrayListExtra("ALLOWED_APPS_LIST", ArrayList(selectedApps))

                        val engineType = tunnelPrefs.getString("tun_engine", "sing-box")
                        putExtra("ENGINE_TYPE", engineType)

                        putExtra("IS_DEFAULT_CONFIG", config.isDefault)
                        putExtra("CONFIG_ID", config.id)
                        putExtra("CONFIG_INDEX", nativeIndex)
                        putExtra("CONFIG_TYPE", "vaydns")

                        putExtra("USE_MULTI_DOMAINS", finalConfig.useMultiDomains)
                        putExtra("DOMAIN_INDEX", domainIndex)
                        putExtra("DOMAIN", healthyDomains)
                        putExtra("PUBKEY", finalConfig.pubkey)

                        val finalBaseDohUrl = if (finalConfig.mode.lowercase() == "doh") finalConfig.dnsAddress else ""
                        putExtra("BASE_DOH_URL", finalBaseDohUrl)
                        putExtra("UDP", multipathDns)
                        putExtra("MODE", finalConfig.mode)
                        putExtra("RECORD_TYPE", finalConfig.recordType)
                        putExtra("IDLE_TIMEOUT", finalConfig.idleTimeout)
                        putExtra("KEEP_ALIVE", finalConfig.keepAlive)
                        putExtra("CLIENT_ID_SIZE", finalConfig.clientIdSize)
                        putExtra("MTU", config.mtu)
                        putExtra("DNSTT_COMPATIBLE", finalConfig.dnsttCompatible)
                        putExtra("USE_AUTH", finalConfig.useAuth)
                        putExtra("PROTOCOL", finalConfig.protocol)
                        putExtra("AUTH_PROTOCOL", finalConfig.authProtocol)
                        putExtra("SS_METHOD", finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" })

                        val finalUserVal = if (finalConfig.protocol == "shadowsocks") {
                            finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" }
                        } else if (config.useAuth) {
                            finalConfig.user.ifEmpty { "none" }
                        } else {
                            "none"
                        }
                        val finalPassVal = if (finalConfig.useAuth) finalConfig.pass.ifEmpty { "none" } else "none"

                        putExtra("USER", finalUserVal)
                        putExtra("PASS", finalPassVal)
                    }

                    if (isProxyMode) {
                        var proxyPort = etProxyPort.text.toString().toIntOrNull() ?: 1080
                        if (proxyPort < 1024 || proxyPort > 65535) {
                            proxyPort = 1080
                            etProxyPort.setText("1080")
                            Toast.makeText(this@MainActivity, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show()
                        }
                        intent.putExtra("PROXY_PORT", proxyPort.toLong())
                        intent.setClass(this@MainActivity, VayProxyService::class.java)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } else {
                        val vpnIntent = VpnService.prepare(this@MainActivity)
                        if (vpnIntent != null) {
                            vpnPermissionLauncher.launch(vpnIntent)
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopVpnService() {
        getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
            .edit()
            .remove("connected_config_id")
            .apply()

        // TELL SERVICES TO INITIATE GRACEFUL SELF-DESTRUCT
        startService(Intent(this, VayVpnService::class.java).apply { action = "ACTION_STOP_VPN" })
        startService(Intent(this, VayProxyService::class.java).apply { action = "ACTION_STOP_VPN" })

        // 1. Reset tracking variable
        isVpnConnected = false

        // 2. Reset Button Text and Color
//        btnToggle.text = "START TUNNEL"
        btnToggle.isEnabled = false
        btnToggle.text = "STOPPING..."
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GRAY)
//        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2F4A6F"))

        // 3. Reset Status Text
        tvStatus.text = "Status: Disconnected"
        tvStatus.setTextColor(android.graphics.Color.parseColor("#424242"))

        supportActionBar?.title = "VayDNS"
// Unlock and reset the UI after exactly 1 second
        btnToggle.postDelayed({
            btnToggle.isEnabled = true
            btnToggle.text = "START TUNNEL"
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2F4A6F"))
            tvStatus.text = "Status: Disconnected"

            if (isProxyMode && ::etProxyPort.isInitialized) {
                etProxyPort.isEnabled = true
            }
        }, 1000)
    }

    override fun onResume() {
        super.onResume()

        // INSTANT MENU REFRESH: Force Android to redraw the toolbar menu based on new settings
        invalidateOptionsMenu()
        checkAppVerificationState()

        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val prefs = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        val connectedId = prefs.getString("connected_config_id", null)

        if (!isProxyMode) {
            // Check if VayVpnService is actively running in the background
            var isVpnRunning = false
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (VayVpnService::class.java.name == service.service.className) {
                    isVpnRunning = true
                    break
                }
            }

            // ZOMBIE SERVICE GATEKEEPER
            if (isVpnRunning && connectedId == null) {
                isVpnRunning = false
            }

            // CLEANUP: If the OS silently killed the VPN, wipe the stuck ID
            if (!isVpnRunning && connectedId != null) {
                prefs.edit().remove("connected_config_id").apply()
            }

            if (isVpnRunning && !isVpnConnected) {
                isVpnConnected = true
                updateUIState(true)
            } else if (!isVpnRunning && isVpnConnected) {
                isVpnConnected = false
                updateUIState(false)
            } else {
                updateUIState(isVpnRunning)
            }

        } else {
            // Check if VayProxyService is actively running in the background
            var isProxyRunning = false
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (VayProxyService::class.java.name == service.service.className) {
                    isProxyRunning = true
                    break
                }
            }

            // ZOMBIE PROXY GATEKEEPER
            if (isProxyRunning && connectedId == null) {
                isProxyRunning = false
            }

            if (!isProxyRunning && connectedId != null) {
                prefs.edit().remove("connected_config_id").apply()
            }

            if (isProxyRunning && !isVpnConnected) {
                isVpnConnected = true
                updateUIState(true)
            } else if (!isProxyRunning && isVpnConnected) {
                isVpnConnected = false
                updateUIState(false)
            } else {
                updateUIState(isProxyRunning)
            }
        }

        // INSTANT SYNC: Check the startup behavior settings every time we return to this screen
        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        val defaultConfigsEnabled = appPrefs.getBoolean("default_configs_at_start", true)

        // Fetch the Vault JSON
        val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"
        var firstGlobalVlessIp = ""

        try {
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                // We only care about the single checked IP for the VPN Override
                if (obj.getBoolean("isChecked")) {
                    firstGlobalVlessIp = obj.getString("ip")
                    break
                }
            }
            // Safety Fallback: If nothing was checked, grab the first available IP
            if (firstGlobalVlessIp.isEmpty() && jsonArray.length() > 0) {
                firstGlobalVlessIp = jsonArray.getJSONObject(0).getString("ip")
            }
        } catch (e: Exception) { e.printStackTrace() }

        // Pass the saved preference to the Go core layer to sync the latency scanner
        mobile.Mobile.setGlobalVlessWsIP(firstGlobalVlessIp)

        // Update the switch visually without triggering the manual listener twice
        switchDefault.setOnCheckedChangeListener(null)
        switchDefault.isChecked = defaultConfigsEnabled
        switchDefault.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("default_configs_at_start", isChecked)
                .apply()
            refreshConfigList()
        }

        loadSelectedApps()
        checkForPendingDnsUpdates()
        refreshConfigList()   // refresh after returning from editor

    }

    override fun onDestroy() {
        super.onDestroy()
        // unregisterReceiver(vpnStateReceiver)
        // unregisterReceiver(pingReceiver)
        try {
            unregisterReceiver(vpnStateReceiver)
            unregisterReceiver(pingReceiver)
        } catch (e: Exception) {
            // Optional: Catch potential errors if the receiver was never registered
            Log.e("VAY_DEBUG", "Error unregistering receivers: ${e.message}")
        }
        getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    private fun checkForPendingDnsUpdates() {
        try {
            // Find any files left behind by the scanner sandbox
            val files = filesDir.listFiles { _, name -> name.startsWith("apply_dns_") && name.endsWith(".txt") }
            if (files.isNullOrEmpty()) return

            for (file in files) {
                val pendingConfigId = file.name.removePrefix("apply_dns_").removeSuffix(".txt")
                val newIp = file.readText().trim()

                if (newIp.isNotEmpty()) {
                    if (pendingConfigId.startsWith("default_")) {
                        // 1. Update the Main Process's memory for Default Configs
                        val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                        prefs.edit().putString("${pendingConfigId}_dns", newIp).apply()
                    } else {
                        // 2. Update the Main Process's memory for User Configs
                        val currentConfigs = com.net2share.vaydns.ConfigEditorActivity.loadAllConfigs(this).toMutableList()
                        val index = currentConfigs.indexOfFirst { it.id == pendingConfigId }

                        if (index != -1) {
                            currentConfigs[index] = currentConfigs[index].copy(dnsAddress = newIp)
                            com.net2share.vaydns.ConfigEditorActivity.saveAllConfigs(this, currentConfigs)
                        }
                    }
                }
                // 3. Nuke the file so we only apply it once
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}