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

class CfIpManagerActivity : AppCompatActivity() {

    data class CfIpEntry(var address: String, var isChecked: Boolean, var latencyMs: Int = -1, var cdn: String = "Cloudflare")
    private val ipEntries = mutableListOf<CfIpEntry>()
    private lateinit var adapter: CfIpAdapter
    private var isCheckAllActive = true
    private var targetCdn = "Cloudflare"
    private val hiddenOtherCdnIps = mutableListOf<org.json.JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cf_manager)

        // Grab the target CDN from Global Settings
        targetCdn = intent.getStringExtra("TARGET_CDN") ?: "Cloudflare"

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_cf_manager)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Dynamically update the title so the user knows which CDN they are editing
        supportActionBar?.title = "$targetCdn IP Manager"
        toolbar.setNavigationOnClickListener { finish() }

        loadSavedIps()

        val recycler = findViewById<RecyclerView>(R.id.recycler_cf_ips)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CfIpAdapter(ipEntries) { /* Callback when checkbox changes if needed */ }
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
            // Count how many valid IPs are currently checked
            val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
            val totalValidIps = ipEntries.count { it.address.isNotBlank() }

            // GATEKEEPER 1: Prevent saving if multiple are checked
            if (validCheckedCount > 1) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Multiple IPs Selected")
                    .setMessage("Please check exactly ONE IP to act as your active connection before saving.\n\nلطفاً قبل از ذخیره، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // GATEKEEPER 2: Prevent saving if NONE are checked (unless the vault is completely empty)
            if (validCheckedCount == 0 && totalValidIps > 0) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("No IP Selected / هیچ آی‌پی انتخاب نشده است")
                    .setMessage("Please check exactly ONE IP to act as your active connection.\n\nلطفاً دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                    .setPositiveButton("OK / تأیید", null)
                    .show()
                return@setOnClickListener
            }

            // If exactly 1 is checked (or the vault is intentionally empty), proceed!
            saveIps()
            Toast.makeText(this, "Vault Saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btn_import_cf).setOnClickListener {
            showImportDialog()
        }

        findViewById<Button>(R.id.btn_export_cf).setOnClickListener {
            // 1. Gather the checked IPs
            val validIps = ipEntries.filter { it.address.isNotBlank() && it.isChecked }.joinToString("\n") { it.address }

            if (validIps.isEmpty()) {
                Toast.makeText(this, "No IPs selected.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. Create the Android Share Intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Saved Cloudflare IPs")
                putExtra(Intent.EXTRA_TEXT, validIps)
            }

            // 3. Launch the native Chooser dialog
            startActivity(Intent.createChooser(shareIntent, "Share IPs via"))
        }

    }

    private fun loadSavedIps() {
        val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("vault_ips_json", "[]") ?: "[]"

        try {
            val jsonArray = org.json.JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ip = obj.getString("ip")
                val isChecked = obj.getBoolean("isChecked")
                val latency = obj.optInt("latency", -1)
                val cdn = obj.optString("cdn", "Cloudflare")

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

        // Ensure at least one is checked (safety fallback)
        if (ipEntries.isNotEmpty() && ipEntries.none { it.isChecked }) {
            ipEntries.first().isChecked = true
        }

        // Start with a blank row if empty
        if (ipEntries.isEmpty()) {
            ipEntries.add(CfIpEntry("", true, -1, targetCdn))
        }
    }

    private fun saveIps() {
        val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()

        for (entry in ipEntries) {
            if (entry.address.isNotBlank()) {
                val obj = org.json.JSONObject()
                obj.put("ip", entry.address)
                obj.put("isChecked", entry.isChecked)
                obj.put("latency", entry.latencyMs)
                obj.put("cdn", targetCdn) // Enforce the target CDN
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

                // 2. Sanitize and filter exclusively for valid IPv4 addresses
                val validIps = parsed.mapNotNull { sanitizeInput(it) }.distinct()

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