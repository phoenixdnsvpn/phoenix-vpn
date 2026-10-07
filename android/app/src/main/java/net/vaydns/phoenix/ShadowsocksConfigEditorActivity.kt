package net.vaydns.phoenix

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ShadowsocksConfigEditorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shadowsocks_config_editor)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_shadowsocks)
        toolbar.setNavigationOnClickListener { finish() }

        // =================================================================
        // 1. INITIALIZE ALL VIEWS FIRST
        // =================================================================
        val etName = findViewById<EditText>(R.id.et_ss_name)
        val etIp = findViewById<EditText>(R.id.et_ss_ip)
        val etPort = findViewById<EditText>(R.id.et_ss_port)
        val etPassword = findViewById<EditText>(R.id.et_ss_password)
        val spMethod = findViewById<Spinner>(R.id.sp_ss_method)

        val spNetwork = findViewById<Spinner>(R.id.sp_ss_network)
        val tvHostLabel = findViewById<TextView>(R.id.tv_ss_host_label)
        val etHost = findViewById<EditText>(R.id.et_ss_host)
        val tvPathLabel = findViewById<TextView>(R.id.tv_ss_path_label)
        val etPath = findViewById<EditText>(R.id.et_ss_path)
        val spTlsType = findViewById<Spinner>(R.id.sp_ss_tls_type)

        val layoutTls = findViewById<LinearLayout>(R.id.layout_tls_settings)
        val layoutReality = findViewById<LinearLayout>(R.id.layout_reality_settings)

        val spTlsFingerprint = findViewById<Spinner>(R.id.sp_tls_fingerprint)
        val spTlsAlpn = findViewById<Spinner>(R.id.sp_tls_alpn)
        val spTlsAllowInsecure = findViewById<Spinner>(R.id.sp_tls_allow_insecure)
        val etTlsSha256 = findViewById<EditText>(R.id.et_tls_sha256)

        val spRealityFingerprint = findViewById<Spinner>(R.id.sp_reality_fingerprint)
        val etRealitySni = findViewById<EditText>(R.id.et_reality_sni)
        val etRealityPubkey = findViewById<EditText>(R.id.et_reality_pubkey)
        val etRealityShortid = findViewById<EditText>(R.id.et_reality_shortid)

        fun setupSpinner(spinner: Spinner, items: Array<String>, defaultSelection: Int = 0) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            spinner.setSelection(defaultSelection)
        }

        // Comprehensive Shadowsocks Ciphers
        val ssCiphers = arrayOf(
            "aes-256-gcm", "aes-128-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
            "xchacha20-poly1305", "xchacha20-ietf-poly1305", "none", "plain",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"
        )
        setupSpinner(spMethod, ssCiphers, 3)

        setupSpinner(spNetwork, arrayOf("tcp", "ws", "xhttp", "httpupgrade", "gRPC"), 1)
        setupSpinner(spTlsType, arrayOf("tls", "reality"), 0)
        val fpOptions = arrayOf("", "chrome", "firefox", "safari", "android", "ios")
        setupSpinner(spTlsFingerprint, fpOptions, 1)
        setupSpinner(spRealityFingerprint, fpOptions, 1)
        setupSpinner(spTlsAlpn, arrayOf("", "h2", "http/1.1", "h2,http/1.1"), 0)
        setupSpinner(spTlsAllowInsecure, arrayOf("true", "false"), 0)

        fun updateTransportEncryptionVisibility() {
            if (spTlsType.selectedItem?.toString() == "reality") {
                layoutTls.visibility = View.GONE
                layoutReality.visibility = View.VISIBLE
                tvHostLabel.visibility = View.GONE
                etHost.visibility = View.GONE
            } else {
                layoutTls.visibility = View.VISIBLE
                layoutReality.visibility = View.GONE
                tvHostLabel.visibility = View.VISIBLE
                etHost.visibility = View.VISIBLE
            }
        }

        spNetwork.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedNetwork = parent.getItemAtPosition(position).toString()

                if (selectedNetwork == "tcp") {
                    tvPathLabel.visibility = View.GONE
                    etPath.visibility = View.GONE
                    etPath.setText("")
                } else {
                    tvPathLabel.visibility = View.VISIBLE
                    etPath.visibility = View.VISIBLE
                }

                when (selectedNetwork) {
                    "tcp" -> tvHostLabel.text = "http host"
                    "ws" -> { tvHostLabel.text = "websocket host"; tvPathLabel.text = "websocket path" }
                    "xhttp" -> { tvHostLabel.text = "xhttp host"; tvPathLabel.text = "xhttp path" }
                    "httpupgrade" -> { tvHostLabel.text = "httpupgrade host"; tvPathLabel.text = "httpupgrade path" }
                    "gRPC" -> { tvHostLabel.text = "grpc host"; tvPathLabel.text = "gRPC serviceName" }
                }

                if (selectedNetwork !in listOf("tcp", "xhttp", "gRPC")) {
                    if (spTlsType.selectedItem?.toString() == "reality") {
                        Toast.makeText(this@ShadowsocksConfigEditorActivity, "Reality is only applicable to tcp, xhttp, and gRPC. Reverting to tls.", Toast.LENGTH_LONG).show()
                        spTlsType.setSelection(0)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spTlsType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (parent.getItemAtPosition(position).toString() == "reality" && spNetwork.selectedItem?.toString() !in listOf("tcp", "xhttp", "gRPC")) {
                    Toast.makeText(this@ShadowsocksConfigEditorActivity, "Reality is only applicable to tcp, xhttp, and gRPC.", Toast.LENGTH_SHORT).show()
                    spTlsType.setSelection(0)
                    return
                }
                updateTransportEncryptionVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // =================================================================
        // 2. LOAD EXISTING CONFIG LOGIC
        // =================================================================
        val isNewConfig = intent.getBooleanExtra("IS_NEW_CONFIG", true)
        val editingConfigId = intent.getStringExtra("CONFIG_ID")

        if (!isNewConfig && editingConfigId != null) {
            toolbar.title = "Edit Shadowsocks Config"
            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", MODE_PRIVATE)
            val configsString = sharedPref.getString("configs", "[]") ?: "[]"

            try {
                val jsonArray = JSONArray(configsString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optString("id") == editingConfigId) {
                        etName.setText(obj.optString("name", ""))
                        etIp.setText(obj.optString("vless_ip", ""))
                        etPort.setText(obj.optString("vless_port", "443"))
                        etPassword.setText(obj.optString("ss_password", ""))

                        val methodVal = obj.optString("ss_method", "chacha20-ietf-poly1305")
                        val methodAdapter = spMethod.adapter as? ArrayAdapter<String>
                        methodAdapter?.getPosition(methodVal)?.let { if (it >= 0) spMethod.setSelection(it) }

                        val networkVal = obj.optString("vless_network", "ws")
                        val networkAdapter = spNetwork.adapter as? ArrayAdapter<String>
                        networkAdapter?.getPosition(networkVal)?.let { if (it >= 0) spNetwork.setSelection(it) }

                        etHost.setText(obj.optString("vless_host", ""))
                        etPath.setText(obj.optString("vless_path", ""))

                        val tlsTypeVal = obj.optString("vless_tls_type", "tls")
                        val tlsAdapter = spTlsType.adapter as? ArrayAdapter<String>
                        tlsAdapter?.getPosition(tlsTypeVal)?.let { if (it >= 0) spTlsType.setSelection(it) }

                        if (tlsTypeVal == "reality") {
                            val fpVal = obj.optString("vless_fingerprint", "chrome")
                            val fpAdapter = spRealityFingerprint.adapter as? ArrayAdapter<String>
                            fpAdapter?.getPosition(fpVal)?.let { if (it >= 0) spRealityFingerprint.setSelection(it) }

                            etRealitySni.setText(obj.optString("vless_sni", ""))
                            etRealityPubkey.setText(obj.optString("vless_pubkey", ""))
                            etRealityShortid.setText(obj.optString("vless_shortid", ""))
                        } else {
                            val fpVal = obj.optString("vless_fingerprint", "chrome")
                            val fpAdapter = spTlsFingerprint.adapter as? ArrayAdapter<String>
                            fpAdapter?.getPosition(fpVal)?.let { if (it >= 0) spTlsFingerprint.setSelection(it) }

                            val alpnVal = obj.optString("vless_alpn", "")
                            val alpnAdapter = spTlsAlpn.adapter as? ArrayAdapter<String>
                            alpnAdapter?.getPosition(alpnVal)?.let { if (it >= 0) spTlsAlpn.setSelection(it) }

                            val allowInsecureVal = if (obj.optBoolean("vless_allow_insecure", true)) "true" else "false"
                            val insecureAdapter = spTlsAllowInsecure.adapter as? ArrayAdapter<String>
                            insecureAdapter?.getPosition(allowInsecureVal)?.let { if (it >= 0) spTlsAllowInsecure.setSelection(it) }

                            etTlsSha256.setText(obj.optString("vless_sha256", ""))
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            toolbar.title = "Add Shadowsocks Config"
        }

        // =================================================================
        // 3. SAVE LOGIC (Handles both Create and Update / Overwrite)
        // =================================================================
        findViewById<ImageButton>(R.id.btn_save_shadowsocks).setOnClickListener {
            val name = etName.text.toString().trim()
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().trim().ifEmpty { "443" }
            val password = etPassword.text.toString().trim()

            if (name.isEmpty() || ip.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Name, IP, and Password are required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (name.isEmpty() || ip.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Name, Address, and Password are required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isValidIp(ip) && !isValidDomain(ip)) {
                Toast.makeText(this, "Invalid Address. Must be a valid IP or Domain.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // STRICT DOMAIN VALIDATION FOR HOST & REALITY SNI
            val host = etHost.text.toString().trim()
            if (host.isNotEmpty() && !isValidDomain(host)) {
                Toast.makeText(this, "Invalid Host. Must be a strictly formatted domain name.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (spTlsType.selectedItem.toString() == "reality") {
                val realitySni = etRealitySni.text.toString().trim()
                if (realitySni.isNotEmpty() && !isValidDomain(realitySni)) {
                    Toast.makeText(this, "Invalid Reality SNI. Must be a strictly formatted domain name.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // STRICT REALITY PUBLIC KEY VALIDATION
                val realityPubkey = etRealityPubkey.text.toString().trim()
                if (realityPubkey.isEmpty() || !isValidRealityPublicKey(realityPubkey)) {
                    Toast.makeText(this, "Invalid Reality Public Key. Must be a 43-character Base64URL string.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", MODE_PRIVATE)
            val jsonArray = JSONArray(sharedPref.getString("configs", "[]") ?: "[]")

            val finalAssignedId = editingConfigId ?: UUID.randomUUID().toString()

            val newObj = JSONObject().apply {
                put("id", finalAssignedId)
                put("name", name)
                put("tunnelProtocol", "shadowsocks")
                put("domain", ip) // Fallback domain for list rendering

                put("vless_ip", ip)
                put("vless_port", port)
                put("ss_password", password)
                put("ss_method", spMethod.selectedItem.toString())

                put("vless_network", spNetwork.selectedItem.toString())
                put("vless_host", etHost.text.toString().trim())
                put("vless_path", etPath.text.toString().trim())
                put("vless_tls_type", spTlsType.selectedItem.toString())

                if (spTlsType.selectedItem.toString() == "tls") {
                    put("vless_fingerprint", spTlsFingerprint.selectedItem.toString())
                    put("vless_alpn", spTlsAlpn.selectedItem.toString())
                    put("vless_allow_insecure", spTlsAllowInsecure.selectedItem.toString() == "true")
                    put("vless_sha256", etTlsSha256.text.toString().trim())
                } else {
                    put("vless_fingerprint", spRealityFingerprint.selectedItem.toString())
                    put("vless_sni", etRealitySni.text.toString().trim())
                    put("vless_pubkey", etRealityPubkey.text.toString().trim())
                    put("vless_shortid", etRealityShortid.text.toString().trim())
                }
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

    private fun isValidRealityPublicKey(input: String): Boolean {
        val cleanInput = input.trim()
        if (cleanInput.isEmpty()) return false

        // X25519 keys in Xray are strictly 43-character Base64URL strings
        val regex = "^[A-Za-z0-9_-]{43}$".toRegex()
        return regex.matches(cleanInput)
    }
}