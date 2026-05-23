package com.net2share.vaydns

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mobile.Mobile

data class ResolverResult(
    val ip: String,
    val latencyMs: Int,
    val probe: String
)

class DnsScannerResultActivity : AppCompatActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvPassed: TextView
    private lateinit var btnStopResume: Button
    private lateinit var btnShare: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: ResolverAdapter
    private var isRunning = false

    // --- GLOBAL STATE (Survives Sandbox Restarts) ---
    val globalResults = mutableListOf<ResolverResult>()
    var passedCount = 0
    var scannedCount = 0
    var totalResolvers = 0

    // --- BATCHING STATE ---
    private val BATCH_SIZE = 1025 // Safe margin well below the 3.8GB OOM limit
    private var currentBatchIndex = 0
    private var allResolvers = listOf<String>()

    // --- CONFIGURATION PARAMETERS ---
    private var isQuickScanner = false
    private var configId = ""
    private var isDefaultConfig = false
    private var configIndex = 0L
    private var baseDohUrl = ""
    private var domain = ""
    private var pubkey = ""
    private var proxyType = ""
    private var tunnelProtocol = ""
    private var ssMethod = ""
    private var lightE2EEnabled = true
    private var engineQuickScan = false
    private var user = ""
    private var pass = ""
    private var recordType = ""
    private var idleTimeout = ""
    private var keepAlive = ""
    private var clientIdSize = 2L
    private var mtu = 0L
    private var selectedMode = ""
    private var workers = 20L
    private var tunnelWait = 2000L
    private var udpTimeout = 1000L
    private var probeTimeout = 15L
    private var retries = 0L

    private val saveFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val successfulIps = globalResults.filter { it.probe == "ok" }.map { it.ip }.joinToString("\n")
                        outputStream.write(successfulIps.toByteArray())
                    }
                    Toast.makeText(this, "Resolvers saved successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SCANNER_UI_REFRESH" -> {
                    val ips = intent.getStringArrayListExtra("IPS") ?: arrayListOf()
                    val latencies = intent.getIntegerArrayListExtra("LATENCIES") ?: arrayListOf()
                    val scannedAdd = intent.getIntExtra("SCANNED_ADD", 0)

                    scannedCount += scannedAdd
                    var addedNew = false

                    for (i in ips.indices) {
                        if (!globalResults.any { res: ResolverResult -> res.ip == ips[i] }) {
                            globalResults.add(ResolverResult(ips[i], latencies[i], "ok"))
                            passedCount++
                            addedNew = true
                        }
                    }

                    if (addedNew || scannedAdd > 0) {
                        adapter.notifyDataSetChanged()
                        tvProgress.text = "$scannedCount / $totalResolvers"
                        tvPassed.text = "$passedCount passed"
                        if (addedNew) recycler.scrollToPosition(globalResults.size - 1)
                    }
                }
                "SCANNER_BATCH_FINISHED" -> {
                    Mobile.manualCleanup()
                    currentBatchIndex++
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isRunning) {
                            startNextBatch()
                        }
                    }, 20000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_scanner_result)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // --- 1. LOAD CONFIGURATION ---
        isQuickScanner = intent.getBooleanExtra("IS_QUICK_SCANNER", false)
        configId = intent.getStringExtra("CONFIG_ID") ?: ""
        isDefaultConfig = configId.startsWith("default_")
        configIndex = if (isDefaultConfig) configId.removePrefix("default_").toLongOrNull() ?: 0L else 0L

        baseDohUrl = intent.getStringExtra("BASE_DOH_URL") ?: ""
        domain = intent.getStringExtra("DOMAIN") ?: ""
        pubkey = intent.getStringExtra("PUBKEY") ?: ""
        proxyType = intent.getStringExtra("PROXY_TYPE") ?: "socks5h"
        tunnelProtocol = intent.getStringExtra("PROTOCOL") ?: "socks"
        ssMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
        lightE2EEnabled = intent.getBooleanExtra("LIGHT_E2E_ENABLED", true)
        engineQuickScan = intent.getBooleanExtra("ENGINE_QUICK_SCAN", false)
        user = intent.getStringExtra("USER") ?: "none"
        pass = intent.getStringExtra("PASS") ?: "none"
        recordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
        idleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
        keepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
        clientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
        mtu = intent.getLongExtra("MTU", 0L)
        selectedMode = intent.getStringExtra("MODE") ?: "udp"
        workers = intent.getLongExtra("WORKERS", 20L)
        tunnelWait = intent.getLongExtra("TUNNEL_WAIT", 2000L)
        udpTimeout = intent.getLongExtra("UDP_TIMEOUT", 1000L)
        probeTimeout = intent.getLongExtra("PROBE_TIMEOUT", 15L)
        retries = intent.getLongExtra("RETRIES", 0L)

        val resolversFilePath = intent.getStringExtra("RESOLVERS_FILE") ?: ""
        val resolversCommaSeparated = if (resolversFilePath.isNotEmpty()) {
            try { java.io.File(resolversFilePath).readText() } catch (e: Exception) { "" }
        } else {
            intent.getStringExtra("RESOLVERS") ?: ""
        }
        allResolvers = resolversCommaSeparated.split(",").filter { it.isNotBlank() }

        // --- 2. INITIALIZE UI ---
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_results)
        toolbar.setNavigationOnClickListener {
            stopScanProcess()
            finish()
        }

        val btnSort = findViewById<ImageButton>(R.id.btn_sort)
        val btnSave = findViewById<ImageButton>(R.id.btn_save)
        val btnSet = findViewById<ImageButton>(R.id.btn_set)
        if (isQuickScanner) {
            btnSet.visibility = android.view.View.GONE
            btnSave.visibility = android.view.View.GONE
        }

        btnSet.setOnClickListener {
            val fastest = globalResults.filter { it.probe == "ok" }.minByOrNull { it.latencyMs }
            if (fastest == null) {
                Toast.makeText(this, "No successful resolvers to set.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Apply Fastest Resolver")
                .setMessage("Set ${fastest.ip} (${fastest.latencyMs} ms) as the DNS server for this configuration?")
                .setPositiveButton("Apply") { _, _ ->
                    try {
                        val updateFile = java.io.File(filesDir, "apply_dns_${configId}.txt")
                        updateFile.writeText(fastest.ip)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to write temp file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }

                    if (configId.startsWith("default_")) {
                        val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                        prefs.edit().putString("${configId}_dns", fastest.ip).apply()
                        Toast.makeText(this, "Applied to Default Config!", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            val currentConfigs = com.net2share.vaydns.ConfigEditorActivity.loadAllConfigs(this).toMutableList()
                            val index = currentConfigs.indexOfFirst { it.id == configId }

                            if (index != -1) {
                                val updatedConfig = currentConfigs[index].copy(dnsAddress = fastest.ip)
                                currentConfigs[index] = updatedConfig
                                com.net2share.vaydns.ConfigEditorActivity.saveAllConfigs(this, currentConfigs)
                                Toast.makeText(this, "Fastest resolver applied!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Error: Config not found.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "Failed to apply custom config: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSort.setOnClickListener {
            if (globalResults.isNotEmpty()) {
                globalResults.sortBy { res: ResolverResult -> res.latencyMs }
                adapter.notifyDataSetChanged()
            }
        }

        tvProgress = findViewById(R.id.tv_progress)
        tvPassed = findViewById(R.id.tv_passed)
        btnStopResume = findViewById(R.id.btn_stop_resume)
        btnShare = findViewById(R.id.btn_share)

        adapter = ResolverAdapter(globalResults, isQuickScanner)
        recycler = findViewById(R.id.recycler_results)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
        recycler.adapter = adapter

        btnStopResume.setOnClickListener {
            if (isRunning) {
                stopScanProcess()
            } else {
                startScanLifecycle()
            }
        }

        btnShare.setOnClickListener {
            val successfulResults = globalResults.filter { it.probe == "ok" }
            if (successfulResults.isEmpty()) {
                Toast.makeText(this, "No successful IPs to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val shareText = "VayDNS Scan Results:\n" + successfulResults.joinToString("\n") {
                "${it.ip} (${it.latencyMs} ms)"
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "VayDNS Scan Results")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Resolvers via"))
        }

        btnSave.setOnClickListener {
            if (globalResults.none { it.probe == "ok" }) {
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

                    val filteredAndSorted = globalResults
                        .filter { it.probe == "ok" && it.latencyMs <= maxLatency }
                        .sortedBy { it.latencyMs }

                    if (filteredAndSorted.isEmpty()) {
                        Toast.makeText(this, "No resolvers found under ${maxLatency}ms.", Toast.LENGTH_SHORT).show()
                    } else {
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

        Mobile.initVault(filesDir.absolutePath)

        // 🟢 REGISTRATION REGISTERED ONCE IN ONCREATE
        val filter = IntentFilter().apply {
            addAction("SCANNER_UI_REFRESH")
            addAction("SCANNER_BATCH_FINISHED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiReceiver, filter)
        }

        if (savedInstanceState == null) {
            startScanLifecycle()
        }
    }

    override fun onResume() {
        super.onResume()
        // 🟢 Cleaned: Merged data collected in the background into UI rows instantly
        adapter.notifyDataSetChanged()
        tvProgress.text = "$scannedCount / $totalResolvers"
        tvPassed.text = "$passedCount passed"
    }

    override fun onPause() {
        super.onPause()
        // 🟢 Cleaned: Removed unregisterReceiver() from here to keep scanning active!
    }

    private fun stopScanProcess() {
        if (isRunning) {
            stopService(Intent(this, VayScannerService::class.java))
            btnStopResume.text = "Resume"
            isRunning = false
        }
    }

    private fun startScanLifecycle() {
        currentBatchIndex = 0
        totalResolvers = allResolvers.size
        scannedCount = 0
        passedCount = 0
        globalResults.clear()

        isRunning = true
        btnStopResume.text = "STOP"
        adapter.notifyDataSetChanged()

        startNextBatch()
    }

    private fun startNextBatch() {
        val startIndex = currentBatchIndex * BATCH_SIZE
        if (startIndex >= allResolvers.size) {
            isRunning = false
            btnStopResume.text = "Resume"
            Toast.makeText(this, "Scan complete!", Toast.LENGTH_SHORT).show()
            return
        }

        val endIndex = minOf(startIndex + BATCH_SIZE, allResolvers.size)
        val batch = allResolvers.subList(startIndex, endIndex)
        val resolversString = batch.joinToString(",")

        try {
            val serviceIntent = Intent(this, VayScannerService::class.java).apply {
                putExtra("isDefaultConfig", isDefaultConfig)
                putExtra("configIndex", configIndex)
                putExtra("selectedMode", selectedMode)
                putExtra("domain", domain)
                putExtra("pubkey", pubkey)
                putExtra("resolvers", resolversString)
                putExtra("baseDohUrl", baseDohUrl)
                putExtra("proxyType", proxyType)
                putExtra("tunnelProtocol", tunnelProtocol)
                putExtra("user", user)
                putExtra("pass", pass)
                putExtra("ssMethod", ssMethod)
                putExtra("recordType", recordType)
                putExtra("idleTimeout", idleTimeout)
                putExtra("keepAlive", keepAlive)
                putExtra("clientIdSize", clientIdSize)
                putExtra("mtu", mtu)
                putExtra("workers", workers)
                putExtra("tunnelWait", tunnelWait)
                putExtra("udpTimeout", udpTimeout)
                putExtra("probeTimeout", probeTimeout)
                putExtra("retries", retries)
                putExtra("lightE2EEnabled", lightE2EEnabled)
                putExtra("engineQuickScan", engineQuickScan)
                putExtra("isQuickScanner", isQuickScanner)

                putExtra("totalResolvers", totalResolvers)
                putExtra("scannedSoFar", scannedCount)
                putExtra("passedSoFar", passedCount)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start batch: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        // 🟢 SAFELY REMOVE LINK ONLY WHEN THE ACTIVITY DISAPPEARS COMPLETELY
        try {
            unregisterReceiver(uiReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
        if (isFinishing) {
            stopScanProcess()
        }
    }
}