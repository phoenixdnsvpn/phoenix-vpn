package com.net2share.vaydns

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar

class GlobalSettingsActivity : AppCompatActivity() {

    private lateinit var cbDefaultAtStart: SwitchCompat

    // Menu Toggles
    private lateinit var cbUpdateApp: SwitchCompat
    private lateinit var cbUpdateConfigs: SwitchCompat
    private lateinit var cbUpdateResolvers: SwitchCompat
    private lateinit var cbUploadConfigs: SwitchCompat
    private lateinit var cbUploadResolvers: SwitchCompat
    private lateinit var cbDefaultVpnMode: SwitchCompat
    private lateinit var cbImportResolvers: SwitchCompat
    private lateinit var cbExportResolvers: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_global_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        cbDefaultAtStart = findViewById(R.id.cb_default_configs_at_start)

        cbDefaultAtStart.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("default_configs_at_start", isChecked)
                .apply()
        }

        cbDefaultVpnMode = findViewById(R.id.cb_default_vpn_mode)
        cbDefaultVpnMode.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("default_to_vpn_mode", isChecked)
                .apply()
        }

        cbUpdateApp = findViewById(R.id.cb_show_check_app_update)
        cbUpdateConfigs = findViewById(R.id.cb_show_update_configs)
        cbUpdateResolvers = findViewById(R.id.cb_show_update_resolvers)
        cbUploadConfigs = findViewById(R.id.cb_show_upload_configs)
        cbUploadResolvers = findViewById(R.id.cb_show_upload_resolvers)
        cbImportResolvers = findViewById(R.id.cb_show_import_resolvers)
        cbExportResolvers = findViewById(R.id.cb_show_export_resolvers)

        loadSettings()
        applyCommunityBuildFilters()
    }

    private fun loadSettings() {
        // Load Startup Preferences
        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        cbDefaultAtStart.isChecked = appPrefs.getBoolean("default_configs_at_start", false)

        cbDefaultVpnMode.isChecked = appPrefs.getBoolean("default_to_vpn_mode", true)
        // Load Menu Visibility Preferences
        val menuPrefs = getSharedPreferences("VayDNS_MenuPrefs", Context.MODE_PRIVATE)
        cbUpdateApp.isChecked = menuPrefs.getBoolean("show_check_app_update", false)
        cbUpdateConfigs.isChecked = menuPrefs.getBoolean("show_update_configs", false)
        cbUpdateResolvers.isChecked = menuPrefs.getBoolean("show_update_resolvers", false)
        cbUploadConfigs.isChecked = menuPrefs.getBoolean("show_upload_configs", false)
        cbUploadResolvers.isChecked = menuPrefs.getBoolean("show_upload_resolvers", false)
        cbImportResolvers.isChecked = menuPrefs.getBoolean("show_import_resolvers", false)
        cbExportResolvers.isChecked = menuPrefs.getBoolean("show_export_resolvers", false)
    }

    private fun applyCommunityBuildFilters() {
        val configCount = mobile.Mobile.getDefaultConfigCount()
        val buildStatus = mobile.Mobile.getBuildStatus()
        val isOfficialBuild = buildStatus == "Official Release" || configCount > 0

        // If configCount is 0, completely purge infrastructure controls from view
        if (configCount == 0L) {
            cbDefaultAtStart.visibility = View.GONE
            cbUpdateConfigs.visibility = View.GONE
            cbUpdateResolvers.visibility = View.GONE
            cbUploadConfigs.visibility = View.GONE
            cbUploadResolvers.visibility = View.GONE
            cbImportResolvers.visibility = View.GONE
            cbExportResolvers.visibility = View.GONE
        }

        // If it's not even an official build status, hide app updates toggle as well
        if (!isOfficialBuild) {
            cbUpdateApp.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()

        val configCount = mobile.Mobile.getDefaultConfigCount()
        val buildStatus = mobile.Mobile.getBuildStatus()
        val isOfficialBuild = buildStatus == "Official Release" || configCount > 0

        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
        val menuPrefs = getSharedPreferences("VayDNS_MenuPrefs", Context.MODE_PRIVATE).edit()

        appPrefs.putBoolean("default_to_vpn_mode", cbDefaultVpnMode.isChecked)
        // GUARD COMMITS: Only write back to shared preference files if options are actually active
        if (configCount > 0L) {
            appPrefs.putBoolean("default_configs_at_start", cbDefaultAtStart.isChecked)
            menuPrefs.putBoolean("show_update_configs", cbUpdateConfigs.isChecked)
            menuPrefs.putBoolean("show_update_resolvers", cbUpdateResolvers.isChecked)
            menuPrefs.putBoolean("show_upload_configs", cbUploadConfigs.isChecked)
            menuPrefs.putBoolean("show_upload_resolvers", cbUploadResolvers.isChecked)
            menuPrefs.putBoolean("show_import_resolvers", cbImportResolvers.isChecked)
            menuPrefs.putBoolean("show_export_resolvers", cbExportResolvers.isChecked)
        }

        if (isOfficialBuild) {
            menuPrefs.putBoolean("show_check_app_update", cbUpdateApp.isChecked)
        }

        appPrefs.apply()
        menuPrefs.apply()
    }
}