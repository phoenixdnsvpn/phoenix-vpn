package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WarpScannerActivity : AppCompatActivity() {

    private lateinit var tvProgress: TextView
    private lateinit var tvPassed: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnSet: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: WarpScannerAdapter
    private lateinit var etScanCount: com.google.android.material.textfield.TextInputEditText
    private lateinit var etTargetPort: com.google.android.material.textfield.TextInputEditText
    private lateinit var etWorkers: com.google.android.material.textfield.TextInputEditText
    private lateinit var etHandshakeTimeout: com.google.android.material.textfield.TextInputEditText
    private lateinit var etMaxTimeout: com.google.android.material.textfield.TextInputEditText
    private lateinit var rbUseWarp: MaterialRadioButton
    private lateinit var rbUseMasque: MaterialRadioButton
    private lateinit var cbAllPorts: MaterialCheckBox
    private lateinit var tvPortsList: TextView

    private var isScanning = false
    private val warpResults = mutableListOf<WarpResult>()

    // Vault credentials
    private var privateKey = ""
    private var publicKey = ""
    private var r1 = 0
    private var r2 = 0
    private var r3 = 0
    private var maxWarpIps: Int = 0
    private var maxMasqueIps: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warp_scanner)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_warp_scanner)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        etScanCount = findViewById(R.id.et_warp_scan_count)
        etTargetPort = findViewById(R.id.et_warp_target_port)
        etWorkers = findViewById(R.id.et_warp_workers)
        etHandshakeTimeout = findViewById(R.id.et_warp_handshake_timeout)
        etMaxTimeout = findViewById(R.id.et_warp_max_timeout)
        rbUseWarp = findViewById(R.id.rb_use_warp)
        rbUseMasque = findViewById(R.id.rb_use_masque)
        cbAllPorts = findViewById(R.id.cb_warp_all_ports)
        tvPortsList = findViewById(R.id.tv_warp_ports_list)

        tvProgress = findViewById(R.id.tv_warp_progress)
        tvPassed = findViewById(R.id.tv_warp_passed)
        btnStartStop = findViewById(R.id.btn_warp_start_stop)
        btnSet = findViewById(R.id.btn_warp_set)
        btnShare = findViewById(R.id.btn_warp_share)

        recycler = findViewById(R.id.recycler_warp_results)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(this, LinearLayoutManager.VERTICAL))

        adapter = WarpScannerAdapter(warpResults)
        recycler.adapter = adapter

        loadMaxIpCounts()
        loadVaultCredentials()

        // Protocol switch logic with dynamic workers & port updates
        rbUseWarp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etTargetPort.setText("2408")
                etWorkers.setText("10")
                cbAllPorts.isEnabled = true
                cbAllPorts.isChecked = false
                tvPortsList.text = "WIREGUARD: 2408, 500, 4500, 1701"

                // Explicitly reset to 256 for WireGuard
                var defaultCount = 256

                // Enforce WARP max IP limit just in case it's lower than 256
                if (defaultCount > maxWarpIps && maxWarpIps > 0) {
                    defaultCount = maxWarpIps
                }
                etScanCount.setText(defaultCount.toString())
            }
        }

        rbUseMasque.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etTargetPort.setText("443")
                etWorkers.setText("20")
                cbAllPorts.isEnabled = true
                tvPortsList.text = "MASQUE: 443, 500, 1701, 4443, 4500, 8443, 8095"

                var defaultCount = if (cbAllPorts.isChecked) 512 else 2048

                // Enforce MASQUE max IP limit (Caps it if the backend max is lower)
                if (defaultCount > maxMasqueIps && maxMasqueIps > 0) {
                    defaultCount = maxMasqueIps
                }
                etScanCount.setText(defaultCount.toString())
            }
        }

        // Update count dynamically if user toggles ports while MASQUE is active
        cbAllPorts.setOnCheckedChangeListener { _, isChecked ->
            if (rbUseMasque.isChecked) {
                var defaultCount = if (isChecked) 512 else 2048

                if (defaultCount > maxMasqueIps && maxMasqueIps > 0) {
                    defaultCount = maxMasqueIps
                }
                etScanCount.setText(defaultCount.toString())
            }
        }

        // Restore state across device rotations
        savedInstanceState?.let { bundle ->
            val savedJson = bundle.getString("SAVED_WARP_RESULTS", "[]")
            try {
                val jsonArr = JSONArray(savedJson)
                warpResults.clear()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    warpResults.add(
                        WarpResult(
                            ip = obj.getString("ip"),
                            port = obj.optInt("port", 443),
                            latencyMs = obj.getInt("latency")
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                tvPassed.text = "${warpResults.size} found"
                btnSet.isEnabled = warpResults.isNotEmpty()
                btnShare.isEnabled = warpResults.isNotEmpty()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        btnStartStop.setOnClickListener {
            if (!isScanning) {
                val countStr = etScanCount.text.toString().trim()
                var scanCount = countStr.toIntOrNull() ?: 256
                val portStr = etTargetPort.text.toString().trim()
                val targetPort = portStr.toIntOrNull() ?: if (rbUseMasque.isChecked) 443 else 2408
                val workers = etWorkers.text.toString().trim().toIntOrNull() ?: 10
                val handshakeTimeout = etHandshakeTimeout.text.toString().trim().toIntOrNull() ?: 100
                val maxTimeout = etMaxTimeout.text.toString().trim().toIntOrNull() ?: 1500

                // ENFORCE MAXIMUM IPS OVERRIDE
                val maxAllowed = if (rbUseMasque.isChecked) maxMasqueIps else maxWarpIps
                if (scanCount > maxAllowed && maxAllowed > 0) {
                    scanCount = maxAllowed
                    etScanCount.setText(scanCount.toString()) // Visually update the UI
                    Toast.makeText(this, "Adjusted to max available IPs ($maxAllowed)", Toast.LENGTH_SHORT).show()
                }

                val totalJobsDisplay = if (cbAllPorts.isChecked) {
                    if (rbUseMasque.isChecked) scanCount * 7 else scanCount * 4
                } else {
                    scanCount
                }

                if (rbUseWarp.isChecked && (privateKey.isEmpty() || publicKey.isEmpty())) {
                    Toast.makeText(this, "WARP keys missing! Generate them in WARP Settings first.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                if (scanCount <= 0 || targetPort !in 1..65535) {
                    Toast.makeText(this, "Invalid scan count or target port.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                isScanning = true
                btnStartStop.text = "STOP SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
                tvProgress.text = "0 / $totalJobsDisplay"
                tvPassed.text = "0 found"
                btnSet.isEnabled = false
                btnShare.isEnabled = false
                setInputsEnabled(false)

                warpResults.clear()
                adapter.notifyDataSetChanged()

                startScan(scanCount, targetPort, workers, handshakeTimeout, maxTimeout)
            } else {
                stopScan()
            }
        }

        btnSet.setOnClickListener {
            if (warpResults.isEmpty()) {
                Toast.makeText(this, "No valid IPs found yet.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
                }
                addView(etMaxLatency)
            }
            container.addView(tilMaxLatency)

            val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val rbMerge = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "Merge With Existing IPs"
                textSize = 16f
                isChecked = true
            }
            val rbOverwrite = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = "Overwrite Existing IPs"
                textSize = 16f
            }
            radioGroup.addView(rbMerge)
            radioGroup.addView(rbOverwrite)
            container.addView(radioGroup)

            MaterialAlertDialogBuilder(this)
                .setTitle("Save Clean Endpoints")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val maxLatency = etMaxLatency.text.toString().toIntOrNull() ?: 2000
                    val filteredResults = warpResults.filter { it.latencyMs <= maxLatency }

                    // FIX: Map as IP:Port to preserve the exact successful port found by the scanner
                    val scannedIpsWithPort = filteredResults.map { "${it.ip}:${it.port}" }

                    if (scannedIpsWithPort.isEmpty()) {
                        Toast.makeText(this, "No IPs under $maxLatency ms to save.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
                    val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"
                    val finalJsonArray = JSONArray()
                    val existingTargetCdnIps = mutableListOf<String>()
                    val existingLatencies = mutableMapOf<String, Int>()

                    val currentProtocol = if (rbUseMasque.isChecked) "MASQUE" else "WARP"

                    try {
                        val jsonArray = JSONArray(jsonString)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val ipCdn = obj.optString("cdn", "WARP")
                            val rawIp = obj.optString("ip", "")
                            val ip = CryptoHelper.decrypt(rawIp)
                            val savedPort = obj.optInt("port", if (ipCdn == "MASQUE") 443 else 2408)
                            val lat = obj.optInt("latency", -1)

                            if (ipCdn.equals(currentProtocol, ignoreCase = true)) {
                                if (ip.isNotEmpty()) {
                                    val ipPort = "$ip:$savedPort"
                                    existingTargetCdnIps.add(ipPort)
                                    existingLatencies[ipPort] = lat
                                }
                            } else {
                                finalJsonArray.put(obj)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    val finalTargetIpsToSave = mutableListOf<String>()
                    if (rbMerge.isChecked) {
                        finalTargetIpsToSave.addAll(existingTargetCdnIps)
                        for (ipPort in scannedIpsWithPort) {
                            if (!finalTargetIpsToSave.contains(ipPort)) finalTargetIpsToSave.add(ipPort)
                        }
                    } else {
                        finalTargetIpsToSave.addAll(scannedIpsWithPort)
                    }

                    for ((index, ipPort) in finalTargetIpsToSave.withIndex()) {
                        val fakeIp = ipPort.substringBefore(":")
                        val actualPort = ipPort.substringAfter(":").toIntOrNull() ?: if (rbUseMasque.isChecked) 443 else 2408

                        var realIp = mobile.Mobile.decryptIP(fakeIp)
                        if (realIp.isEmpty()) realIp = fakeIp

                        val obj = JSONObject()
                        obj.put("ip", CryptoHelper.encrypt(realIp))
                        obj.put("isChecked", index == 0)

                        val matchedResult = filteredResults.find { it.ip == fakeIp && it.port == actualPort }
                        val latency = matchedResult?.latencyMs ?: existingLatencies[ipPort] ?: -1

                        obj.put("latency", latency)
                        obj.put("cdn", currentProtocol)

                        // FIX: Save the actual successful port to the Vault, not the default text box input
                        obj.put("port", actualPort)
                        finalJsonArray.put(obj)
                    }

                    vaultPrefs.edit().putString("vault_ips_json", finalJsonArray.toString()).apply()

                    val fastestIpPort = finalTargetIpsToSave.firstOrNull() ?: ""
                    if (fastestIpPort.isNotEmpty()) {
                        val fastestFakeIp = fastestIpPort.substringBefore(":")
                        val fastestPort = fastestIpPort.substringAfter(":").toIntOrNull() ?: if (rbUseMasque.isChecked) 443 else 2408

                        var realFastestIp = mobile.Mobile.decryptIP(fastestFakeIp)
                        if (realFastestIp.isEmpty()) realFastestIp = fastestFakeIp

                        if (rbUseMasque.isChecked) {
                            // FIX: Save to custom_ip and change mode to 'ip', matching WireGuard!
                            val uPrefs = getSharedPreferences("UsqueProfilePrefs", Context.MODE_PRIVATE)
                            uPrefs.edit()
                                .putString("custom_ip", realFastestIp)
                                .putString("port", fastestPort.toString())
                                .putString("connection_mode", "ip")
                                .apply()

                            try {
                                val uFile = File(filesDir, "usque_config.json")
                                if (uFile.exists()) {
                                    val json = JSONObject(uFile.readText())
                                    json.put("endpoint_v4", realFastestIp)
                                    json.put("port", fastestPort)
                                    json.remove("endpoint_v6")
                                    uFile.writeText(json.toString().replace("\\/", "/"))
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        } else {
                            val prefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)
                            prefs.edit()
                                .putString("custom_ip", realFastestIp)
                                .putString("port", fastestPort.toString())
                                .putString("connection_mode", "ip")
                                .apply()

                            try {
                                val keyFile = File(filesDir, "warp_keys.json")
                                if (keyFile.exists()) {
                                    val decrypted = mobile.Mobile.decryptText(keyFile.readText())
                                    val json = JSONObject(decrypted)
                                    json.put("endpoint", realFastestIp)
                                    json.put("port", fastestPort)
                                    keyFile.writeText(mobile.Mobile.encryptText(json.toString()))
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }

                    Toast.makeText(this, "Saved ${scannedIpsWithPort.size} endpoints successfully!", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnShare.setOnClickListener {
            if (warpResults.isEmpty()) {
                Toast.makeText(this, "No successful endpoints to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Combine the encrypted IP and the Port, then Base64 encode the whole string
            val shareText = warpResults.joinToString("\n") { result ->
                // The mobile package already maps (encrypts) the IPs for UI safety
                // val encryptedIp = mobile.Mobile.encryptIP(result.ip)

                // Combine them as EncryptedIP:Port
                val combinedString = "$result.ip:${result.port}"

                // Base64 encode the combined string with no wrapping
                android.util.Base64.encodeToString(
                    combinedString.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                val currentProtocol = if (rbUseMasque.isChecked) "MASQUE" else "WARP"
                putExtra(Intent.EXTRA_SUBJECT, "Scanner Results ($currentProtocol)")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Clean Endpoints via"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val jsonArray = JSONArray()
        for (item in warpResults) {
            val obj = JSONObject()
            obj.put("ip", item.ip)
            obj.put("port", item.port)
            obj.put("latency", item.latencyMs)
            jsonArray.put(obj)
        }
        outState.putString("SAVED_WARP_RESULTS", jsonArray.toString())
    }

    private fun setInputsEnabled(enabled: Boolean) {
        etScanCount.isEnabled = enabled
        etTargetPort.isEnabled = enabled
        etWorkers.isEnabled = enabled
        etHandshakeTimeout.isEnabled = enabled
        etMaxTimeout.isEnabled = enabled
        rbUseWarp.isEnabled = enabled
        rbUseMasque.isEnabled = enabled
        cbAllPorts.isEnabled = enabled && rbUseMasque.isChecked
    }

    private fun loadVaultCredentials() {
        try {
            val keyFile = File(filesDir, "warp_keys.json")
            if (keyFile.exists()) {
                val decrypted = mobile.Mobile.decryptText(keyFile.readText())
                val json = JSONObject(decrypted)
                privateKey = json.optString("private_key", "")
                publicKey = json.optString("public_key", "")
                val reservedStr = json.optString("reserved_bytes", "[0,0,0]")
                val arr = JSONArray(reservedStr)
                r1 = arr.optInt(0, 0)
                r2 = arr.optInt(1, 0)
                r3 = arr.optInt(2, 0)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private var scanThread: Thread? = null

    private fun startScan(count: Int, port: Int, workers: Int, handshakeTimeout: Int, maxTimeout: Int) {
        val engineType = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE).getString("tun_engine", "xray") ?: "xray"
        val isMasque = rbUseMasque.isChecked
        val useAllPorts = cbAllPorts.isChecked

        val totalJobsDisplay = if (useAllPorts) {
            if (isMasque) count * 7 else count * 4
        } else {
            count
        }

        scanThread = Thread {
            try {
                val resultJson = if (isMasque) {
                    mobile.Mobile.runNativeMasqueScanner(
                        count.toLong(),
                        "",
                        port.toLong(),
                        useAllPorts,
                        workers.toLong(),
                        handshakeTimeout.toLong(),
                        maxTimeout.toLong()
                    )
                } else {
                    mobile.Mobile.runWarpScanner(
                        count.toLong(),
                        "",
                        port.toLong(),
                        useAllPorts,
                        privateKey,
                        publicKey,
                        r1.toLong(),
                        r2.toLong(),
                        r3.toLong(),
                        engineType,
                        workers.toLong()
                    )
                }

                val safeJson = if (resultJson.isNullOrBlank() || resultJson == "null") "[]" else resultJson
                val resultsArray = JSONArray(safeJson)

                for (i in 0 until resultsArray.length()) {
                    if (!isScanning) break
                    val item = resultsArray.getJSONObject(i)
                    val realIp = item.getString("ip")
                    val latency = item.getLong("latency_ms")
                    val resultPort = item.optInt("port", port)

                    val fakeIp = mobile.Mobile.encryptIP(realIp)
                    warpResults.add(WarpResult(fakeIp, resultPort, latency.toInt()))
                    warpResults.sortBy { z -> z.latencyMs }

                    runOnUiThread {
                        tvProgress.text = "${i + 1} / $totalJobsDisplay"
                        tvPassed.text = "${warpResults.size} found"
                        adapter.notifyDataSetChanged()
                        btnSet.isEnabled = warpResults.isNotEmpty()
                        btnShare.isEnabled = warpResults.isNotEmpty()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            runOnUiThread {
                isScanning = false
                btnStartStop.text = "START SCAN"
                btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
                setInputsEnabled(true)
            }
        }
        scanThread?.start()
    }

    private fun loadMaxIpCounts() {
        try {
            val jsonStr = mobile.Mobile.getCloudIPCounts()
            val jsonObj = JSONObject(jsonStr)
            maxWarpIps = jsonObj.optInt("wireguard", 1000000) // Fallback limit
            maxMasqueIps = jsonObj.optInt("masque", 508) // Fallback limit
        } catch (e: Exception) {
            e.printStackTrace()
            maxWarpIps = 1000000
            maxMasqueIps = 508
        }
    }

    private fun stopScan() {
        isScanning = false
        scanThread?.interrupt()
        btnStartStop.text = "START SCAN"
        btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
        setInputsEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            stopScan()
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}