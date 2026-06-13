package com.net2share.vaydns

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
import mobile.Mobile

class CloudflareScannerActivity : AppCompatActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvPassed: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnSet: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ResolverAdapter

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
                // 1. Reset Global UI State (Applies to both success and failure)
                isScanning = false
                btnStartStop.text = "START SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                etScanCount.isEnabled = true // Unlocks the input field universally

                val rawResult = intent.getStringExtra("RAW_RESULT") ?: ""

                // 2. Grab the dynamic target count for the UI (Default to 512 if empty)
                val targetCount = etScanCount.text.toString().ifEmpty { "512" }

                if (rawResult.isNotEmpty()) {
                    val parts = rawResult.split("|")
                    val jsonString = parts.getOrNull(0) ?: "[]"
                    val scannedCount = parts.getOrNull(1) ?: "0"
                    val foundCount = parts.getOrNull(2) ?: "0"

                    // Apply dynamic target count
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
                    // Apply dynamic target count here as well
                    tvProgress.text = "0 / $targetCount"
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
        btnStartStop = findViewById(R.id.btn_cf_start_stop)
        btnSet = findViewById(R.id.btn_cf_set)
        btnShare = findViewById(R.id.btn_cf_share)

        recycler = findViewById(R.id.recycler_cf_results)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        adapter = ResolverAdapter(cfResults, false)
        recycler.adapter = adapter

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

                // Clean UI text as requested
                tvProgress.text = "Scanning..."
                tvPassed.text = ""

                btnSet.isEnabled = false
                btnShare.isEnabled = false
                etScanCount.isEnabled = false
                cfResults.clear()
                adapter.notifyDataSetChanged()

                val serviceIntent = Intent(this, CFScannerService::class.java).apply {
                    action = "ACTION_START_SCAN"
                    putExtra("IS_DEFAULT", isDefaultConfig)
                    putExtra("CONFIG_INDEX", configIndex)
                    putExtra("SCAN_COUNT", scanCount)
                }
                startService(serviceIntent)
            } else {
                isScanning = false
                btnStartStop.text = "START SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                etScanCount.isEnabled = true
                startService(Intent(this, CFScannerService::class.java).apply { action = "ACTION_STOP_SCAN" })
            }
        }

        btnSet.setOnClickListener {
            val fastest = cfResults.firstOrNull()

            if (fastest != null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Apply Fastest IP")
                    .setMessage("Set ${fastest.ip} (${fastest.latencyMs} ms) as your VLESS WS Server?")
                    .setPositiveButton("Apply") { _, _ ->

                        // 1. Save to Global Settings (Legacy / Fallback)
                        getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("vless_ws_ip", fastest.ip)
                            .apply()

                        Mobile.setGlobalVlessWsIP(fastest.ip)

                        // 2. NEW: Save specifically to the active config's memory!
                        if (configId.isNotEmpty()) {
                            if (isDefaultConfig) {
                                // Save to official configs override memory
                                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("${configId}_vlessIp", fastest.ip)
                                    .apply()
                            } else {
                                // Save to custom user configs JSON
                                val currentConfigs = com.net2share.vaydns.ConfigEditorActivity.loadAllConfigs(this).toMutableList()
                                val cIndex = currentConfigs.indexOfFirst { it.id == configId }
                                if (cIndex != -1) {
                                    currentConfigs[cIndex] = currentConfigs[cIndex].copy(vlessIp = fastest.ip)
                                    com.net2share.vaydns.ConfigEditorActivity.saveAllConfigs(this, currentConfigs)
                                }
                            }
                        }

                        Toast.makeText(this, "VLESS WS IP Set to: ${fastest.ip}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(this, "No valid IPs found yet.", Toast.LENGTH_SHORT).show()
            }
        }

        btnShare.setOnClickListener {
            if (cfResults.isEmpty()) {
                Toast.makeText(this, "No successful IPs to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val shareText = "Cloudflare Scanner Results:\n" + cfResults.joinToString("\n") {
                "${it.ip} (${it.latencyMs} ms)"
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Cloudflare Scanner Results")
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