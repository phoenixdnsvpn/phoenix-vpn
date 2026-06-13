package com.net2share.vaydns

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar
import mobile.Mobile

class GlobalSettingsActivity : AppCompatActivity() {

    private lateinit var cbDefaultAtStart: SwitchCompat
    private lateinit var rgTunnelMode: android.widget.RadioGroup
    private lateinit var cbDisplayVaydnsConfigs: SwitchCompat

    // Menu Toggles
    // private lateinit var cbUpdateApp: SwitchCompat
    private lateinit var cbUpdateConfigs: SwitchCompat
    private lateinit var cbUpdateResolvers: SwitchCompat
    private lateinit var cbUploadConfigs: SwitchCompat
    private lateinit var cbUploadResolvers: SwitchCompat
    private lateinit var cbImportResolvers: SwitchCompat
    private lateinit var cbExportResolvers: SwitchCompat

    // Global VLESS Fallback IP (Still relevant for scanner)
    private lateinit var etVlessWsIp: EditText
    private lateinit var rgEngineMode: android.widget.RadioGroup
    private lateinit var cbGlobalProtocolOverride: SwitchCompat
    private lateinit var spinnerGlobalProtocol: android.widget.Spinner
    private val supportedProtocols = listOf("vaydns", "hysteria2", "reality", "vless-ws")
    // Notification Management
    private lateinit var etUnlockedDelay: EditText
    private lateinit var etLockedDelay: EditText
    private lateinit var etNotifUpdate: EditText

    private lateinit var tvGlobalProtocolHeader: TextView
    private lateinit var divProtocolModeDivider: View
    private lateinit var tvStartupBehaviorHeader: TextView
    private lateinit var divStartupModeDivider: View
    private lateinit var tvProtocolModeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_global_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 1. Intercept toolbar back click
        toolbar.setNavigationOnClickListener { checkIpAndExit() }

