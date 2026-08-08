package net.vaydns.phoenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray

class CdnScannerActivity : AppCompatActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvPassed: TextView
//    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnSet: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: CdnAdapter
    private lateinit var spinnerCdn: android.widget.Spinner
    private var isScanning = false
    private var isDefaultConfig = false
    private var configIndex = -1L
    private var configId = ""
    private var customDomain = ""
    private lateinit var etScanCount: com.google.android.material.textfield.TextInputEditText
    private lateinit var etDialTimeout: com.google.android.material.textfield.TextInputEditText
    private lateinit var etReadDeadline: com.google.android.material.textfield.TextInputEditText
    private val cfResults = mutableListOf<ResolverResult>()
    private lateinit var spinnerProtocol: android.widget.Spinner

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "CF_SCANNER_RESULT") {

                val isFinished = intent.getBooleanExtra("IS_FINISHED", true)
                // 1. Reset Global UI State (Applies to both success and failure)
                if (isFinished) {
                    isScanning = false
                    btnStartStop.text = "START SCAN"
                    btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                    etScanCount.isEnabled = true // Unlocks the input field universally
                    etDialTimeout.isEnabled = true
                    etReadDeadline.isEnabled = true
                    spinnerCdn.isEnabled = true
                    spinnerProtocol.isEnabled = true
                }

                val rawResult = intent.getStringExtra("RAW_RESULT") ?: ""

                // 2. Grab the dynamic target count for the UI (Default to 512 if empty)
                val targetCount = etScanCount.text.toString().ifEmpty { "512" }

                if (rawResult.isNotEmpty()) {
                    val parts = rawResult.split("|")
                    val jsonString = parts.getOrNull(0) ?: "[]"
                    val scannedCount = parts.getOrNull(1) ?: "0"
                    val foundCount = parts.getOrNull(2) ?: "0"

                    // Apply dynamic target count
                    if (isFinished) {
//                        tvStatus.text = "Done"
                    } else {
//                        tvStatus.text = "Scanning..."
                    }
                    tvProgress.text = "$scannedCount / $targetCount" // Back to just numbers!
                    tvPassed.text = "$foundCount found"

                    cfResults.clear()
                    try {
                        val jsonArray = JSONArray(jsonString)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val ip = obj.getString("ip")
                            val latency = obj.getInt("latency")
                            cfResults.add(ResolverResult(ip, latency, "ok"))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    adapter.notifyDataSetChanged()
                    btnSet.isEnabled = cfResults.isNotEmpty()
                    btnShare.isEnabled = cfResults.isNotEmpty()

                } else {
                    // Apply dynamic target count here as well
//                    tvStatus.text = "Stopped"
                    tvProgress.text = "0 / $targetCount" // Back to just numbers!
                    tvPassed.text = "0 found"
                    cfResults.clear()
                    adapter.notifyDataSetChanged()
                    btnSet.isEnabled = false
                    btnShare.isEnabled = false
                    Toast.makeText(this@CdnScannerActivity, "Scan stopped or timed out.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cdn_scanner)

        isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT", false)
        configIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
        customDomain = intent.getStringExtra("DOMAIN") ?: ""
        configId = intent.getStringExtra("CONFIG_ID") ?: ""

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_cf)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        etScanCount = findViewById(R.id.et_cf_scan_count)
        etDialTimeout = findViewById(R.id.et_cf_dial_timeout)
        etReadDeadline = findViewById(R.id.et_cf_read_deadline)
        tvProgress = findViewById(R.id.tv_cf_progress)
        tvPassed = findViewById(R.id.tv_cf_passed)
//        tvStatus = findViewById(R.id.tv_cf_status)
        btnStartStop = findViewById(R.id.btn_cf_start_stop)
        btnSet = findViewById(R.id.btn_cf_set)
        btnShare = findViewById(R.id.btn_cf_share)
        spinnerCdn = findViewById(R.id.spinner_scanner_cdn)
        spinnerProtocol = findViewById(R.id.spinner_scanner_protocol)
        recycler = findViewById(R.id.recycler_cf_results)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        adapter = CdnAdapter(cfResults)
        recycler.adapter = adapter

        // 1. Populate CDN Spinner dynamically from Go Native Vault
        val cdnList = mutableListOf<String>()
        val cdnCount = mobile.Mobile.getCdnCount()

        for (i in 0 until cdnCount) {
            val name = mobile.Mobile.getCdnName(i)
            if (name.isNotEmpty()) {
                cdnList.add(name)
            }
        }

        if (cdnList.isEmpty()) {
            cdnList.add("CloudX")
            cdnList.add("CloudY")
            cdnList.add("CloudZ")
        }

        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, cdnList)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCdn.adapter = spinnerAdapter

        // 2. Extract the base VLESS protocols supported by THIS CONFIG
        val allowedCdnProtocols = listOf("vless-ws", "vless-grpc", "vless-httpupgrade", "vless-xhttp")
        val configSupportedProtocols = mutableListOf<String>()
        var savedProtocol = ""

        if (isDefaultConfig) {
            savedProtocol = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${configId}_tunnelProtocol", "") ?: ""

            val nativeIndex = configId.removePrefix("default_").toLongOrNull() ?: 0L
            val types = mobile.Mobile.getDefaultConfigType(nativeIndex).split(",").map { it.trim().lowercase() }

            // Filter strictly by the VLESS allowlist
            configSupportedProtocols.addAll(types.filter { allowedCdnProtocols.contains(it) })
        } else {
            val currentConfigs = net.vaydns.phoenix.ConfigEditorActivity.loadAllConfigs(this)
            val config = currentConfigs.find { it.id == configId }
            if (config != null) {
                savedProtocol = config.tunnelProtocol ?: ""
            }
            // Custom configs can theoretically use any of the allowed VLESS protocols
            configSupportedProtocols.addAll(allowedCdnProtocols)
        }

        if (configSupportedProtocols.isEmpty()) {
            configSupportedProtocols.add("vless-ws")
        }

        val filter = IntentFilter("CF_SCANNER_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }

        // 3. Add dynamic listener to CDN Spinner to auto-filter the Protocol Spinner
        spinnerCdn.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val selectedCdn = parent.getItemAtPosition(position).toString()

                // Filter the config's protocols by checking if the newly selected CDN ACTUALLY supports them
                val cdnFilteredProtocols = configSupportedProtocols.filter { proto ->
                    mobile.Mobile.cdnSupportsProtocol(selectedCdn, proto)
                }.toMutableList()

                // Failsafe: If the JSON is broken and returns empty, fallback to the config's primary protocol
                if (cdnFilteredProtocols.isEmpty()) {
                    cdnFilteredProtocols.add(configSupportedProtocols.first())
                }

                // Try to remember the user's previously selected protocol if they are just flipping CDNs
                val currentSelectedProto = spinnerProtocol.selectedItem?.toString()?.lowercase() ?: savedProtocol.lowercase()

                val protocolAdapter = android.widget.ArrayAdapter(this@CdnScannerActivity, android.R.layout.simple_spinner_item, cdnFilteredProtocols)
                protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerProtocol.adapter = protocolAdapter

                // Pre-select the safest matching protocol
                if (cdnFilteredProtocols.contains(currentSelectedProto)) {
                    spinnerProtocol.setSelection(cdnFilteredProtocols.indexOf(currentSelectedProto))
                } else if (cdnFilteredProtocols.contains(savedProtocol.lowercase())) {
                    spinnerProtocol.setSelection(cdnFilteredProtocols.indexOf(savedProtocol.lowercase()))
                } else {
                    spinnerProtocol.setSelection(0)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        btnStartStop.setOnClickListener {
            if (!isScanning) {

                // Grab the target CDN early for the validation check
                val selectedCdn = spinnerCdn.selectedItem?.toString() ?: "CloudX"

                // 1. Fetch the tunnel protocol directly from the Spinner UI
                val currentProtocol = spinnerProtocol.selectedItem?.toString() ?: "vaydns"

                // 2. GUARDRAIL: Verify CDN and Protocol compatibility
                if (currentProtocol.lowercase() in listOf("vless-ws", "vless-grpc", "vless-httpupgrade", "vless-xhttp")) {
                    val supported = mobile.Mobile.cdnSupportsProtocol(selectedCdn, currentProtocol)
                    if (!supported) {
                        Toast.makeText(this, "Cannot Scan: CDN '$selectedCdn' does not support protocol '$currentProtocol'.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                }

                // Grab the user's requested count
                val countStr = etScanCount.text.toString()
                var scanCount = countStr.toIntOrNull() ?: 512
                val dialTimeout = etDialTimeout.text.toString().toIntOrNull() ?: -1
                val readDeadline = etReadDeadline.text.toString().toIntOrNull() ?: -1

                // Cap the requested scan count to the maximum available IPs ---
                try {
                    // Fetch the JSON string representing the total IPs from your Go backend
                    val countsJsonStr = mobile.Mobile.getCloudIPCounts()
                    val countsJson = org.json.JSONObject(countsJsonStr)

                    // Match the key (e.g., "CloudX" -> "cloudx")
                    val cdnKey = selectedCdn.lowercase()
                    val maxAvailable = countsJson.optLong(cdnKey, Long.MAX_VALUE)

                    // If the user asked for more IPs than actually exist, hard-cap it
                    if (maxAvailable > 0 && scanCount > maxAvailable) {
                        scanCount = maxAvailable.toInt()

                        // Automatically update the text field so the user sees the correction
                        etScanCount.setText(scanCount.toString())
                        Toast.makeText(this@CdnScannerActivity, "Count capped to max available IPs: $scanCount", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Validate the inputs before starting
                if (dialTimeout <= 0 || readDeadline <= 0) {
                    Toast.makeText(this@CdnScannerActivity, "Invalid timeout values. Must be > 0.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }


                isScanning = true
                btnStartStop.text = "STOP SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))

                // Clean UI text as requested
//                tvStatus.text = "Scanning..."
                tvProgress.text = "0 / $scanCount"
                tvPassed.text = "0 found"

                btnSet.isEnabled = false
                btnShare.isEnabled = false
                etScanCount.isEnabled = false
                etDialTimeout.isEnabled = false
                etReadDeadline.isEnabled = false
                spinnerCdn.isEnabled = false
                spinnerProtocol.isEnabled = false

                cfResults.clear()
                adapter.notifyDataSetChanged()

                val serviceIntent = Intent(this, CdnScannerService::class.java).apply {
                    action = "ACTION_START_SCAN"
                    putExtra("IS_DEFAULT", isDefaultConfig)
                    putExtra("CONFIG_INDEX", configIndex)
                    putExtra("SCAN_COUNT", scanCount)
                    putExtra("TARGET_CDN", selectedCdn)
                }
                startService(serviceIntent)
            } else {
                isScanning = false
                btnStartStop.text = "START SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                etScanCount.isEnabled = true
                spinnerCdn.isEnabled = true
                startService(Intent(this, CdnScannerService::class.java).apply { action = "ACTION_STOP_SCAN" })
            }
        }

        btnSet.setOnClickListener {
            if (cfResults.isEmpty()) {
                Toast.makeText(this, "No valid IPs found yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Capture the currently selected CDN from the spinner
            val selectedCdn = spinnerCdn.selectedItem?.toString() ?: "CloudX"

            // 1. Create the custom layout for the Dialog
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, (16 * resources.displayMetrics.density).toInt(), padding, 0)
            }

            // --- NEW: Max Latency Input Field ---
            val etMaxLatency = com.google.android.material.textfield.TextInputEditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("2000")
            }

            val tilMaxLatency = com.google.android.material.textfield.TextInputLayout(
                this,
                null,
                com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox
            ).apply {
                hint = "Max Latency (ms)"
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
                }
                addView(etMaxLatency)
            }

            container.addView(tilMaxLatency)
            // ------------------------------------

            val radioGroup = android.widget.RadioGroup(this).apply {
                orientation = android.widget.RadioGroup.VERTICAL
            }

            val rbMerge = android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "Merge With Existing IPs"
                textSize = 16f
                isChecked = true // Default choice
            }

            val rbOverwrite = android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "Overwrite Existing IPs"
                textSize = 16f
            }

            radioGroup.addView(rbMerge)
            radioGroup.addView(rbOverwrite)
            container.addView(radioGroup)

            // 2. Build the new Dialog
            MaterialAlertDialogBuilder(this)
                .setTitle("Save IPs")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->

                    // --- NEW: Filter by Max Latency ---
                    val maxLatency = etMaxLatency.text.toString().toIntOrNull() ?: 2000

                    val filteredResults = cfResults.filter { it.latencyMs <= maxLatency }
                    val scannedIps = filteredResults.map { it.ip }

                    if (scannedIps.isEmpty()) {
                        Toast.makeText(this, "No IPs under $maxLatency ms to save.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    // ----------------------------------

                    val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
                    val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"

                    val finalJsonArray = org.json.JSONArray()
                    val existingTargetCdnIps = mutableListOf<String>()
                    val existingLatencies = mutableMapOf<String, Int>()

                    // 3. Separate other CDNs from the Target CDN
                    try {
                        val jsonArray = org.json.JSONArray(jsonString)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val ipCdn = obj.optString("cdn", "CloudX")
                            val rawIp = obj.optString("ip", "")
                            val ip = CryptoHelper.decrypt(rawIp)
                            val lat = obj.optInt("latency", -1)

                            if (ipCdn.equals(selectedCdn, ignoreCase = true)) {
                                if (ip.isNotEmpty()) {
                                    existingTargetCdnIps.add(ip)
                                    existingLatencies[ip] = lat
                                }
                            } else {
                                // Keep other CDN data completely untouched!
                                finalJsonArray.put(obj)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    // 4. Process Merge or Overwrite exclusively for the Target CDN
                    val finalTargetIpsToSave = mutableListOf<String>()
                    if (rbMerge.isChecked) {
                        finalTargetIpsToSave.addAll(existingTargetCdnIps)
                        for (ip in scannedIps) {
                            if (!finalTargetIpsToSave.contains(ip)) {
                                finalTargetIpsToSave.add(ip)
                            }
                        }
                    } else {
                        finalTargetIpsToSave.addAll(scannedIps)
                    }

                    // 5. Build and append the updated Target CDN IPs
                    for ((index, ip) in finalTargetIpsToSave.withIndex()) {
                        val obj = org.json.JSONObject()
                        obj.put("ip", CryptoHelper.encrypt(ip))
                        obj.put("isChecked", index == 0) // Check the fastest IP for this specific CDN

                        // Use filteredResults here to get the correct latency
                        val matchedResult = filteredResults.find { it.ip == ip }
                        val latency = matchedResult?.latencyMs ?: existingLatencies[ip] ?: -1

                        obj.put("latency", latency)
                        obj.put("cdn", selectedCdn)

                        finalJsonArray.put(obj)
                    }

                    vaultPrefs.edit().putString("vault_ips_json", finalJsonArray.toString()).apply()

                    // 6. Apply fastest IP to the current config ONLY if the CDN matches
                    val fastestIp = scannedIps.firstOrNull() ?: ""
                    if (fastestIp.isNotEmpty() && configId.isNotEmpty()) {

                        // Fetch the currently assigned CDN for this specific config
                        val configCdn = if (isDefaultConfig) {
                            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                                .getString("${configId}_cdn", "CloudX") ?: "CloudX"
                        } else {
                            getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
                                .getString("${configId}_cdn", "CloudX") ?: "CloudX"
                        }

                        // Protect against CDN cross-contamination
                        if (configCdn.equals(selectedCdn, ignoreCase = true)) {
                            if (isDefaultConfig) {
                                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("${configId}_vlessIp", CryptoHelper.encrypt(fastestIp))
                                    .apply()
                            } else {
                                val currentConfigs = net.vaydns.phoenix.ConfigEditorActivity.loadAllConfigs(this@CdnScannerActivity).toMutableList()
                                val cIndex = currentConfigs.indexOfFirst { it.id == configId }
                                if (cIndex != -1) {
                                    currentConfigs[cIndex] = currentConfigs[cIndex].copy(vlessIp = fastestIp)
                                    net.vaydns.phoenix.ConfigEditorActivity.saveAllConfigs(this@CdnScannerActivity, currentConfigs)
                                }
                            }
                        }
                    }

                    Toast.makeText(this, "Saved ${scannedIps.size} IPs for $selectedCdn!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnShare.setOnClickListener {
            if (cfResults.isEmpty()) {
                Toast.makeText(this, "No successful IPs to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Grab the CDN that was just scanned (ensure this matches your scanner's CDN variable)
            val scannedCdn = intent.getStringExtra("TARGET_CDN") ?:
            getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                .getString("selected_cdn", "CloudX") ?: "CloudX"

            // Prepend the human-readable CDN name so the user knows which one to import to
            val shareText = "Target CDN: $scannedCdn\n\n" + cfResults.joinToString("\n") {
                CryptoHelper.encrypt(it.ip)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$scannedCdn Scanner Results")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share IPs via"))
        }
    }

    private fun cleanupAndExit() {
        if (isScanning) {
            startService(Intent(this, CdnScannerService::class.java).apply { action = "ACTION_STOP_SCAN" })
        }
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            cleanupAndExit()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(scanReceiver)
        } catch (e: Exception) {}
    }
}