package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.CheckBox
import android.text.Editable
import android.text.TextWatcher
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.widget.AppCompatCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import mobile.Mobile

class ConfigEditorActivity : AppCompatActivity() {

    private var editingConfigId: String? = null
    private var multipathDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var switchMultiDomain: SwitchCompat

    data class ResolverEntry(
        var address: String,
        var isChecked: Boolean = false,
        val isManual: Boolean = false,
        val latency: String = ""
    )

    private val resolverEntries = mutableListOf<ResolverEntry>()
    private lateinit var layoutResolverContainer: LinearLayout
    // Remember last values for each mode
    private var lastUdp = "8.8.8.8:53"
    private var lastTcp = "8.8.8.8:53"
    private var lastDot = "8.8.8.8:853"
    private var lastDoh = "https://dns.google/dns-query"

    private lateinit var tvMultipathStatus: TextView
    private lateinit var btnSelectMultipath: ImageButton
    private lateinit var layoutMultipathControls: LinearLayout
    private lateinit var tvMultipathLabel: TextView
    private lateinit var tvMultipathDesc: TextView
    private lateinit var cbBestCfIp: SwitchCompat

    private fun updateDomainRadioGroup() {
        val domains = if (editingConfigId?.startsWith("default_") == true) {
            val index = editingConfigId!!.removePrefix("default_").toLongOrNull() ?: 0L
            val count = mobile.Mobile.getDefaultConfigDomainCount(index).toInt()
            List(count) { "Domain ${it + 1}" }
        } else {
            findViewById<EditText>(R.id.et_domain).text.toString()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        val rg = findViewById<RadioGroup>(R.id.rg_domain_selector)
        val label = findViewById<TextView>(R.id.tv_domain_selector_label)
        val rbs = arrayOf(
            findViewById<RadioButton>(R.id.rb_domain_1),
            findViewById<RadioButton>(R.id.rb_domain_2),
            findViewById<RadioButton>(R.id.rb_domain_3),
            findViewById<RadioButton>(R.id.rb_domain_4)
        )

        if (domains.size > 1) {
            rg.visibility = View.VISIBLE
            label.visibility = View.VISIBLE
        } else {
            rg.visibility = View.GONE
            label.visibility = View.GONE
        }

        for (i in 0..3) {
            if (i < domains.size) {
                rbs[i].isEnabled = true
                rbs[i].text = domains[i]
            } else {
                rbs[i].isEnabled = false
                rbs[i].text = "Unused"
            }
        }

        val checkedId = rg.checkedRadioButtonId
        val checkedIndex = when (checkedId) {
            R.id.rb_domain_2 -> 1
            R.id.rb_domain_3 -> 2
            R.id.rb_domain_4 -> 3
            else -> 0
        }
        if (checkedIndex >= domains.size && domains.isNotEmpty()) {
            rg.check(R.id.rb_domain_1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

//        Mobile.getDefaultConfigCount()
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_editor)

        toolbar.setNavigationOnClickListener {
            finish() // Closes this window and returns to the Main Menu
        }

        val etName = findViewById<EditText>(R.id.et_config_name)
        val etDomain = findViewById<EditText>(R.id.et_domain)
        switchMultiDomain = findViewById(R.id.switch_multi_domain)
        val etPubkey = findViewById<EditText>(R.id.et_pubkey)
        val etDns = findViewById<EditText>(R.id.et_dns)
        //val btnLoadSavedResolvers = findViewById<ImageButton>(R.id.btn_load_saved_resolvers)
        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
        val spRecordType = findViewById<Spinner>(R.id.sp_record_type)
        val etIdleTimeout = findViewById<EditText>(R.id.et_idle_timeout)
        val etKeepAlive = findViewById<EditText>(R.id.et_keep_alive)
        val etClientIdSize = findViewById<EditText>(R.id.et_client_id_size)
        val etMtu = findViewById<EditText>(R.id.et_mtu)
        val swDnstt = findViewById<SwitchCompat>(R.id.sw_dnstt)
        val swAuth = findViewById<SwitchCompat>(R.id.sw_auth)
        val rgProxyProtocol = findViewById<RadioGroup>(R.id.rg_proxy_protocol)
        val rgAuthProtocol = findViewById<RadioGroup>(R.id.rg_auth_protocol)
        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val btnSave = findViewById<Button>(R.id.btn_save_config)
        val swSshKey = findViewById<SwitchCompat>(R.id.sw_ssh_key)
        val spSsMethod = findViewById<Spinner>(R.id.sp_ss_method)
        val tvUserLabel = findViewById<TextView>(R.id.tv_user_label)
        val tvPassLabel = findViewById<TextView>(R.id.tv_pass_label)
        val tvSsMethodLabel = findViewById<TextView>(R.id.tv_ss_method_label)
        val tvAuthProtocolLabel = findViewById<TextView>(R.id.tv_auth_protocol_label)
        val swUseDefaultResolvers = findViewById<SwitchCompat>(R.id.sw_use_default_resolvers)
        swUseDefaultResolvers.visibility = View.GONE
        val tvProxyProtocolLabel = findViewById<TextView>(R.id.tv_proxy_protocol_label)
        // MULTIPATH BINDINGS
        tvMultipathStatus = findViewById(R.id.tv_multipath_status)
        btnSelectMultipath = findViewById(R.id.btn_select_multipath)
        layoutMultipathControls = findViewById(R.id.layout_multipath_controls)
        tvMultipathLabel = findViewById(R.id.tv_multipath_label)
        tvMultipathDesc = findViewById(R.id.tv_multipath_desc)

        cbBestCfIp = findViewById(R.id.cb_best_cf_ip)

        cbBestCfIp.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
// Fetch ALL IPs from the JSON Vault for the Layer 7 scanner
                val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
                val jsonString = prefs.getString("vault_ips_json", "[]") ?: "[]"
                val allIpsList = mutableListOf<String>()

                try {
                    val jsonArray = org.json.JSONArray(jsonString)
                    for (i in 0 until jsonArray.length()) {
                        allIpsList.add(jsonArray.getJSONObject(i).getString("ip"))
                    }
                } catch (e: Exception) { e.printStackTrace() }

                val savedIps = allIpsList.joinToString(",")

                if (savedIps.isBlank()) {
                    Toast.makeText(this, "No IPs in Global Settings!", Toast.LENGTH_SHORT).show()
                    buttonView.isChecked = false
                    return@setOnCheckedChangeListener
                }

                Toast.makeText(this, "Racing Global IPs in background...", Toast.LENGTH_SHORT).show()
                buttonView.isEnabled = false
                // Layer 7 latency measurement
                Thread {
                    val isDefault = editingConfigId?.startsWith("default_") == true
                    val cIndex = if (isDefault) editingConfigId?.removePrefix("default_")?.toLongOrNull() ?: -1L else -1L

                    // Grab the domain currently typed into the editor (Adjust the ID to match your domain EditText if needed)
                    val etDomain = findViewById<EditText>(R.id.et_domain)
                    val currentDomain = etDomain?.text?.toString()?.trim() ?: ""

                    // Call the NEW Layer 7 Scanner
                    val result = Mobile.getFastestCloudflareIP(isDefault, cIndex, savedIps, currentDomain)

                    runOnUiThread {
                        buttonView.isEnabled = true
                        buttonView.isChecked = false

                        if (result.isNotEmpty() && result.contains("|")) {
                            val parts = result.split("|")
                            val bestIp = parts[0]
                            val latency = parts[1]

                            val etVlessIp = findViewById<EditText>(R.id.et_vless_ip)
                            etVlessIp.setText(bestIp)
                            Toast.makeText(this@ConfigEditorActivity, "Winner: $bestIp (${latency}ms)", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@ConfigEditorActivity, "All IPs failed the Layer 7 Handshake.", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()

                // Layer 4 latency measurement
                /*Thread {
                    val isDefault = editingConfigId?.startsWith("default_") == true
                    val cIndex = if (isDefault) editingConfigId?.removePrefix("default_")?.toLongOrNull() ?: -1L else -1L

                    // Call our new native Go scanner
                    val result = Mobile.pingBestDirectIP(isDefault, cIndex, savedIps, "direct", "vless-ws")

                    runOnUiThread {
                        buttonView.isEnabled = true
                        buttonView.isChecked = false // Reset toggle

                        if (result.isNotEmpty() && result.contains("|")) {
                            val parts = result.split("|")
                            val bestIp = parts[0]
                            val latency = parts[1]

                            // TARGET THE CORRECT EDIT TEXT
                            val etVlessIp = findViewById<EditText>(R.id.et_vless_ip)
                            etVlessIp.setText(bestIp)
                            Toast.makeText(this@ConfigEditorActivity, "Winner: $bestIp (${latency}ms)", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@ConfigEditorActivity, "All Global IPs failed to respond.", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()*/
            }
        }

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

        // Load Data into memory (does not build UI yet)
        setupMultipathData(editingConfigId ?: "new_temp_config")

        // The clock icon now opens the dedicated Full Screen Activity window
        btnSelectMultipath.setOnClickListener {
            val mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_tcp -> "tcp"
                R.id.rb_tls -> "dot"
                R.id.rb_https -> "doh"
                else -> "udp"
            }
            val intent = Intent(this, MultipathResolverActivity::class.java).apply {
                putExtra("CONFIG_ID", editingConfigId ?: "new_temp_config")
                putExtra("TUNNEL_MODE", mode)
            }
            startActivity(intent)
        }

        if (editingConfigId != null) {

            if (isDefault) {
                //  HIDE ALL MULTIPATH UI FOR DEFAULTS

                val index = editingConfigId!!.removePrefix("default_").toLongOrNull() ?: 0L
                etName.setText(mobile.Mobile.getDefaultConfigName(index))
                etDomain.setText("----------")
                etPubkey.setText("----------")

                val originalName = mobile.Mobile.getDefaultConfigName(index).ifEmpty { "Official Server ${index + 1}" }
                val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                val savedName = prefs.getString("${editingConfigId}_name", originalName)
                val savedDns = prefs.getString("${editingConfigId}_dns", "8.8.8.8:53")
                val savedMode = prefs.getString("${editingConfigId}_mode", "udp")
                val savedMtu = prefs.getLong("${editingConfigId}_mtu", 0L)
                val savedUseMulti = prefs.getBoolean("${editingConfigId}_useMultiDomains", false)

                val savedDomainIndex = prefs.getInt("${editingConfigId}_domainIndex", 0)
                val selectedId = when (savedDomainIndex) {
                    1 -> R.id.rb_domain_2
                    2 -> R.id.rb_domain_3
                    3 -> R.id.rb_domain_4
                    else -> R.id.rb_domain_1
                }
                findViewById<RadioGroup>(R.id.rg_domain_selector).check(selectedId)
                updateDomainRadioGroup()

                etName.setText(savedName)
                etName.isEnabled = true
                switchMultiDomain.isChecked = savedUseMulti

                etName.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val currentText = s?.toString() ?: ""
                        if (!currentText.startsWith(originalName)) {
                            // If they delete any part of the original name, force it back
                            etName.setText(originalName)
                            etName.setSelection(originalName.length)
                        }
                    }
                })

                swUseDefaultResolvers.visibility = View.VISIBLE
                swUseDefaultResolvers.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        val defaultResolversStr = mobile.Mobile.getDefaultConfigDisplayResolvers(index)

                        if (defaultResolversStr.isEmpty()) {
                            Toast.makeText(this, "No default resolvers found. Update from menu.", Toast.LENGTH_SHORT).show()
                            swUseDefaultResolvers.isChecked = false
                        } else {
                            val ipArray = defaultResolversStr.split(",").toTypedArray()

                            MaterialAlertDialogBuilder(this)
                                .setTitle("Select Official Resolver")
                                .setItems(ipArray) { _, which ->
                                    etDns.setText(ipArray[which])
                                    when (rgMode.checkedRadioButtonId) {
                                        R.id.rb_udp -> lastUdp = ipArray[which]
                                        R.id.rb_tcp -> lastTcp = ipArray[which]
                                        R.id.rb_tls -> lastDot = ipArray[which]
                                        R.id.rb_https -> lastDoh = ipArray[which]
                                    }
                                    // Uncheck toggle so it can be clicked again later if needed
                                    swUseDefaultResolvers.isChecked = false
                                }
                                .setNegativeButton("Cancel") { _, _ ->
                                    swUseDefaultResolvers.isChecked = false
                                }
                                .show()
                        }
                    }
                }

                etDns.setText(savedDns)
                when(savedMode) {
                    "tcp" -> rgMode.check(R.id.rb_tcp)
                    "dot" -> rgMode.check(R.id.rb_tls)
                    "doh" -> rgMode.check(R.id.rb_https)
                    else -> rgMode.check(R.id.rb_udp)
                }
                etMtu.setText(savedMtu.toString())
                val rt = mobile.Mobile.getDefaultConfigRecordType(index)
                val rtIndex = recordTypes.indexOf(rt.uppercase())
                if (rtIndex >= 0) spRecordType.setSelection(rtIndex) // Set selection for defaults

                etIdleTimeout.setText(mobile.Mobile.getDefaultConfigIdleTimeout(index))
                etKeepAlive.setText(mobile.Mobile.getDefaultConfigKeepAlive(index))

                etClientIdSize.setText(mobile.Mobile.getDefaultConfigClientIdSize(index).toString())
                swDnstt.isChecked = mobile.Mobile.getDefaultConfigDnsttCompatible(index)

                val ssMethod = mobile.Mobile.getDefaultConfigMethod(index)
                val user = ""
                val pass = ""
                val useSshKey = mobile.Mobile.getDefaultConfigUseSshKey(index)

                // EXTRACT NATIVE PROTOCOLS
                val nativeProto = mobile.Mobile.getDefaultConfigProtocol(index)
                val authProto = if (nativeProto == "ssh" || nativeProto == "shadowsocks") nativeProto else "basic"

                etUser.setText(user)
                etPass.setText(pass)

                // SET RADIO BUTTONS
                rgProxyProtocol.check(R.id.rb_proxy_socks) // Official servers are always SOCKS5 proxy

                when(authProto.lowercase()) {
                    "ssh" -> rgAuthProtocol.check(R.id.rb_auth_ssh)
                    "shadowsocks" -> rgAuthProtocol.check(R.id.rb_auth_shadowsocks)
                    else -> rgAuthProtocol.check(R.id.rb_auth_socks)
                }

                swSshKey.isChecked = useSshKey
                swSshKey.isEnabled = false

                etPass.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()

                swAuth.isChecked = (user.isNotEmpty() || pass.isNotEmpty())
                swAuth.isEnabled = false

                val proxyType = mobile.Mobile.getDefaultConfigProxy(index)
                if (proxyType.lowercase() == "http") {
                    rgProxyProtocol.check(R.id.rb_proxy_http)
                } else {
                    rgProxyProtocol.check(R.id.rb_proxy_socks)
                }

                tvProxyProtocolLabel.visibility = View.VISIBLE
                rgProxyProtocol.visibility = View.VISIBLE


                // VISUAL PROTECTION: Disable interacting with the locked default configs
                for (i in 0 until rgProxyProtocol.childCount) {
                    val v = rgProxyProtocol.getChildAt(i)
                    v.isEnabled = false
                    v.alpha = 0.5f
                }

                for (i in 0 until rgAuthProtocol.childCount) {
                    val v = rgAuthProtocol.getChildAt(i)
                    v.isEnabled = false
                    v.alpha = 0.5f
                }

                swAuth.isEnabled = false
                etUser.isEnabled = false
                etPass.isEnabled = false
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
                tvUserLabel.visibility = View.GONE
                tvPassLabel.visibility = View.GONE
                etMtu.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tv_mtu_label).visibility = View.VISIBLE

