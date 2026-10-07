package net.vaydns.phoenix

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AmneziaWgConfigEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_amneziawg_config_editor)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_amneziawg)
        toolbar.setNavigationOnClickListener { finish() }

        // =================================================================
        // 1. INITIALIZE ALL VIEWS FIRST
        // =================================================================
        val etName = findViewById<EditText>(R.id.et_awg_name)
        val etIp = findViewById<EditText>(R.id.et_awg_ip)
        val etPort = findViewById<EditText>(R.id.et_awg_port)
        val etInternalIp = findViewById<EditText>(R.id.et_awg_internal_ip)

        val etClientPriv = findViewById<EditText>(R.id.et_awg_client_priv)
        val etClientPub = findViewById<EditText>(R.id.et_awg_client_pub)
        val etServerPub = findViewById<EditText>(R.id.et_awg_server_pub)

        val btnGenerateKeys = findViewById<ImageButton>(R.id.btn_generate_keys)
        val btnRandomHeaders = findViewById<ImageButton>(R.id.btn_awg_random_headers)

        val etJc = findViewById<EditText>(R.id.et_awg_jc)
        val etJmin = findViewById<EditText>(R.id.et_awg_jmin)
        val etJmax = findViewById<EditText>(R.id.et_awg_jmax)
        val etS1 = findViewById<EditText>(R.id.et_awg_s1)
        val etS2 = findViewById<EditText>(R.id.et_awg_s2)
        val etH1 = findViewById<EditText>(R.id.et_awg_h1)
        val etH2 = findViewById<EditText>(R.id.et_awg_h2)
        val etH3 = findViewById<EditText>(R.id.et_awg_h3)
        val etH4 = findViewById<EditText>(R.id.et_awg_h4)

        val switchLockKeys = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_lock_keys)

        fun updateKeyLockState(isLocked: Boolean) {
            etClientPriv.isEnabled = !isLocked
            etClientPub.isEnabled = !isLocked
            btnGenerateKeys.isEnabled = !isLocked
            btnGenerateKeys.alpha = if (isLocked) 0.3f else 1.0f

            // Lock Random H1-H4 Headers
            btnRandomHeaders.isEnabled = !isLocked
            btnRandomHeaders.alpha = if (isLocked) 0.3f else 1.0f
            etH1.isEnabled = !isLocked
            etH2.isEnabled = !isLocked
            etH3.isEnabled = !isLocked
            etH4.isEnabled = !isLocked
        }

        switchLockKeys.setOnCheckedChangeListener { _, isChecked ->
            updateKeyLockState(isChecked)
        }

        // Key Generation Logic via Go Backend
        btnGenerateKeys.setOnClickListener {
            try {
                val keysString = mobile.Mobile.generateAmneziaWGKeys()

                if (keysString != null && keysString.contains(",")) {
                    val parts = keysString.split(",")
                    etClientPriv.setText(parts[0])
                    etClientPub.setText(parts[1])
                    // Auto-lock to prevent accidental overwrites
                    switchLockKeys.isChecked = true
                } else {
                    Toast.makeText(this, "Failed to read keys from Go backend.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Backend key generation failed.", Toast.LENGTH_LONG).show()
            }
        }

        // Random H1-H4 Generation
        btnRandomHeaders.setOnClickListener {
            etH1.setText((100000000..999999999).random().toString())
            etH2.setText((100000000..999999999).random().toString())
            etH3.setText((100000000..999999999).random().toString())
            etH4.setText((100000000..999999999).random().toString())
        }

        // =================================================================
        // 2. LOAD EXISTING CONFIG LOGIC
        // =================================================================
        val isNewConfig = intent.getBooleanExtra("IS_NEW_CONFIG", true)
        val editingConfigId = intent.getStringExtra("CONFIG_ID")

        if (!isNewConfig && editingConfigId != null) {
            toolbar.title = "Edit AmneziaWG Config"
            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", MODE_PRIVATE)
            val configsString = sharedPref.getString("configs", "[]") ?: "[]"

            try {
                val jsonArray = JSONArray(configsString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optString("id") == editingConfigId) {

                        etName.setText(obj.optString("name", ""))
                        etIp.setText(obj.optString("vless_ip", ""))
                        etPort.setText(obj.optString("vless_port", "51820"))
                        etInternalIp.setText(obj.optString("awg_internal_ip", ""))

                        etClientPriv.setText(obj.optString("awg_client_priv", ""))
                        etClientPub.setText(obj.optString("awg_client_pub", ""))
                        etServerPub.setText(obj.optString("awg_server_pub", ""))

                        etJc.setText(obj.optInt("awg_jc", 120).toString())
                        etJmin.setText(obj.optInt("awg_jmin", 50).toString())
                        etJmax.setText(obj.optInt("awg_jmax", 1000).toString())
                        etS1.setText(obj.optInt("awg_s1", 15).toString())
                        etS2.setText(obj.optInt("awg_s2", 15).toString())
                        etH1.setText(obj.optLong("awg_h1", 1L).toString())
                        etH2.setText(obj.optLong("awg_h2", 2L).toString())
                        etH3.setText(obj.optLong("awg_h3", 3L).toString())
                        etH4.setText(obj.optLong("awg_h4", 4L).toString())

                        // Lock the UI if a private key is already established
                        if (etClientPriv.text.toString().isNotEmpty()) {
                            switchLockKeys.isChecked = true
                            updateKeyLockState(true)
                        } else {
                            switchLockKeys.isChecked = false
                            updateKeyLockState(false)
                        }

                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            toolbar.title = "Add AmneziaWG Config"
        }

        // =================================================================
        // 3. SAVE LOGIC (Handles both Create and Update / Overwrite)
        // =================================================================
        findViewById<ImageButton>(R.id.btn_save_amneziawg).setOnClickListener {
            val name = etName.text.toString().trim()
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().trim()
            val internalIp = etInternalIp.text.toString().trim()
            val clientPriv = etClientPriv.text.toString().trim()
            val serverPub = etServerPub.text.toString().trim()

            if (name.isEmpty() || ip.isEmpty() || port.isEmpty() || internalIp.isEmpty() || clientPriv.isEmpty() || serverPub.isEmpty()) {
                Toast.makeText(this, "Core Configuration fields cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (name.isEmpty() || ip.isEmpty() || port.isEmpty() || internalIp.isEmpty() || clientPriv.isEmpty() || serverPub.isEmpty()) {
                Toast.makeText(this, "Core Configuration fields cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isValidIp(ip) && !isValidDomain(ip)) {
                Toast.makeText(this, "Invalid Endpoint Address. Must be a valid IP or Domain.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", MODE_PRIVATE)
            val jsonArray = JSONArray(sharedPref.getString("configs", "[]") ?: "[]")

            val finalAssignedId = editingConfigId ?: UUID.randomUUID().toString()

            val newObj = JSONObject().apply {
                put("id", finalAssignedId)
                put("name", name)
                put("tunnelProtocol", "amneziawg")
                put("domain", ip) // Fallback domain for list rendering

                // Map standard fields to the shared base
                put("vless_ip", ip)
                put("vless_port", port)

                // AmneziaWG specific fields
                put("awg_internal_ip", internalIp)
                put("awg_client_priv", clientPriv)
                put("awg_client_pub", etClientPub.text.toString().trim())
                put("awg_server_pub", serverPub)

                put("awg_jc", etJc.text.toString().toIntOrNull() ?: 120)
                put("awg_jmin", etJmin.text.toString().toIntOrNull() ?: 50)
                put("awg_jmax", etJmax.text.toString().toIntOrNull() ?: 1000)
                put("awg_s1", etS1.text.toString().toIntOrNull() ?: 15)
                put("awg_s2", etS2.text.toString().toIntOrNull() ?: 15)
                put("awg_h1", etH1.text.toString().toLongOrNull() ?: 1L)
                put("awg_h2", etH2.text.toString().toLongOrNull() ?: 2L)
                put("awg_h3", etH3.text.toString().toLongOrNull() ?: 3L)
                put("awg_h4", etH4.text.toString().toLongOrNull() ?: 4L)
            }

            val arrayToSave = if (!isNewConfig && editingConfigId != null) {
                // UPDATE: Preserve existing orderIndex and update in place
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optString("id") == finalAssignedId) {
                        newObj.put("orderIndex", obj.optInt("orderIndex", 0))
                        jsonArray.put(i, newObj)
                        break
                    }
                }
                jsonArray
            } else {
                // CREATE: Insert new item at the TOP (index 0) and reindex the rest
                val newArray = JSONArray()
                newObj.put("orderIndex", 0)
                newArray.put(newObj)

                for (i in 0 until jsonArray.length()) {
                    val oldObj = jsonArray.getJSONObject(i)
                    oldObj.put("orderIndex", i + 1) // Shift all existing down by 1
                    newArray.put(oldObj)
                }
                newArray
            }

            sharedPref.edit().putString("configs", arrayToSave.toString()).apply()
            finish()
        }
    }

    private fun isValidDomain(input: String): Boolean {
        val cleanInput = input.trim()
        if (cleanInput.isEmpty()) return false

        return android.util.Patterns.DOMAIN_NAME.matcher(cleanInput).matches()
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