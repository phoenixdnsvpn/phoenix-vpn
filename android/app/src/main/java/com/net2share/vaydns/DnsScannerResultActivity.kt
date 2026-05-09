package com.net2share.vaydns

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import android.widget.EditText
import android.content.Context
import android.util.Log
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var isQuickScanner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_scanner_result)

        // Prevent the phone from automatically sleeping while on this screen
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_results)
        isQuickScanner = intent.getBooleanExtra("IS_QUICK_SCANNER", false)
        val btnSort = findViewById<ImageButton>(R.id.btn_sort)
        val btnSave = findViewById<ImageButton>(R.id.btn_save)
        val btnSet = findViewById<ImageButton>(R.id.btn_set)

        if (isQuickScanner) {
            // Hiding the Apply/Check icon because we are only sorting and sharing
            btnSet.visibility = android.view.View.GONE
            btnSave.visibility = android.view.View.GONE
        }

        val resolversFilePath = intent.getStringExtra("RESOLVERS_FILE") ?: ""
        val resolversCommaSeparated = if (resolversFilePath.isNotEmpty()) {
            // High Volume: Read from Disk
            try {
                java.io.File(resolversFilePath).readText()
            } catch (e: Exception) {
                ""
            }
        } else {
            // Standard Volume: Read from Intent
            intent.getStringExtra("RESOLVERS") ?: ""
        }

        val isDefaultResolvers = intent.getBooleanExtra("IS_DEFAULT_RESOLVERS", false)
        // The new way to handle the back arrow click
        toolbar.setNavigationOnClickListener {
            stopScanProcess()
            finish()
        }

        val configId = intent.getStringExtra("CONFIG_ID") ?: ""
        val isDefaultConfig = configId.startsWith("default_")
        val configIndex = if (isDefaultConfig) configId.removePrefix("default_").toLongOrNull() ?: 0L else 0L

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
        adapter = ResolverAdapter(results, isQuickScanner)
        recycler = findViewById(R.id.recycler_results)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
        recycler.adapter = adapter
        // Extract Intent Data
        val baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
        val domain = intent.getStringExtra("DOMAIN") ?: ""
        val pubkey = intent.getStringExtra("PUBKEY") ?: ""
        //val resolversCommaSeparated = intent.getStringExtra("RESOLVERS") ?: ""
        val proxyType = intent.getStringExtra("PROXY_TYPE") ?: "socks5h"
        val tunnelProtocol = intent.getStringExtra("PROTOCOL") ?: "socks"
        val ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
        val fastFailEnabled = intent.getBooleanExtra("FAST_FAIL_ENABLED", true)
        val user = intent.getStringExtra("USER") ?: "none"
        val pass = intent.getStringExtra("PASS") ?: "none"
        val recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
        val idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
        val keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
        val clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
        val mtu = intent.getLongExtra("MTU", 0L)
        val selectedMode = intent.getStringExtra("MODE") ?: "udp"
//        val isConservative = intent.getBooleanExtra("CONSERVATIVE", false)
        val workers = intent.getLongExtra("WORKERS", 20L)
        val tunnelWait = intent.getLongExtra("TUNNEL_WAIT", 2000L)
        val udpTimeout = intent.getLongExtra("UDP_TIMEOUT", 1000L)
        val probeTimeout = intent.getLongExtra("PROBE_TIMEOUT", 15L)
        val retries = intent.getLongExtra("RETRIES", 0L)
        //val skipQuickCheck = intent.getBooleanExtra("SKIP_QUICK_CHECK", false)

        totalResolvers = intent.getIntExtra("TOTAL_RESOLVERS", 500)
        tvProgress.text = "0 / $totalResolvers"

        val isAuthEnabled = intent.getBooleanExtra("USE_AUTH", false)

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

                startScan(isDefaultConfig, configIndex, selectedMode, domain, pubkey, resolversCommaSeparated, baseDohUrl, proxyType, tunnelProtocol, recordType,
                    workers, tunnelWait, udpTimeout, probeTimeout, retries, fastFailEnabled, isQuickScanner, user, pass, ssMethod,
                    idleTimeout, keepAlive, clientIdSize, mtu)
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
        mobile.Mobile.initVault(filesDir.absolutePath)

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VayDNS::ScannerWakelock")
            wakeLock?.acquire(30 * 60 * 1000L) // 30 minutes
            Log.i("VAY_SCANNER", "WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.e("VAY_SCANNER", "Failed to acquire WakeLock: ${e.message}")
        }

        // Start initial scan
        if (savedInstanceState == null) {
            startScan(isDefaultConfig, configIndex, selectedMode, domain, pubkey, resolversCommaSeparated, baseDohUrl, proxyType, tunnelProtocol, recordType,
                workers, tunnelWait, udpTimeout, probeTimeout, retries, fastFailEnabled, isQuickScanner, user, pass, ssMethod,
                idleTimeout, keepAlive, clientIdSize, mtu)
        }

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
        isDefaultConfig: Boolean,
        configIndex: Long,
        selectedMode: String,
        domain: String,
        pubkey: String,
        resolvers: String,
        baseDohUrl: String,
        proxyType: String,
        tunnelProtocol: String,
        recordType: String,
        workers: Long,
        tunnelWait: Long,
        udpTimeout: Long,
        probeTimeout: Long,
        retries: Long,
        fastFailEnabled: Boolean,
        isQuickScan: Boolean,
        user: String,
        pass: String,
        ssMethod: String,
        idleTimeout: String,
        keepAlive: String,
        clientIdSize: Long,
        mtu:Long
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
                isDefaultConfig,
                configIndex,
                selectedMode,
                domain,
                pubkey,
                resolvers,
                baseDohUrl,
                proxyType,
                tunnelProtocol,
                user,
                pass,
                ssMethod,
                recordType,
                idleTimeout,
                keepAlive,
                clientIdSize,
                mtu,
                workers,
                tunnelWait,
                udpTimeout,
                probeTimeout,
                retries,
                fastFailEnabled,
                isQuickScan,
                scanCallback!!
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        super.onDestroy()
        if (isFinishing) {
            // The user explicitly pressed Back or closed the screen.
            // It is safe to nuke the sandbox process to prevent the kcp-go crash.
            System.exit(0)
        } else {
            // The OS is temporarily destroying the UI in the background to save RAM.
            // Stop the Go engine so it doesn't leak or run headless.
            stopScanProcess() // now we are Sandboxing the scanner process to prevent crash from kcp-go leaking.
        }
    }
}