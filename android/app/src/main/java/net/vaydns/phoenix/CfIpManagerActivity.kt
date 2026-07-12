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

    data class CfIpEntry(var address: String, var isChecked: Boolean, var latencyMs: Int = -1)

    private val ipEntries = mutableListOf<CfIpEntry>()
    private lateinit var adapter: CfIpAdapter
    private var isCheckAllActive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cf_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_cf_manager)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
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
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                val lines = text.split(Regex("[\\n\\r,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
                if (ipEntries.size == 1 && ipEntries[0].address.isBlank()) ipEntries.clear()

                var imported = 0
                for (line in lines) {
                    if (ipEntries.none { it.address == line }) {
                        ipEntries.add(CfIpEntry(line, true))
                        imported++
                    }
                }
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "Imported $imported IPs", Toast.LENGTH_SHORT).show()
            }
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

                if (ip.isNotBlank()) {
                    ipEntries.add(CfIpEntry(ip, isChecked, latency))
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
            ipEntries.add(CfIpEntry("", true))
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
                jsonArray.put(obj)
            }
        }

        prefs.edit().putString("vault_ips_json", jsonArray.toString()).apply()
    }

}