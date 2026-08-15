package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

class CdnIpManagerActivity : AppCompatActivity() {

    // Added 'port' to the data class to match the scanner's new output
    data class CfIpEntry(var address: String, var isChecked: Boolean, var latencyMs: Int = -1, var cdn: String = "CloudX", var port: Int = 443)

    private val ipEntries = mutableListOf<CfIpEntry>()
    private lateinit var adapter: CdnIpAdapter
    private var isCheckAllActive = true

    private var targetCdn = "CloudX"
    private var targetPort = 443

    private val hiddenOtherCdnIps = mutableListOf<org.json.JSONObject>()

    // UI State Trackers to handle cascading spinner updates cleanly
    private var isRevertingCdn = false
    private var isRevertingPort = false
    private var lastCdnIndex = 0
    private var lastPortIndex = 0
    private val portList = mutableListOf<String>()

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
                        .show()
                    return
                }

                if (validCheckedCount == 0 && totalValidIps > 0) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@CdnIpManagerActivity)
                        .setTitle("No IP Selected / هیچ آی‌پی انتخاب نشده است")
                        .setMessage("Please check exactly ONE IP to act as your active connection before exiting.\n\nلطفاً قبل از خروج، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                        .setPositiveButton("OK / تأیید", null)
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

        // Button Listeners
        findViewById<Button>(R.id.btn_toggle_all_cf).setOnClickListener {
            ipEntries.forEach { it.isChecked = isCheckAllActive }
            isCheckAllActive = !isCheckAllActive
            (it as Button).text = if (isCheckAllActive) "CHECK ALL" else "UNCHECK ALL"
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btn_delete_cf).setOnClickListener {
            ipEntries.removeAll { it.isChecked }
            if (ipEntries.isEmpty()) {
                ipEntries.add(CfIpEntry("", false, -1, targetCdn, targetPort))
            }
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btn_save_cf).setOnClickListener {
            saveIps()
            Toast.makeText(this, "Vault Saved!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_import_cf).setOnClickListener {
            showImportDialog()
        }

        findViewById<Button>(R.id.btn_export_cf).setOnClickListener {
            // Include the port with the address (e.g., 192.0.2.1:2053) before encrypting
            val validIps = ipEntries.filter { it.address.isNotBlank() && it.isChecked }
                .joinToString("\n") { entry ->
                    val addressWithPort = if (entry.address.contains(":")) {
                        entry.address // already has a port
                    } else {
                        "${entry.address}:${entry.port}"
                    }
                    CryptoHelper.encrypt("$addressWithPort:${entry.cdn}")
                }

            if (validIps.isEmpty()) {
                Toast.makeText(this, "No IPs selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Saved $targetCdn IPs")
                putExtra(Intent.EXTRA_TEXT, validIps)
            }
            startActivity(Intent.createChooser(shareIntent, "Share IPs via"))
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
            hint = "Paste IPs (comma, space, or newline separated)...\nآی‌پی‌ها را اینجا جای‌گذاری کنید..."
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

                val validIps = parsed.mapNotNull { token ->
                    val decryptedToken = CryptoHelper.decrypt(token)
                    sanitizeInput(decryptedToken)
                }.distinct()

                if (validIps.isNotEmpty()) {

                    // 1. SCAN FOR STRICT FORMAT, PORT, AND CDN MISMATCHES
                    val mismatchedPorts = mutableSetOf<Int>()
                    val mismatchedCdns = mutableSetOf<String>()
                    var hasLegacyFormat = false

                    for (rawToken in validIps) {
                        val segments = rawToken.split(":")

                        // STRICT SHIELD: Must be exactly IP:Port:CDN
                        if (segments.size < 3) {
                            hasLegacyFormat = true
                            break // Fatal format error, stop parsing immediately
                        }

                        // Check Port
                        val extractedPort = segments[1].toIntOrNull()
                        if (extractedPort == null || extractedPort != targetPort) {
                            if (extractedPort != null) mismatchedPorts.add(extractedPort)
                        }

                        // Check CDN
                        val extractedCdn = segments[2]
                        if (!extractedCdn.equals(targetCdn, ignoreCase = true)) {
                            mismatchedCdns.add(extractedCdn)
                        }
                    }

                    // 2. REJECT LEGACY OR IMPROPER FORMATS INSTANTLY
                    if (hasLegacyFormat) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Legacy Format Detected / فرمت قدیمی")
                            .setMessage("The imported data is using an old format (missing Port or CDN metadata).\n\nPlease use a newly exported list to ensure compatibility.\n\n" +
                                    "اطلاعات وارد شده مربوط به نسخه قدیمی است (فاقد اطلاعات پورت یا CDN). لطفاً از لیست‌های جدید استفاده کنید.")
                            .setPositiveButton("OK", null)
                            .show()
                        return@setPositiveButton
                    }

                    // 3. REJECT IF PORT OR CDN MISMATCHES THE CURRENT UI
                    if (mismatchedPorts.isNotEmpty() || mismatchedCdns.isNotEmpty()) {
                        val portStr = if (mismatchedPorts.isNotEmpty()) "Port(s): ${mismatchedPorts.joinToString(", ")}" else ""
                        val cdnStr = if (mismatchedCdns.isNotEmpty()) "CDN(s): ${mismatchedCdns.joinToString(", ")}" else ""
                        val combinedWarning = listOf(cdnStr, portStr).filter { it.isNotEmpty() }.joinToString("\n")

                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Target Mismatch / مغایرت اطلاعات")
                            .setMessage("You are trying to import IPs that belong to a different CDN or Port than what is currently selected.\n\n" +
                                    "Import Data Contains:\n$combinedWarning\n\n" +
                                    "Your screen is currently set to:\nCDN: $targetCdn | Port: $targetPort\n\n" +
                                    "Please change the dropdown menus to match the imported data, and try again.\n\n" +
                                    "شما در حال وارد کردن آی‌پی‌هایی هستید که با تنظیمات فعلی مغایرت دارند. لطفاً منوهای بالا را تغییر دهید.")
                            .setPositiveButton("OK", null)
                            .show()
                        return@setPositiveButton
                    }

                    // 4. ALL CHECKS PASSED, PROCEED WITH IMPORT
                    if (ipEntries.size == 1 && ipEntries[0].address.isBlank()) ipEntries.clear()

                    var imported = 0
                    for (rawToken in validIps) {
                        // We already strictly verified it has at least 3 segments, so index [0] is perfectly safe
                        val cleanIp = rawToken.split(":")[0]

                        if (ipEntries.none { it.address == cleanIp }) {
                            ipEntries.add(CfIpEntry(cleanIp, false, -1, targetCdn, targetPort))
                            imported++
                        }
                    }

                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Imported $imported valid IPs", Toast.LENGTH_SHORT).show()

                    if (imported > 0) {
                        saveIps() // Auto-save after successful import
                    }

                } else {
                    Toast.makeText(this, "No valid IPv4 addresses found.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sanitizeInput(token: String): String? {
        if (!token.startsWith("http://") && !token.startsWith("https://") && isValidIpv4WithOptionalPort(token)) {
            return token
        }
        return null
    }

    private fun isValidIpv4WithOptionalPort(input: String): Boolean {
        val core = if (input.contains(":")) input.split(":").first() else input
        val parts = core.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }
}