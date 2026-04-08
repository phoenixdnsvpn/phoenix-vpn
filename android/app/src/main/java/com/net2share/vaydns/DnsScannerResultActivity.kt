package com.net2share.vaydns

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
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
    private lateinit var btnCopy: ImageButton
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
        val btnSort = findViewById<ImageButton>(R.id.btn_sort) // Initialize sort button

        // The new way to handle the back arrow click
        toolbar.setNavigationOnClickListener {
            stopScanProcess()
            finish()
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
        // Initialize Views
        tvProgress = findViewById(R.id.tv_progress)
        tvPassed = findViewById(R.id.tv_passed)
        btnStopResume = findViewById(R.id.btn_stop_resume)
        btnCopy = findViewById(R.id.btn_copy)
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
        val isConservative = intent.getBooleanExtra("CONSERVATIVE", false)
        totalResolvers = intent.getIntExtra("TOTAL_RESOLVERS", 500)
        tvProgress.text = "0 / $totalResolvers"
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
                startScan(domain, pubkey, resolversCommaSeparated, proxyType, isConservative)
            }
        }

        // Copy Logic
// Copy Logic
        btnCopy.setOnClickListener {
            if (results.isEmpty()) {
                Toast.makeText(this, "No results to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 1. Filter for successful probes
            // 2. Map to a string format: "1.2.3.4 (150 ms)"
            val copyText = results
                .filter { it.probe == "ok" }
                .joinToString("\n") { "${it.ip} (${it.latencyMs} ms)" }

            if (copyText.isEmpty()) {
                Toast.makeText(this, "No successful IPs to copy", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("VayDNS Scanned Resolvers", copyText)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(this, "Copied results to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        // Start initial scan
        startScan(domain, pubkey, resolversCommaSeparated, proxyType, isConservative)
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

    private fun startScan(domain: String, pubkey: String, resolvers: String,
                          proxyType: String, isConservative: Boolean) {

        scanCallback = object : ScanResultCallback {
            override fun onResult(jsonResult: String) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread // Safety check

                    try {
                        val json = JSONObject(jsonResult)
                        val ip = json.getString("resolver")
                        val latency = json.optInt("latency_ms", 99999)
                        val probe = json.optString("probe", "ok")

                        val item = ResolverResult(ip, latency, probe)
                        results.add(item)

                        if (probe == "ok") {
                            passedCount++
                        }
                        adapter.notifyItemInserted(results.size - 1)
//                        recycler.scrollToPosition(results.size - 1)

                        tvProgress.text = "${results.size} / $totalResolvers"
                        tvPassed.text = "$passedCount passed"

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
                "none",
                "none",
                isConservative,
                scanCallback!!
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        stopScanProcess() // Safety kill
        super.onDestroy()
    }
}