                // HIDE ADVANCED INPUTS FROM DEFAULTS
                spRecordType.visibility = View.GONE
                etIdleTimeout.visibility = View.GONE
                etKeepAlive.visibility = View.GONE
                etClientIdSize.visibility = View.GONE
                swDnstt.visibility = View.GONE
                swAuth.visibility = View.GONE
                swSshKey.visibility = View.GONE
                spSsMethod.visibility = View.GONE
                etUser.visibility = View.GONE
                etPass.visibility = View.GONE
                rgProxyProtocol.visibility = View.VISIBLE
                rgAuthProtocol.visibility = View.GONE
                tvAuthProtocolLabel.visibility = View.GONE
                // Hide the new Radio Groups
                //rgProxyProtocol.visibility = View.GONE

                val parentLayout = etName.parent as ViewGroup
                for (i in 0 until parentLayout.childCount) {
                    val view = parentLayout.getChildAt(i)
                    if (view is TextView && view !is Button && view !is EditText) {
                        val txt = view.text.toString()
                        val forbiddenLabels = listOf(
                            "Tunnel Domain:", "Server Public Key:",
                            "Following parameters", "Record Type:",
                            "Idle Timeout:", "Keep Alive:",
                            // "Client ID Size:", "Local Proxy Protocol:", "Authentication Protocol"
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
                tvProxyProtocolLabel.visibility = View.GONE
                rgProxyProtocol.visibility = View.GONE

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
                        etMtu, config.mtu,
                        swDnstt, config.dnsttCompatible,
                        swAuth, config.useAuth,
                        swSshKey, config.useSshKey,
                        rgProxyProtocol, config.protocol,
                        rgAuthProtocol, config.authProtocol,
                        spSsMethod, config.ssMethod,
                        etUser, config.user,
                        etPass, config.pass,
                        tvUserLabel, tvPassLabel,
                        config.useMultiDomains,
                        config.domainIndex
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
            tvProxyProtocolLabel.visibility = View.GONE
            rgProxyProtocol.visibility = View.GONE
            // Set Default Protocols
            swAuth.isChecked = false
            rgProxyProtocol.check(R.id.rb_proxy_socks)
            rgAuthProtocol.check(R.id.rb_auth_socks)
            swSshKey.isChecked = false

            // --- THIS IS THE MANUALLY TRIGGERED INITIAL STATE ---
            // Even though the views are visible, we lock Auth inputs because swAuth is false
            etUser.isEnabled = false
            etPass.isEnabled = false
            swSshKey.isEnabled = false

            etUser.visibility = View.VISIBLE
            etPass.visibility = View.VISIBLE
            swSshKey.visibility = View.VISIBLE
            spSsMethod.visibility = View.VISIBLE
            tvUserLabel.visibility = View.VISIBLE
            tvPassLabel.visibility = View.VISIBLE

            // Only disable the AUTH group, NOT the Proxy group!
            for (i in 0 until rgAuthProtocol.childCount) {
                val v = rgAuthProtocol.getChildAt(i)
                v.isEnabled = false
                v.alpha = 0.5f
            }
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
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                etPass.minLines = 15
                etPass.maxLines = 40
                etPass.setHorizontallyScrolling(false)
                etPass.gravity = android.view.Gravity.TOP
                etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
            } else {
                // Restore defaults for Password mode
                tvUserLabel.text = "User:"
                etUser.hint = "Optional"
                tvPassLabel.text = "Password:"
                etPass.hint = "Optional"
                //etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT
                etPass.minLines = 1
                etPass.maxLines = 1
                etPass.gravity = android.view.Gravity.CENTER_VERTICAL
                //etPass.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
            }
        }

        etDomain.addTextChangedListener(object: TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (editingConfigId?.startsWith("default_") != true) {
                    updateDomainRadioGroup()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Smart mode switching with value memory
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_udp -> etDns.setText(lastUdp)
                R.id.rb_tcp -> etDns.setText(lastTcp)
                R.id.rb_tls -> etDns.setText(lastDot)
                R.id.rb_https -> etDns.setText(lastDoh)
            }
        }

        rgAuthProtocol.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_auth_ssh -> {
                    swSshKey.isEnabled = swAuth.isChecked
                    tvUserLabel.visibility = View.VISIBLE
                    etUser.visibility = View.VISIBLE
                    tvSsMethodLabel.visibility = View.GONE
                    spSsMethod.visibility = View.GONE
                }
                R.id.rb_auth_shadowsocks -> {
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false
                    tvUserLabel.visibility = View.GONE
                    etUser.visibility = View.GONE
                    tvSsMethodLabel.visibility = View.VISIBLE
                    spSsMethod.visibility = View.VISIBLE
                }
                else -> { // Basic
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false
                    tvUserLabel.visibility = View.VISIBLE
                    etUser.visibility = View.VISIBLE
                    tvSsMethodLabel.visibility = View.GONE
                    spSsMethod.visibility = View.GONE
                }
            }

        }

        swAuth.setOnCheckedChangeListener { _, isChecked ->
            // 1. Enable/Disable User and Password fields
            etUser.isEnabled = isChecked
            etPass.isEnabled = isChecked

            // 2. Enable/Disable the Protocol RadioGroup buttons
            for (i in 0 until rgAuthProtocol.childCount) {
                val v = rgAuthProtocol.getChildAt(i)
                v.isEnabled = isChecked
                v.alpha = if (isChecked) 1.0f else 0.5f
            }

            // 3. Handle the SSH Key toggle logic
            // It should only be enabled if Auth is ON AND the selected protocol is SSH
            if (isChecked && rgAuthProtocol.checkedRadioButtonId == R.id.rb_auth_ssh) {
                swSshKey.isEnabled = true
            } else {
                swSshKey.isEnabled = false
            }
        }

        val spinnerTunnelProtocol = findViewById<Spinner>(R.id.spinner_tunnel_protocol)

        // 1. Determine which protocols this specific config is allowed to use
        val supportedProtocols = if (isDefault) {
            val nativeIndex = editingConfigId?.removePrefix("default_")?.toLongOrNull() ?: 0L
            val types = mobile.Mobile.getDefaultConfigType(nativeIndex).split(",").map { it.trim().lowercase() }
            if (types.isEmpty() || types[0] == "") listOf("vaydns") else types
        } else {
            listOf("vaydns")
        }

        val tpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedProtocols)
        tpAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTunnelProtocol.adapter = tpAdapter

        // 2. Load the currently saved value
        val currentProtocol = if (isDefault) {
            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${editingConfigId}_tunnelProtocol", null) ?: supportedProtocols.firstOrNull() ?: "vaydns"
        } else {
            val configs = loadAllConfigs(this)
            val config = configs.find { it.id == editingConfigId }
            config?.tunnelProtocol ?: "vaydns"
        }

        val pIndex = supportedProtocols.indexOf(currentProtocol)
        if (pIndex >= 0) spinnerTunnelProtocol.setSelection(pIndex)

        val tvVlessIpLabel = findViewById<TextView>(R.id.tv_vless_ip_label)
        val etVlessIp = findViewById<EditText>(R.id.et_vless_ip)

        // 1. Load the currently saved value
        val currentVlessIp = if (isDefault) {
            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${editingConfigId}_vlessIp", "") ?: ""
        } else {
            val configs = loadAllConfigs(this)
            val config = configs.find { it.id == editingConfigId }
            config?.vlessIp ?: ""
        }
        etVlessIp.setText(currentVlessIp)
        if (!mobile.Mobile.isOfficialBuild()) {
            // 1. Hide the Tunnel Protocol Selection
            findViewById<TextView>(R.id.tv_tunnel_protocol_label)?.visibility = View.GONE
            spinnerTunnelProtocol.visibility = View.GONE

            // 2. Hide the VLESS IP configuration
            tvVlessIpLabel.visibility = View.GONE
            etVlessIp.visibility = View.GONE
        }
