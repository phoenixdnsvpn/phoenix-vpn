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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.net2share.vaydns.ConfigEditorActivity.Companion.loadAllConfigs
import com.net2share.vaydns.ConfigEditorActivity.Companion.saveAllConfigs
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
class MainActivity : AppCompatActivity() {

    private var configAdapter: ConfigAdapter? = null
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Catch the stats broadcast and update text instantly
            if (intent?.action == "VPN_STATS_UPDATE") {
                tvSpeed.text = intent.getStringExtra("speed") ?: ""
                tvTotal.text = intent.getStringExtra("total") ?: ""
                return
            }

            // Original status handling
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

    private fun updateUIState(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                tvStatus.text = "Status: Connected"
                tvStatus.setTextColor(Color.parseColor("#006400")) // Green text for status

                btnToggle.text = "STOP TUNNEL"
                // Sleek Red for Stop state
                btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4F7F84"))
                layoutNetworkStats.visibility = android.view.View.VISIBLE
            } else {
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

        // Toolbar + menu
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "VayDNS"

        tvStatus = findViewById(R.id.tv_status)
        btnToggle = findViewById(R.id.btn_toggle)
        layoutNetworkStats = findViewById(R.id.layout_network_stats)
        tvSpeed = findViewById(R.id.tv_speed)
        tvTotal = findViewById(R.id.tv_total)
        //btnStart = findViewById(R.id.btn_start)
        //btnStop = findViewById(R.id.btn_stop)
        recyclerConfigs = findViewById(R.id.recycler_configs)
        switchDefault = findViewById(R.id.switch_default_configs)

        val configCount = mobile.Mobile.getDefaultConfigCount()
        val buildStatus = mobile.Mobile.getBuildStatus()
        //val isOfficialBuild = mobile.Mobile.getBuildStatus() == "Official Release"

        if (configCount > 0) {
            // Injection worked!
            switchDefault.visibility = android.view.View.VISIBLE
            switchDefault.isEnabled = true
            switchDefault.text = "Use default configs"
        } else if (buildStatus == "Official Release") {
            // The build is recognized as official, but data is empty
            switchDefault.visibility = android.view.View.VISIBLE
            switchDefault.isEnabled = false
            switchDefault.text = "Use default configs (None found)"
        } else {
            // Fallback for community editions without cached files
            val configFile = java.io.File(filesDir, "cached_default_configs.bin")
            if (configFile.exists()) {
                switchDefault.visibility = android.view.View.VISIBLE
            } else {
                switchDefault.visibility = android.view.View.GONE
            }
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
            if (isVpnConnected) {
                stopVpnService()
            } else {
                startVpnService()
            }
        }

        // App selector button stays the same
        findViewById<Button>(R.id.btn_select_apps).setOnClickListener {
            startActivity(Intent(this, AppSelectorActivity::class.java))
        }

        // Register receiver
        val filter = IntentFilter()
        filter.addAction("VPN_STATE_CHANGED")
        filter.addAction("VPN_STATS_UPDATE")
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

    private fun refreshConfigList() {
        configList.clear()
        configList.addAll(loadAllConfigs(this))
        if (switchDefault.isChecked) {
            configList.addAll(DefaultConfigProvider.getDefaultConfigs(this))
        }

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
//        menu.add(Menu.NONE, 1001, Menu.NONE, "Update Configs")
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
        // We use GetDefaultConfigCount as a secondary check.
        // If Go has configs in memory, it MUST be an official build.
        val buildStatus = mobile.Mobile.getBuildStatus()
        val configCount = mobile.Mobile.getDefaultConfigCount()

        val isOfficialBuild = buildStatus == "Official Release" || configCount > 0

        if (!isOfficialBuild) {
            // Hide all private infrastructure options for public builds
            menu.findItem(R.id.action_verify)?.isVisible = false
            menu.findItem(R.id.action_check_app_update)?.isVisible = false
            menu.findItem(R.id.action_update_configs)?.isVisible = false
            menu.findItem(R.id.action_update_resolvers)?.isVisible = false
            menu.findItem(R.id.action_upload_resolvers)?.isVisible = false
            menu.findItem(R.id.action_upload_configs)?.isVisible = false
        } else {
            // Ensure these items are visible if it is an official build
            menu.findItem(R.id.action_verify)?.isVisible = true
            menu.findItem(R.id.action_check_app_update)?.isVisible = true
            menu.findItem(R.id.action_update_configs)?.isVisible = true
            menu.findItem(R.id.action_update_resolvers)?.isVisible = true
            menu.findItem(R.id.action_upload_resolvers)?.isVisible = true
            menu.findItem(R.id.action_upload_configs)?.isVisible = true

            // Verification Logic
            val prefs = getSharedPreferences("VayDNS_Prefs", Context.MODE_PRIVATE)
            val savedKey = prefs.getString("verified_public_key", null)
            val verifyItem = menu.findItem(R.id.action_verify)

            if (savedKey != null && verifyItem != null) {
                val stillValid = mobile.Mobile.checkVerification(savedKey)
                if (stillValid) {
                    verifyItem.title = "App Verified ✅"
                } else {
                    verifyItem.title = "App Not Verified ⚠️"
                    prefs.edit().remove("verified_public_key").apply()
                }
            }
        }
        return super.onPrepareOptionsMenu(menu)
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

                    val intent = Intent(this, DnsScannerActivity::class.java).apply {
                        putExtra("CONFIG_ID", config.id)
                        putExtra("DOMAIN", config.domain)
                        putExtra("PUBKEY", config.pubkey)
                        putExtra("RECORD_TYPE", config.recordType)
                        putExtra("IS_DEFAULT", config.isDefault)
                        putExtra("IDLE_TIMEOUT", config.idleTimeout)
                        putExtra("CLIENT_ID_SIZE", config.clientIdSize)
                    }
                    startActivity(intent)
                }
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
            R.id.action_verify -> {
                showVerificationDialog()
                true
            }

            R.id.action_check_app_update -> {
                checkForAppUpdate()
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

            R.id.action_how_to_use -> {
                showHowToUseDialog()
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
            https://github.com/Starling226/vaydns-vpn
        """.trimIndent())
                    .setPositiveButton("Close", null)
                    .setIcon(R.mipmap.ic_launcher_round)
                    .show() // .show() handles create and show automatically

                // DELETE the manual setTextColor line.
                // The theme overlay we created earlier handles this now.
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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
        /**val instructions = """
            1. Select Apps to Tunnel: Tap on SELECT APPS TO TUNNEL and choose a few specific apps (3–4 recommended) that you want to pass through the tunnel. Only selected apps will be routed; all other traffic will remain on your local network.

            2. Add Your Configuration:
               • To use your own server: Tap the Menu (three dots) and select "Add Config" or "Import Config".
               • To use built-in servers: Toggle on "Use default configs" and select a server from the list.

            3. Find a Usable Resolver: To establish a tunnel, you must find a functional DNS Resolver for your network.
               • For new users the quickest way: From menu tap on Update Resolvers. Select a config and tap on pencil icon, then tap on "Use default resolvers" and select one from the list. Tap on START TUNNEL.
               • To scan a fresh resolvers:
               • Open the Menu and select "DNS Scanner".
               • Use the default parameters and tap "START SCAN".
               • Look for a resolver with a latency lower than 6000 ms.
               • Tap the Set (Checkmark) icon to apply the fastest resolver to your config, or the Save icon to store a list of fast resolvers.
               • Note: If no usable resolvers are found, go back and start a new scan to get a fresh random list.

            4. Start the Tunnel: Return to the main menu and tap START TUNNEL. It may take up to 20 seconds to establish a stable connection.

            5. Troubleshooting: Different configurations use different DNS record types (TXT, NULL, etc.). A resolver that works for one config may not work for another. If you cannot connect, try switching to a different configuration or record type.

            6. Performance Expectations: DNS tunneling is inherently slower than traditional VPNs due to protocol overhead. Expect speeds ranging from 10 KB/sec to 200 KB/sec, depending on your network conditions.

            --------------------------------------------------
            
            ۱. انتخاب برنامه‌ها برای تونل (Split Tunneling): روی گزینه SELECT APPS TO TUNNEL تپ کنید و چند برنامه خاص (پیشنهاد می‌شود ۳ تا ۴ برنامه) را برای عبور از تونل انتخاب کنید. فقط ترافیک برنامه‌های انتخاب شده از تونل عبور می‌کند و بقیه برنامه‌ها از اینترنت عادی شما استفاده خواهند کرد.

            ۲. افزودن پیکربندی (Configuration):
               • برای استفاده از سرور شخصی خود: منو (سه نقطه) را باز کرده و "Add Config" و یا "Import Config" را انتخاب کنید.
               • برای استفاده از سرورهای پیش‌فرض: گزینه "Use default configs" را فعال کرده و یکی از سرورهای لیست را انتخاب کنید.

            ۳. یافتن یک DNS (Resolver) مناسب: برای برقراری اتصال، باید یک Resolver فعال که با شبکه شما سازگار باشد پیدا کنید.
            • سریع‌ترین روش برای کاربران جدید: از منو روی Update Resolvers تپ کنید. یک پیکربندی (Config) را انتخاب کرده و روی آیکون مداد تپ کنید، سپس گزینه "Use default resolvers" را بزنید و یکی را از لیست انتخاب کنید. در نهایت روی START TUNNEL تپ کنید.
• برای اسکن Resolverهای جدید:
               • از منو گزینه "DNS Scanner" را انتخاب کنید.
               • از پارامترهای پیش‌فرض استفاده کنید و روی "START SCAN" تپ کنید.
               • پس از اتمام اسکن، به دنبال موردی بگردید که تاخیر (Latency) آن کمتر از 6000 میلی‌ثانیه باشد.
               • روی آیکون تأیید (Checkmark) تپ کنید تا سریع‌ترین Resolver مستقیماً روی تنظیمات شما اعمال شود، یا از آیکون ذخیره (Save) برای نگهداری لیست استفاده کنید.
               • نکته: اگر هیچ Resolver مناسبی پیدا نشد، به عقب برگردید و اسکن جدیدی شروع کنید تا لیست تصادفی جدیدی دریافت کنید.

            ۴. شروع اتصال (Start Tunnel): به منوی اصلی برگردید و روی "START TUNNEL" تپ کنید. برقراری اتصال پایدار ممکن است تا ۲۰ ثانیه زمان ببرد.

            ۵. عیب‌یابی: پیکربندی‌های مختلف از انواع رکوردهای DNS (مانند TXT یا NULL) استفاده می‌کنند. ممکن است یک Resolver که برای یک سرور کار می‌کند، برای سرور دیگر مناسب نباشد. در صورت عدم اتصال، سرور یا نوع رکورد را تغییر دهید.

            ۶. انتظارات از عملکرد: لطفاً توجه داشته باشید که تونل‌سازی DNS ذاتا کندتر از VPNهای معمولی است. بسته به شرایط شبکه، انتظار سرعتی بین ۱۰ تا ۲۰۰ کیلوبایت بر ثانیه را داشته باشید.
        """.trimIndent()*/

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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }

        runOnUiThread { Toast.makeText(this, "Checking for app updates...", Toast.LENGTH_SHORT).show() }

        Thread {
            // NATIVE VAULT: Go handles the network request
            val result = mobile.Mobile.checkForAppUpdate(currentVersion)

            runOnUiThread {
                if (result.startsWith("UPDATE_AVAILABLE|")) {
                    val newVersion = result.split("|")[1]
                    showUpdateDialog(newVersion)
                } else if (result == "NO_UPDATE") {
                    Toast.makeText(this, "You are using the latest version ($currentVersion).", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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

        if (config == null) {
            Toast.makeText(this, "Please select a config first", Toast.LENGTH_SHORT).show()
            return // Abort without changing the UI text
        }

        if (config.domain.isEmpty()) {
            Toast.makeText(this, "Invalid configuration. Please select another.", Toast.LENGTH_SHORT).show()
            return // Abort without changing the UI text
        }

        tvStatus.text = "Status: Connecting..."
        tvStatus.setTextColor(Color.parseColor("#FFA500")) // Optional: Orange for connecting
//        btnToggle.text = "STOP Tunnel"
        btnToggle.text = "CONNECTING..."
        btnToggle.isEnabled = false
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GRAY)

        val intent = Intent(this, VayVpnService::class.java).apply {

            action = "ACTION_START_VPN"

            val nativeIndex = if (config.isDefault) {
                config.id.removePrefix("default_").toLongOrNull() ?: 0L
            } else {
                -1L // Not a default config
            }
            // Use DefaultConfigProvider for masked default configs
            val finalConfig = if (config.isDefault) {
                DefaultConfigProvider.getActualConfig(this@MainActivity, config)
            } else {
                config
            }
            // Now 'config.domain' and 'config.pubkey' contain the real data
            putExtra("IS_DEFAULT_CONFIG", config.isDefault)
            putExtra("CONFIG_ID", config.id)
            putExtra("CONFIG_INDEX", nativeIndex)
            putExtra("DOMAIN", finalConfig.domain)
            putExtra("PUBKEY", finalConfig.pubkey)

            //val realIp = mobile.Mobile.getRealResolver(finalConfig.dnsAddress)
            //putExtra("UDP", realIp)
            putExtra("UDP", finalConfig.dnsAddress)
            putExtra("MODE", finalConfig.mode)
            putExtra("RECORD_TYPE", finalConfig.recordType)
            putExtra("IDLE_TIMEOUT", finalConfig.idleTimeout)
            putExtra("KEEP_ALIVE", finalConfig.keepAlive)
            putExtra("CLIENT_ID_SIZE", finalConfig.clientIdSize)
            putExtra("DNSTT_COMPATIBLE", finalConfig.dnsttCompatible)
            putExtra("USE_AUTH", finalConfig.useAuth)
            putExtra("PROTOCOL", finalConfig.protocol)

            // Apply the "none" fallback logic here as well for safety
            //val finalUser = if (config.useAuth) config.user.ifEmpty { "none" } else "none"
            putExtra("SS_METHOD", finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" })

            val finalUser = if (finalConfig.protocol == "shadowsocks") {
                // If SS, inject the encryption method into the username slot
                finalConfig.ssMethod.ifEmpty { "chacha20-ietf-poly1305" }
            } else if (config.useAuth) {
                finalConfig.user.ifEmpty { "none" }
            } else {
                "none"
            }
            val finalPass = if (finalConfig.useAuth) finalConfig.pass.ifEmpty { "none" } else "none"

            putExtra("USER", finalUser)
            putExtra("PASS", finalPass)
        }

        val vpnIntent = VpnService.prepare(this)
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

    private fun stopVpnService() {
        val stopIntent = Intent(this, VayVpnService::class.java).apply {
            action = "ACTION_STOP_VPN"
        }
        startService(stopIntent)

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

// Unlock and reset the UI after exactly 1 second
        btnToggle.postDelayed({
            btnToggle.isEnabled = true
            btnToggle.text = "START TUNNEL"
            btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2F4A6F"))
            tvStatus.text = "Status: Disconnected"
        }, 1000)
    }

    private fun stopVpnService_x() {
        val stopIntent = Intent(this, VayVpnService::class.java).apply {
            action = "ACTION_STOP_VPN"
        }
        startService(stopIntent)

        tvStatus.text = "Status: Disconnected"
        tvStatus.setTextColor(Color.parseColor("#424242"))
    }

    override fun onResume() {
        super.onResume()
        checkForPendingDnsUpdates()
        refreshConfigList()   // refresh after returning from editor
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(vpnStateReceiver)
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