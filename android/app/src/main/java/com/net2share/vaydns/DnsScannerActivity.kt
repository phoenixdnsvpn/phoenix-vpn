package com.net2share.vaydns

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.random.Random
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private var selectedIdleTimeout = "10s"
    private var selectedKeepAlive = "2s"
    private var selectedClientIdSize = 2L
    private var selectedDomain = ""
    private var selectedPubkey = ""
    private var selectedRecordType = "TXT"
    private var configId = ""
    private var resolversList: List<String> = emptyList()
    private var isDefaultConfig = false
    private lateinit var switchConfigResolvers: Switch

    private lateinit var switchCidrMode: Switch
    private lateinit var layoutCidrSelection: LinearLayout
    private lateinit var tvSelectedCidr: TextView
    private lateinit var btnSelectCidr: ImageButton

    private var loadedCidrs: List<String> = emptyList()
    private var selectedCidr: String? = null

    private val customResolverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadCustomResolversFromUri(uri)
        } else {
            Toast.makeText(this, "No file selected.", Toast.LENGTH_SHORT).show()
        }
    }

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
        configId = intent.getStringExtra("CONFIG_ID") ?: ""
        selectedRecordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
        selectedIdleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
        selectedKeepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
        selectedClientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)

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
        switchConfigResolvers = findViewById(R.id.switch_config_resolvers)

        switchCidrMode = findViewById(R.id.switch_cidr_mode)
        layoutCidrSelection = findViewById(R.id.layout_cidr_selection)
        tvSelectedCidr = findViewById(R.id.tv_selected_cidr)
        btnSelectCidr = findViewById(R.id.btn_select_cidr)

        if (!isDefaultConfig) {
            switchConfigResolvers.visibility = android.view.View.GONE
        }

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
        //btnDefaultResolvers.setOnClickListener { loadDefaultResolvers() }
        btnDefaultResolvers.setOnClickListener {
            if (switchCidrMode.isChecked) loadDefaultCidrs() else loadDefaultResolvers()
        }
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

        switchCidrMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                layoutCidrSelection.visibility = android.view.View.VISIBLE
                switchConfigResolvers.isChecked = false
                switchConfigResolvers.isEnabled = false
                loadDefaultCidrs()
            } else {
                layoutCidrSelection.visibility = android.view.View.GONE
                switchConfigResolvers.isEnabled = isDefaultConfig
                loadDefaultResolvers()
            }
        }

        btnSelectCidr.setOnClickListener { showCidrSelectionDialog() }

        switchConfigResolvers.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // 1. Disable all other resolver sources so they cannot be mixed
                btnDefaultResolvers.isEnabled = false
                btnCustomResolvers.isEnabled = false
                switchPublicDns.isChecked = false
                switchPublicDns.isEnabled = false
                switchCidrMode.isEnabled = false
                // 2. Disable the limit (we want to scan ALL official ones)
                etNumResolvers.isEnabled = false
                etNumResolvers.alpha = 0.5f

                // 3. Fetch ALL FAKE IPs from ALL configs in Go memory
                val count = Mobile.getDefaultConfigCount()
                val allResolvers = mutableSetOf<String>()

                for (i in 0 until count) {
                    val defaultResolversStr = Mobile.getDefaultConfigDisplayResolvers(i.toLong())
                    if (defaultResolversStr.isNotEmpty()) {
                        allResolvers.addAll(defaultResolversStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                    }
                }

                if (allResolvers.isNotEmpty()) {
                    resolversList = allResolvers.toList()
                    tvResolversCount.text = "Loaded all official resolvers: ${resolversList.size}"
                    Toast.makeText(this, "Isolated to official resolvers only.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No default resolvers found. Try updating from the main menu.", Toast.LENGTH_LONG).show()
                    switchConfigResolvers.isChecked = false
                }
            } else {
                // 1. Re-enable all other resolver sources
                btnDefaultResolvers.isEnabled = true
                btnCustomResolvers.isEnabled = true
                switchPublicDns.isEnabled = true
                switchCidrMode.isEnabled = true
                // 2. Re-enable the limit
                etNumResolvers.isEnabled = true
                etNumResolvers.alpha = 1.0f

                // 3. Fallback to the generic assets file
                loadDefaultResolvers()
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
        try {
            // Use "text/*" or "*/*" depending on how broad you want the filter.
            // "text/*" filters for .txt files.
            customResolverPickerLauncher.launch("text/*")
        } catch (e: Exception) {
            Toast.makeText(this, "No file manager found to pick files.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCustomResolversFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val content = inputStream.bufferedReader().use { it.readText() }
                val parsedLines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

                if (parsedLines.isNotEmpty()) {
                    if (switchCidrMode.isChecked) {
                        loadedCidrs = parsedLines
                        tvResolversCount.text = "Loaded custom CIDR blocks: ${loadedCidrs.size}"
                        selectedCidr = null
                        tvSelectedCidr.text = "Tap right icon to select CIDR ->"
                        Toast.makeText(this, "Loaded ${loadedCidrs.size} custom CIDR blocks", Toast.LENGTH_SHORT).show()
                    } else {
                        resolversList = parsedLines
                        tvResolversCount.text = "Loaded custom resolvers: ${resolversList.size}"
                        Toast.makeText(this, "Loaded ${resolversList.size} custom resolvers", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "File is empty or invalid format.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Could not open file.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadDefaultCidrs() {
        try {
            val inputStream = assets.open("ir-cidrs.txt")
            val content = inputStream.bufferedReader().use { it.readText() }
            loadedCidrs = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
            tvResolversCount.text = "Loaded CIDR blocks: ${loadedCidrs.size}"
            selectedCidr = null
            tvSelectedCidr.text = "Tap right icon to select CIDR ->"
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load CIDR blocks (ir-cidrs.txt)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCidrSelectionDialog() {
        if (loadedCidrs.isEmpty()) {
            Toast.makeText(this, "No CIDR blocks loaded", Toast.LENGTH_SHORT).show()
            return
        }

        val displayList = loadedCidrs.map { cidr ->
            val size = getCidrInfo(cidr)?.second ?: 0L
            "$cidr  ($size IPs)"
        }

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayList) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent) as TextView
                view.minHeight = 0
                view.minimumHeight = 0
                val padHorizontal = (16 * resources.displayMetrics.density).toInt()
                val padVertical = (10 * resources.displayMetrics.density).toInt()
                view.setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
                view.textSize = 15f
                return view
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Select a CIDR Block")
            .setAdapter(adapter) { _, which ->
                selectedCidr = loadedCidrs[which]
                val size = getCidrInfo(selectedCidr!!)?.second ?: 0L
                tvSelectedCidr.text = "$selectedCidr ($size IPs)"

                tvResolversCount.text = "Loaded resolvers: $size"

                // Dynamically adjust requested IPs if CIDR block is smaller
                val currentRequested = etNumResolvers.text.toString().toIntOrNull() ?: 500
                if (currentRequested > size) {
                    etNumResolvers.setText(size.toString())
                    Toast.makeText(this, "Adjusted requested IPs to block max size ($size)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCidrInfo(cidr: String): Pair<Long, Long>? {
        try {
            val parts = cidr.split("/")
            val ipStr = parts[0]
            val prefix = if (parts.size == 2) parts[1].toInt() else 32
            if (prefix !in 0..32) return null

            val ipParts = ipStr.split(".")
            if (ipParts.size != 4) return null

            var ipLong = 0L
            for (part in ipParts) {
                ipLong = (ipLong shl 8) + part.toLong()
            }

            val size = 1L shl (32 - prefix)
            val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            val baseIp = ipLong and mask

            return Pair(baseIp, size)
        } catch (e: Exception) {
            return null
        }
    }

    private fun longToIp(ipLong: Long): String {
        return "${(ipLong shr 24) and 255}.${(ipLong shr 16) and 255}.${(ipLong shr 8) and 255}.${ipLong and 255}"
    }

    private fun getIpsFromCidr(cidr: String, limit: Int, random: Boolean): List<String> {
        val info = getCidrInfo(cidr) ?: return emptyList()
        val baseIp = info.first
        val size = info.second
        val result = mutableListOf<String>()

        val actualLimit = minOf(limit.toLong(), size).toInt()

        if (random) {
            // Optimization for when limit equals size (return all shuffled)
            if (actualLimit.toLong() == size) {
                for (i in 0 until actualLimit) {
                    result.add(longToIp(baseIp + i.toLong()))
                }
                return result.shuffled()
            }

            val indices = mutableSetOf<Long>()
            while (indices.size < actualLimit) {
                indices.add(Random.nextLong(0, size))
            }
            for (index in indices) {
                result.add(longToIp(baseIp + index))
            }
        } else {
            for (i in 0 until actualLimit) {
                result.add(longToIp(baseIp + i.toLong()))
            }
        }
        return result
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

        if (switchCidrMode.isChecked) {
            if (selectedCidr == null) {
                Toast.makeText(this, "Please select a CIDR block first", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            if (resolversList.isEmpty()) {
                Toast.makeText(this, "Please load resolvers first", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val parsedNum = etNumResolvers.text.toString().toIntOrNull() ?: 2000
        val isUsingConfigResolvers = switchConfigResolvers.isChecked

        val numResolvers = if (isUsingConfigResolvers && !switchCidrMode.isChecked) resolversList.size else parsedNum

        if (!switchCidrMode.isChecked) {
            if (numResolvers < 1 || numResolvers > resolversList.size) {
                Toast.makeText(this, "Number of resolvers must be between 1 and ${resolversList.size}", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            val cidrSize = getCidrInfo(selectedCidr!!)?.second ?: 0L
            if (numResolvers < 1 || numResolvers > cidrSize) {
                Toast.makeText(this, "Number must be between 1 and $cidrSize for this block", Toast.LENGTH_LONG).show()
                return
            }
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


        /**val baseResolvers = if (useRandom) {
            resolversList.shuffled().take(numResolvers)
        } else {
            resolversList.take(numResolvers)
        }.map { if (it.contains(":")) it else "$it:53" } // Also handles file-based IPs
        */
        // Handle IP generation based on toggle state
        val baseResolvers = if (switchCidrMode.isChecked) {
            getIpsFromCidr(selectedCidr!!, numResolvers, useRandom)
        } else {
            if (useRandom) {
                resolversList.shuffled().take(numResolvers)
            } else {
                resolversList.take(numResolvers)
            }
        }.map { if (it.contains(":")) it else "$it:53" } // Appends port 53 automatically

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
            putExtra("CONFIG_ID", configId)
            putExtra("DOMAIN", selectedDomain)
            putExtra("PUBKEY", selectedPubkey)
            putExtra("RESOLVERS", resolversCommaSeparated)
            putExtra("PROXY_TYPE", proxyType)
            putExtra("RECORD_TYPE", selectedRecordType)
            putExtra("IDLE_TIMEOUT", selectedIdleTimeout)
            putExtra("KEEP_ALIVE", selectedKeepAlive)
            putExtra("CLIENT_ID_SIZE", selectedClientIdSize)
            putExtra("CONSERVATIVE", isConservative)
            putExtra("TOTAL_RESOLVERS", finalResolvers.size)
            putExtra("WORKERS", workers)
            putExtra("TUNNEL_WAIT", tunnelWait)
            putExtra("PROBE_TIMEOUT", probeTimeout)
            putExtra("RETRIES", retries)
            putExtra("IS_DEFAULT_RESOLVERS", isUsingConfigResolvers)
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