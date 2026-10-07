package net.vaydns.phoenix

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class HysteriaConfigEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hysteria_config_editor)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_hysteria)
        toolbar.setNavigationOnClickListener { finish() }

        val etName = findViewById<EditText>(R.id.et_hys_name)
        val etIp = findViewById<EditText>(R.id.et_hys_ip)
        val layoutInterval = findViewById<LinearLayout>(R.id.layout_hys_port_hopping_interval)
        val swPortHopping = findViewById<SwitchCompat>(R.id.sw_hys_port_hopping)
        val etInterval = findViewById<EditText>(R.id.et_hys_port_hopping_interval)
        val etPort = findViewById<EditText>(R.id.et_hys_port)
        val etAuth = findViewById<EditText>(R.id.et_hys_auth)
        val etObfs = findViewById<EditText>(R.id.et_hys_obfs)
        val etSni = findViewById<EditText>(R.id.et_hys_sni)
        val swInsecure = findViewById<SwitchCompat>(R.id.sw_hys_insecure)
        val layoutSha256 = findViewById<LinearLayout>(R.id.layout_hys_sha256)
        val etSha256 = findViewById<EditText>(R.id.et_hys_sha256)
        val btnSave = findViewById<ImageButton>(R.id.btn_save_hysteria)
        val etUpMbps = findViewById<EditText>(R.id.et_hys_up_mbps)
        val etDownMbps = findViewById<EditText>(R.id.et_hys_down_mbps)

        val isNewConfig = intent.getBooleanExtra("IS_NEW_CONFIG", true)
        val editingConfigId = intent.getStringExtra("CONFIG_ID")

        if (!isNewConfig && editingConfigId != null) {
            toolbar.title = "Edit Hysteria Config"

            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", MODE_PRIVATE)
            val configsString = sharedPref.getString("configs", "[]") ?: "[]"

            try {
                val jsonArray = org.json.JSONArray(configsString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optString("id") == editingConfigId) {

                        etName.setText(obj.optString("name", ""))
                        etIp.setText(obj.optString("vless_ip", ""))
                        etSni.setText(obj.optString("vless_sni", ""))
                        etAuth.setText(obj.optString("hys_auth", ""))
                        etObfs.setText(obj.optString("hys_obfs", ""))
                        etSha256.setText(obj.optString("vless_sha256", ""))

                        etUpMbps.setText(obj.optInt("hys_up_mbps", 100).toString())
                        etDownMbps.setText(obj.optInt("hys_down_mbps", 500).toString())

                        swInsecure.isChecked = obj.optBoolean("vless_allow_insecure", true)

                        val usePortHopping = obj.optBoolean("hys_usePortHopping", false)
                        swPortHopping.isChecked = usePortHopping

                        if (usePortHopping) {
                            etPort.setText("20000-50000")
                            layoutInterval.visibility = View.VISIBLE
                        } else {
                            layoutInterval.visibility = View.GONE
                            etPort.setText(obj.optString("vless_port", "443"))
                        }

                        etInterval.setText(obj.optInt("hys_port_hopping_interval", 10).toString())

                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            toolbar.title = "Add Hysteria Config"
        }

        //Log.i("VAY_DEBUG", "Editing ID passed: ${intent.getStringExtra("CONFIG_ID")}, isNew: ${intent.getBooleanExtra("IS_NEW_CONFIG", true)}")
        // Port Hopping Toggle Logic
        swPortHopping.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etPort.setText("20000-50000")
                layoutInterval.visibility = View.VISIBLE
            } else {
                layoutInterval.visibility = View.GONE
                if (etPort.text.toString() == "20000-50000") {
                    etPort.setText("443")
                }
            }
        }

        // Allow Insecure Toggle Logic (OFF reveals SHA-256 field)
        layoutSha256.visibility = if (swInsecure.isChecked) View.GONE else View.VISIBLE

        swInsecure.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                layoutSha256.visibility = View.GONE
                // FIXED: Removed etSha256.setText("") so the key is permanently preserved in the background
            } else {
                layoutSha256.visibility = View.VISIBLE
            }
        }

        // Save Logic
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().trim()
            val auth = etAuth.text.toString().trim()

            val upMbps = etUpMbps.text.toString().trim().toIntOrNull() ?: 100
            val downMbps = etDownMbps.text.toString().trim().toIntOrNull() ?: 500

            if (name.isEmpty() || ip.isEmpty() || port.isEmpty() || auth.isEmpty()) {
                Toast.makeText(this, "Name, IP, Port, and Auth Password are required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (name.isEmpty() || ip.isEmpty() || port.isEmpty() || auth.isEmpty()) {
                Toast.makeText(this, "Name, Address, Port, and Auth Password are required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isValidIp(ip) && !isValidDomain(ip)) {
                Toast.makeText(this, "Invalid Address. Must be a valid IP or Domain.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // STRICT DOMAIN VALIDATION FOR SNI
            val sni = etSni.text.toString().trim()
            if (sni.isNotEmpty() && !isValidDomain(sni)) {
                Toast.makeText(this, "Invalid SNI. Must be a strictly formatted domain name.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!swInsecure.isChecked && etSha256.text.toString().trim().isEmpty()) {
                Toast.makeText(this, "SHA-256 Pin is required when Insecure is disabled.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val rawSha256 = etSha256.text.toString().trim()
            val cleanSha256 = normalizeSha256Pin(rawSha256)
            if (!swInsecure.isChecked && cleanSha256 == null) {
                Toast.makeText(this, "Enter a SHA-256 pin as hex or base64.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intervalStr = etInterval.text.toString().trim()
            val interval = if (intervalStr.isEmpty()) 10 else intervalStr.toIntOrNull() ?: 10

            saveHysteriaConfig(
                configId = editingConfigId,
                name = name,
                ip = ip,
                usePortHopping = swPortHopping.isChecked,
                port = port,
                auth = auth,
                obfs = etObfs.text.toString().trim(),
                sni = etSni.text.toString().trim(),
                allowInsecure = swInsecure.isChecked,
                sha256 = cleanSha256 ?: "",
                upMbps = upMbps,
                downMbps = downMbps,
                portHoppingInterval = interval
            )

            finish()
        }
    }

    private fun normalizeSha256Pin(raw: String): String? {
        if (raw.isEmpty()) return null

        var pin = raw.trim()
        pin = pin.substringAfterLast("Fingerprint=", pin)
        pin = pin.replace("\\s".toRegex(), "").replace(":", "")

        val hex = pin.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (hex && pin.length == 64) return pin.lowercase()

        val compact = pin.replace("=", "")
        val b64 = compact.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }
        if (b64 && compact.length == 43) return pin

        return null
    }

    private fun saveHysteriaConfig(
        configId: String?,
        name: String, ip: String, usePortHopping: Boolean, port: String,
        auth: String, obfs: String, sni: String, allowInsecure: Boolean, sha256: String,
        upMbps: Int, downMbps: Int, portHoppingInterval: Int
    ) {
        val sharedPref = getSharedPreferences("PhoenixVpnPrefs", MODE_PRIVATE)
        val configsString = sharedPref.getString("configs", "[]") ?: "[]"
        val jsonArray = JSONArray(configsString)

        val finalAssignedId = configId ?: UUID.randomUUID().toString()

        val newObj = JSONObject().apply {
            put("id", finalAssignedId)
            put("name", name)
            put("tunnelProtocol", "hysteria")

            val displayDomain = if (sni.isNotEmpty()) sni else ip
            put("domain", displayDomain)

            // Map standard fields to the shared base
            put("vless_ip", ip)
            put("vless_port", port)
            put("vless_sni", sni)
            put("vless_allow_insecure", allowInsecure)
            put("vless_sha256", sha256)

            // Hysteria specific overrides
            put("hys_usePortHopping", usePortHopping)
            put("hys_port_hopping_interval", portHoppingInterval)
            put("hys_auth", auth)
            put("hys_obfs", obfs)
            put("hys_up_mbps", upMbps)
            put("hys_down_mbps", downMbps)
        }

        val isNewConfig = intent.getBooleanExtra("IS_NEW_CONFIG", true)

        val arrayToSave = if (!isNewConfig && configId != null) {
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