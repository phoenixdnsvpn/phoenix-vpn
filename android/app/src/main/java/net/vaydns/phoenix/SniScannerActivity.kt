package net.vaydns.phoenix

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import mobile.Mobile
import org.json.JSONArray

class SniScannerActivity : AppCompatActivity() {

    private lateinit var spinnerConfig: Spinner
    private lateinit var spinnerConfigType: Spinner
    private lateinit var etCustomSni: EditText
    private lateinit var etCustomPort: EditText
    private lateinit var btnCheckCustom: Button
    private lateinit var btnCheckInternal: Button
    private lateinit var recycler: RecyclerView

    private val directConfigs = mutableListOf<Config>()
    private val resultsList = mutableListOf<SniResultItem>()
    private lateinit var adapter: SniScannerAdapter
    private lateinit var tvAdvancedToggle: TextView
    private lateinit var layoutAdvancedOptions: android.widget.LinearLayout
    private var isAdvancedExpanded = false
    private var isScanning = false
    private lateinit var etCustomIp: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sni_scanner)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_sni_scanner)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        spinnerConfig = findViewById(R.id.spinner_sni_config)
        spinnerConfigType = findViewById(R.id.spinner_config_type)
        etCustomSni = findViewById(R.id.et_custom_sni)
        etCustomPort = findViewById(R.id.et_custom_port)
        btnCheckCustom = findViewById(R.id.btn_check_current_sni)
        btnCheckInternal = findViewById(R.id.btn_check_internal_snis)
        recycler = findViewById(R.id.recycler_sni_scanner)

        tvAdvancedToggle = findViewById(R.id.tv_advanced_toggle)
        layoutAdvancedOptions = findViewById(R.id.layout_advanced_options)

        etCustomIp = findViewById(R.id.et_custom_ip)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = SniScannerAdapter(resultsList)
        recycler.adapter = adapter

        loadDirectConfigs()

        val typeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("VLESS-TCP", "VLESS-xHTTP", "Hysteria2")
        )
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerConfigType.adapter = typeAdapter

        btnCheckCustom.setOnClickListener {
            if (isScanning) return@setOnClickListener
            val customSni = etCustomSni.text.toString().trim()
            if (customSni.isEmpty()) {
                Toast.makeText(this, "Please enter an SNI", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startSingleScan(customSni)
        }

        tvAdvancedToggle.setOnClickListener {
            isAdvancedExpanded = !isAdvancedExpanded
            if (isAdvancedExpanded) {
                layoutAdvancedOptions.visibility = android.view.View.VISIBLE
                tvAdvancedToggle.text = "Advanced Options ▲"
            } else {
                layoutAdvancedOptions.visibility = android.view.View.GONE
                tvAdvancedToggle.text = "Advanced Options ▼"
            }
        }

        btnCheckInternal.setOnClickListener {
            if (isScanning) return@setOnClickListener
            startInternalScan()
        }
    }

    private fun loadDirectConfigs() {
        val allConfigs = ConfigEditorActivity.loadAllConfigs(this) + DefaultConfigProvider.getDefaultConfigs(this)

        // Filter out purely DNS tunnels AND standard CDN protocols (VLESS-WS/gRPC/Upgrade)
        // Keep ONLY SNI-spoofing protocols like REALITY and Hysteria2
        directConfigs.clear()
        directConfigs.addAll(allConfigs.filter { config ->
            val nativeIndex = if (config.isDefault) config.id.removePrefix("default_").toLongOrNull() ?: 0L else -1L
            val configType = if (config.isDefault) Mobile.getDefaultConfigType(nativeIndex).lowercase() else "vaydns"
            val protocol = config.protocol.lowercase()

            // Strictly check for SNI-dependent protocols
            val isSniDependent = protocol == "reality-tcp" ||
                    protocol == "reality-xhttp" ||
                    protocol == "hysteria2" ||
                    configType.contains("reality") ||
                    configType.contains("hysteria")

            isSniDependent && !config.freeScanner
        })

        val configNames = directConfigs.map { it.name }
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, configNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerConfig.adapter = spinnerAdapter
    }

    private fun getSelectedConfigIndex(): Long {
        if (directConfigs.isEmpty()) return -1L
        val selectedPosition = spinnerConfig.selectedItemPosition
        val selectedConfig = directConfigs[selectedPosition]
        return if (selectedConfig.isDefault) {
            selectedConfig.id.removePrefix("default_").toLongOrNull() ?: -1L
        } else {
            -1L // Custom configurations return -1
        }
    }

    private fun lockUI() {
        isScanning = true
        btnCheckCustom.isEnabled = false
        btnCheckInternal.isEnabled = false
        resultsList.clear()
        adapter.notifyDataSetChanged()
    }

    private fun unlockUI() {
        isScanning = false
        btnCheckCustom.isEnabled = true
        btnCheckInternal.isEnabled = true
    }

    private fun getDnsParams(): Pair<String, Boolean> {
        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        var globalDnsServer = tunnelPrefs.getString("global_dns_server", "")?.trim() ?: ""
        if (globalDnsServer.isEmpty()) globalDnsServer = "1.1.1.1"
        val getServerIpFromDomain = tunnelPrefs.getBoolean("get_server_ip_from_domain", false)
        return Pair(globalDnsServer, getServerIpFromDomain)
    }

    // --- SCAN 1: Custom SNI ---
    private fun startSingleScan(sni: String) {

        val customIp = etCustomIp.text.toString().trim()

        // Validate IPv4 format
        if (customIp.isNotEmpty() && !android.util.Patterns.IP_ADDRESS.matcher(customIp).matches()) {
            Toast.makeText(this, "Invalid IPv4 address format", Toast.LENGTH_SHORT).show()
            return
        }

        lockUI()
        btnCheckCustom.text = "SCANNING..."

        val configIndex = getSelectedConfigIndex()
        val configType = spinnerConfigType.selectedItem.toString()
        val serverPort = etCustomPort.text.toString().trim().toLongOrNull() ?: 0L
        val (globalDnsServer, getServerIpFromDomain) = getDnsParams()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Call the single SNI Go tester with new parameters
                val resultStr = Mobile.checkSniHealth(sni, configIndex, configType, globalDnsServer, getServerIpFromDomain, serverPort, customIp)

                withContext(Dispatchers.Main) {
                    if (resultStr.startsWith("SUCCESS")) {
                        val parts = resultStr.split("|")
                        val latency = if (parts.size >= 2) parts[1].toLongOrNull() ?: 0L else 0L
                        val msg = if (parts.size >= 3) parts[2] else ""
                        resultsList.add(SniResultItem(sni, true, latency, msg))
                    } else {
                        val msg = resultStr.substringAfter("|")
                        resultsList.add(SniResultItem(sni, false, 0L, msg))
                    }

                    adapter.notifyDataSetChanged()
                    btnCheckCustom.text = "CHECK CUSTOM"
                    unlockUI()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SniScannerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    btnCheckCustom.text = "CHECK CUSTOM"
                    unlockUI()
                }
            }
        }
    }

    // --- SCAN 2: Internal SNI Pool ---
    private fun startInternalScan() {

        val customIp = etCustomIp.text.toString().trim()

        // Validate IPv4 format
        if (customIp.isNotEmpty() && !android.util.Patterns.IP_ADDRESS.matcher(customIp).matches()) {
            Toast.makeText(this, "Invalid IPv4 address format", Toast.LENGTH_SHORT).show()
            return
        }

        lockUI()
        btnCheckInternal.text = "SCANNING..."

        val configIndex = getSelectedConfigIndex()
        val configType = spinnerConfigType.selectedItem.toString()
        val serverPort = etCustomPort.text.toString().trim().toLongOrNull() ?: 0L
        val (globalDnsServer, getServerIpFromDomain) = getDnsParams()
        val maxWorkers = 5L

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Call the bulk Go tester with new parameters
                val jsonResultStr = Mobile.checkAllSniPool(configIndex, configType, globalDnsServer, getServerIpFromDomain, serverPort, customIp, maxWorkers)

                withContext(Dispatchers.Main) {
                    parseInternalResults(jsonResultStr)
                    btnCheckInternal.text = "CHECK INTERNAL"
                    unlockUI()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SniScannerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    btnCheckInternal.text = "CHECK INTERNAL"
                    unlockUI()
                }
            }
        }
    }

    private fun parseInternalResults(jsonStr: String) {
        try {
            val jsonArray = JSONArray(jsonStr)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val index = item.getInt("sni_index")
                val isSuccess = item.getBoolean("success")
                val latency = item.getLong("latency_ms")
                val message = item.getString("message")

                // Mask internal SNIs to protect server infrastructure (SNI-1, SNI-2, etc.)
                val maskedName = "SNI-${index + 1}"

                resultsList.add(SniResultItem(maskedName, isSuccess, latency, message))
            }
            adapter.notifyDataSetChanged()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to parse SNI pool results.", Toast.LENGTH_SHORT).show()
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