// 2. Add Listener to toggle visibility and states dynamically
        spinnerTunnelProtocol.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val selected = parent.getItemAtPosition(position).toString().lowercase().trim()
                val isVaydns = selected == "vaydns"
                val visibilityState = if (isVaydns) View.VISIBLE else View.GONE

                // 1. Handle Vless IP visibility universally
                if (selected == "vless-ws" || selected == "vless-httpupgrade") {
                    tvVlessIpLabel.visibility = View.VISIBLE
                    etVlessIp.visibility = View.VISIBLE
                    cbBestCfIp.visibility = View.VISIBLE
                } else {
                    tvVlessIpLabel.visibility = View.GONE
                    etVlessIp.visibility = View.GONE
                    cbBestCfIp.visibility = View.GONE
                    cbBestCfIp.isChecked = false
                }

                if (!isDefault) {
                    // =================================================================
                    // CUSTOM CONFIGS: Toggle all fields dynamically
                    // =================================================================
                    val vaydnsFields = listOf<View?>(
                        etDomain, switchMultiDomain, etPubkey,
                        etDns, (etDns.parent as? ViewGroup),
                        rgMode, btnSelectMultipath, spRecordType, etIdleTimeout,
                        etKeepAlive, etClientIdSize, etMtu, swDnstt, swAuth, swSshKey,
                        rgProxyProtocol, rgAuthProtocol, spSsMethod, etUser, etPass,
                        layoutMultipathControls, swUseDefaultResolvers,

                        findViewById(R.id.tv_user_label), findViewById(R.id.tv_pass_label),
                        findViewById(R.id.tv_multipath_label), findViewById(R.id.tv_multipath_desc),
                        findViewById(R.id.tv_multipath_status), findViewById(R.id.tv_proxy_protocol_label),
                        findViewById(R.id.tv_auth_protocol_label), findViewById(R.id.tv_ss_method_label),
                        findViewById(R.id.tv_mtu_label), findViewById(R.id.tv_domain_label),
                        findViewById(R.id.tv_pubkey_label), findViewById(R.id.tv_dns_label),
                        findViewById(R.id.tv_mode_label), findViewById(R.id.tv_record_type_label),
                        findViewById(R.id.tv_idle_timeout_label), findViewById(R.id.tv_keep_alive_label),
                        findViewById(R.id.tv_client_id_size_label)
                    )

                    for (v in vaydnsFields) {
                        v?.visibility = visibilityState
                        v?.isEnabled = isVaydns
                        v?.alpha = if (isVaydns) 1.0f else 0.3f
                    }

                    if (!isVaydns) {
                        swAuth.isChecked = false
                        swSshKey.isChecked = false
                    } else {
                        // Smart Recovery for Custom Configs
                        val isAuthOn = swAuth.isChecked
                        val authProtoId = rgAuthProtocol.checkedRadioButtonId
                        val tvSsMethodLabelLocal = findViewById<TextView>(R.id.tv_ss_method_label)
                        val tvUserLabelLocal = findViewById<TextView>(R.id.tv_user_label)

                        if (authProtoId == R.id.rb_auth_shadowsocks) {
                            tvUserLabelLocal?.visibility = View.GONE
                            etUser.visibility = View.GONE
                            tvSsMethodLabelLocal?.visibility = View.VISIBLE
                            spSsMethod.visibility = View.VISIBLE
                        } else {
                            tvUserLabelLocal?.visibility = View.VISIBLE
                            etUser.visibility = View.VISIBLE
                            tvSsMethodLabelLocal?.visibility = View.GONE
                            spSsMethod.visibility = View.GONE
                        }

                        etUser.isEnabled = isAuthOn
                        etPass.isEnabled = isAuthOn

                        val sshAllowed = isAuthOn && authProtoId == R.id.rb_auth_ssh
                        swSshKey.isEnabled = sshAllowed
                        swSshKey.alpha = if (sshAllowed) 1.0f else 0.3f
                        if (!sshAllowed) swSshKey.isChecked = false

                        for (i in 0 until rgAuthProtocol.childCount) {
                            val child = rgAuthProtocol.getChildAt(i)
                            child.isEnabled = isAuthOn
                            child.alpha = if (isAuthOn) 1.0f else 0.5f
                        }
                    }

                } else {
                    // =================================================================
                    // OFFICIAL (DEFAULT) CONFIGS: Strict Lockdown
                    // =================================================================

                    // 1. Only toggle these few allowed fields for Phoenix
                    val allowedDefaultFields = listOf<View?>(
                        etMtu, findViewById(R.id.tv_mtu_label),
                        switchMultiDomain, swUseDefaultResolvers,
                        findViewById(R.id.tv_domain_selector_label), findViewById(R.id.rg_domain_selector),
                        findViewById(R.id.tv_dns_label), etDns, (etDns.parent as? ViewGroup),
                        findViewById(R.id.tv_multipath_label), findViewById(R.id.tv_multipath_desc),
                        findViewById(R.id.tv_multipath_status), layoutMultipathControls, btnSelectMultipath,
                        findViewById(R.id.tv_mode_label), rgMode,
                        findViewById(R.id.tv_proxy_protocol_label), rgProxyProtocol
                    )
                    for (v in allowedDefaultFields) {
                        v?.visibility = visibilityState
                    }

                    // 2. ABSOLUTE HIDE: Ensure forbidden fields are NEVER shown for official configs!
                    val forbiddenDefaultFields = listOf<View?>(
                        etDomain, etPubkey, spRecordType, etIdleTimeout,
                        etKeepAlive, etClientIdSize, swDnstt, swAuth, swSshKey,
                        rgAuthProtocol, spSsMethod, etUser, etPass,

                        findViewById(R.id.tv_user_label), findViewById(R.id.tv_pass_label),
                        findViewById(R.id.tv_auth_protocol_label), findViewById(R.id.tv_ss_method_label),
                        findViewById(R.id.tv_domain_label), findViewById(R.id.tv_pubkey_label),
                        findViewById(R.id.tv_record_type_label), findViewById(R.id.tv_idle_timeout_label),
                        findViewById(R.id.tv_keep_alive_label), findViewById(R.id.tv_client_id_size_label)
                    )

                    for (v in forbiddenDefaultFields) {
                        v?.visibility = View.GONE // Force permanent hide
                    }

                    // Strictly force switches OFF so they don't apply ghost data
                    // swAuth.isChecked = false
                    // swSshKey.isChecked = false
                }

                // Keep the dynamic resolver field safe
                updateDnsFieldState()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // 2. Add Listener to toggle visibility dynamically
        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Config name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_tcp -> "tcp"
                R.id.rb_tls -> "dot"
                R.id.rb_https -> "doh"
                else -> "udp"
            }
            val ssMethod = spSsMethod.selectedItem.toString()
            val domain = etDomain.text.toString().trim()
            val pubkey = etPubkey.text.toString().trim()
            val dns = etDns.text.toString().trim()
            val clientIdSize = etClientIdSize.text.toString().toLongOrNull() ?: 2L
            val configId = intent.getStringExtra("CONFIG_ID") ?: "user_${System.currentTimeMillis()}"
            val mtu = etMtu.text.toString().toLongOrNull() ?: 0L

            if ( mtu != 0L && (mtu < 30 || mtu > 130)) {
                Toast.makeText(this, "Invalid MTU: Please enter a value between 30 and 130, or 0 for default.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val dnstt = swDnstt.isChecked
            val useAuth = swAuth.isChecked
            val useSshKey = swSshKey.isChecked
            val useMultiDomains = switchMultiDomain.isChecked
            val proxyProtocol = if (rgProxyProtocol.checkedRadioButtonId == R.id.rb_proxy_http) "http" else "socks5"
            val authProtocol = when (rgAuthProtocol.checkedRadioButtonId) {
                R.id.rb_auth_ssh -> "ssh"
                R.id.rb_auth_shadowsocks -> "shadowsocks"
                else -> "socks"
            }
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()
            val rt = spRecordType.selectedItem.toString()
            val selectedTunnelProtocol = spinnerTunnelProtocol.selectedItem.toString()
            val selectedVlessIp = etVlessIp.text.toString().trim()

            val selectedDomainIndex = when (findViewById<RadioGroup>(R.id.rg_domain_selector).checkedRadioButtonId) {
                R.id.rb_domain_2 -> 1
                R.id.rb_domain_3 -> 2
                R.id.rb_domain_4 -> 3
                else -> 0
            }

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

            // Save the current value for future switching
            when (rgMode.checkedRadioButtonId) {
                R.id.rb_udp -> lastUdp = dns
                R.id.rb_tcp -> lastTcp = dns
                R.id.rb_tls -> lastDot = dns
                R.id.rb_https -> lastDoh = dns
            }

            // 1. Save 10 Manual Rows (Persistent inputs)
            val manualAddrs = resolverEntries.filter { it.isManual }.map { it.address }
            java.io.File(filesDir, "manual_resolvers_$configId.txt").writeText(manualAddrs.joinToString("\n"))

            // 2. Save Selected Multipath (Final list for Go engine)
            val selectedAddrs = resolverEntries
                .filter { it.isChecked && it.address.isNotEmpty() }
                .mapNotNull { sanitizeResolverInput(it.address, mode) }

            //  FORMATTING RULE: Assign appropriate ports/URLs based on Tunnel Mode
            val engineResolvers = if (selectedAddrs.isEmpty()) {
                listOf(dns)
            } else {
                selectedAddrs.map { res ->
                    when (mode) {
                        "doh" -> if (res.startsWith("https://") || res.startsWith("http://")) res else "https://$res/dns-query"
                        "dot" -> if (res.contains(":")) res else "$res:853"
                        else ->  if (res.contains(":")) res else "$res:53" // Covers UDP and TCP
                    }
                }
            }

            java.io.File(filesDir, "selected_multipath_$configId.txt").writeText(selectedAddrs.joinToString("\n"))

            // PASS configId TO THIS FUNCTION
            saveOrUpdateConfig(
                configId,
                name, domain, pubkey, dns, mode, rt, idle, keep,
                clientIdSize, mtu,dnstt, useAuth, useSshKey, proxyProtocol,
                authProtocol, ssMethod, user, pass, useMultiDomains, selectedTunnelProtocol,
                selectedVlessIp, selectedDomainIndex
            )
            finish()

        }

        if (editingConfigId == null) {
            // Since default is Socks, force SSH Key switch to be disabled
            swSshKey.isChecked = false
            swSshKey.isEnabled = false
            swAuth.isChecked = false
        }

        updateDnsFieldState()
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255 // Safely checks for null before evaluating range
        }
    }

    private fun isValidIpv4WithOptionalPort(input: String): Boolean {
        if (input.contains(":")) {
            val parts = input.split(":")
            if (parts.size != 2) return false
            val port = parts[1].toIntOrNull()
            return isValidIpv4(parts[0]) && port != null && port in 1..65535
        }
        return isValidIpv4(input)
    }

    private fun sanitizeResolverInput(input: String, mode: String): String? {
        val parsedLines = input.split(Regex("[\\s,;]+"))
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }

        for (trimmed in parsedLines) {
            when (mode.lowercase()) {
                "doh" -> {
                    if (trimmed.startsWith("https://") || isValidIpv4WithOptionalPort(trimmed)) {
                        return trimmed
                    }
                }
                else -> {
                    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && isValidIpv4WithOptionalPort(trimmed)) {
                        return trimmed
                    }
                }
            }
        }
        return null
    }

    private fun updateDnsFieldState() {
        val selectedFile = java.io.File(filesDir, "selected_multipath_${editingConfigId ?: "new_temp_config"}.txt")
        val hasSelections = selectedFile.exists() && selectedFile.readLines().any { it.trim().isNotEmpty() }

        val etDns = findViewById<EditText>(R.id.et_dns)
        val spinnerTunnelProtocol = findViewById<Spinner>(R.id.spinner_tunnel_protocol)

        val isVaydns = spinnerTunnelProtocol?.selectedItem?.toString()?.lowercase()?.trim() == "vaydns"

        // Only manage the lockouts if the field is actually visible on screen
        if (isVaydns) {
            etDns.isEnabled = !hasSelections
            etDns.alpha = if (hasSelections) 0.5f else 1.0f

            if (hasSelections) {
                etDns.hint = "Disabled (Multipath active)"
            } else {
                etDns.hint = "8.8.8.8:53"
            }
        }
    }

    private fun setupMultipathData(configId: String) {
        resolverEntries.clear()

        // 1. Load Scanner Results FIRST (They will appear at the top of the list)
        val scanFile = java.io.File(filesDir, "resolvers_$configId.txt")
        if (scanFile.exists()) {
            scanFile.readLines().forEach { line ->
                val parts = line.split(",") // Split the "IP,Latency" format
                val ip = parts[0].trim()
                val latencyVal = if (parts.size > 1) parts[1].trim() else ""

                if (ip.isNotEmpty()) {
                    resolverEntries.add(ResolverEntry(ip, isChecked = false, isManual = false, latency = latencyVal))
                }
            }
        }

        // 2. Load 10 Persistent Manual Rows SECOND (They will appear at the bottom)
        val manualFile = java.io.File(filesDir, "manual_resolvers_$configId.txt")
        val savedManuals = if (manualFile.exists()) manualFile.readLines() else emptyList()

        for (i in 0 until 20) {
            val addr = savedManuals.getOrNull(i) ?: ""
            resolverEntries.add(ResolverEntry(addr, isChecked = false, isManual = true, latency = ""))
        }

        // 3. Restore Selection State
        val selectedFile = java.io.File(filesDir, "selected_multipath_$configId.txt")
        val currentSelections = if (selectedFile.exists()) selectedFile.readLines().toSet() else emptySet()

        resolverEntries.forEach { entry ->
            if (currentSelections.contains(entry.address)) entry.isChecked = true
        }

        updateMultipathStatus()
    }

    private fun updateMultipathStatus() {
        val count = resolverEntries.count { it.isChecked }
        if (::tvMultipathStatus.isInitialized) {
            tvMultipathStatus.text = "$count IPs selected"
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
        etMtu: EditText, mtuValue: Long,
        swDnstt: SwitchCompat, dnsttValue: Boolean,
        swAuth: SwitchCompat, useAuth: Boolean,
        swSshKey: SwitchCompat, useSshKey: Boolean,
        rgProxyProtocol: RadioGroup, proxyProtocolValue: String,
        rgAuthProtocol: RadioGroup, authProtocolValue: String,
        spSsMethod: Spinner, ssMethodValue: String,
        etUser: EditText, userValue: String,
        etPass: EditText, passValue: String,
        tvUserLabel: TextView, tvPassLabel: TextView,
        useMultiDomains: Boolean,
        domainIndex: Int
    ) {
        // 1. Basic Text Fields
        etName.setText(nameValue)
        etDomain.setText(domainValue)
        switchMultiDomain.isChecked = useMultiDomains
        etPubkey.setText(pubkeyValue)
        etDns.setText(dnsValue)
        etIdleTimeout.setText(idleValue)
        etKeepAlive.setText(keepValue)
        etClientIdSize.setText(clientIdValue.toString())
        etMtu.setText(mtuValue.toString())
        etUser.setText(userValue)
        etPass.setText(passValue)

        val selectedId = when (domainIndex) {
            1 -> R.id.rb_domain_2
            2 -> R.id.rb_domain_3
            3 -> R.id.rb_domain_4
            else -> R.id.rb_domain_1
        }
        findViewById<RadioGroup>(R.id.rg_domain_selector).check(selectedId)
        updateDomainRadioGroup()

        val adapter = spSsMethod.adapter as? ArrayAdapter<String>
        if (adapter != null) {
            val methodIndex = adapter.getPosition(ssMethodValue)
            if (methodIndex >= 0) spSsMethod.setSelection(methodIndex)
        }

        etUser.isEnabled = useAuth
        etPass.isEnabled = useAuth

        // ONLY disable the Auth group based on the Auth Switch (leave Proxy Group alone)
        for (i in 0 until rgAuthProtocol.childCount) {
            val v = rgAuthProtocol.getChildAt(i)
            v.isEnabled = useAuth
            v.alpha = if (useAuth) 1.0f else 0.5f
        }

        // 2. Mode Logic & Memory (Restored)
        when (modeValue.lowercase()) {
            "tcp" -> {
                rgMode.check(R.id.rb_tcp)
                lastTcp = dnsValue
            }
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

        // 3. Proxy Protocol Selection
        when (proxyProtocolValue.lowercase()) {
            "http" -> rgProxyProtocol.check(R.id.rb_proxy_http)
            else -> rgProxyProtocol.check(R.id.rb_proxy_socks)
        }

        // 4. Auth Protocol Selection
        when (authProtocolValue.lowercase()) {
            "ssh" -> {
                rgAuthProtocol.check(R.id.rb_auth_ssh)
                swSshKey.isEnabled = true
                swSshKey.isChecked = useSshKey
            }
            "shadowsocks" -> {
                rgAuthProtocol.check(R.id.rb_auth_shadowsocks)
                swSshKey.isEnabled = false
                swSshKey.isChecked = false
            }
            else -> {
                rgAuthProtocol.check(R.id.rb_auth_socks)
                swSshKey.isEnabled = false
                swSshKey.isChecked = false
            }
        }

        // 5. SSH Key Label Logic & Visibility (Restored)
        if (useSshKey && authProtocolValue.lowercase() == "ssh") {
            tvPassLabel.text = "SSH Private Key"
            etPass.hint = "Paste your private key here..."
            etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            etPass.minLines = 15
            etPass.maxLines = 40
            etPass.setHorizontallyScrolling(false)
            etPass.gravity = android.view.Gravity.TOP
            etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
        } else {
            tvPassLabel.text = "Password:"
            etPass.hint = "Optional"
            //etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT
            etPass.minLines = 1
            etPass.maxLines = 1
            etPass.gravity = android.view.Gravity.CENTER_VERTICAL
            //etPass.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
        }

        // Dynamic visibility for SS Method vs Username based on auth type
        val tvSsMethodLabel = findViewById<TextView>(R.id.tv_ss_method_label)
        when (authProtocolValue.lowercase()) {
            "ssh", "socks" -> {
                tvUserLabel.visibility = View.VISIBLE
                etUser.visibility = View.VISIBLE
                tvSsMethodLabel?.visibility = View.GONE
                spSsMethod.visibility = View.GONE
            }
            "shadowsocks" -> {
                tvUserLabel.visibility = View.GONE
                etUser.visibility = View.GONE
                tvSsMethodLabel?.visibility = View.VISIBLE
                spSsMethod.visibility = View.VISIBLE
            }
        }

        // 6. Auth and Extras
        swDnstt.isChecked = dnsttValue
        swAuth.isChecked = useAuth || userValue.isNotEmpty() || passValue.isNotEmpty()
        if (rtIndex >= 0) spRecordType.setSelection(rtIndex)

        etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
    }

    private fun populateJsonObject(
        obj: JSONObject, name: String, domain: String, pubkey: String, dns: String,
        mode: String, recordType: String, idleTimeout: String, keepAlive: String,
        clientIdSize: Long, mtu: Long, dnsttCompatible: Boolean, useAuth: Boolean,
        useSshKey: Boolean, proxyProtocolValue: String, authProtocolValue: String,
        ssMethod: String, user: String, pass: String, useMultiDomains: Boolean,
        tunnelProtocol: String, vlessIp: String, domainIndex: Int
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
        obj.put("mtu", mtu)
        obj.put("dnsttCompatible", dnsttCompatible)
        obj.put("useAuth", useAuth)
        obj.put("useSshKey", useSshKey)
        obj.put("protocol", proxyProtocolValue)
        obj.put("authProtocol", authProtocolValue)
        obj.put("ssMethod", ssMethod)
        obj.put("user", user)
        obj.put("pass", pass)
        obj.put("useMultiDomains", useMultiDomains)
        obj.put("domainIndex", domainIndex)
        obj.put("tunnelProtocol", tunnelProtocol)
        obj.put("vlessIp", vlessIp)
    }

    private fun saveOrUpdateConfig(
        passedConfigId: String,
        name: String,
        domain: String,
        pubkey: String,
        dns: String,
        mode: String,
        recordType: String,
        idleTimeout: String,
        keepAlive: String,
        clientIdSize: Long,
        mtu: Long,
        dnsttCompatible: Boolean,
        useAuth: Boolean,
        useSshKey: Boolean,
        proxyProtocolValue: String,
        authProtocolValue: String,
        ssMethod: String,
        user: String,
        pass: String,
        useMultiDomains: Boolean,
        tunnelProtocol: String,
        selectedVlessIp: String,
        domainIndex: Int
    ) {
        if (editingConfigId?.startsWith("default_") == true) {
            val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("${editingConfigId}_name", name)
                putString("${editingConfigId}_dns", dns)
                putString("${editingConfigId}_mode", mode)
                putLong("${editingConfigId}_mtu", mtu)
                putBoolean("${editingConfigId}_useMultiDomains", useMultiDomains)
                putString("${editingConfigId}_tunnelProtocol", tunnelProtocol)
                putString("${editingConfigId}_vlessIp", selectedVlessIp)
                putInt("${editingConfigId}_domainIndex", domainIndex)
            }.apply()
            finish()
            return
        }

        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        val configsString = sharedPref.getString("configs", "[]") ?: "[]"
        val jsonArray = JSONArray(configsString)

        // FIX: This tracks the absolute final configuration token string written to memory
        val finalAssignedId: String

        if (editingConfigId != null) {
            finalAssignedId = editingConfigId!!
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("id") == editingConfigId) {
                    populateJsonObject(obj, name, domain, pubkey, dns, mode, recordType,
                        idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible, useAuth,
                        useSshKey, proxyProtocolValue, authProtocolValue, ssMethod, user, pass,
                        useMultiDomains, tunnelProtocol, selectedVlessIp, domainIndex)
                    break
                }
            }
        } else {
            // Generate the real random string ID before making file moves
            finalAssignedId = java.util.UUID.randomUUID().toString()
            val newObj = JSONObject()
            newObj.put("id", finalAssignedId)
            populateJsonObject(newObj, name, domain, pubkey, dns, mode, recordType,
                idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible, useAuth,
                useSshKey, proxyProtocolValue, authProtocolValue, ssMethod, user, pass,
                useMultiDomains, tunnelProtocol, selectedVlessIp, domainIndex)
            jsonArray.put(newObj)

            // File remapping targets now point to finalAssignedId, not temp placeholders!
            val tempManual = java.io.File(filesDir, "manual_resolvers_new_temp_config.txt")
            if (tempManual.exists()) {
                tempManual.renameTo(java.io.File(filesDir, "manual_resolvers_${finalAssignedId}.txt"))
            }

            val tempSelected = java.io.File(filesDir, "selected_multipath_new_temp_config.txt")
            if (tempSelected.exists()) {
                tempSelected.renameTo(java.io.File(filesDir, "selected_multipath_${finalAssignedId}.txt"))
            }

            val tempScanned = java.io.File(filesDir, "resolvers_new_temp_config.txt")
            if (tempScanned.exists()) {
                tempScanned.renameTo(java.io.File(filesDir, "resolvers_${finalAssignedId}.txt"))
            }
        }

        sharedPref.edit().putString("configs", jsonArray.toString()).apply()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the subtitle summary values immediately upon returning to window
        val currentId = editingConfigId ?: "new_temp_config"
        setupMultipathData(currentId)
        updateDnsFieldState()
    }

    companion object {
        fun loadAllConfigs(context: Context): List<Config> {
            val sharedPref = context.getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
            val jsonStr = sharedPref.getString("configs", "[]") ?: "[]"
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Config>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val finalProxyProto = obj.optString("protocol", "socks5")
                val finalAuthProto = obj.optString("authProtocol", "socks")

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
                        mtu = obj.optLong("mtu", 0L),
                        dnsttCompatible = obj.optBoolean("dnsttCompatible", false),
                        useAuth = obj.optBoolean("useAuth", false),
                        useSshKey = obj.optBoolean("useSshKey", false),
                        protocol = finalProxyProto,
                        authProtocol = finalAuthProto,
                        ssMethod = obj.optString("ssMethod", "chacha20-ietf-poly1305"),
                        user = obj.optString("user", ""),
                        pass = obj.optString("pass", ""),
                        useMultiDomains = obj.optBoolean("useMultiDomains", false),
                        domainIndex = obj.optInt("domainIndex", 0),
                        tunnelProtocol = obj.optString("tunnelProtocol", "vaydns"),
                        vlessIp = obj.optString("vlessIp", ""),
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
                        put("mtu", config.mtu)
                        put("dnsttCompatible", config.dnsttCompatible)

                        // --- New Protocol & Auth Fields ---
                        put("useAuth", config.useAuth)
                        put("useSshKey", config.useSshKey)
                        put("protocol", config.protocol)
                        put("authProtocol", config.authProtocol)
                        put("ssMethod", config.ssMethod)
                        put("user", config.user)
                        put("pass", config.pass)
                        put("useMultiDomains", config.useMultiDomains)
                        put("tunnelProtocol", config.tunnelProtocol)
                        put("vlessIp", config.vlessIp)
                        put("domainIndex", config.domainIndex)
                    }
                    array.put(obj)
                }
            }

            sharedPref.edit().putString("configs", array.toString()).apply()
        }
    }
}