package com.net2share.vaydns

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import mobile.Mobile
import mobile.ScanResultCallback
import org.json.JSONObject

// Moved outside the class so Adapter can see it easily
data class ResolverResult(
    val ip: String,
    val latencyMs: Int,
    val probe: String
)

class DnsScannerResultActivity : AppCompatActivity() {

    private var passedCount = 0
    private lateinit var tvProgress: TextView
    private lateinit var tvPassed: TextView
    private lateinit var btnStopResume: Button
    private lateinit var btnShare: ImageButton
//    private lateinit var btnBack: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ResolverAdapter
    private val results = mutableListOf<ResolverResult>()
    private var totalResolvers = 0
    private var isRunning = true
    private var scanCallback: ScanResultCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_scanner_result)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_results)
        val btnSort = findViewById<ImageButton>(R.id.btn_sort)
        val btnSave = findViewById<ImageButton>(R.id.btn_save)

        val isDefaultResolvers = intent.getBooleanExtra("IS_DEFAULT_RESOLVERS", false)
        // The new way to handle the back arrow click
        toolbar.setNavigationOnClickListener {
            stopScanProcess()
            finish()
        }

        val configId = intent.getStringExtra("CONFIG_ID") ?: "unknown_config"
        val btnSet = findViewById<ImageButton>(R.id.btn_set)
        btnSet.setOnClickListener {
            // 1. Find the fastest successful resolver
            val fastest = results.filter { it.probe == "ok" }.minByOrNull { it.latencyMs }

            if (fastest == null) {
                Toast.makeText(this, "No successful resolvers to set.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. Ask for confirmation so the user knows what is happening
            MaterialAlertDialogBuilder(this)
                .setTitle("Apply Fastest Resolver")
                .setMessage("Set ${fastest.ip} (${fastest.latencyMs} ms) as the DNS server for this configuration?")
                .setPositiveButton("Apply") { _, _ ->
                    try {
                        val updateFile = java.io.File(filesDir, "apply_dns_${configId}.txt")
                        updateFile.writeText(fastest.ip)
                        Toast.makeText(this, "Fastest resolver applied!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to apply: ${e.message}", Toast.LENGTH_SHORT).show()
                    }

                    if (configId.startsWith("default_")) {
                        // --- Logic for Official/Default Configs ---
                        val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                        prefs.edit().putString("${configId}_dns", fastest.ip).apply()

                        Toast.makeText(this, "Applied to Default Config!", Toast.LENGTH_SHORT).show()

                    } else {
                        // --- Logic for Custom User Configs ---
                        try {
                            // Load existing list using the companion object in ConfigEditorActivity
                            val currentConfigs = com.net2share.vaydns.ConfigEditorActivity.loadAllConfigs(this).toMutableList()

                            val index = currentConfigs.indexOfFirst { it.id == configId }
                            if (index != -1) {
                                // Data classes allow us to copy the object and change only one field
                                val updatedConfig = currentConfigs[index].copy(dnsAddress = fastest.ip)
                                currentConfigs[index] = updatedConfig

                                // Save the updated list back to SharedPreferences
                                com.net2share.vaydns.ConfigEditorActivity.saveAllConfigs(this, currentConfigs)
                                Toast.makeText(this, "Fastest resolver applied!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Error: Config not found.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "Failed to apply: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
// Sort Logic: Ascending Latency
        btnSort.setOnClickListener {
            if (results.isNotEmpty()) {
                // Sort the list in place by latency
                results.sortBy { it.latencyMs }

                // Notify the adapter that the whole list has changed order
                adapter.notifyDataSetChanged()

//                Toast.makeText(this, "Sorted by latency (fastest first)", Toast.LENGTH_SHORT).show()
            }
        }

        btnSave.setOnClickListener {
            if (results.none { it.probe == "ok" }) {
                Toast.makeText(this, "No successful resolvers to save.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val input = EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            input.hint = "e.g., 3000"

            MaterialAlertDialogBuilder(this)
                .setTitle("Save Fast Resolvers")
                .setMessage("Enter maximum acceptable latency (ms):")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val maxLatencyStr = input.text.toString()
                    val maxLatency = maxLatencyStr.toIntOrNull() ?: Int.MAX_VALUE

                    // Filter and sort
                    val filteredAndSorted = results
                        .filter { it.probe == "ok" && it.latencyMs <= maxLatency }
                        .sortedBy { it.latencyMs }

                    if (filteredAndSorted.isEmpty()) {
                        Toast.makeText(this, "No resolvers found under ${maxLatency}ms.", Toast.LENGTH_SHORT).show()
                    } else {
                        // 🚨 FIX 1 & 3: Save IP + Latency directly to a physical File!
                        // Format: "1.1.1.1,45" per line. This completely bypasses the multi-process cache bug.
                        val dataToSave = filteredAndSorted.joinToString("\n") { "${it.ip},${it.latencyMs}" }

                        try {
                            val file = java.io.File(filesDir, "resolvers_$configId.txt")
                            file.writeText(dataToSave)
                            Toast.makeText(this, "Saved ${filteredAndSorted.size} resolvers!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Initialize Views
        tvProgress = findViewById(R.id.tv_progress)
        tvPassed = findViewById(R.id.tv_passed)
        btnStopResume = findViewById(R.id.btn_stop_resume)
        btnShare = findViewById(R.id.btn_share)
//        btnBack = findViewById(R.id.btn_back)
        recycler = findViewById(R.id.recycler_results)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        adapter = ResolverAdapter(results)
        recycler.adapter = adapter
        // Extract Intent Data
        val domain = intent.getStringExtra("DOMAIN") ?: ""
        val pubkey = intent.getStringExtra("PUBKEY") ?: ""
        val resolversCommaSeparated = intent.getStringExtra("RESOLVERS") ?: ""
        val proxyType = intent.getStringExtra("PROXY_TYPE") ?: "socks5h"
        val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
        val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
        val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
        val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
//        val isConservative = intent.getBooleanExtra("CONSERVATIVE", false)
        val workers = intent.getLongExtra("WORKERS", 10L)
        val tunnelWait = intent.getLongExtra("TUNNEL_WAIT", 2000L)
        val probeTimeout = intent.getLongExtra("PROBE_TIMEOUT", 15L)
        val retries = intent.getLongExtra("RETRIES", 0L)
        totalResolvers = intent.getIntExtra("TOTAL_RESOLVERS", 500)
        tvProgress.text = "0 / $totalResolvers"

        val isAuthEnabled = intent.getBooleanExtra("USE_AUTH", false)

        // Set to actual value if ON, otherwise fallback to "none"
        val user = if (isAuthEnabled) {
            intent.getStringExtra("USER")?.takeIf { it.isNotEmpty() } ?: "none"
        } else {
            "none"
        }

        val pass = if (isAuthEnabled) {
            intent.getStringExtra("PASS")?.takeIf { it.isNotEmpty() } ?: "none"
        } else {
            "none"
        }
//        supportActionBar?.title = "Scan in Progress"

        // Back Arrow Action
/*        btnBack.setOnClickListener {
            stopScanProcess()
            finish() // Return to previous screen
        }*/

        // Stop/Resume Logic
        btnStopResume.setOnClickListener {
            if (isRunning) {
                // 1. Tell the Mobile engine to stop the scan
                stopScanProcess()
            } else {
                // 2. Clear previous results if you want a fresh start
                results.clear()
                passedCount = 0
                recycler.adapter?.notifyDataSetChanged()

                tvProgress.text = "0 / $totalResolvers"
                tvPassed.text = "0 passed"
                // 3. Update UI and restart
                btnStopResume.text = "STOP"
                isRunning = true

                startScan(domain, pubkey, resolversCommaSeparated, proxyType, recordType,
                    workers, tunnelWait, probeTimeout, retries, user, pass,
                    idleTimeout, keepAlive, clientIdSize)
            }
        }

// Share Logic
        btnShare.setOnClickListener {
            if (results.isEmpty()) {
                Toast.makeText(this, "No results to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1. Filter for successful probes
            val successfulResults = results.filter { it.probe == "ok" }

            if (successfulResults.isEmpty()) {
                Toast.makeText(this, "No successful IPs to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. Map to a formatted string
            val shareText = "VayDNS Scan Results:\n" + successfulResults.joinToString("\n") {
                "${it.ip} (${it.latencyMs} ms)"
            }

            // 3. Create the Share Intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "VayDNS Scan Results")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }

            // 4. Launch the Android Chooser (WhatsApp, Email, Telegram, etc.)
            startActivity(Intent.createChooser(shareIntent, "Share Resolvers via"))
        }

        // ---  INJECT MEMORY INTO SANDBOXED PROCESS ---
        // Because this activity runs in a separate process, Go's memory is blank.
        // We must re-inject the Base64 strings from SharedPreferences before scanning.
        if (isDefaultResolvers) {
            try {
                val resolversFile = java.io.File(filesDir, "cached_default_resolvers.bin")
                if (resolversFile.exists()) {
                    val cachedResolverBytes = resolversFile.readBytes()
                    if (cachedResolverBytes.isNotEmpty()) {
                        mobile.Mobile.setDefaultResolvers(cachedResolverBytes)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val configFile = java.io.File(filesDir, "cached_default_configs.bin")
                if (configFile.exists()) {
                    val cachedConfigBytes = configFile.readBytes()
                    if (cachedConfigBytes.isNotEmpty()) {
                        mobile.Mobile.setDefaultConfigs(cachedConfigBytes)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Start initial scan
        startScan(domain, pubkey, resolversCommaSeparated, proxyType, recordType,
            workers, tunnelWait, probeTimeout, retries, user, pass,
            idleTimeout, keepAlive, clientIdSize)
    }

    private fun stopScanProcess() {
        if (isRunning) {
            try {
                Mobile.stopF35Scan()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            btnStopResume.text = "Resume"
            isRunning = false
        }
    }

    private fun startScan(
        domain: String,
        pubkey: String,
        resolvers: String,
        proxyType: String,
        recordType: String,
        workers: Long,
        tunnelWait: Long,
        probeTimeout: Long,
        retries: Long,
        user: String,
        pass: String,
        idleTimeout: String,
        keepAlive: String,
        clientIdSize: Long
    ) {
        scanCallback = object : ScanResultCallback {
            override fun onResult(jsonResult: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread // Safety check

                    try {
                        val json = JSONObject(jsonResult)

                        // 🚨 NEW: Check if this is a system status message first!
                        if (json.has("status")) {
                            val status = json.getString("status")
                            if (status == "finished") {
                                // The scan naturally completed!
                                stopScanProcess() // Flushes Go resources and flips UI to "RESUME"

                                val errorMsg = json.optString("error", "")
                                if (errorMsg.isNotEmpty()) {
                                    Toast.makeText(this@DnsScannerResultActivity, "Scan finished with error: $errorMsg", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@DnsScannerResultActivity, "Scan Complete!", Toast.LENGTH_SHORT).show()
                                }
                                return@runOnUiThread
                            }
                        }

                        // Otherwise, process it as a normal resolver result
                        if (json.has("resolver")) {
                            val ip = json.getString("resolver")
                            val latency = json.optInt("latency_ms", 99999)
                            val probe = json.optString("probe", "ok")

                            val item = ResolverResult(ip, latency, probe)
                            results.add(item)

                            if (probe == "ok") {
                                passedCount++
                            }
                            adapter.notifyItemInserted(results.size - 1)

                            tvProgress.text = "${results.size} / $totalResolvers"
                            tvPassed.text = "$passedCount passed"
                        }

                    } catch (e: Exception) {
                        // ignore bad json
                    }
                }
            }
        }

        try {
            Mobile.startF35Scan(
                domain,
                pubkey,
                resolvers,
                proxyType,
                user,
                pass,
                recordType,
                idleTimeout,
                keepAlive,
                clientIdSize,
                workers,
                tunnelWait,
                probeTimeout,
                retries,
                scanCallback!!
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
//        stopScanProcess() // Safety kill
        super.onDestroy()
        System.exit(0) // now we are Sandboxing the scanner process to prevent crash from kcp-go leaking.
    }
}