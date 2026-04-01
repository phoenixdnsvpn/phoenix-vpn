package com.net2share.vaydns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.net2share.vaydns.R
import com.net2share.vaydns.VayVpnService

private lateinit var rgMode: RadioGroup
private var lastUdp = "8.8.8.8:53"
private var lastDot = "8.8.8.8:853"
private var lastDoh = "https://dns.google/dns-query"
private var currentModeId: Int = R.id.rb_udp

class MainActivity : AppCompatActivity() {

    private lateinit var etUdp: EditText
    private val VPN_REQUEST_CODE = 100

    // UI Elements
    private lateinit var etDomain: EditText
    private lateinit var etPubKey: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView // Move this here so it's accessible

    // 1. Create the Receiver
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status")
            if (status == "CONNECTED") {
                // Use runOnUiThread to make sure the screen actually repaints
                runOnUiThread {
                    tvStatus.text = "Status: Connected"
//                    tvStatus.setTextColor(Color.GREEN)
                    tvStatus.setTextColor(Color.parseColor("#006400")) // Dark Green (Forest Green)
//                    tvStatus.setTextColor(Color.rgb(0, 100, 0))
                    Toast.makeText(this@MainActivity, "VPN Live!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Permission granted! Start the service
            startVpnService()
        } else {
            Toast.makeText(this, "Permission denied. VPN cannot start.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // supportActionBar?.hide() // Uncomment this if the blue bar is still there
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        // 1. Initialize UI (Added rgMode here)
        rgMode = findViewById(R.id.rg_mode)
        etUdp = findViewById(R.id.et_udp)
        etDomain = findViewById(R.id.et_domain)
        etPubKey = findViewById(R.id.et_pubkey)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        // 2. Load saved settings (Added 'udp' and 'mode' loading)
        val prefs = getSharedPreferences("VayDNSSettings", MODE_PRIVATE)
        etDomain.setText(prefs.getString("domain", "t.example.com"))
        etPubKey.setText(prefs.getString("pubkey", ""))
        etUdp.setText(prefs.getString("udp", "8.8.8.8:53"))

        // Load the last selected Radio Button
        val lastModeId = prefs.getInt("last_mode_id", R.id.rb_udp)
        rgMode.check(lastModeId)
        currentModeId = lastModeId // Update our tracker variable

        // 3. The "Memory" Listener: Swap text when Radio Button changes
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            // Save current text to the variable of the OLD mode before switching
            when (currentModeId) {
                R.id.rb_udp -> lastUdp = etUdp.text.toString()
                R.id.rb_tls -> lastDot = etUdp.text.toString()
                R.id.rb_https -> lastDoh = etUdp.text.toString()
            }

            currentModeId = checkedId

            // Load the text for the NEW mode
            when (checkedId) {
                R.id.rb_udp -> etUdp.setText(lastUdp)
                R.id.rb_tls -> etUdp.setText(lastDot)
                R.id.rb_https -> etUdp.setText(lastDoh)
            }
        }

        btnStart.setOnClickListener {
            if (etDomain.text.isBlank() || etPubKey.text.isBlank()) {
                Toast.makeText(this, "Domain and Public Key are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.text = "Status: Connecting..."
            tvStatus.setTextColor(Color.BLUE)
            saveSettings()
            prepareAndStartVpn()
        }

        btnStop.setOnClickListener {
            stopVpnService()
            tvStatus.text = "Status: Disconnected"
            tvStatus.setTextColor(Color.GRAY)
            Toast.makeText(this, "Stopping VPN...", Toast.LENGTH_SHORT).show()
//            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//            notificationManager.cancel(1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Adding the RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag is mandatory now
            registerReceiver(vpnStateReceiver, IntentFilter("VPN_STATE_CHANGED"), RECEIVER_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, IntentFilter("VPN_STATE_CHANGED"))
        }

        val btnSelectApps = findViewById<Button>(R.id.btn_select_apps)

        btnSelectApps.setOnClickListener {
            // This opens the activity where the user picks which apps to tunnel
            val intent = Intent(this, AppSelectorActivity::class.java)
            startActivity(intent)
        }
    }
    private fun saveSettings() {
        val prefs = getSharedPreferences("VayDNSSettings", MODE_PRIVATE)
        prefs.edit().apply {
            putString("domain", etDomain.text.toString())
            putString("pubkey", etPubKey.text.toString())
            putString("udp", etUdp.text.toString())
            putInt("last_mode_id", rgMode.checkedRadioButtonId) // Save which radio was picked
            apply()
        }
    }

    private fun prepareAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // This triggers the popup using the new API
            vpnPermissionLauncher.launch(intent)
        } else {
            // Permission already exists, go straight to starting
            startVpnService()
        }
    }

    private fun startVpnService() {

        val mode = when (rgMode.checkedRadioButtonId) {
            R.id.rb_tls -> "dot"   // Changed from 'tls' to 'dot'
            R.id.rb_https -> "doh" // Changed from 'https' to 'doh'
            else -> "udp"
        }

        val intent = Intent(this, VayVpnService::class.java).apply {
            putExtra("DOMAIN", etDomain.text.toString())
            putExtra("PUBKEY", etPubKey.text.toString())
            putExtra("UDP", etUdp.text.toString())
            putExtra("UDP", "46.250.246.10:53")
        }

        // On Android 8.0+, foreground services must use startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val intent = Intent(this, VayVpnService::class.java).apply {
                // This is how the Service gets your UI data
                putExtra("DOMAIN", etDomain.text.toString())
                putExtra("PUBKEY", etPubKey.text.toString())
                putExtra("UDP", etUdp.text.toString())
            }
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val stopIntent = Intent(this, VayVpnService::class.java).apply {
            action = "ACTION_STOP_VPN" // We add a specific "Kill" action
        }
        startService(stopIntent) // We "start" it with a STOP command
        tvStatus.text = "Status: Disconnected"
        tvStatus.setTextColor(Color.parseColor("#424242"))
        Toast.makeText(this, "VPN Stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 3. Clean up to prevent memory leaks
        unregisterReceiver(vpnStateReceiver)
    }
}