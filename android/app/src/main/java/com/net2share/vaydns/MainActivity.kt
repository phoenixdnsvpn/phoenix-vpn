package com.net2share.vaydns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.net2share.vaydns.ConfigEditorActivity.Companion.loadAllConfigs
import com.net2share.vaydns.ConfigEditorActivity.Companion.saveAllConfigs

private lateinit var rgMode: RadioGroup   // kept only for editor (we don't use it here anymore)
private lateinit var tvStatus: TextView
private lateinit var btnStart: Button
private lateinit var btnStop: Button
private lateinit var recyclerConfigs: RecyclerView
private lateinit var switchDefault: androidx.appcompat.widget.SwitchCompat
private var selectedConfigId: String? = null   // which config is active for START
private val configList = mutableListOf<Config>() // Class-level list to hold User + Default configs
class MainActivity : AppCompatActivity() {

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status")
            when (status) {
                "CONNECTED" -> {
                    runOnUiThread {
                        tvStatus.text = "Status: Connected"
                        tvStatus.setTextColor(Color.parseColor("#006400"))
                        updateButtonStates(true) // Disable Start, Enable Stop
//                        Toast.makeText(this@MainActivity, "VPN Live!", Toast.LENGTH_SHORT).show()
                    }
                }
                "DISCONNECTED" , "STOPPED" -> {
                    runOnUiThread {
                        tvStatus.text = "Status: Disconnected"
                        tvStatus.setTextColor(Color.parseColor("#2F4A6F"))
                        updateButtonStates(false) // Enable Start, Disable Stop
                    }
                }
            }
        }
    }

    private fun updateButtonStates(isConnected: Boolean) {
        val startActiveColor = Color.parseColor("#2F4A6F")   // Deep Blue
        val stopActiveColor = Color.parseColor("#2F4A6F")    // Dark Gray/Black
        val disabledBgColor = Color.parseColor("#EBF5FB")    // Light Blue
        val disabledTextColor = Color.parseColor("#2F4A6F")  // Gray Text
        val white = Color.WHITE

        if (isConnected) {
            // --- CASE: VPN IS CONNECTED ---

            // START: Disable, Light Blue Background, Gray Text
            btnStart.isEnabled = false
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(disabledBgColor)
            btnStart.setTextColor(disabledTextColor)

            // STOP: Enable, Dark Gray Background, White Text
            btnStop.isEnabled = true
            btnStop.backgroundTintList = android.content.res.ColorStateList.valueOf(stopActiveColor)
            btnStop.setTextColor(white)

        } else {
            // --- CASE: VPN IS DISCONNECTED ---

            // START: Enable, Deep Blue Background, White Text
            btnStart.isEnabled = true
            btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(startActiveColor)
            btnStart.setTextColor(white)

            // STOP: Disable, Light Blue Background, Gray Text
            btnStop.isEnabled = false
            btnStop.backgroundTintList = android.content.res.ColorStateList.valueOf(disabledBgColor)
            btnStop.setTextColor(disabledTextColor)
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

        // Toolbar + menu
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "VayDNS"

        tvStatus = findViewById(R.id.tv_status)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        recyclerConfigs = findViewById(R.id.recycler_configs)
        switchDefault = findViewById(R.id.switch_default_configs)

        val defaultConfigs = DefaultConfigProvider.getDefaultConfigs(this)

        if (defaultConfigs.isEmpty()) {
            switchDefault.isChecked = false
            switchDefault.isEnabled = false
            // Optional: Update text to show why it's disabled
            switchDefault.text = "Use default configs (None found)"
        }
        // Load saved configs and selected ID

//        Log.i("NativeConfigs", "Loaded $count default configs from JSON")

        loadSelectedConfig()

        switchDefault.setOnCheckedChangeListener { _, _ ->
            refreshConfigList()
        }

        // RecyclerView for configs
        recyclerConfigs.layoutManager = LinearLayoutManager(this)
        refreshConfigList()

        updateButtonStates(false)

        btnStart.setOnClickListener {
            if (selectedConfigId == null) {
                Toast.makeText(this, "Please select a config first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1. Update UI immediately
            tvStatus.text = "Status: Connecting..."
            tvStatus.setTextColor(Color.BLUE)
            updateButtonStates(true) // This flips the colors instantly

            // 2. Start the service
            prepareAndStartVpn()
        }

        btnStop.setOnClickListener {
            // 1. Tell the service to stop
            stopVpnService()

            // 2. Immediately flip the UI without waiting for the broadcast
            updateButtonStates(false)
            tvStatus.text = "Status: Disconnected"
            tvStatus.setTextColor(Color.parseColor("#424242"))
        }

        // App selector button stays the same
        findViewById<Button>(R.id.btn_select_apps).setOnClickListener {
            startActivity(Intent(this, AppSelectorActivity::class.java))
        }

        findViewById<Button>(R.id.btn_dns_scanner).setOnClickListener {
            val rawConfig = configList.find { it.id == selectedConfigId } // UPDATED: find in class list
            if (rawConfig == null) {
                Toast.makeText(this, "Please select a config first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // NEW: Get real data from JNI for the scanner
            val config = DefaultConfigProvider.getActualConfig(this, rawConfig)

            val intent = Intent(this, DnsScannerActivity::class.java).apply {
                val index = config.id.removePrefix("default_").toIntOrNull() ?: 0
                putExtra("DOMAIN", config.domain)
                putExtra("PUBKEY", config.pubkey)
                // Pass the flag so the Scanner Activity knows to MASK the UI
                putExtra("IS_DEFAULT", config.isDefault)
            }
            startActivity(intent)
        }

        // Register receiver
        val filter = IntentFilter("VPN_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
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

    /*private fun refreshConfigList() {
        val configs = loadAllConfigs(this)
        val adapter = ConfigAdapter(
            configs,
            selectedConfigId, // This tells the adapter which ID to highlight
            onConfigSelected = { config ->
                selectedConfigId = config.id // Update the ID
                refreshConfigList()          // <--- ADD THIS: Force the UI to redraw
//                Toast.makeText(this, "Selected: ${config.name}", Toast.LENGTH_SHORT).show()
            },
            onEditClicked = { config ->
                val intent = Intent(this, ConfigEditorActivity::class.java)
                intent.putExtra("CONFIG_ID", config.id)
                startActivity(intent)
            },
            onDeleteClicked = { config ->
                showDeleteConfirmation(config)
            }
        )
        recyclerConfigs.adapter = adapter
    }*/

    private fun refreshConfigList() {
        configList.clear() // NEW: clear class list first

        // 1. Add User Configs
        configList.addAll(loadAllConfigs(this))

        // 2. NEW: Add Native Default Configs if switch is ON
        if (switchDefault.isChecked) {
            configList.addAll(DefaultConfigProvider.getDefaultConfigs(this))
        }

        val adapter = ConfigAdapter(
            configList, // UPDATED: pass the class list
            selectedConfigId,
            onConfigSelected = { config ->
                saveSelectedConfigId(config.id)
                refreshConfigList()
            },
            onEditClicked = { config ->
                val intent = Intent(this, ConfigEditorActivity::class.java).apply {
                    putExtra("CONFIG_ID", config.id)
                }
                startActivity(intent)
            },
            onDeleteClicked = { config ->
                showDeleteConfirmation(config)
            }
        )
        recyclerConfigs.adapter = adapter
    }
    /*private fun refreshConfigList() {
        val configs = loadAllConfigs(this)
        val adapter = ConfigAdapter(configs, selectedConfigId,
            onConfigSelected = { config ->
                saveSelectedConfigId(config.id)
                refreshConfigList()   // refresh highlight
            },
            onEditClicked = { config ->
                val intent = Intent(this, ConfigEditorActivity::class.java).apply {
                    putExtra("CONFIG_ID", config.id)
                }
                startActivity(intent)
            },
            onDeleteClicked = { config ->
                showDeleteConfirmation(config)
            }
        )
        recyclerConfigs.adapter = adapter
    }*/

    private fun showDeleteConfirmation(config: Config) {

        if (config.isDefault) {
            Toast.makeText(this, "Default configs cannot be deleted", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
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
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_config -> {
                startActivity(Intent(this, ConfigEditorActivity::class.java))
                true
            }
            R.id.action_about -> {
                val version = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (e: Exception) {
                    "1.0"
                }

                AlertDialog.Builder(this)
                    .setTitle("VayDNS")
                    .setMessage("""
            Version: $version
            
            DNS Tunneling app designed for heavily censored environments.
            
            Made with ❤️
        """.trimIndent())
                    .setPositiveButton("Close", null)
                    .setIcon(R.mipmap.ic_launcher_round)
                    .show()

                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService()
    }

    /*private fun startVpnService() {
        val configs = loadAllConfigs(this)
        val config = configs.find { it.id == selectedConfigId } ?: return

        val intent = Intent(this, VayVpnService::class.java).apply {
            putExtra("DOMAIN", config.domain)
            putExtra("PUBKEY", config.pubkey)
            putExtra("UDP", config.dnsAddress)
            putExtra("MODE", config.mode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }*/

    private fun startVpnService() {
        // 1. Look for the config in the combined list (User + Default)
        // We use the 'configList' that is updated by refreshConfigList()
//        val rawConfig = configList.find { it.id == selectedConfigId }
        val rawConfig = configList.find { it.id == selectedConfigId } ?: return

        if (rawConfig == null) {
            Toast.makeText(this, "Please select a configuration", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. UNMASK the config: If it's a default config, this fetches
        // the real Domain and Pubkey from your .so library via JNI.
        val config = DefaultConfigProvider.getActualConfig(this, rawConfig)

        val intent = Intent(this, VayVpnService::class.java).apply {
            // Now 'config.domain' and 'config.pubkey' contain the real data
            putExtra("DOMAIN", config.domain)
            putExtra("PUBKEY", config.pubkey)
            putExtra("UDP", config.dnsAddress)
            putExtra("MODE", config.mode)
        }

        // 3. Start the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val stopIntent = Intent(this, VayVpnService::class.java).apply {
            action = "ACTION_STOP_VPN"
        }
        startService(stopIntent)
        tvStatus.text = "Status: Disconnected"
        tvStatus.setTextColor(Color.parseColor("#424242"))
        /*
                com.google.android.material.snackbar.Snackbar.make(
                    findViewById(R.id.bottom_controls),
                    "VPN Stopped",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).setAnchorView(R.id.btn_stop).show()
        */
        tvStatus.text = "Status: Disconnected"
        tvStatus.setTextColor(Color.parseColor("#424242"))
    }

    override fun onResume() {
        super.onResume()
        refreshConfigList()   // refresh after returning from editor
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(vpnStateReceiver)
    }
}