        // 2. Intercept system hardware back press / swipe gestures
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                checkIpAndExit()
            }
        })

        // Bind UI Elements
        cbDefaultAtStart = findViewById(R.id.cb_default_configs_at_start)
        rgTunnelMode = findViewById(R.id.rg_tunnel_mode)
        cbDisplayVaydnsConfigs = findViewById(R.id.cb_display_vaydns_configs)

        // cbUpdateApp = findViewById(R.id.cb_show_check_app_update)
        cbUpdateConfigs = findViewById(R.id.cb_show_update_configs)
        cbUpdateResolvers = findViewById(R.id.cb_show_update_resolvers)
        cbUploadConfigs = findViewById(R.id.cb_show_upload_configs)
        cbUploadResolvers = findViewById(R.id.cb_show_upload_resolvers)
        cbImportResolvers = findViewById(R.id.cb_show_import_resolvers)
        cbExportResolvers = findViewById(R.id.cb_show_export_resolvers)

        etVlessWsIp = findViewById(R.id.et_vless_ws_ip)
        rgEngineMode = findViewById(R.id.rg_engine_mode)

        // Bind Override UI
        cbGlobalProtocolOverride = findViewById(R.id.cb_global_protocol_override)
        spinnerGlobalProtocol = findViewById(R.id.spinner_global_protocol)

        // Populate the Spinner

        val tpAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedProtocols)
        tpAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerGlobalProtocol.adapter = tpAdapter

        cbGlobalProtocolOverride.setOnCheckedChangeListener { _, isChecked ->
            spinnerGlobalProtocol.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        // NOTE: rgProtocolMode has been permanently removed!

        etUnlockedDelay = findViewById(R.id.et_unlocked_delay)
        etLockedDelay = findViewById(R.id.et_locked_delay)
        etNotifUpdate = findViewById(R.id.et_notif_update)

        tvGlobalProtocolHeader = findViewById(R.id.tv_global_protocol_header)
        divProtocolModeDivider = findViewById(R.id.div_protocol_mode_divider)
        tvStartupBehaviorHeader = findViewById(R.id.tv_startup_behavior_header)
        divStartupModeDivider = findViewById(R.id.div_startup_mode_divider)
        tvProtocolModeLabel = findViewById(R.id.tv_protocol_mode_label)

        loadSettings()
    }

    private fun loadSettings() {
        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        val menuPrefs = getSharedPreferences("MenuSettings", Context.MODE_PRIVATE)
        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)

        etUnlockedDelay.setText(appPrefs.getLong("unlocked_delay_ms", 2000L).toString())
        etLockedDelay.setText(appPrefs.getLong("locked_delay_ms", 5000L).toString())
        etNotifUpdate.setText(appPrefs.getLong("notif_update_ms", 4000L).toString())

        // Load Startup Preferences
        cbDefaultAtStart.isChecked = appPrefs.getBoolean("default_configs_at_start", true)
        val isVpnMode = appPrefs.getBoolean("default_to_vpn_mode", true)
        if (isVpnMode) {
            rgTunnelMode.check(R.id.rb_mode_vpn)
        } else {
            rgTunnelMode.check(R.id.rb_mode_proxy)
        }

        cbDisplayVaydnsConfigs.isChecked = appPrefs.getBoolean("display_vaydns_configs", false)

        // Load Menu Toggles
        // cbUpdateApp.isChecked = menuPrefs.getBoolean("show_check_app_update", true)
        cbUpdateConfigs.isChecked = menuPrefs.getBoolean("show_update_configs", false)
        cbUpdateResolvers.isChecked = menuPrefs.getBoolean("show_update_resolvers", false)
        cbUploadConfigs.isChecked = menuPrefs.getBoolean("show_upload_configs", false)
        cbUploadResolvers.isChecked = menuPrefs.getBoolean("show_upload_resolvers", false)
        cbImportResolvers.isChecked = menuPrefs.getBoolean("show_import_resolvers", false)
        cbExportResolvers.isChecked = menuPrefs.getBoolean("show_export_resolvers", false)

        // Load VLESS IP Fallback
        etVlessWsIp.setText(tunnelPrefs.getString("vless_ws_ip", ""))
        val engineType = tunnelPrefs.getString("tun_engine", "sing-box")
        if (engineType == "tun2socks") {
            rgEngineMode.check(R.id.rb_engine_tun2socks)
        } else {
            rgEngineMode.check(R.id.rb_engine_singbox)
        }

// Load Global Protocol Override
        val isOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
        val globalProtocol = tunnelPrefs.getString("global_protocol_selected", "vaydns") ?: "vaydns"
        cbGlobalProtocolOverride.isChecked = isOverride
        spinnerGlobalProtocol.visibility = if (isOverride) View.VISIBLE else View.GONE
        val pIndex = supportedProtocols.indexOf(globalProtocol)
        if (pIndex >= 0) spinnerGlobalProtocol.setSelection(pIndex)

