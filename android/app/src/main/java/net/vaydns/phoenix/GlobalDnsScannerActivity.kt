package net.vaydns.phoenix

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import mobile.Mobile
import org.json.JSONArray

class GlobalDnsScannerActivity : AppCompatActivity() {

    private lateinit var tvScannedCount: TextView
    private lateinit var tvPassedCount: TextView
    private lateinit var btnStart: Button
    private lateinit var recycler: RecyclerView

    private val resultsList = mutableListOf<GlobalDnsResult>()
    private lateinit var adapter: GlobalDnsAdapter
    private var isScanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_dns)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_global_dns)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvScannedCount = findViewById(R.id.tv_scanned_count)
        tvPassedCount = findViewById(R.id.tv_passed_count)
        btnStart = findViewById(R.id.btn_start_global_scan)
        recycler = findViewById(R.id.recycler_global_dns)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = GlobalDnsAdapter(resultsList)
        recycler.adapter = adapter

        btnStart.setOnClickListener {
            if (isScanning) return@setOnClickListener
            startScan()
        }
    }

    private fun startScan() {
        isScanning = true
        btnStart.text = "SCANNING..."
        btnStart.isEnabled = false

        // Reset UI
        resultsList.clear()
        adapter.notifyDataSetChanged()

        tvScannedCount.text = "Scanning..."
        tvPassedCount.text = "0 passed"

        // Hardcoded to DoH for absolute DPI evasion!
        val selectedMode = "doh"

        // Run the heavy Go networking task on an IO thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Call the native Go engine (Mode, Timeout 3000ms, 20 Workers)
                val jsonResultStr = Mobile.runGlobalDNSScanner(selectedMode, 3000, 20)

                withContext(Dispatchers.Main) {
                    parseAndDisplayResults(jsonResultStr)

                    isScanning = false
                    btnStart.text = "START SCAN"
                    btnStart.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GlobalDnsScannerActivity, "Scan Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    isScanning = false
                    btnStart.text = "START SCAN"
                    btnStart.isEnabled = true
                }
            }
        }
    }

    private fun parseAndDisplayResults(jsonStr: String) {
        try {
            val jsonArray = JSONArray(jsonStr)
            var passedCount = 0

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                // Safety check: skip errors returned by Go
                if (obj.has("error")) {
                    Toast.makeText(this, obj.getString("error"), Toast.LENGTH_LONG).show()
                    return
                }

                val latency = obj.getLong("latency_ms")

                // Only count it as passed if the latency is greater than 0
                if (latency > 0) {
                    passedCount++
                }

                resultsList.add(
                    GlobalDnsResult(
                        providerName = obj.getString("provider_name"),
                        address = obj.getString("address"),
                        mode = obj.getString("mode"),
                        latencyMs = latency
                    )
                )
            }

            // The exact number of IP addresses tested is the length of the JSON array
            val totalIpsScanned = jsonArray.length()

            // Update Header Counters
            tvScannedCount.text = "$totalIpsScanned / $totalIpsScanned Scanned"
            tvPassedCount.text = "$passedCount passed"

            adapter.notifyDataSetChanged()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to parse results.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}