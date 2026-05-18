package com.net2share.vaydns

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar

class TunnelSettingsActivity : AppCompatActivity() {

    private lateinit var cbEnablePrescan: SwitchCompat
    private lateinit var rbProxySocks5h: RadioButton
    private lateinit var rbProxySocks: RadioButton
    private lateinit var rbProxyHttp: RadioButton
    private lateinit var rbTrueE2e: RadioButton
    private lateinit var rbLightE2e: RadioButton
    private lateinit var etWorkers: EditText
    private lateinit var etTunnelWait: EditText
    private lateinit var etProbeTimeout: EditText
    private lateinit var etUdpTimeout: EditText
    private lateinit var etRetries: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunnel_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_tunnel_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        cbEnablePrescan = findViewById(R.id.cb_enable_prescan)
        rbProxySocks5h = findViewById(R.id.rb_proxy_socks5h)
        rbProxySocks = findViewById(R.id.rb_proxy_socks)
        rbProxyHttp = findViewById(R.id.rb_proxy_http)
        rbTrueE2e = findViewById(R.id.rb_true_e2e)
        rbLightE2e = findViewById(R.id.rb_light_e2e)
        etWorkers = findViewById(R.id.et_workers)
        etTunnelWait = findViewById(R.id.et_tunnel_wait)
        etProbeTimeout = findViewById(R.id.et_probe_timeout)
        etUdpTimeout = findViewById(R.id.et_udp_timeout)
        etRetries = findViewById(R.id.et_retries)

        loadSettings()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)

        // Default to true (ON)
        cbEnablePrescan.isChecked = prefs.getBoolean("enable_prescan", true)

        when (prefs.getString("proxy_type", "socks5h")) {
            "http" -> rbProxyHttp.isChecked = true
            "socks" -> rbProxySocks.isChecked = true
            else -> rbProxySocks5h.isChecked = true
        }

        if (prefs.getBoolean("light_e2e", true)) {
            rbLightE2e.isChecked = true
        } else {
            rbTrueE2e.isChecked = true
        }

        etWorkers.setText(prefs.getInt("workers", 20).toString())
        etTunnelWait.setText(prefs.getInt("tunnel_wait", 3000).toString())
        etProbeTimeout.setText(prefs.getInt("probe_timeout", 15000).toString())
        etUdpTimeout.setText(prefs.getInt("udp_timeout", 1000).toString())
        etRetries.setText(prefs.getInt("retries", 0).toString())
    }

    override fun onPause() {
        super.onPause()
        val prefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE).edit()

        prefs.putBoolean("enable_prescan", cbEnablePrescan.isChecked)

        val proxyType = when {
            rbProxyHttp.isChecked -> "http"
            rbProxySocks.isChecked -> "socks"
            else -> "socks5h"
        }
        prefs.putString("proxy_type", proxyType)

        prefs.putBoolean("light_e2e", rbLightE2e.isChecked)
        prefs.putInt("workers", etWorkers.text.toString().toIntOrNull() ?: 20)
        prefs.putInt("tunnel_wait", etTunnelWait.text.toString().toIntOrNull() ?: 3000)
        prefs.putInt("probe_timeout", etProbeTimeout.text.toString().toIntOrNull() ?: 15000)
        prefs.putInt("udp_timeout", etUdpTimeout.text.toString().toIntOrNull() ?: 1000)
        prefs.putInt("retries", etRetries.text.toString().toIntOrNull() ?: 0)

        prefs.apply()
    }
}