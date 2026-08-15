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
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray

class CdnScannerActivity : AppCompatActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvPassed: TextView
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
    private lateinit var etDelayTime: com.google.android.material.textfield.TextInputEditText
    private lateinit var etDialTimeout: com.google.android.material.textfield.TextInputEditText
    private lateinit var etReadDeadline: com.google.android.material.textfield.TextInputEditText
    private lateinit var switchUniformDistribution: SwitchMaterial

    private val cfResults = mutableListOf<ResolverResult>()
    private lateinit var spinnerProtocol: android.widget.Spinner
    private lateinit var spinnerPort: android.widget.Spinner

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "CF_SCANNER_RESULT") {

                val isFinished = intent.getBooleanExtra("IS_FINISHED", true)
                // 1. Reset Global UI State (Applies to both success and failure)
                if (isFinished) {
                    isScanning = false
                    btnStartStop.text = "START SCAN"
                    btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                    etScanCount.isEnabled = true
                    etDelayTime.isEnabled = true
                    etDialTimeout.isEnabled = true
                    etReadDeadline.isEnabled = true
                    spinnerCdn.isEnabled = true
                    spinnerProtocol.isEnabled = true
                    spinnerPort.isEnabled = true
                    switchUniformDistribution.isEnabled = true
                }

                val rawResult = intent.getStringExtra("RAW_RESULT") ?: ""

                // 2. Grab the dynamic target count for the UI (Default to 512 if empty)
                val targetCount = etScanCount.text.toString().ifEmpty { "512" }

                if (rawResult.isNotEmpty()) {
                    val parts = rawResult.split("|")
                    val jsonString = parts.getOrNull(0) ?: "[]"
                    val scannedCount = parts.getOrNull(1) ?: "0"
                    val foundCount = parts.getOrNull(2) ?: "0"

                    tvProgress.text = "$scannedCount / $targetCount"
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
                    tvProgress.text = "0 / $targetCount"
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
        etDelayTime = findViewById(R.id.et_cf_delay_time)
        etDialTimeout = findViewById(R.id.et_cf_dial_timeout)
        etReadDeadline = findViewById(R.id.et_cf_read_deadline)
        switchUniformDistribution = findViewById(R.id.switch_uniform_distribution)
        tvProgress = findViewById(R.id.tv_cf_progress)
        tvPassed = findViewById(R.id.tv_cf_passed)
        btnStartStop = findViewById(R.id.btn_cf_start_stop)
        btnSet = findViewById(R.id.btn_cf_set)
        btnShare = findViewById(R.id.btn_cf_share)
        spinnerCdn = findViewById(R.id.spinner_scanner_cdn)
        spinnerProtocol = findViewById(R.id.spinner_scanner_protocol)
        spinnerPort = findViewById(R.id.spinner_scanner_port)
        recycler = findViewById(R.id.recycler_cf_results)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        adapter = CdnAdapter(cfResults)
        recycler.adapter = adapter

        // 1. Populate CDN Spinner dynamically from Go Native Vault
        val cdnList = mutableListOf<String>()
        if (isDefaultConfig) {
            val nativeIndex = configId.removePrefix("default_").toLongOrNull() ?: 0L
            val configCloudsStr = mobile.Mobile.getDefaultConfigClouds(nativeIndex)
            if (configCloudsStr.isNotEmpty()) {
                cdnList.addAll(configCloudsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }

        // Fallback: If it's a Custom Config (or the JSON didn't have the "clouds" array), load the Global list
        if (cdnList.isEmpty()) {
            val cdnCount = mobile.Mobile.getCdnCount()
            for (i in 0 until cdnCount) {
                val name = mobile.Mobile.getCdnName(i)
                if (name.isNotEmpty()) {
                    cdnList.add(name)
                }
            }
        }

        if (cdnList.isEmpty()) {
            cdnList.add("CloudX")
            cdnList.add("CloudY")
            cdnList.add("CloudZ")
            cdnList.add("CloudV")
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

                val portsCsv = mobile.Mobile.getCdnPortsCsv(selectedCdn)
                val cdnFilteredPorts = if (portsCsv.isNotEmpty()) {
                    portsCsv.split(",").map { it.trim() }
                } else {
                    listOf("443")
                }

                val currentSelectedPort = spinnerPort.selectedItem?.toString() ?: "443"
                val portAdapter = android.widget.ArrayAdapter(this@CdnScannerActivity, android.R.layout.simple_spinner_item, cdnFilteredPorts)
                portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerPort.adapter = portAdapter

                if (cdnFilteredPorts.contains(currentSelectedPort)) {
                    spinnerPort.setSelection(cdnFilteredPorts.indexOf(currentSelectedPort))
                } else if (cdnFilteredPorts.contains("443")) {
                    spinnerPort.setSelection(cdnFilteredPorts.indexOf("443"))
                } else {
                    spinnerPort.setSelection(0)
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
                val currentPortStr = spinnerPort.selectedItem?.toString() ?: "443"
                val currentPort = currentPortStr.toLongOrNull() ?: 443L

                // 2. GUARDRAIL: Verify CDN and Protocol compatibility
                if (currentProtocol.lowercase() in listOf("vless-ws", "vless-grpc", "vless-httpupgrade", "vless-xhttp")) {
                    val supported = mobile.Mobile.cdnSupportsProtocol(selectedCdn, currentProtocol)
                    if (!supported) {
                        Toast.makeText(this, "Cannot Scan: CDN '$selectedCdn' does not support protocol '$currentProtocol'.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                }

                // 3. GUARDRAIL: Verify CDN and Port integrity (NEW)
                // (Note: Gomobile maps Go 'int' to Kotlin 'Long' automatically)
                val portSupported = mobile.Mobile.cdnSupportsPort(selectedCdn, currentPort)
                if (!portSupported) {
                    Toast.makeText(this, "Cannot Scan: CDN '$selectedCdn' does not support port '$currentPortStr'.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                // Grab the user's requested values
                val countStr = etScanCount.text.toString()
                var scanCount = countStr.toIntOrNull() ?: 512
                val delayTime = etDelayTime.text.toString().toIntOrNull() ?: 30
                val dialTimeout = etDialTimeout.text.toString().toIntOrNull() ?: -1
                val readDeadline = etReadDeadline.text.toString().toIntOrNull() ?: -1
                val uniformDist = switchUniformDistribution.isChecked

                // Cap the requested scan count to the maximum available IPs
                try {
                    val countsJsonStr = mobile.Mobile.getCloudIPCounts()
                    val countsJson = org.json.JSONObject(countsJsonStr)

                    val cdnKey = selectedCdn.lowercase()
                    val maxAvailable = countsJson.optLong(cdnKey, Long.MAX_VALUE)

                    if (maxAvailable > 0 && scanCount > maxAvailable) {
                        scanCount = maxAvailable.toInt()
                        etScanCount.setText(scanCount.toString())
                        Toast.makeText(this@CdnScannerActivity, "Count capped to max available IPs: $scanCount", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Validate the inputs before starting
                if (dialTimeout <= 0 || readDeadline <= 0 || delayTime < 0) {
                    Toast.makeText(this@CdnScannerActivity, "Invalid numeric values.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                isScanning = true
                btnStartStop.text = "STOP SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))

                tvProgress.text = "0 / $scanCount"
                tvPassed.text = "0 found"

                btnSet.isEnabled = false
                btnShare.isEnabled = false
                etScanCount.isEnabled = false
                etDelayTime.isEnabled = false
                etDialTimeout.isEnabled = false
                etReadDeadline.isEnabled = false
                spinnerCdn.isEnabled = false
                spinnerProtocol.isEnabled = false
                spinnerPort.isEnabled = false
                switchUniformDistribution.isEnabled = false

                cfResults.clear()
                adapter.notifyDataSetChanged()

                val serviceIntent = Intent(this, CdnScannerService::class.java).apply {
                    action = "ACTION_START_SCAN"
                    putExtra("IS_DEFAULT", isDefaultConfig)
                    putExtra("CONFIG_INDEX", configIndex)
                    putExtra("SCAN_COUNT", scanCount)
                    putExtra("TARGET_CDN", selectedCdn)
                    putExtra("TARGET_PORT", currentPort.toInt())
                    putExtra("DIAL_TIMEOUT", dialTimeout)
                    putExtra("READ_DEADLINE", readDeadline)
                    putExtra("BATCH_DELAY_SEC", delayTime)
                    putExtra("UNIFORM_DIST", uniformDist)
                }
                startService(serviceIntent)
            } else {
                isScanning = false
                btnStartStop.text = "START SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                etScanCount.isEnabled = true
                etDelayTime.isEnabled = true
                spinnerCdn.isEnabled = true
                switchUniformDistribution.isEnabled = true
                startService(Intent(this, CdnScannerService::class.java).apply { action = "ACTION_STOP_SCAN" })
            }
        }

        btnSet.setOnClickListener {
            if (cfResults.isEmpty()) {
                Toast.makeText(this, "No valid IPs found yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedCdn = spinnerCdn.selectedItem?.toString() ?: "CloudX"
            val selectedPort = spinnerPort.selectedItem?.toString()?.toIntOrNull() ?: 443

            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, (16 * resources.displayMetrics.density).toInt(), padding, 0)
            }

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

            MaterialAlertDialogBuilder(this)
                .setTitle("Save IPs")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->

                    val maxLatency = etMaxLatency.text.toString().toIntOrNull() ?: 2000
                    val filteredResults = cfResults.filter { it.latencyMs <= maxLatency }
                    val scannedIps = filteredResults.map { it.ip }

                    if (scannedIps.isEmpty()) {
                        Toast.makeText(this, "No IPs under $maxLatency ms to save.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
                    val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"

                    val finalJsonArray = org.json.JSONArray()
                    val existingTargetCdnIps = mutableListOf<String>()
                    val existingLatencies = mutableMapOf<String, Int>()

                    try {
                        val jsonArray = org.json.JSONArray(jsonString)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val ipCdn = obj.optString("cdn", "CloudX")
                            val ipPort = obj.optInt("port", 443)
                            val rawIp = obj.optString("ip", "")
                            val ip = CryptoHelper.decrypt(rawIp)
                            val lat = obj.optInt("latency", -1)

                            if (ipCdn.equals(selectedCdn, ignoreCase = true)) {
                                if (ip.isNotEmpty()) {
                                    existingTargetCdnIps.add(ip)
                                    existingLatencies[ip] = lat
                                }
                            } else {
                                finalJsonArray.put(obj)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }

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

                    for ((index, ip) in finalTargetIpsToSave.withIndex()) {
                        val obj = org.json.JSONObject()
                        obj.put("ip", CryptoHelper.encrypt(ip))
                        obj.put("isChecked", index == 0)

                        val matchedResult = filteredResults.find { it.ip == ip }
                        val latency = matchedResult?.latencyMs ?: existingLatencies[ip] ?: -1

                        obj.put("latency", latency)
                        obj.put("cdn", selectedCdn)
                        obj.put("port", selectedPort)

                        finalJsonArray.put(obj)
                    }

                    vaultPrefs.edit().putString("vault_ips_json", finalJsonArray.toString()).apply()

                    val fastestIp = scannedIps.firstOrNull() ?: ""
                    if (fastestIp.isNotEmpty() && configId.isNotEmpty()) {
                        val configCdn = if (isDefaultConfig) {
                            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                                .getString("${configId}_cdn", "CloudX") ?: "CloudX"
                        } else {
                            getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
                                .getString("${configId}_cdn", "CloudX") ?: "CloudX"
                        }

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

            // Dynamically grab the CDN and Port directly from the spinners so they match exactly what was scanned
            val scannedCdn = spinnerCdn.selectedItem?.toString() ?: "CloudX"
            val scannedPort = spinnerPort.selectedItem?.toString() ?: "443"

            // Append BOTH the port and the CDN before encrypting
            val shareText = "Target CDN: $scannedCdn (Port $scannedPort)\n\n" + cfResults.joinToString("\n") { result ->
                val addressWithPort = if (result.ip.contains(":")) {
                    result.ip
                } else {
                    "${result.ip}:$scannedPort"
                }
                CryptoHelper.encrypt("$addressWithPort:$scannedCdn")
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$scannedCdn Scanner Results (Port $scannedPort)")
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