package com.net2share.vaydns

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import mobile.Mobile
import mobile.ScanResultCallback

class DnsScannerActivity : AppCompatActivity() {

    private lateinit var switchPublicDns: Switch
    private lateinit var etDomain: EditText
    private lateinit var rgProxy: RadioGroup
    private lateinit var switchConservative: Switch
    private lateinit var btnDefaultResolvers: Button
    private lateinit var btnCustomResolvers: Button
    private lateinit var tvResolversCount: TextView
    private lateinit var etNumResolvers: EditText
    private lateinit var switchRandom: Switch
    private lateinit var etWorkers: EditText
    private lateinit var etTunnelWait: EditText
    private lateinit var etProbeTimeout: EditText
    private lateinit var etRetries: EditText
    private lateinit var btnStartScan: Button

    private var selectedDomain = ""
    private var selectedPubkey = ""
    private var selectedRecordType = "TXT"
    private var configId = ""
    private var resolversList: List<String> = emptyList()
    private var isDefaultConfig = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_scanner)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_scanner)
        toolbar.setNavigationOnClickListener {
            finish()
        }
        // Get data from selected config
        isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT", false)
        selectedDomain = intent.getStringExtra("DOMAIN") ?: ""
        selectedPubkey = intent.getStringExtra("PUBKEY") ?: ""

        selectedRecordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"

        toolbar.title = "DNS Resolver Scanner"
