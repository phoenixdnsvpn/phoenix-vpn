package com.net2share.vaydns

import android.content.Context
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import android.view.View
import android.view.ViewGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import mobile.Mobile

class ConfigEditorActivity : AppCompatActivity() {

    private var editingConfigId: String? = null

    // Remember last values for each mode
    private var lastUdp = "8.8.8.8:53"
    private var lastDot = "8.8.8.8:853"
    private var lastDoh = "https://dns.google/dns-query"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

//        Mobile.getDefaultConfigCount()
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_editor)

        toolbar.setNavigationOnClickListener {
            finish() // Closes this window and returns to the Main Menu
        }

//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//        supportActionBar?.title = "Config parameters"

        val etName = findViewById<EditText>(R.id.et_config_name)
        val etDomain = findViewById<EditText>(R.id.et_domain)
        val etPubkey = findViewById<EditText>(R.id.et_pubkey)
        val etDns = findViewById<EditText>(R.id.et_dns)
        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
        val spRecordType = findViewById<Spinner>(R.id.sp_record_type)
        val etIdleTimeout = findViewById<EditText>(R.id.et_idle_timeout)
        val etKeepAlive = findViewById<EditText>(R.id.et_keep_alive)
        val etClientIdSize = findViewById<EditText>(R.id.et_client_id_size)
        val swDnstt = findViewById<SwitchCompat>(R.id.sw_dnstt)
        val swAuth = findViewById<SwitchCompat>(R.id.sw_auth)
        val rgProtocol = findViewById<RadioGroup>(R.id.rg_protocol)
        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val btnSave = findViewById<Button>(R.id.btn_save_config)
        val swSshKey = findViewById<SwitchCompat>(R.id.sw_ssh_key)
        val spSsMethod = findViewById<Spinner>(R.id.sp_ss_method)
        val tvUserLabel = findViewById<TextView>(R.id.tv_user_label)
        val tvPassLabel = findViewById<TextView>(R.id.tv_pass_label)
        val tvSsMethodLabel = findViewById<TextView>(R.id.tv_ss_method_label)

        etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()

        val ssMethods = arrayOf("chacha20-ietf-poly1305", "aes-128-gcm", "aes-256-gcm", "xchacha20-ietf-poly1305")
        val ssAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ssMethods)
        ssAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSsMethod.adapter = ssAdapter

        val recordTypes = arrayOf("TXT", "NULL", "CNAME", "A", "AAAA", "MX", "NS", "SRV", "CAA")
