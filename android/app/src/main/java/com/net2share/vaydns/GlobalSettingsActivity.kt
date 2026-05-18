package com.net2share.vaydns

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar

class GlobalSettingsActivity : AppCompatActivity() {

    private lateinit var cbDefaultAtStart: SwitchCompat
    // Startup Toggles
    private lateinit var cbPingAtStart: SwitchCompat

    // Menu Toggles
    private lateinit var cbUpdateApp: SwitchCompat
    private lateinit var cbUpdateConfigs: SwitchCompat
    private lateinit var cbUpdateResolvers: SwitchCompat
    private lateinit var cbUploadConfigs: SwitchCompat
    private lateinit var cbUploadResolvers: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_global_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        cbDefaultAtStart = findViewById(R.id.cb_default_configs_at_start)
        cbPingAtStart = findViewById(R.id.cb_ping_at_start)

        cbDefaultAtStart.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("default_configs_at_start", isChecked)
                .apply()
        }

        cbPingAtStart.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("ping_at_start", isChecked)
                .apply()
        }

        cbUpdateApp = findViewById(R.id.cb_show_check_app_update)
        cbUpdateConfigs = findViewById(R.id.cb_show_update_configs)
        cbUpdateResolvers = findViewById(R.id.cb_show_update_resolvers)
        cbUploadConfigs = findViewById(R.id.cb_show_upload_configs)
        cbUploadResolvers = findViewById(R.id.cb_show_upload_resolvers)

        loadSettings()
    }

    private fun loadSettings() {
        // Load Startup Preferences
        val appPrefs = getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE)
        cbDefaultAtStart.isChecked = appPrefs.getBoolean("default_configs_at_start", false)
        cbPingAtStart.isChecked = appPrefs.getBoolean("ping_at_start", false)

        // Load Menu Visibility Preferences
        val menuPrefs = getSharedPreferences("VayDNS_MenuPrefs", Context.MODE_PRIVATE)
        cbUpdateApp.isChecked = menuPrefs.getBoolean("show_check_app_update", false)
        cbUpdateConfigs.isChecked = menuPrefs.getBoolean("show_update_configs", false)
        cbUpdateResolvers.isChecked = menuPrefs.getBoolean("show_update_resolvers", false)
        cbUploadConfigs.isChecked = menuPrefs.getBoolean("show_upload_configs", false)
        cbUploadResolvers.isChecked = menuPrefs.getBoolean("show_upload_resolvers", false)
    }

    override fun onPause() {
        super.onPause()

        // Auto-Save Startup Preferences
        getSharedPreferences("VayDNSPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("ping_at_start", cbPingAtStart.isChecked)
            .apply()

        // Auto-Save Menu Visibility Preferences
        getSharedPreferences("VayDNS_MenuPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("default_configs_at_start", cbDefaultAtStart.isChecked)
            .putBoolean("show_check_app_update", cbUpdateApp.isChecked)
            .putBoolean("show_update_configs", cbUpdateConfigs.isChecked)
            .putBoolean("show_update_resolvers", cbUpdateResolvers.isChecked)
            .putBoolean("show_upload_configs", cbUploadConfigs.isChecked)
            .putBoolean("show_upload_resolvers", cbUploadResolvers.isChecked)
            .apply()
    }
}