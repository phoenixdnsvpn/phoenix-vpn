package net.vaydns.phoenix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RestoreBackupActivity : AppCompatActivity() {

    private lateinit var etInput: EditText

    // Launcher for the native "Import JSON Backup from File" dialog
    private val restoreBackupFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (!jsonString.isNullOrEmpty()) {
                        processRestore(jsonString)
                    } else {
                        Toast.makeText(this, "The selected file is empty.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_restore_backup)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_restore)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        etInput = findViewById(R.id.et_backup_input)
        val btnFile = findViewById<Button>(R.id.btn_import_file)
        val btnRestore = findViewById<Button>(R.id.btn_restore)

        btnFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" // Use */* so aggressive file managers don't gray out the selection
            }
            restoreBackupFileLauncher.launch(intent)
        }

        btnRestore.setOnClickListener {
            val input = etInput.text.toString().trim()
            if (input.isEmpty()) {
                // Throw bilingual warning and prevent action
                Toast.makeText(this, "Nothing has been pasted yet. / هیچ متنی جای‌گذاری نشده است.", Toast.LENGTH_LONG).show()
            } else {
                processRestore(input)
            }
        }
    }

    private fun processRestore(jsonStr: String) {
        try {
            // Parse the initial pasted text or file content
            val importedJson = org.json.JSONObject(jsonStr)

            // --- NEW: DECRYPTION WRAPPER ---
            // Check if it's our new secure wrapper or a legacy plaintext backup
            val backup = if (importedJson.optBoolean("is_encrypted_backup", false)) {
                val encryptedPayload = importedJson.getString("payload")
                val decryptedString = CryptoHelper.decrypt(encryptedPayload)
                org.json.JSONObject(decryptedString)
            } else {
                importedJson // Fallback for old/unencrypted backups
            }
            // -------------------------------

            // SANITY CHECK: Ensure this is actually a Phoenix Backup File
            if (!backup.has("backup_version")) {
                throw Exception("Missing signature. This does not appear to be a valid Phoenix VPN backup file.")
            }

            // 1. Restore App List
            if (backup.has("allowed_apps")) {
                val appsArray = backup.getJSONArray("allowed_apps")
                val appsSet = mutableSetOf<String>()
                for (i in 0 until appsArray.length()) appsSet.add(appsArray.getString(i))
                getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE).edit().putStringSet("allowed_apps", appsSet).apply()
            }

            // 2. Restore Custom Configs
            if (backup.has("custom_configs")) {
                getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE).edit().putString("configs", backup.getJSONArray("custom_configs").toString()).apply()
            }

            // 3. Restore CDN IPs
            if (backup.has("cdn_ips")) {
                getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE).edit().putString("vault_ips_json", backup.getJSONArray("cdn_ips").toString()).apply()
            }

            // 4. Restore Traffic Data
            if (backup.has("traffic_data")) {
                val trafficObj = backup.getJSONObject("traffic_data")
                val editor = getSharedPreferences("Phoenix_Traffic", Context.MODE_PRIVATE).edit()
                // Wipe current memory first to prevent ghost merging
                editor.clear()
                val keys = trafficObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    editor.putLong(key, trafficObj.getLong(key))
                }
                editor.apply()
            }

            // 5. Restore Multipath Resolvers
            if (backup.has("multipath_resolvers")) {
                val resolversObj = backup.getJSONObject("multipath_resolvers")
                val keys = resolversObj.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val configRes = resolversObj.getJSONObject(id)

                    if (configRes.has("selected")) {
                        val arr = configRes.getJSONArray("selected")
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        java.io.File(filesDir, "selected_multipath_$id.txt").writeText(list.joinToString("\n"))
                    }
                    if (configRes.has("scanned")) {
                        val arr = configRes.getJSONArray("scanned")
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        java.io.File(filesDir, "resolvers_$id.txt").writeText(list.joinToString("\n"))
                    }
                    if (configRes.has("manual")) {
                        val arr = configRes.getJSONArray("manual")
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        java.io.File(filesDir, "manual_resolvers_$id.txt").writeText(list.joinToString("\n"))
                    }
                }
            }

            Toast.makeText(this, "Backup Restored Successfully! / بکاپ با موفقیت بازگردانی شد", Toast.LENGTH_LONG).show()

            // Close this activity. MainActivity's onResume() will automatically reload the UI!
            finish()

        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Restore Error")
                .setMessage("Failed to parse backup data. The JSON format is invalid or corrupted.\n\n${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}