//        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, recordTypes)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            recordTypes
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRecordType.adapter = adapter

        editingConfigId = intent.getStringExtra("CONFIG_ID")
        val isDefault = editingConfigId?.startsWith("default_") == true

        if (editingConfigId != null) {
//            supportActionBar?.title = "Edit Config"
            if (isDefault) {
                val index = editingConfigId!!.removePrefix("default_").toLongOrNull() ?: 0L
//                etName.setText(DefaultConfigs.getConfigName(index))

                etName.setText(mobile.Mobile.getDefaultConfigName(index))
                etDomain.setText(mobile.Mobile.getDefaultConfigDomain(index))
                etPubkey.setText(mobile.Mobile.getDefaultConfigPubkey(index))

                val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                val savedDns = prefs.getString("${editingConfigId}_dns", "8.8.8.8:53")
                val savedMode = prefs.getString("${editingConfigId}_mode", "udp")

                etDns.setText(savedDns)
                when(savedMode) {
                    "dot" -> rgMode.check(R.id.rb_tls)
                    "doh" -> rgMode.check(R.id.rb_https)
                    else -> rgMode.check(R.id.rb_udp)
                }

                val rt = mobile.Mobile.getDefaultConfigRecordType(index)
                val rtIndex = recordTypes.indexOf(rt.uppercase())
                if (rtIndex >= 0) spRecordType.setSelection(rtIndex) // Set selection for defaults

                etIdleTimeout.setText(mobile.Mobile.getDefaultConfigIdleTimeout(index))
                etKeepAlive.setText(mobile.Mobile.getDefaultConfigKeepAlive(index))

                etClientIdSize.setText(mobile.Mobile.getDefaultConfigClientIdSize(index).toString())
                swDnstt.isChecked = mobile.Mobile.getDefaultConfigDnsttCompatible(index)

                val ssMethod = mobile.Mobile.getDefaultConfigMethod(index)
                val user = mobile.Mobile.getDefaultConfigUser(index)
                val pass = mobile.Mobile.getDefaultConfigPass(index)
                val protocol = mobile.Mobile.getDefaultConfigProtocol(index) // e.g. "socks", "ssh"
                val useSshKey = mobile.Mobile.getDefaultConfigUseSshKey(index)

                etUser.setText(user)
                etPass.setText(pass)

                when(protocol.lowercase()) {
                    "ssh" -> rgProtocol.check(R.id.rb_ssh)
                    "shadowsocks" -> rgProtocol.check(R.id.rb_shadowsocks)
                    else -> rgProtocol.check(R.id.rb_socks)
                }
                swSshKey.isChecked = useSshKey
                swSshKey.isEnabled = false

                etPass.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()

                swAuth.isChecked = (user.isNotEmpty() || pass.isNotEmpty())
                swAuth.isEnabled = false

                rgProtocol.check(R.id.rb_socks)

                for (i in 0 until rgProtocol.childCount) {
                    val v = rgProtocol.getChildAt(i)
                    v.isEnabled = false
                    v.alpha = 0.5f // Visual cue that they are disabled
                }

                // Visual protection for defaults

                rgProtocol.check(R.id.rb_socks)
                swAuth.isEnabled = false
                etUser.isEnabled = false
                etPass.isEnabled = false
                etName.isEnabled = false
                etDomain.isEnabled = false
                etPubkey.isEnabled = false
                spRecordType.isEnabled = false
                etIdleTimeout.isEnabled = false
                etKeepAlive.isEnabled = false
                etClientIdSize.isEnabled = false
                swDnstt.isEnabled = false

                etDomain.setText("----------")
                etPubkey.setText("----------")
                toolbar.title = "Edit Default Parameters"

                etDomain.visibility = View.GONE
                etPubkey.visibility = View.GONE
                // We target the labels by finding them via their text if they don't have IDs,
                // but usually, it's safer to hide the ones we know:
                tvUserLabel.visibility = View.GONE
                tvPassLabel.visibility = View.GONE

                // Hide Advanced Inputs
                spRecordType.visibility = View.GONE
                etIdleTimeout.visibility = View.GONE
                etKeepAlive.visibility = View.GONE
                etClientIdSize.visibility = View.GONE
                swDnstt.visibility = View.GONE
                swAuth.visibility = View.GONE
                rgProtocol.visibility = View.GONE
                swSshKey.visibility = View.GONE
                spSsMethod.visibility = View.GONE
                etUser.visibility = View.GONE
                etPass.visibility = View.GONE

                val parentLayout = etName.parent as ViewGroup
                for (i in 0 until parentLayout.childCount) {
                    val view = parentLayout.getChildAt(i)
                    if (view is TextView && view !is Button && view !is EditText) {
                        val txt = view.text.toString()
                        val forbiddenLabels = listOf(
                            "Tunnel Domain:", "Server Public Key:",
                            "Following parameters", "Record Type:",
                            "Idle Timeout:", "Keep Alive:",
                            "Client ID Size:", "Protocol"
                        )
                        if (forbiddenLabels.any { txt.contains(it) }) {
                            view.visibility = View.GONE
                        }
                    }
                }

            } else {
                // 1. Load the data to calculate the index and values before calling the helper
                val configs = loadAllConfigs(this)
                val config = configs.find { it.id == editingConfigId }

                if (config != null) {
                    val rtIndex = recordTypes.indexOf(config.recordType.uppercase())

                    // 2. Call the helper with the calculated values
                    loadConfigForEditing(
                        etName, config.name,
                        etDomain, config.domain,
                        etPubkey, config.pubkey,
                        etDns, config.dnsAddress,
                        rgMode, config.mode,
                        spRecordType, rtIndex,
                        etIdleTimeout, config.idleTimeout,
                        etKeepAlive, config.keepAlive,
                        etClientIdSize, config.clientIdSize,
                        swDnstt, config.dnsttCompatible,
                        swAuth, config.useAuth,
                        swSshKey, config.useSshKey,
                        rgProtocol, config.protocol,
                        spSsMethod, config.ssMethod,
                        etUser, config.user,
                        etPass, config.pass,
                        tvUserLabel, tvPassLabel // Pass the labels here
                    )
                }
                toolbar.title = "Edit Config"
            }
        } else {
            // --- 3. ADD NEW CONFIG MODE ---
            toolbar.title = "Add New Config"

            // Set standard defaults
            rgMode.check(R.id.rb_udp)
            etDns.setText(lastUdp)
            etIdleTimeout.setText("10s")
            etKeepAlive.setText("2s")
            etClientIdSize.setText("2")

            // Set Auth defaults
            swAuth.isChecked = false
            rgProtocol.check(R.id.rb_socks)
            swSshKey.isChecked = false

            // --- THIS IS THE MANUALLY TRIGGERED INITIAL STATE ---
            // Even though the views are visible, we lock them because swAuth is false
            etUser.isEnabled = false
            etPass.isEnabled = false
            swSshKey.isEnabled = false
            // Ensure they are visible (incase XML had them hidden)
            etUser.visibility = View.VISIBLE
            etPass.visibility = View.VISIBLE
            swSshKey.visibility = View.VISIBLE
            spSsMethod.visibility = View.VISIBLE
            tvUserLabel.visibility = View.VISIBLE
            tvPassLabel.visibility = View.VISIBLE

            for (i in 0 until rgProtocol.childCount) {
                val v = rgProtocol.getChildAt(i)
                v.isEnabled = false
                v.alpha = 0.5f
            }
            swSshKey.isEnabled = false
        }

        swSshKey.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Clear and lock User field
                etUser.setText("User")
                //etUser.hint = "Optional"
                etUser.isEnabled = true
                etPass.isEnabled = true
                tvPassLabel.text = "SSH Private Key:"
                etPass.hint = "Paste Private Key here"

                // Force plain text visibility for the key
                etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
            } else {
                // Restore defaults for Password mode
                tvUserLabel.text = "User:"
                etUser.hint = "Optional"
                tvPassLabel.text = "Password:"
                etPass.hint = "Optional"
            }
        }

        // Smart mode switching with value memory
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_udp -> etDns.setText(lastUdp)
                R.id.rb_tls -> etDns.setText(lastDot)
                R.id.rb_https -> etDns.setText(lastDoh)
            }
        }

        rgProtocol.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_ssh -> {
                    swSshKey.isEnabled = swAuth.isChecked

                    tvUserLabel.visibility = View.VISIBLE
                    etUser.visibility = View.VISIBLE
                    tvSsMethodLabel.visibility = View.GONE
                    spSsMethod.visibility = View.GONE
                }
                R.id.rb_shadowsocks -> {
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false

                    // Shadowsocks uses a Method instead of a User
                    tvUserLabel.visibility = View.GONE
                    etUser.visibility = View.GONE
                    tvSsMethodLabel.visibility = View.VISIBLE
                    spSsMethod.visibility = View.VISIBLE
                }
                else -> {
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false

                    tvUserLabel.visibility = View.VISIBLE
                    etUser.visibility = View.VISIBLE
                    tvSsMethodLabel.visibility = View.GONE
                    spSsMethod.visibility = View.GONE
                }
            }
        }

        /*rgProtocol.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_ssh -> {
                    // Only enable the toggle if the master Auth switch is also ON
                    swSshKey.isEnabled = swAuth.isChecked
                }
                else -> {
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false
                }
            }
        }*/

        swAuth.setOnCheckedChangeListener { _, isChecked ->
            // 1. Enable/Disable User and Password fields
            etUser.isEnabled = isChecked
            etPass.isEnabled = isChecked

            // 2. Enable/Disable the Protocol RadioGroup buttons
            for (i in 0 until rgProtocol.childCount) {
                val v = rgProtocol.getChildAt(i)
                v.isEnabled = isChecked
                // Optional: reduce alpha to make it look clearly disabled
                v.alpha = if (isChecked) 1.0f else 0.5f
            }

            // 3. Handle the SSH Key toggle logic
            // It should only be enabled if Auth is ON AND the selected protocol is SSH
            if (isChecked && rgProtocol.checkedRadioButtonId == R.id.rb_ssh) {
                swSshKey.isEnabled = true
            } else {
                swSshKey.isEnabled = false
            }
        }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Config name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ssMethod = spSsMethod.selectedItem.toString()
            val domain = etDomain.text.toString().trim()
            val pubkey = etPubkey.text.toString().trim()
            val dns = etDns.text.toString().trim()
            val clientIdSize = etClientIdSize.text.toString().toLongOrNull() ?: 2L
            val dnstt = swDnstt.isChecked
            val useAuth = swAuth.isChecked
            val useSshKey = swSshKey.isChecked
            val protocol = when (rgProtocol.checkedRadioButtonId) {
                R.id.rb_ssh -> "ssh"
                R.id.rb_shadowsocks -> "shadowsocks"
                else -> "socks"
            }
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()
            val rt = spRecordType.selectedItem.toString()

            // --- SANITY CHECK START ---
            fun normalizeDuration(input: String, default: String): String {
                val raw = input.lowercase().trim()
                if (raw.isEmpty()) return default

                return when {
                    // If the user already specified ms or s, leave it exactly as is
                    raw.endsWith("ms") || raw.endsWith("s") -> raw

                    // If it's just a number, append "s" as the default unit
                    raw.all { it.isDigit() } -> "${raw}s"

                    // Fallback for invalid characters
                    else -> default
                }
            }

            val idle = normalizeDuration(etIdleTimeout.text.toString(), "10s")
            val keep = normalizeDuration(etKeepAlive.text.toString(), "2s")

            val mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_tls -> "dot"
                R.id.rb_https -> "doh"
                else -> "udp"
            }

            // Save the current value for future switching
            when (rgMode.checkedRadioButtonId) {
                R.id.rb_udp -> lastUdp = dns
                R.id.rb_tls -> lastDot = dns
                R.id.rb_https -> lastDoh = dns
            }

            saveOrUpdateConfig(
                name, domain, pubkey, dns, mode, rt, idle, keep,
                clientIdSize, dnstt, useAuth, useSshKey, protocol, ssMethod, user, pass
            )
            finish()

        }

        if (editingConfigId == null) {
            // Since default is Socks, force SSH Key switch to be disabled
            swSshKey.isChecked = false
            swSshKey.isEnabled = false
            swAuth.isChecked = false
        }

    }

    private fun loadConfigForEditing(
        etName: EditText, nameValue: String,
        etDomain: EditText, domainValue: String,
        etPubkey: EditText, pubkeyValue: String,
        etDns: EditText, dnsValue: String,
        rgMode: RadioGroup, modeValue: String,
        spRecordType: Spinner, rtIndex: Int,
        etIdleTimeout: EditText, idleValue: String,
        etKeepAlive: EditText, keepValue: String,
        etClientIdSize: EditText, clientIdValue: Long,
        swDnstt: SwitchCompat, dnsttValue: Boolean,
        swAuth: SwitchCompat, useAuth: Boolean,
        swSshKey: SwitchCompat, useSshKey: Boolean,
        rgProtocol: RadioGroup, protocolValue: String,
        spSsMethod: Spinner, ssMethodValue: String,
        etUser: EditText, userValue: String,
        etPass: EditText, passValue: String,
        tvUserLabel: TextView, tvPassLabel: TextView // Added these to update labels
    ) {
        // 1. Basic Text Fields
        etName.setText(nameValue)
        etDomain.setText(domainValue)
        etPubkey.setText(pubkeyValue)
        etDns.setText(dnsValue)
        etIdleTimeout.setText(idleValue)
        etKeepAlive.setText(keepValue)
        etClientIdSize.setText(clientIdValue.toString())
        etUser.setText(userValue)
        etPass.setText(passValue)
        val adapter = spSsMethod.adapter as ArrayAdapter<String>
        val methodIndex = adapter.getPosition(ssMethodValue)
        if (methodIndex >= 0) spSsMethod.setSelection(methodIndex)

        etUser.isEnabled = useAuth
        etPass.isEnabled = useAuth

        for (i in 0 until rgProtocol.childCount) {
            val v = rgProtocol.getChildAt(i)
            v.isEnabled = useAuth
            v.alpha = if (useAuth) 1.0f else 0.5f
        }

        // 2. Mode Logic & Memory (Restored)
        when (modeValue.lowercase()) {
            "dot" -> {
                rgMode.check(R.id.rb_tls)
                lastDot = dnsValue
            }
            "doh" -> {
                rgMode.check(R.id.rb_https)
                lastDoh = dnsValue
            }
            else -> {
                rgMode.check(R.id.rb_udp)
                lastUdp = dnsValue
            }
        }

        // 3. Protocol Selection
        when (protocolValue.lowercase()) {
            "ssh" -> {
                rgProtocol.check(R.id.rb_ssh)
                swSshKey.isEnabled = true
                swSshKey.isChecked = useSshKey
            }
            "shadowsocks" -> {
                rgProtocol.check(R.id.rb_shadowsocks)
                swSshKey.isEnabled = false
                swSshKey.isChecked = false
            }
            else -> {
                rgProtocol.check(R.id.rb_socks)
                swSshKey.isEnabled = false
                swSshKey.isChecked = false
            }
        }

        // 4. SSH Key Label Logic
        if (useSshKey && protocolValue.lowercase() == "ssh") {
            tvPassLabel.text = "SSH Private Key"
            etPass.hint = "Paste your private key here..."
//            swSshKey.isEnabled = true
        } else {
            tvPassLabel.text = "Password"
            etPass.hint = ""
//            swSshKey.isEnabled = false
        }

        // 5. Auth and Extras
        swDnstt.isChecked = dnsttValue
        swAuth.isChecked = useAuth || userValue.isNotEmpty() || passValue.isNotEmpty()
        if (rtIndex >= 0) spRecordType.setSelection(rtIndex)

        etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
//        syncAuthUi(useAuth)
    }

    private fun saveOrUpdateConfig(
        name: String,
        domain: String,
        pubkey: String,
        dns: String,
        mode: String,
        recordType: String,
        idleTimeout: String,
        keepAlive: String,
        clientIdSize: Long,      // Consistent with Go/Data Class
        dnsttCompatible: Boolean,
        useAuth: Boolean,
        useSshKey: Boolean,
        protocol: String,
        ssMethod: String,
        user: String,
        pass: String
    ) {
        if (editingConfigId?.startsWith("default_") == true) {
            // Save only DNS and Mode to a separate preference file
            val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("${editingConfigId}_dns", dns)
                putString("${editingConfigId}_mode", mode)
            }.apply()

//            Toast.makeText(this, "Default parameters updated!", Toast.LENGTH_SHORT).show()
            // IMPORTANT: finish() here so it doesn't run the user-config save logic below
            finish()
            return
        }

