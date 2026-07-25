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

class CloudflareScannerActivity : AppCompatActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvPassed: TextView
//    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnSet: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ResolverAdapter
    private lateinit var spinnerCdn: android.widget.Spinner
    private var isScanning = false
    private var isDefaultConfig = false
    private var configIndex = -1L
    private var configId = ""
    private var customDomain = ""
    private lateinit var etScanCount: com.google.android.material.textfield.TextInputEditText
    private val cfResults = mutableListOf<ResolverResult>()

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
                    spinnerCdn.isEnabled = true
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
                    Toast.makeText(this@CloudflareScannerActivity, "Scan stopped or timed out.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cf_scanner)

        isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT", false)
        configIndex = intent.getLongExtra("CONFIG_INDEX", -1L)
        customDomain = intent.getStringExtra("DOMAIN") ?: ""
        configId = intent.getStringExtra("CONFIG_ID") ?: ""

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_cf)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        etScanCount = findViewById(R.id.et_cf_scan_count)
        tvProgress = findViewById(R.id.tv_cf_progress)
        tvPassed = findViewById(R.id.tv_cf_passed)
//        tvStatus = findViewById(R.id.tv_cf_status)
        btnStartStop = findViewById(R.id.btn_cf_start_stop)
        btnSet = findViewById(R.id.btn_cf_set)
        btnShare = findViewById(R.id.btn_cf_share)
        spinnerCdn = findViewById(R.id.spinner_scanner_cdn)
        recycler = findViewById(R.id.recycler_cf_results)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        adapter = ResolverAdapter(cfResults, false)
        recycler.adapter = adapter

    // Populate CDN Spinner dynamically from Go Native Vault
        val cdnList = mutableListOf<String>()
        val cdnCount = mobile.Mobile.getCdnCount()

        for (i in 0 until cdnCount) {
            val name = mobile.Mobile.getCdnName(i)
            if (name.isNotEmpty()) {
                cdnList.add(name)
            }
        }

        // Fallback options if the JSON hasn't been downloaded or loaded yet
        if (cdnList.isEmpty()) {
            cdnList.add("Cloudflare")
            cdnList.add("Amazon")
        }

        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, cdnList)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCdn.adapter = spinnerAdapter

        val filter = IntentFilter("CF_SCANNER_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }

        btnStartStop.setOnClickListener {
            if (!isScanning) {
                isScanning = true
                btnStartStop.text = "STOP SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))

                // Grab the user's requested count
                val countStr = etScanCount.text.toString()
                val scanCount = countStr.toIntOrNull() ?: 512
                val selectedCdn = spinnerCdn.selectedItem?.toString() ?: "Cloudflare"

                // Clean UI text as requested
//                tvStatus.text = "Scanning..."
                tvProgress.text = "0 / $scanCount"
                tvPassed.text = "0 found"

                btnSet.isEnabled = false
                btnShare.isEnabled = false
                etScanCount.isEnabled = false
                cfResults.clear()
                adapter.notifyDataSetChanged()

                spinnerCdn.isEnabled = false // (Locks dropdown during scan)

                val serviceIntent = Intent(this, CFScannerService::class.java).apply {
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
                startService(Intent(this, CFScannerService::class.java).apply { action = "ACTION_STOP_SCAN" })
            }
        }

        btnSet.setOnClickListener {
            if (cfResults.isEmpty()) {
                Toast.makeText(this, "No valid IPs found yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Capture the currently selected CDN from the spinner
            val selectedCdn = spinnerCdn.selectedItem?.toString() ?: "Cloudflare"

            // 1. Create the custom layout for the Dialog
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, (16 * resources.displayMetrics.density).toInt(), padding, 0)
            }

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

                    val scannedIps = cfResults.map { it.ip }
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
                            val ipCdn = obj.optString("cdn", "Cloudflare")
                            val ip = obj.optString("ip", "")
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
                        obj.put("ip", ip)
                        obj.put("isChecked", index == 0) // Check the fastest IP for this specific CDN

                        val matchedResult = cfResults.find { it.ip == ip }
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
                                .getString("${configId}_cdn", "Cloudflare") ?: "Cloudflare"
                        } else {
                            getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
                                .getString("${configId}_cdn", "Cloudflare") ?: "Cloudflare"
                        }

                        // Protect against CDN cross-contamination
                        if (configCdn.equals(selectedCdn, ignoreCase = true)) {
                            if (isDefaultConfig) {
                                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("${configId}_vlessIp", fastestIp)
                                    .apply()
                            } else {
                                val currentConfigs = net.vaydns.phoenix.ConfigEditorActivity.loadAllConfigs(this@CloudflareScannerActivity).toMutableList()
                                val cIndex = currentConfigs.indexOfFirst { it.id == configId }
                                if (cIndex != -1) {
                                    currentConfigs[cIndex] = currentConfigs[cIndex].copy(vlessIp = fastestIp)
                                    net.vaydns.phoenix.ConfigEditorActivity.saveAllConfigs(this@CloudflareScannerActivity, currentConfigs)
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

            val shareText = "CDN Scanner Results:\n" + cfResults.joinToString("\n") {
                "${it.ip} (${it.latencyMs} ms)"
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "CDN Scanner Results")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share IPs via"))
        }
    }

    private fun cleanupAndExit() {
        if (isScanning) {
            startService(Intent(this, CFScannerService::class.java).apply { action = "ACTION_STOP_SCAN" })
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