//        toolbar.subtitle = if (selectedDomain.contains("-")) "Protected Config" else selectedDomain

        if (selectedDomain.isEmpty() || selectedPubkey.isEmpty()) {
            Toast.makeText(this, "No config selected. Please go back and select a config.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        supportActionBar?.title = "DNS Resolver Scanner"
//        supportActionBar?.subtitle = selectedDomain

        initViews()
        setupListeners()

        // Load default resolvers on start
        loadDefaultResolvers()
    }

    private fun initViews() {
        switchPublicDns = findViewById(R.id.switch_public_dns)
        etDomain = findViewById(R.id.et_domain)
        rgProxy = findViewById(R.id.rg_proxy)
        switchConservative = findViewById(R.id.switch_conservative)
        btnDefaultResolvers = findViewById(R.id.btn_default_resolvers)
        btnCustomResolvers = findViewById(R.id.btn_custom_resolvers)
        tvResolversCount = findViewById(R.id.tv_resolvers_count)
        etNumResolvers = findViewById(R.id.et_num_resolvers)
        switchRandom = findViewById(R.id.switch_random)
        etWorkers = findViewById(R.id.et_workers)
        etTunnelWait = findViewById(R.id.et_tunnel_wait)
        etProbeTimeout = findViewById(R.id.et_probe_timeout)
        etRetries = findViewById(R.id.et_retries)
        btnStartScan = findViewById(R.id.btn_start_scan)

        // Set domain from config
        etDomain = findViewById(R.id.et_domain)
        if (isDefaultConfig) {
            // HIDE the real domain from the User Interface
            etDomain.setText("----------")
            etDomain.isEnabled = false
            // Note: selectedDomain still holds the REAL "t.emrooz.store" in memory
        } else {
            // Show real domain for custom user configs
            etDomain.setText(selectedDomain)
        }
    }

    private fun setupListeners() {
        btnDefaultResolvers.setOnClickListener { loadDefaultResolvers() }
        btnCustomResolvers.setOnClickListener { chooseCustomResolvers() }

        // IMPLEMENTED LOGIC: Guardrail to prevent scanning through a VPN
        btnStartScan.setOnClickListener {
            if (isVpnActive()) {
                AlertDialog.Builder(this)
                    .setTitle("VPN Active Detected")
                    .setMessage("Scanning while a VPN is active can produce inaccurate results. Please disconnect your VPN to scan your local network environment directly.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                startScan()
            }
        }

        // Update workers and wait when conservative mode changes
        switchConservative.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Safer, slower settings for restrictive firewalls
                etWorkers.setText("10")
                etTunnelWait.setText("2000")
                etProbeTimeout.setText("8")
                etRetries.setText("1")
            } else {
                // High-performance settings
                etWorkers.setText("10")
                etTunnelWait.setText("1000")
                etProbeTimeout.setText("15")
                etRetries.setText("0")
            }
        }
    }

    private fun loadDefaultResolvers() {
        try {
            val inputStream = assets.open("resolvers.txt")
            val content = inputStream.bufferedReader().use { it.readText() }
            resolversList = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

            tvResolversCount.text = "Loaded resolvers: ${resolversList.size}"
//            Toast.makeText(this, "Loaded ${resolversList.size} default resolvers", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load default resolvers", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseCustomResolvers() {
        Toast.makeText(this, "Custom file picker not implemented yet.\nUsing default for now.", Toast.LENGTH_LONG).show()
        loadDefaultResolvers()
    }

    private fun startScan() {

        if (isVpnActive()) {
            AlertDialog.Builder(this)
                .setTitle("VPN Active")
                .setMessage("Please disconnect the VPN before running a scan to get accurate latency results.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (resolversList.isEmpty()) {
            Toast.makeText(this, "Please load resolvers first", Toast.LENGTH_SHORT).show()
            return
        }

        val numResolvers = etNumResolvers.text.toString().toIntOrNull() ?: 2000
        if (numResolvers < 1 || numResolvers > resolversList.size) {
            Toast.makeText(this, "Number of resolvers must be between 1 and ${resolversList.size}", Toast.LENGTH_LONG).show()
            return
        }

        // Hardcoded Public DNS List
        val publicDnsList = listOf(
            "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
            "185.228.168.9", "185.228.169.9", "84.200.69.80", "84.200.70.40",
            "193.110.81.0", "185.253.5.0", "64.6.64.6", "64.6.65.6", "209.244.0.3",
            "209.244.0.4", "77.88.8.8", "77.88.8.1", "8.26.56.26", "8.20.247.20"
        ).map { if (it.contains(":")) it else "$it:53" } // Ensures port is appended

        val proxyType = when (rgProxy.checkedRadioButtonId) {
            R.id.rb_socks5 -> "socks5"
            R.id.rb_http -> "http"
            else -> "socks5h"
        }

        val isConservative = switchConservative.isChecked
        val useRandom = switchRandom.isChecked

        val workers = etWorkers.text.toString().toLongOrNull() ?: 10L
        val tunnelWait = etTunnelWait.text.toString().toLongOrNull() ?: 2000L
        val probeTimeout = etProbeTimeout.text.toString().toLongOrNull() ?: 15L
        val retries = etRetries.text.toString().toLongOrNull() ?: 0L

    /*    val baseResolvers = if (useRandom) {
            resolversList.shuffled().take(numResolvers)
        } else {
            resolversList.take(numResolvers)
        }*/

        val baseResolvers = if (useRandom) {
            resolversList.shuffled().take(numResolvers)
        } else {
            resolversList.take(numResolvers)
        }.map { if (it.contains(":")) it else "$it:53" } // Also handles file-based IPs

        // Combine lists if switch is ON
        val finalResolvers = if (switchPublicDns.isChecked) {
            // .distinct() prevents duplicates if public IPs are also in your file
            (publicDnsList + baseResolvers).distinct()
        } else {
            baseResolvers
        }

        if (finalResolvers.isEmpty()) {
            Toast.makeText(this, "Please select at least one resolver", Toast.LENGTH_SHORT).show()
            return
        }

        val resolversCommaSeparated = finalResolvers.joinToString(",")

        // Open the new result window and pass all needed data
        val intent = Intent(this, DnsScannerResultActivity::class.java).apply {
            putExtra("DOMAIN", selectedDomain)
            putExtra("PUBKEY", selectedPubkey)
            putExtra("RESOLVERS", resolversCommaSeparated)
            putExtra("PROXY_TYPE", proxyType)
            putExtra("RECORD_TYPE", selectedRecordType)
            putExtra("CONSERVATIVE", isConservative)
            putExtra("TOTAL_RESOLVERS", finalResolvers.size)
            putExtra("WORKERS", workers)
            putExtra("TUNNEL_WAIT", tunnelWait)
            putExtra("PROBE_TIMEOUT", probeTimeout)
            putExtra("RETRIES", retries)
        }
        startActivity(intent)

    }

    private fun isVpnActive(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // activeNetwork is available from API 23+
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

}