// 2. Handle User Configs
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        val configsString = sharedPref.getString("configs", "[]") ?: "[]"
        val jsonArray = JSONArray(configsString)

        if (editingConfigId != null) {
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("id") == editingConfigId) {
                    populateJsonObject(obj, name, domain, pubkey, dns, mode, recordType,
                        idleTimeout, keepAlive, clientIdSize, dnsttCompatible, useAuth, useSshKey, protocol, ssMethod, user, pass)
                    break
                }
            }
        } else {
            val newObj = JSONObject()
            newObj.put("id", java.util.UUID.randomUUID().toString())
            populateJsonObject(newObj, name, domain, pubkey, dns, mode, recordType,
                idleTimeout, keepAlive, clientIdSize, dnsttCompatible, useAuth, useSshKey, protocol, ssMethod, user, pass)
            jsonArray.put(newObj)
        }

        sharedPref.edit().putString("configs", jsonArray.toString()).apply()
//        Toast.makeText(this, "Config saved!", Toast.LENGTH_SHORT).show()
    }

    private fun populateJsonObject(
        obj: JSONObject, name: String, domain: String, pubkey: String, dns: String, mode: String,
        recordType: String, idleTimeout: String, keepAlive: String,
        clientIdSize: Long, dnsttCompatible: Boolean, useAuth: Boolean, useSshKey: Boolean, protocol: String, ssMethod: String, user: String, pass: String
    ) {
        obj.put("name", name)
        obj.put("domain", domain)
        obj.put("pubkey", pubkey)
        obj.put("dnsAddress", dns)
        obj.put("mode", mode)
        obj.put("recordType", recordType)
        obj.put("idleTimeout", idleTimeout)
        obj.put("keepAlive", keepAlive)
        obj.put("clientIdSize", clientIdSize)
        obj.put("dnsttCompatible", dnsttCompatible)
        obj.put("useAuth", useAuth)
        obj.put("useSshKey", useSshKey)
        obj.put("protocol", protocol)
        obj.put("ssMethod", ssMethod)
        obj.put("user", user)
        obj.put("pass", pass)
    }

    companion object {
        fun loadAllConfigs(context: Context): List<Config> {
            val sharedPref = context.getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
            val jsonStr = sharedPref.getString("configs", "[]") ?: "[]"
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Config>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Config(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        domain = obj.getString("domain"),
                        pubkey = obj.getString("pubkey"),
                        dnsAddress = obj.getString("dnsAddress"),
                        mode = obj.getString("mode"),
                        recordType = obj.optString("recordType", "TXT"),
                        idleTimeout = obj.optString("idleTimeout", "10s"),
                        keepAlive = obj.optString("keepAlive", "2s"),
                        clientIdSize = obj.optLong("clientIdSize", 2),
                        dnsttCompatible = obj.optBoolean("dnsttCompatible", false),

                        // --- Updated Protocol & Auth Fields ---
                        useAuth = obj.optBoolean("useAuth", false),
                        useSshKey = obj.optBoolean("useSshKey", false),
                        protocol = obj.optString("protocol", "socks"),
                        ssMethod = obj.optString("ssMethod", "chacha20-ietf-poly1305"),
                        user = obj.optString("user", ""),
                        pass = obj.optString("pass", ""),
                        isDefault = false // User configs are never default
                    )
                )
            }
            return list
        }

        fun saveAllConfigs(context: Context, configs: List<Config>) {
            val sharedPref = context.getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
            val array = JSONArray()

            configs.forEach { config ->
                // We only save custom user configs to SharedPreferences.
                // Official/Default configs are handled by the .so library or DefaultOverrides.
                if (!config.isDefault) {
                    val obj = JSONObject().apply {
                        put("id", config.id)
                        put("name", config.name)
                        put("domain", config.domain)
                        put("pubkey", config.pubkey)
                        put("dnsAddress", config.dnsAddress)
                        put("mode", config.mode)
                        put("recordType", config.recordType)
                        put("idleTimeout", config.idleTimeout)
                        put("keepAlive", config.keepAlive)
                        put("clientIdSize", config.clientIdSize)
                        put("dnsttCompatible", config.dnsttCompatible)

                        // --- New Protocol & Auth Fields ---
                        put("useAuth", config.useAuth)
                        put("useSshKey", config.useSshKey)
                        put("protocol", config.protocol)
                        put("ssMethod", config.ssMethod)
                        put("user", config.user)
                        put("pass", config.pass)
                    }
                    array.put(obj)
                }
            }

            sharedPref.edit().putString("configs", array.toString()).apply()
        }
    }
}