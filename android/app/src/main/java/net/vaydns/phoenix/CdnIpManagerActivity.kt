package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import androidx.lifecycle.lifecycleScope
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ImageButton

class CdnIpManagerActivity : AppCompatActivity() {

    // Added 'port' to the data class to match the scanner's new output
    data class CfIpEntry(var address: String, var isChecked: Boolean, var latencyMs: Int = -1, var cdn: String = "CloudX", var port: Int = 443)

    private val ipEntries = mutableListOf<CfIpEntry>()
    private lateinit var adapter: CdnIpAdapter
    private var isCheckAllActive = true

    private var targetCdn = "CloudX"
    private var targetPort = 443
    var protocol: String = "vless-ws"

    private val hiddenOtherCdnIps = mutableListOf<org.json.JSONObject>()

    // UI State Trackers to handle cascading spinner updates cleanly
    private var isRevertingCdn = false
    private var isRevertingPort = false
    private var lastCdnIndex = 0
    private var lastPortIndex = 0
    private val portList = mutableListOf<String>()
    private var targetProtocol = "vless-ws"
    private var isRevertingProtocol = false
    private var lastProtocolIndex = 0
    private val protocolList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cdn_manager)

        targetCdn = intent.getStringExtra("TARGET_CDN") ?: "CloudX"
        targetPort = intent.getIntExtra("TARGET_PORT", 443)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_cf_manager)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "$targetCdn IP Manager"

        val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
                val totalValidIps = ipEntries.count { it.address.isNotBlank() }

                if (validCheckedCount > 1) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@CdnIpManagerActivity)
                        .setTitle("Multiple IPs Selected")
                        .setMessage("Please check exactly ONE IP to act as your active connection before exiting.\n\nلطفاً قبل از خروج، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Discard / لغو") { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .show()
                    return
                }

                if (validCheckedCount == 0 && totalValidIps > 0) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@CdnIpManagerActivity)
                        .setTitle("No IP Selected")
                        .setMessage("Please check exactly ONE IP to act as your active connection before exiting.\n\nلطفاً قبل از خروج، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Discard / لغو") { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .show()
                    return
                }

                saveIps()
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val recycler = findViewById<RecyclerView>(R.id.recycler_cf_ips)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CdnIpAdapter(ipEntries) { }
        recycler.adapter = adapter

        // --- SPINNER INITIALIZATION ---
        val spinnerCdn = findViewById<android.widget.Spinner>(R.id.spinner_manager_cdn)
        val spinnerPort = findViewById<android.widget.Spinner>(R.id.spinner_manager_port)
        val spinnerProtocol = findViewById<android.widget.Spinner>(R.id.spinner_manager_protocol)

        val cdnList = mutableListOf<String>()
        val cdnCount = mobile.Mobile.getCdnCount()

        for (i in 0 until cdnCount) {
            val name = mobile.Mobile.getCdnName(i)
            if (name.isNotEmpty()) cdnList.add(name)
        }
        if (cdnList.isEmpty()) cdnList.addAll(listOf("CloudX", "CloudY", "CloudZ", "CloudV"))

        val spinnerCdnAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, cdnList)
        spinnerCdnAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCdn.adapter = spinnerCdnAdapter

        lastCdnIndex = cdnList.indexOf(targetCdn).takeIf { it >= 0 } ?: 0
        spinnerCdn.setSelection(lastCdnIndex)

        // Pre-populate the port list based on the initially selected CDN
        updatePortSpinner(spinnerPort, targetCdn, targetPort)
        updateProtocolSpinner(spinnerProtocol, targetCdn, targetProtocol)

        spinnerCdn.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (isRevertingCdn) {
                    isRevertingCdn = false
                    return
                }
                if (position == lastCdnIndex) return // No change

                val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
                if (validCheckedCount > 1) {
                    Toast.makeText(this@CdnIpManagerActivity, "Cannot switch CDNs: Multiple IPs checked.", Toast.LENGTH_SHORT).show()
                    isRevertingCdn = true
                    spinnerCdn.setSelection(lastCdnIndex)
                    return
                }

                saveIps() // Save before leaving
                targetCdn = cdnList[position]
                lastCdnIndex = position
                supportActionBar?.title = "$targetCdn IP Manager"

                // Cascade update down to the Port Spinner
                updatePortSpinner(spinnerPort, targetCdn, targetPort)
                updateProtocolSpinner(spinnerProtocol, targetCdn, targetProtocol)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        spinnerPort.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (isRevertingPort) {
                    isRevertingPort = false
                    return
                }
                if (position == lastPortIndex) return // No change

                val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
                if (validCheckedCount > 1) {
                    Toast.makeText(this@CdnIpManagerActivity, "Cannot switch Ports: Multiple IPs checked.", Toast.LENGTH_SHORT).show()
                    isRevertingPort = true
                    spinnerPort.setSelection(lastPortIndex)
                    return
                }

                saveIps() // Save before leaving
                targetPort = portList[position].toIntOrNull() ?: 443
                lastPortIndex = position

                loadSavedIps()
                adapter.notifyDataSetChanged()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (isRevertingProtocol) {
                    isRevertingProtocol = false
                    return
                }
                if (position == lastProtocolIndex) return

                val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
                if (validCheckedCount > 1) {
                    Toast.makeText(this@CdnIpManagerActivity, "Cannot switch Protocols: Multiple IPs checked.", Toast.LENGTH_SHORT).show()
                    isRevertingProtocol = true
                    spinnerProtocol.setSelection(lastProtocolIndex)
                    return
                }

                saveIps()
                targetProtocol = protocolList[position]
                lastProtocolIndex = position

                loadSavedIps()
                adapter.notifyDataSetChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // TOOLBAR SORT BUTTON
        findViewById<ImageButton>(R.id.btn_sort_cdn).setOnClickListener {
            if (ipEntries.isEmpty()) return@setOnClickListener

            // Sort IPs: Lowest valid latency first. Push -1 (untested/failed) to the absolute bottom.
            ipEntries.sortBy { if (it.latencyMs <= 0) Int.MAX_VALUE else it.latencyMs }

            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Sorted by fastest latency", Toast.LENGTH_SHORT).show()
        }

        // TOOLBAR PING BUTTON
        findViewById<ImageButton>(R.id.btn_ping_cdn).setOnClickListener {
            val checkedEntries = ipEntries.filter { it.isChecked && it.address.isNotBlank() }

            if (checkedEntries.isEmpty()) {
                Toast.makeText(this, "Please check the IPs you want to ping.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // --- START VISUAL LOADING STATE ---
            val progressSpinner = findViewById<ProgressBar>(R.id.progress_ping_cdn)
            val btnSort = findViewById<ImageButton>(R.id.btn_sort_cdn)

            it.isEnabled = false
            btnSort.isEnabled = false // Prevent sorting while scanning
            it.visibility = View.INVISIBLE
            progressSpinner.visibility = View.VISIBLE

            Toast.makeText(this, "Pinging ${checkedEntries.size} IPs...", Toast.LENGTH_SHORT).show()

            checkedEntries.forEach { entry -> entry.latencyMs = -1 }
            adapter.notifyDataSetChanged()

            val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
            var globalDnsServer = tunnelPrefs.getString("global_dns_server", "")?.trim() ?: ""
            if (globalDnsServer.isEmpty()) {
                globalDnsServer = "1.1.1.1"
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val ipsCSV = checkedEntries.joinToString(",") { entry ->
                    mobile.Mobile.decryptIP(entry.address)
                }

                val resultJson = mobile.Mobile.pingCloudLayer7Wrapper(
                    ipsCSV,
                    targetPort.toLong(),
                    targetProtocol,
                    globalDnsServer,
                    true,
                    targetCdn
                )

                withContext(Dispatchers.Main) {
                    // --- END VISUAL LOADING STATE ---
                    it.isEnabled = true
                    btnSort.isEnabled = true
                    it.visibility = View.VISIBLE
                    progressSpinner.visibility = View.GONE

                    try {
                        val jsonArr = org.json.JSONArray(resultJson)
                        var successCount = 0

                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.getJSONObject(i)
                            val returnedRealIp = obj.getString("ip")
                            val port = obj.getInt("port")
                            val latency = obj.getInt("latency_ms")

                            val mappedIp = mobile.Mobile.encryptIP(returnedRealIp)
                            val entry = ipEntries.find { e -> e.address == mappedIp && e.port == port }

                            if (entry != null) {
                                entry.latencyMs = latency
                                successCount++
                            }
                        }
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this@CdnIpManagerActivity, "Ping complete! $successCount successful.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@CdnIpManagerActivity, "Ping failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<android.widget.ImageButton>(R.id.btn_import_cf).setOnClickListener {
            showImportDialog()
        }

        findViewById<android.widget.ImageButton>(R.id.btn_toggle_all_cf).setOnClickListener {
            ipEntries.forEach { it.isChecked = isCheckAllActive }
            isCheckAllActive = !isCheckAllActive

            // Replaced the text update with a Toast for the new ImageButton
            val actionText = if (isCheckAllActive) "Unchecked All" else "Checked All"
            Toast.makeText(this, actionText, Toast.LENGTH_SHORT).show()

            adapter.notifyDataSetChanged()
        }

        findViewById<android.widget.ImageButton>(R.id.btn_delete_cf).setOnClickListener {
            ipEntries.removeAll { it.isChecked }
            if (ipEntries.isEmpty()) {
                ipEntries.add(CfIpEntry("", false, -1, targetCdn, targetPort))
            }
            adapter.notifyDataSetChanged()
        }

        findViewById<android.widget.ImageButton>(R.id.btn_export_cf).setOnClickListener {
            // Grab ONLY the checked IPs
            val checkedIps = ipEntries.filter { it.address.isNotBlank() && it.isChecked }

            if (checkedIps.isEmpty()) {
                Toast.makeText(this, "No IPs selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val validIpsText = checkedIps.joinToString("\n") { entry ->
                // 1. Clean and encrypt the IP
                val cleanIp = if (entry.address.contains(":")) entry.address.substringBefore(":") else entry.address
                val encryptedIp = mobile.Mobile.encryptIP(cleanIp)

                // 2. Combine as EncryptedIP:Port:CDN
                val combinedString = "$encryptedIp:${entry.port}:${entry.cdn}"

                // 3. Encode the entire combined string in Base64
                android.util.Base64.encodeToString(
                    combinedString.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Saved $targetCdn IPs")
                putExtra(Intent.EXTRA_TEXT, validIpsText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share IPs via"))
        }

        findViewById<android.widget.ImageButton>(R.id.btn_save_cf).setOnClickListener {
            saveIps()
            Toast.makeText(this, "Vault Saved!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateProtocolSpinner(spinnerProtocol: android.widget.Spinner, cdn: String, preferredProtocol: String) {
        val protosCsv = mobile.Mobile.getCdnProtocolsCsv(cdn)
        protocolList.clear()
        if (protosCsv.isNotEmpty()) {
            protocolList.addAll(protosCsv.split(",").map { it.trim() })
        } else {
            protocolList.add("vless-ws")
        }

        val protocolAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, protocolList)
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        isRevertingProtocol = true
        spinnerProtocol.adapter = protocolAdapter

        var newIndex = protocolList.indexOf(preferredProtocol)
        if (newIndex == -1) newIndex = protocolList.indexOf("vless-ws")
        if (newIndex == -1) newIndex = 0

        targetProtocol = protocolList[newIndex]
        lastProtocolIndex = newIndex

        isRevertingProtocol = true
        spinnerProtocol.setSelection(newIndex)

        loadSavedIps()
        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun updatePortSpinner(spinnerPort: android.widget.Spinner, cdn: String, preferredPort: Int) {
        val portsCsv = mobile.Mobile.getCdnPortsCsv(cdn)
        portList.clear()
        if (portsCsv.isNotEmpty()) {
            portList.addAll(portsCsv.split(",").map { it.trim() })
        } else {
            portList.add("443")
        }

        val portAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, portList)
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // Suppress onItemSelected trigger while setting adapter to prevent duplicate triggers
        isRevertingPort = true
        spinnerPort.adapter = portAdapter

        // Find the index to select
        val prefPortStr = preferredPort.toString()
        var newIndex = portList.indexOf(prefPortStr)
        if (newIndex == -1) newIndex = portList.indexOf("443") // Fallback to 443
        if (newIndex == -1) newIndex = 0 // Absolute fallback

        targetPort = portList[newIndex].toIntOrNull() ?: 443
        lastPortIndex = newIndex

        isRevertingPort = true // Suppress programmatic selection event
        spinnerPort.setSelection(newIndex)

        // Finally, load the IPs for the freshly updated CDN & Port combination
        loadSavedIps()
        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadSavedIps() {
        ipEntries.clear()
        hiddenOtherCdnIps.clear()

        val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("vault_ips_json", "[]") ?: "[]"

        try {
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawIp = obj.getString("ip")
                val ip = CryptoHelper.decrypt(rawIp)
                val isChecked = obj.getBoolean("isChecked")
                val latency = obj.optInt("latency", -1)

                val cdn = obj.optString("cdn", "CloudX")
                val port = obj.optInt("port", 443) // Extract saved port

                // STRICT PARTITION: Must match both the selected CDN AND the selected Port
                if (cdn.equals(targetCdn, ignoreCase = true) && port == targetPort) {
                    if (ip.isNotBlank()) {
                        ipEntries.add(CfIpEntry(ip, isChecked, latency, cdn, port))
                    }
                } else {
                    hiddenOtherCdnIps.add(obj)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (ipEntries.isNotEmpty() && ipEntries.none { it.isChecked }) {
            ipEntries.first().isChecked = true
        }

        if (ipEntries.isEmpty()) {
            ipEntries.add(CfIpEntry("", true, -1, targetCdn, targetPort))
        }
    }

    private fun saveIps() {
        val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()

        // Preserve all hidden IPs
        for (hiddenObj in hiddenOtherCdnIps) {
            jsonArray.put(hiddenObj)
        }

        // Save active IPs with their current Target CDN and Port
        for (entry in ipEntries) {
            if (entry.address.isNotBlank()) {
                val obj = org.json.JSONObject()
                obj.put("ip", CryptoHelper.encrypt(entry.address))
                obj.put("isChecked", entry.isChecked)
                obj.put("latency", entry.latencyMs)
                obj.put("cdn", targetCdn)
                obj.put("port", targetPort)
                jsonArray.put(obj)
            }
        }

        prefs.edit().putString("vault_ips_json", jsonArray.toString()).apply()
    }

    private fun showImportDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Paste IPs (e.g. 1.1.1.1:443 or Base64)...\nآی‌پی‌ها را اینجا جای‌گذاری کنید..."
            setLines(5)
            setPadding(45, 45, 45, 45)
            gravity = android.view.Gravity.TOP
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Import $targetCdn IPs")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val parsed = input.text.toString()
                    .split(Regex("[\\s,;]+"))
                    .map { it.replace("\"", "").trim() }
                    .filter { it.isNotEmpty() }

                val mismatchedPorts = mutableSetOf<Int>()
                val mismatchedCdns = mutableSetOf<String>()
                val pendingEntries = mutableListOf<CfIpEntry>()

                // NEW: Track the exact CDN and Port combinations found in the import data
                val importedDetails = mutableSetOf<String>()

                for (token in parsed) {
                    var finalFakeIp = ""
                    var parsedPort = targetPort
                    var parsedCdn = targetCdn

                    // SMART ROUTING: BASE64 vs PLAINTEXT
                    // Base64 strings of "MappedIP:Port:CDN" will NEVER contain a dot (.), colon (:), or bracket ([)
                    if (!token.contains(".") && !token.contains(":") && !token.contains("[")) {
                        try {
                            // Decode Base64 back into "MappedIP:Port:CDN"
                            val decodedBytes = android.util.Base64.decode(token, android.util.Base64.DEFAULT)
                            val mappedString = String(decodedBytes, Charsets.UTF_8)

                            if (mappedString.contains(":")) {
                                val parts = mappedString.split(":")
                                finalFakeIp = parts[0] // Store the Fake IP directly
                                if (parts.size >= 2) {
                                    val extractedPort = parts[1].toIntOrNull()
                                    if (extractedPort != null) parsedPort = extractedPort
                                }
                                if (parts.size >= 3) {
                                    parsedCdn = parts.drop(2).joinToString(":")
                                } else {
                                    parsedCdn = "UNKNOWN"
                                }
                            }
                        } catch (e: Exception) {
                            // Decoding failed or garbage input, skip it
                            continue
                        }
                    } else {
                        // It is a user-provided Plain IP
                        var rawIpPart = token

                        // EXTRACT PORT (Safely handles IPv4:Port and [IPv6]:Port)
                        if (token.contains(":")) {
                            val lastColonIndex = token.lastIndexOf(":")
                            val portCandidate = token.substring(lastColonIndex + 1)

                            if (portCandidate.toIntOrNull() != null) {
                                val basePart = token.substring(0, lastColonIndex)
                                if (basePart.contains(".")) {
                                    rawIpPart = basePart
                                    parsedPort = portCandidate.toInt()
                                } else if (token.startsWith("[")) {
                                    rawIpPart = basePart.removePrefix("[").removeSuffix("]")
                                    parsedPort = portCandidate.toInt()
                                } else {
                                    rawIpPart = token // Plain IPv6 address with no port
                                }
                            } else if (token.startsWith("[")) {
                                rawIpPart = token.removePrefix("[").removeSuffix("]")
                            }
                        }

                        // Sanity check the Plain Output
                        if (isValidIp(rawIpPart)) {
                            // Instantly encrypt the Plain IP into a Fake IP for UI safety
                            finalFakeIp = mobile.Mobile.encryptIP(rawIpPart)

                            // A manual IP has no CDN metadata, so it inherits the current Target CDN.
                            // We assign it here explicitly for tracking.
                            parsedCdn = targetCdn
                        }
                    }

                    // SAVE TO PENDING LIST (If valid)
                    val cleanFinalIp = finalFakeIp.trim()
                    if (cleanFinalIp.isNotEmpty()) {

                        // NEW: Add the discovered metadata to our tracking list
                        importedDetails.add("CDN: $parsedCdn | Port: $parsedPort")

                        // Track mismatches before adding
                        if (parsedPort != targetPort) mismatchedPorts.add(parsedPort)
                        if (!parsedCdn.equals(targetCdn, ignoreCase = true)) mismatchedCdns.add(parsedCdn)

                        pendingEntries.add(CfIpEntry(cleanFinalIp, false, -1, parsedCdn, parsedPort))
                    }
                }

                // REJECT IF PORT OR CDN MISMATCHES THE CURRENT UI
                if (mismatchedPorts.isNotEmpty() || mismatchedCdns.isNotEmpty()) {

                    // NEW: Format the exact details we found in the import string
                    val detailsStr = importedDetails.joinToString("\n")

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Target Mismatch / مغایرت اطلاعات")
                        .setMessage("You are trying to import IPs that belong to a different CDN or Port than what is currently selected.\n\n" +
                                "Imported Data Contains:\n$detailsStr\n\n" +
                                "Your screen is currently set to:\nCDN: $targetCdn | Port: $targetPort\n\n" +
                                "Please change the dropdown menus to match the imported data, and try again.\n\n" +
                                "شما در حال وارد کردن آی‌پی‌هایی هستید که با تنظیمات فعلی مغایرت دارند. لطفاً منوهای بالا را تغییر دهید.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }

                // ALL CHECKS PASSED, PROCEED WITH IMPORT (No auto-save)
                if (pendingEntries.isNotEmpty()) {
                    if (ipEntries.size == 1 && ipEntries[0].address.isBlank()) ipEntries.clear()

                    var imported = 0
                    for (entry in pendingEntries) {
                        if (ipEntries.none { it.address == entry.address && it.port == entry.port && it.cdn == entry.cdn }) {
                            ipEntries.add(entry)
                            imported++
                        }
                    }

                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Imported $imported valid IPs. Please press Save.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No valid IPs found.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isValidIp(input: String): Boolean {
        val cleanInput = input.removePrefix("[").removeSuffix("]")

        // 1. Use Android's native POSIX-compliant standard parser (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return android.net.InetAddresses.isNumericAddress(cleanInput)
        }

        // 2. Programmatic Standard Parsing Fallback (For older Android versions)
        if (cleanInput.contains(".")) {
            // IPv4 Standard Check: Exactly 4 octets, ranging from 0 to 255, no leading zeros.
            val parts = cleanInput.split(".")
            if (parts.size != 4) return false
            return parts.all { octet ->
                val value = octet.toIntOrNull()
                value != null && value in 0..255 && !(octet.length > 1 && octet.startsWith("0"))
            }
        } else if (cleanInput.contains(":")) {
            // IPv6 Standard Check: Valid hex blocks, correct length, max one "::" compression.
            if (cleanInput.contains(":::")) return false
            if (cleanInput.split("::").size - 1 > 1) return false // Cannot have multiple "::"

            val parts = cleanInput.split(":")
            if (parts.size !in 3..8) return false

            return parts.all { block ->
                block.isEmpty() || (block.length <= 4 && block.toIntOrNull(16) != null)
            }
        }

        return false
    }
}