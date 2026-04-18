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
private var selectedConfigId: String? = null   // which config is active for START
private val configList = mutableListOf<Config>() // Class-level list to hold User + Default configs
class MainActivity : AppCompatActivity() {

    private var configAdapter: ConfigAdapter? = null
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

            R.id.action_update_configs -> {
                syncConfigsWithServer()
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

    private fun fetchSecureContent(urlString: String): String {
        val url = java.net.URL(urlString)
        val connection = url.openConnection() as java.net.HttpURLConnection
        val mySecretKey = mobile.Mobile.getAppSecretKey()

        if (mySecretKey.isNotEmpty()) {
            // Use your secret key here
//            android.util.Log.d("VayDNS_Secret", "Secret key loaded securely!")
        }
        // Set up the connection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000 // 5 seconds
        connection.readTimeout = 5000

        // --- ADD YOUR SECRET TOKEN HERE ---
        // This must match exactly what you put in Nginx
        connection.setRequestProperty("X-VayDNS-Token", mySecretKey)

        if (connection.responseCode == 200) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("Server rejected connection: HTTP ${connection.responseCode}")
        }
    }

    private fun syncConfigsWithServer() {
        val currentVersion = mobile.Mobile.getDefaultConfigVersion()

        Thread {
            try {
                var baseUrl = mobile.Mobile.getUpdateServerURL()

                if (baseUrl.isEmpty()) {
                    runOnUiThread { Toast.makeText(this, "Update server is not configured.", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                baseUrl = baseUrl.trimEnd('/')

                // 1. Fetch version using the secure connection
                val versionUrl = "$baseUrl/config/version.txt"
                val latestVersionText = fetchSecureContent(versionUrl).trim()
                val latestVersion = latestVersionText.toInt()

                if (latestVersion > currentVersion) {
                    runOnUiThread { Toast.makeText(this, "New update found (v$latestVersion). Downloading...", Toast.LENGTH_SHORT).show() }

                    // 2. Fetch the Base64 config file using the secure connection
                    val configUrl = "$baseUrl/config/default_configs.bin"
                    val newConfigB64 = fetchSecureContent(configUrl).trim()

                    // 3. Persist the update
                    getSharedPreferences("ConfigUpdates", Context.MODE_PRIVATE)
                        .edit()
                        .putString("cached_obscured_json", newConfigB64)
                        .apply()

                    // 4. Update the Go engine
                    mobile.Mobile.setDefaultConfigs(newConfigB64)

                    runOnUiThread {
                        refreshConfigList()
                        Toast.makeText(this, "Configurations updated to v$latestVersion!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread { Toast.makeText(this, "Configurations are already up to date.", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                android.util.Log.e("VayDNS_Update", "Crash during update process:", e)
                runOnUiThread {
                    Toast.makeText(this, "Update failed: Check your connection.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
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
        val config = configList.find { it.id == selectedConfigId }

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
        btnToggle.text = "STOP Tunnel"

        val intent = Intent(this, VayVpnService::class.java).apply {

            action = "ACTION_START_VPN"

            // Use DefaultConfigProvider for masked default configs
            val finalConfig = if (config.isDefault) {
                DefaultConfigProvider.getActualConfig(this@MainActivity, config)
            } else {
                config
            }
            // Now 'config.domain' and 'config.pubkey' contain the real data
            putExtra("DOMAIN", finalConfig.domain)
            putExtra("PUBKEY", finalConfig.pubkey)
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
        btnToggle.text = "START TUNNEL"
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2F4A6F"))

        // 3. Reset Status Text
        tvStatus.text = "Status: Disconnected"
        tvStatus.setTextColor(android.graphics.Color.parseColor("#424242"))
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
        refreshConfigList()   // refresh after returning from editor
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(vpnStateReceiver)
    }
}