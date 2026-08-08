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

    data class CfIpEntry(var address: String, var isChecked: Boolean, var latencyMs: Int = -1, var cdn: String = "CloudX")
    private val ipEntries = mutableListOf<CfIpEntry>()
    private lateinit var adapter: CdnIpAdapter
    private var isCheckAllActive = true
    private var targetCdn = "CloudX"
    private val hiddenOtherCdnIps = mutableListOf<org.json.JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cdn_manager)

        // Grab the target CDN from Global Settings
        targetCdn = intent.getStringExtra("TARGET_CDN") ?: "CloudX"

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

                // If perfectly valid, save their work one last time and safely exit
                saveIps()
                isEnabled = false // Disable this interceptor to allow actual exit
                onBackPressedDispatcher.onBackPressed()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // Route the top-left toolbar arrow through the strict gatekeeper
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // --- NEW: INITIALIZE SPINNER ---
        val spinnerCdn = findViewById<android.widget.Spinner>(R.id.spinner_manager_cdn)
        val cdnList = mutableListOf<String>()
        val cdnCount = mobile.Mobile.getCdnCount()

        for (i in 0 until cdnCount) {
            val name = mobile.Mobile.getCdnName(i)
            if (name.isNotEmpty()) cdnList.add(name)
        }
        if (cdnList.isEmpty()) {
            cdnList.add("CloudX")
            cdnList.add("CloudY")
            cdnList.add("CloudZ")
        }

        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, cdnList)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCdn.adapter = spinnerAdapter

        // Set initial selection
        val initialIndex = cdnList.indexOf(targetCdn)
        if (initialIndex >= 0) spinnerCdn.setSelection(initialIndex)

        var isInitialSetup = true
        spinnerCdn.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (isInitialSetup) {
                    isInitialSetup = false
                    return
                }

                val selectedCdn = parent.getItemAtPosition(position).toString()
                if (selectedCdn != targetCdn) {
                    // GATEKEEPER: Ensure they don't have multiple checked before auto-saving
                    val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
                    if (validCheckedCount > 1) {
                        Toast.makeText(this@CdnIpManagerActivity, "Cannot switch CDNs: Multiple IPs checked.", Toast.LENGTH_SHORT).show()
                        spinnerCdn.setSelection(cdnList.indexOf(targetCdn)) // Revert UI
                        return
                    }

                    // 1. Auto-save the current CDN's state before switching
                    saveIps()

                    // 2. Switch the active target
                    targetCdn = selectedCdn
                    supportActionBar?.title = "$targetCdn IP Manager"

                    // 3. Reload the UI for the new CDN
                    loadSavedIps()
                    adapter.notifyDataSetChanged()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        // -------------------------------

        loadSavedIps()

        val recycler = findViewById<RecyclerView>(R.id.recycler_cf_ips)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CdnIpAdapter(ipEntries) { /* Callback when checkbox changes if needed */ }
        recycler.adapter = adapter

        findViewById<Button>(R.id.btn_toggle_all_cf).setOnClickListener {
            ipEntries.forEach { it.isChecked = isCheckAllActive }
            isCheckAllActive = !isCheckAllActive
            (it as Button).text = if (isCheckAllActive) "CHECK ALL" else "UNCHECK ALL"
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.btn_delete_cf).setOnClickListener {
            ipEntries.removeAll { it.isChecked }
            if (ipEntries.isEmpty()) {
                ipEntries.add(CfIpEntry("", false))
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
            val validIps = ipEntries.filter { it.address.isNotBlank() && it.isChecked }
                .joinToString("\n") { CryptoHelper.encrypt(it.address) }

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

    override fun onBackPressed() {
        val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
        val totalValidIps = ipEntries.count { it.address.isNotBlank() }

        // GATEKEEPER 1: Prevent exiting if multiple are checked
        if (validCheckedCount > 1) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Multiple IPs Selected")
                .setMessage("Please check exactly ONE IP to act as your active connection before exiting.\n\nلطفاً قبل از خروج، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // GATEKEEPER 2: Prevent exiting if NONE are checked (unless the vault is empty)
        if (validCheckedCount == 0 && totalValidIps > 0) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("No IP Selected / هیچ آی‌پی انتخاب نشده است")
                .setMessage("Please check exactly ONE IP to act as your active connection before exiting.\n\nلطفاً قبل از خروج، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                .setPositiveButton("OK / تأیید", null)
                .show()
            return
        }

        // If the selection is valid, auto-save their work just in case, then exit
        saveIps()
        super.onBackPressed()
    }

    private fun loadSavedIps() {
        // --- BUG FIX: Clear memory explicitly to prevent duplication on Spinner switch ---
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

                // Partition the data
                if (cdn.equals(targetCdn, ignoreCase = true)) {
                    if (ip.isNotBlank()) {
                        ipEntries.add(CfIpEntry(ip, isChecked, latency, cdn))
                    }
                } else {
                    // Safely store away IPs belonging to other CDNs
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
            ipEntries.add(CfIpEntry("", true, -1, targetCdn))
        }
    }

    private fun saveIps() {
        val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()

        // Preserve ALL IPs that belong to other CDNs
        for (hiddenObj in hiddenOtherCdnIps) {
            jsonArray.put(hiddenObj)
        }

        // Add the current CDN IPs exactly as they are on the screen
        for (entry in ipEntries) {
            if (entry.address.isNotBlank()) {
                val obj = org.json.JSONObject()
                obj.put("ip", CryptoHelper.encrypt(entry.address))
                obj.put("isChecked", entry.isChecked) // Blindly save the user's selection
                obj.put("latency", entry.latencyMs)
                obj.put("cdn", targetCdn)
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
                // 1. Split by spaces, commas, semicolons, or newlines
                val parsed = input.text.toString()
                    .split(Regex("[\\s,;]+"))
                    .map { it.replace("\"", "").trim() }
                    .filter { it.isNotEmpty() }

                // 2. DECRYPT first, then sanitize and filter for valid IPv4 addresses
                val validIps = parsed.mapNotNull { token ->
                    val decryptedToken = CryptoHelper.decrypt(token)
                    sanitizeInput(decryptedToken)
                }.distinct()

                if (validIps.isNotEmpty()) {
                    // Clear the empty placeholder row if it exists
                    if (ipEntries.size == 1 && ipEntries[0].address.isBlank()) ipEntries.clear()

                    var imported = 0
                    for (ip in validIps) {
                        if (ipEntries.none { it.address == ip }) {
                            // Add as UNCHECKED so the user doesn't hit the "Multiple IPs Selected" error on save
                            ipEntries.add(CfIpEntry(ip, false, -1, targetCdn))
                            imported++
                        }
                    }
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Imported $imported valid IPs for $targetCdn", Toast.LENGTH_SHORT).show()
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
        // Strip the port if one exists
        val core = if (input.contains(":")) input.split(":").first() else input
        val parts = core.split(".")

        // Ensure exactly 4 octets
        if (parts.size != 4) return false

        // Ensure each octet is a valid number between 0 and 255
        return parts.all { it.toIntOrNull() in 0..255 }
    }

}