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
import android.view.Gravity
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.EditText
import android.util.TypedValue
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.net2share.vaydns.ConfigEditorActivity.Companion.loadAllConfigs
import com.net2share.vaydns.ConfigEditorActivity.Companion.saveAllConfigs

private lateinit var rgMode: RadioGroup   // kept only for editor (we don't use it here anymore)
private lateinit var tvStatus: TextView
//private lateinit var btnStart: Button
//private lateinit var btnStop: Button
private lateinit var btnToggle: Button
private var isVpnConnected = false // Track state locally for the toggle logic
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
                    isVpnConnected = true
                    updateUIState(true)
                }
                "DISCONNECTED", "STOPPED" -> {
                    isVpnConnected = false
                    updateUIState(false)
                }
            }
        }
    }
    /*private val vpnStateReceiver = object : BroadcastReceiver() {
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
    }*/

    private fun updateUIState(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                tvStatus.text = "Status: Connected"
                tvStatus.setTextColor(Color.parseColor("#006400")) // Green text for status

                btnToggle.text = "STOP TUNNEL"
                // Sleek Red for Stop state
                btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4F7F84"))
            } else {
                tvStatus.text = "Status: Disconnected"
                tvStatus.setTextColor(Color.parseColor("#2F4A6F")) // Original theme color

                btnToggle.text = "START TUNNEL"
                // Original Sleek Blue/Gray for Start state
                btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
            }
            btnToggle.isEnabled = true
        }
    }

    /*private fun updateButtonStates(isConnected: Boolean) {
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
    }*/

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
        btnToggle = findViewById(R.id.btn_toggle)

        //btnStart = findViewById(R.id.btn_start)
        //btnStop = findViewById(R.id.btn_stop)
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

        //updateButtonStates(false)

        btnToggle.setOnClickListener {
            if (!isVpnConnected) {
                // START LOGIC
                if (selectedConfigId == null) {
                    Toast.makeText(this, "Please select a config first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btnToggle.isEnabled = false // Prevent double clicks
                tvStatus.text = "Status: Connecting..."
//                tvStatus.setTextColor(Color.BLUE)
                prepareAndStartVpn()
            } else {
                // STOP LOGIC
                btnToggle.isEnabled = false
                stopVpnService()
                // The receiver will update the UI to "Disconnected"
            }
        }

        /*btnStart.setOnClickListener {
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
        }*/

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

// Inside MainActivity.kt

    private fun setupRecyclerView() {
        // 1. Gather all configs (Default + User Saved)
        val allConfigs = mutableListOf<Config>()
        allConfigs.addAll(DefaultConfigProvider.getDefaultConfigs(this))
        allConfigs.addAll(loadAllConfigs(this))

        // 2. Initialize the Adapter with the new export callback
        val adapter = ConfigAdapter(
            allConfigs,
            selectedConfigId,
            onConfigSelected = { config ->
                selectedConfigId = config.id
                refreshConfigList()

                // Provide feedback via snackbar instead of Toast for a better UI
                com.google.android.material.snackbar.Snackbar.make(
                    recyclerConfigs,
                    "Selected: ${config.name}",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            },
            onEditClicked = { config ->
                if (config.isDefault) {
                    Toast.makeText(this, "Default configs cannot be edited", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(this, ConfigEditorActivity::class.java)
                    intent.putExtra("EDIT_CONFIG_ID", config.id)
                    startActivity(intent)
                }
            },
            onDeleteClicked = { config ->
                if (config.isDefault) {
                    Toast.makeText(this, "Default configs cannot be deleted", Toast.LENGTH_SHORT).show()
                } else {
                    val currentList = loadAllConfigs(this).toMutableList()
                    currentList.removeAll { it.id == config.id }
                    saveAllConfigs(this, currentList)
                    refreshConfigList()
                }
            },
            onExportClicked = { config ->
                // Check if it's a default config before exporting
                if (config.isDefault) {
                    Toast.makeText(this, "This config cannot be shared.", Toast.LENGTH_SHORT).show()
                } else {
                    generateVayDnsProfile(config)
                }
            }
        )

        recyclerConfigs.layoutManager = LinearLayoutManager(this)
        recyclerConfigs.adapter = adapter
    }

    /**
     * Generates a vaydns:// URI and copies it to the system clipboard
     */
    private fun generateVayDnsProfile(config: Config) {
        try {
            val version = "18" // App version at Index 1
            val protocol = "vaydns" // Protocol type at Index 2

            // Construct the 40-field array to maintain pipe indexing
            val fields = Array(40) { "" }

            fields[0] = version
            fields[1] = protocol
            fields[2] = config.name
            fields[3] = config.domain
            fields[4] = config.dnsAddress // String format (e.g., 8.8.8.8:53 or https://...)
            fields[5] = "0"
            fields[6] = "200"
            fields[7] = "bbr"
            fields[8] = "1080"
            fields[9] = "127.0.0.1"
            fields[10] = "0"
            fields[11] = config.pubkey
            fields[12] = "" // Placeholder for username
            fields[13] = "" // Placeholder for password
            // Fields 14-23 remain empty
            fields[24] = config.mode // "udp", "dot", or "doh"
            // Fields 25-39 remain empty

            // Join into pipe-delimited string
            val rawProfile = fields.joinToString("|")

            // Base64 Encode (NO_WRAP is essential for URI compatibility)
            val encodedData = android.util.Base64.encodeToString(
                rawProfile.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )

            val finalUri = "vaydns://$encodedData"

            // Copy to Clipboard
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("VayDNS Config", finalUri)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(this, "Config copied to clipboard", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        configList.clear()

        // 1. Add User Configs
        configList.addAll(loadAllConfigs(this))

        // 2. Add Native Default Configs if switch is ON
        if (switchDefault.isChecked) {
            configList.addAll(DefaultConfigProvider.getDefaultConfigs(this))
        }

        val adapter = ConfigAdapter(
            configList,
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
            },
            onExportClicked = { config ->
                // Check if it's a default config from the native provider
                if (config.isDefault) {
                    Toast.makeText(this, "This config cannot be shared.", Toast.LENGTH_SHORT).show()
                } else {
                    exportToVayDnsProfile(config)
                }
            }
        )
        recyclerConfigs.adapter = adapter
    }

    /**
     * Constructs the vaydns:// profile and copies it to clipboard
     */

    private fun exportToVayDnsProfile(config: Config) {
        try {
            // Dynamically get the app version name from build.gradle.kts via PackageManager
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }

            val appVersionName = packageInfo.versionName ?: "1.0" // Fallback if name is null
            val protocol = "vaydns"

            // Initialize 40-field array for pipe-delimited structure
            val fields = Array(40) { "" }

            fields[0] = appVersionName // Now uses the real version name (e.g., "1.0")
            fields[1] = protocol
            fields[2] = config.name
            fields[3] = config.domain
            fields[4] = config.dnsAddress // String format (e.g. 8.8.8.8:53 or https://...)
            fields[5] = "0"
            fields[6] = "200"
            fields[7] = "bbr"
            fields[8] = "1080"
            fields[9] = "127.0.0.1"
            fields[10] = "0"
            fields[11] = config.pubkey
            fields[12] = "" // Placeholder for username
            fields[13] = "" // Placeholder for password
            // ... indices 14-23 remain empty ...
            fields[24] = config.mode // "udp", "dot", or "doh"

            val rawString = fields.joinToString("|")

            // Base64 encode without line wraps for URI safety
            val encoded = android.util.Base64.encodeToString(
                rawString.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )

            val vayDnsUri = "vaydns://$encoded"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "VayDNS Configuration: ${config.name}")
                putExtra(Intent.EXTRA_TEXT, vayDnsUri)
            }

            // 5. Start the system chooser
            startActivity(Intent.createChooser(shareIntent, "Share VayDNS Profile via:"))

        } catch (e: Exception) {
//            Toast.makeText(this, "Error exporting: ${e.message}", Toast.LENGTH_SHORT).show()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    private fun showImportDialog() {
        val brandColor = Color.parseColor("#2F4A6F") // Your specific color
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
        }

        val label = TextView(this).apply {
            text = "Paste the config below"
            textSize = 18f // Use float; Android treats this as SP by default
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }

        val etInput = EditText(this).apply {
            hint = "vaydns://..."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        layout.addView(label)
        layout.addView(etInput)

        val dialog = AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val input = etInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    processImport(input)
                }
            }
            .setNegativeButton("Cancel", null)
            .create() // Use .create() instead of .show()

        dialog.show()

        val btnImport = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val btnCancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        btnImport.setTextColor(brandColor)
        btnCancel.setTextColor(brandColor)

//        btnImport.isAllCaps = false
//        btnCancel.isAllCaps = false
    }
/*
    private fun showImportDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val label = TextView(this).apply {
            text = "Paste the config below"
            textSize = 16sp
                    setPadding(0, 0, 0, 20)
        }

        val etInput = EditText(this).apply {
            hint = "vaydns://..."
            background = null // Optional: styling
        }

        layout.addView(label)
        layout.addView(etInput)

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                val input = etInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    processImport(input)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
*/

    private fun processImport(data: String) {
        try {
            if (!data.startsWith("vaydns://")) {
                throw Exception("Invalid profile prefix")
            }

            // 1. Decode Base64
            val base64Data = data.removePrefix("vaydns://")
            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val rawString = String(decodedBytes, Charsets.UTF_8)
            val fields = rawString.split("|")

            // 2. Validate Required Fields (Domain and Pubkey)
            // Domain at Index 3, Pubkey at Index 11
            val domain = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }
            val pubkey = fields.getOrNull(11)?.takeIf { it.isNotEmpty() }

            if (domain == null || pubkey == null) {
                throw Exception("Profile missing Tunnel Domain or Server Public Key")
            }

            // 3. Name Logic & Conflict Resolution
            val rawName = fields.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "Imported"
            val currentConfigs = loadAllConfigs(this)
            val finalName = getUniqueName(rawName, currentConfigs)

            // 4. DNS & Mode Logic
            // Use profile DNS if exists, otherwise default to 8.8.8.8:53
            val dnsServer = fields.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: "8.8.8.8:53"
            val mode: String

            if (dnsServer.startsWith("http")) {
                mode = "doh"
            } else {
                // Logic: 853 -> dot, 53 -> udp, anything else -> udp
                mode = when {
                    dnsServer.contains(":853") -> "dot"
                    dnsServer.contains(":53") -> "udp"
                    else -> "udp"
                }
            }

            // 5. Create and Save Config
            // Note: We do NOT pass an 'id' here. The Config data class
            // will automatically generate a new unique UUID.
            val newConfig = Config(
                name = finalName,
                domain = domain,
                pubkey = pubkey,
                dnsAddress = dnsServer,
                mode = mode,
                isDefault = false
            )

            val updatedList = currentConfigs.toMutableList()
            updatedList.add(newConfig)
            saveAllConfigs(this, updatedList)

            // 6. Refresh UI
            refreshConfigList()
            Toast.makeText(this, "Config '$finalName' imported", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Import Error")
                .setMessage(e.message ?: "Failed to parse profile")
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
        val prefs = getSharedPreferences("VayDNS_Prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("verified_public_key", null)

        val verifyItem = menu.findItem(R.id.action_verify)

        if (savedKey != null && verifyItem != null) {
            // DON'T just trust the string is there.
            // Ask the Go library: "Does the CURRENT binary match this saved key?"
            val stillValid = mobile.Mobile.checkVerification(savedKey)

            if (stillValid) {
                verifyItem.title = "App Verified ✅"
            } else {
                // The binary has changed! (Maybe a malicious update)
                verifyItem.title = "App Not Verified ⚠️"
                prefs.edit().remove("verified_public_key").apply() // Wipe the fake status
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }
    private fun showVerificationDialog() {
        val prefs = getSharedPreferences("VayDNS_Prefs", Context.MODE_PRIVATE)
        val storedKey = prefs.getString("verified_public_key", "")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val textView = TextView(this).apply {
            text = if (storedKey.isNullOrEmpty()) "Paste the public key below" else "App is verified with the key below"
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 20)
        }

        val input = EditText(this).apply {
            setText(storedKey) // PRE-FILL THE STORED KEY
            hint = "8278278f..."
            maxLines = 3
            gravity = Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
        }

        val linkSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 0)
        }

        val linkLabel = TextView(this).apply {
            text = "Get the verification key from:"
            textSize = 12f
            setTextColor(Color.GRAY)
        }

        val twitterLink = TextView(this).apply {
            text = "x.com/Starling226"
            textSize = 13f
            setTextColor(Color.parseColor("#2F4A6F"))
            setPadding(0, 10, 0, 5)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://x.com/Starling226"))
                startActivity(intent)
            }
        }

        val githubLink = TextView(this).apply {
            text = "github.com/Starling226/vaydns-vpn"
            textSize = 13f
            setTextColor(Color.parseColor("#2F4A6F"))
            setPadding(0, 5, 0, 0)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Starling226/vaydns-vpn"))
                startActivity(intent)
            }
        }

        linkSection.addView(linkLabel)
        linkSection.addView(twitterLink)
        linkSection.addView(githubLink)
        // -------------------------

        container.addView(textView)
        container.addView(input)
        container.addView(linkSection)

        val dialog = AlertDialog.Builder(this)
            .setTitle("App Verification")
            .setView(container)
            .setPositiveButton("Verify") { _, _ ->
                val pastedKey = input.text.toString().trim()
                if (pastedKey.isEmpty()) return@setPositiveButton

                val isVerified = mobile.Mobile.checkVerification(pastedKey)

                if (isVerified) {
                    prefs.edit().putString("verified_public_key", pastedKey).apply()
                    invalidateOptionsMenu() // Refresh the menu to show the ✅
                }

                showVerificationResult(isVerified)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Style buttons: Color #2F4A6F and correct casing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setTextColor(Color.parseColor("#2F4A6F"))
            text = "Verify"
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            setTextColor(Color.parseColor("#2F4A6F"))
            text = "Cancel"
        }
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
        return when (item.itemId) {
            R.id.action_import -> {
                showImportDialog()
                true
            }

            R.id.action_add_config -> {
                startActivity(Intent(this, ConfigEditorActivity::class.java))
                true
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

                AlertDialog.Builder(this)
                    .setTitle("VayDNS")
                    .setMessage("""
            Version: $version
            
            DNS Tunneling app designed for heavily censored environments.
            
            Made with ❤️
            x.com/Starling226
            https://github.com/Starling226/vaydns-vpn
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
//        tvStatus.text = "Status: Disconnected"
//        tvStatus.setTextColor(Color.parseColor("#424242"))
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