// --- DYNAMIC UI VISIBILITY (Community vs Official Builds) ---
        val isOfficialBuild = Mobile.isOfficialBuild()
        val configCount = Mobile.getDefaultConfigCount()

        if (!isOfficialBuild) {
            // 1. Completely remove the Startup Behavior Section
            tvStartupBehaviorHeader.visibility = View.GONE
            divStartupModeDivider.visibility = View.GONE
            cbDefaultAtStart.visibility = View.GONE
            cbDisplayVaydnsConfigs.visibility = View.GONE

            // 2. Completely remove the Global Protocol Override Section
            tvGlobalProtocolHeader.visibility = View.GONE
            divProtocolModeDivider.visibility = View.GONE
            cbGlobalProtocolOverride.visibility = View.GONE
            spinnerGlobalProtocol.visibility = View.GONE

            // 3. Remove Update & Upload options from the Menu Visibility Section
            cbUpdateConfigs.visibility = View.GONE
            cbUpdateResolvers.visibility = View.GONE
            cbUploadConfigs.visibility = View.GONE
            cbUploadResolvers.visibility = View.GONE

            // 4. Remove Cloudflare IP Address Section
            tvProtocolModeLabel.visibility = View.GONE
            etVlessWsIp.visibility = View.GONE

        } else if (configCount == 0L) {
            // Fallback for Official Builds that haven't downloaded configs yet
            cbDefaultAtStart.visibility = View.GONE
            cbUpdateConfigs.visibility = View.GONE
            cbUpdateResolvers.visibility = View.GONE
        }
    }

    private fun checkIpAndExit() {
        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        val menuPrefs = getSharedPreferences("MenuSettings", Context.MODE_PRIVATE)

        // 1. Save VLESS IP
        var ip = etVlessWsIp.text.toString().trim()
        if (ip.isEmpty()) {
            ip = "0.0.0.0"
        }

        val selectedEngine = if (rgEngineMode.checkedRadioButtonId == R.id.rb_engine_tun2socks) {
            "tun2socks"
        } else {
            "sing-box"
        }

        // Extract Global Protocol Override states
        val overrideChecked = cbGlobalProtocolOverride.isChecked
        val selectedGlobalProto = spinnerGlobalProtocol.selectedItem?.toString() ?: "vaydns"

        tunnelPrefs.edit().apply {
            putString("vless_ws_ip", ip)
            putString("tun_engine", selectedEngine)
            putBoolean("global_protocol_override", overrideChecked)
            putString("global_protocol_selected", selectedGlobalProto)
        }.apply()

        tunnelPrefs.edit().apply {
            putString("vless_ws_ip", ip)
            putString("tun_engine", selectedEngine)
        }.apply()

        var unlockedDelay = etUnlockedDelay.text.toString().toLongOrNull() ?: 2000L
        var lockedDelay = etLockedDelay.text.toString().toLongOrNull() ?: 5000L
        var notifUpdate = etNotifUpdate.text.toString().toLongOrNull() ?: 4000L

        // Constraint 1: All values must be between 1000 and 10000
        unlockedDelay = unlockedDelay.coerceIn(1000L, 10000L)
        lockedDelay = lockedDelay.coerceIn(1000L, 10000L)
        notifUpdate = notifUpdate.coerceIn(1000L, 10000L)

        // Constraint 2: Locked State must be >= Unlocked State
        if (lockedDelay < unlockedDelay) lockedDelay = unlockedDelay

        // Constraint 3: Notification Update must be >= Unlocked State
        if (notifUpdate < unlockedDelay) notifUpdate = unlockedDelay

        // 2. Save App Preferences
        appPrefs.edit().apply {
            val isVpnSelected = rgTunnelMode.checkedRadioButtonId == R.id.rb_mode_vpn
            putBoolean("default_to_vpn_mode", isVpnSelected)
            putBoolean("default_configs_at_start", cbDefaultAtStart.isChecked)
            putBoolean("display_vaydns_configs", cbDisplayVaydnsConfigs.isChecked)

            putLong("unlocked_delay_ms", unlockedDelay)
            putLong("locked_delay_ms", lockedDelay)
            putLong("notif_update_ms", notifUpdate)
        }.apply()

        // 3. Save Menu Preferences
        val isOfficialBuild = Mobile.isOfficialBuild()
        val configCount = Mobile.getDefaultConfigCount()

        menuPrefs.edit().apply {
            if (configCount > 0L) {
                putBoolean("show_update_configs", cbUpdateConfigs.isChecked)
                putBoolean("show_update_resolvers", cbUpdateResolvers.isChecked)
                putBoolean("show_upload_configs", cbUploadConfigs.isChecked)
                putBoolean("show_upload_resolvers", cbUploadResolvers.isChecked)
                putBoolean("show_import_resolvers", cbImportResolvers.isChecked)
                putBoolean("show_export_resolvers", cbExportResolvers.isChecked)
            }
            // if (isOfficialBuild) {
            //    putBoolean("show_check_app_update", cbUpdateApp.isChecked)
            // }
        }.apply()

        // Close the activity
        finish()
    }
}