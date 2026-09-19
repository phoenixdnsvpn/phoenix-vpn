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
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import mobile.Mobile

class ConfigEditorActivity : AppCompatActivity() {

    private var editingConfigId: String? = null
    private lateinit var switchMultiDomain: SwitchCompat
    private var sshUserCache = ""
    private var sshPassCache = ""
    private var ssPassCache = ""
    private var basicUserCache = ""
    private var basicPassCache = ""
    private var currentAuthMode = "socks"
    private var isInitializing = true
    private var realVlessIp = ""

    data class ResolverEntry(
        var address: String,
        var isChecked: Boolean = false,
        val isManual: Boolean = false,
        val latency: String = ""
    )

    private val resolverEntries = mutableListOf<ResolverEntry>()
    private var lastUdp = "8.8.8.8:53"
    private var lastTcp = "8.8.8.8:53"
    private var lastDot = "8.8.8.8:853"
    private var lastDoh = "https://dns.google/dns-query"

    private lateinit var tvMultipathStatus: TextView
    private lateinit var btnSelectMultipath: ImageButton
    private lateinit var layoutMultipathControls: LinearLayout
    private lateinit var tvMultipathLabel: TextView
    private lateinit var tvMultipathDesc: TextView

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

    private fun isValidTLSCertificate(certData: String): Boolean {
        if (certData.isBlank()) return false
        return try {
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            val stream = if (certData.contains("-----BEGIN CERTIFICATE-----")) {
                java.io.ByteArrayInputStream(certData.toByteArray(Charsets.UTF_8))
            } else {
                val decoded = android.util.Base64.decode(
                    certData,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
                )
                java.io.ByteArrayInputStream(decoded)
            }
            cf.generateCertificate(stream)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isValidSSHPrivateKey(keyData: String): Boolean {
        if (keyData.isBlank()) return false
        // Check for standard PEM/OpenSSH headers and footers
        return keyData.contains("-----BEGIN ") && keyData.contains(" PRIVATE KEY-----")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_editor)
        toolbar.setNavigationOnClickListener { finish() }

        val etName = findViewById<EditText>(R.id.et_config_name)
        val etDomain = findViewById<EditText>(R.id.et_domain)
        switchMultiDomain = findViewById(R.id.switch_multi_domain)
        val etPubkey = findViewById<EditText>(R.id.et_pubkey)
        val etDns = findViewById<EditText>(R.id.et_dns)
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
        val swSshKey = findViewById<SwitchCompat>(R.id.sw_ssh_key)
        val spSsMethod = findViewById<Spinner>(R.id.sp_ss_method)
        val tvUserLabel = findViewById<TextView>(R.id.tv_user_label)
        val tvPassLabel = findViewById<TextView>(R.id.tv_pass_label)
        val tvSsMethodLabel = findViewById<TextView>(R.id.tv_ss_method_label)
        val tvAuthProtocolLabel = findViewById<TextView>(R.id.tv_auth_protocol_label)
        val swUseDefaultResolvers = findViewById<SwitchCompat>(R.id.sw_use_default_resolvers)
        swUseDefaultResolvers.visibility = View.GONE
        val tvProxyProtocolLabel = findViewById<TextView>(R.id.tv_proxy_protocol_label)
        val tvPubkeyLabel = findViewById<TextView>(R.id.tv_pubkey_label)
        val layoutSlipstreamParams = findViewById<LinearLayout>(R.id.layout_slipstream_params)
        val spSlipstreamCongestion = findViewById<Spinner>(R.id.sp_slipstream_congestion)
        val swSlipstreamAuthoritative = findViewById<SwitchCompat>(R.id.sw_slipstream_authoritative)
        val swSlipstreamGso = findViewById<SwitchCompat>(R.id.sw_slipstream_gso)

        val congestionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("BBR", "DCUBIC"))
        congestionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSlipstreamCongestion.adapter = congestionAdapter

        swSlipstreamAuthoritative.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, "Only use when DNS resolver is your own server. Public resolvers (Google, Cloudflare, etc.) will rate limit and block your connection.", Toast.LENGTH_LONG).show()
            }
        }

        val tvSlipstreamAuthWarning = findViewById<TextView>(R.id.tv_slipstream_auth_warning)
        swSlipstreamAuthoritative.setOnCheckedChangeListener { _, isChecked ->
            // Replaces the Toast with the inline red text
            tvSlipstreamAuthWarning.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val swSlipstreamCert = findViewById<SwitchCompat>(R.id.sw_slipstream_cert)
        swSlipstreamCert.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etPubkey.visibility = View.VISIBLE
                etPubkey.hint = "Paste Public Certificate (PEM) here..."
                etPubkey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                etPubkey.minLines = 15
                etPubkey.maxLines = 60
                etPubkey.setHorizontallyScrolling(false)
                etPubkey.gravity = android.view.Gravity.TOP
            } else {
                etPubkey.visibility = View.GONE
                //etPubkey.setText("")
            }
        }

        tvMultipathStatus = findViewById(R.id.tv_multipath_status)
        btnSelectMultipath = findViewById(R.id.btn_select_multipath)
        layoutMultipathControls = findViewById(R.id.layout_multipath_controls)
        tvMultipathLabel = findViewById(R.id.tv_multipath_label)
        tvMultipathDesc = findViewById(R.id.tv_multipath_desc)

        // NEW: MasterDNS Spinner Initialization
        val tvMasterDnsMethodLabel = findViewById<TextView>(R.id.tv_masterdns_method_label)
        val spMasterDnsMethod = findViewById<Spinner>(R.id.sp_masterdns_method)
        val masterDnsMethods = arrayOf("None", "XOR", "Chacha20", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM")
        val masterDnsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, masterDnsMethods)
        masterDnsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMasterDnsMethod.adapter = masterDnsAdapter

        // =================================================================
        // Toggle Encryption Key visibility when MasterDNS method changes
        // =================================================================
        spMasterDnsMethod.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedMethod = parent.getItemAtPosition(position).toString()
                val currentTunnelProto = findViewById<Spinner>(R.id.spinner_tunnel_protocol)?.selectedItem?.toString()?.lowercase()?.trim()

                // Only toggle if MasterDNS is actually the active protocol tab
                if (currentTunnelProto == "masterdns") {
                    if (selectedMethod.equals("None", ignoreCase = true)) {
                        tvPubkeyLabel?.visibility = View.GONE
                        etPubkey.visibility = View.GONE
                    } else {
                        tvPubkeyLabel?.visibility = View.VISIBLE
                        etPubkey.visibility = View.VISIBLE
                        tvPubkeyLabel?.text = "Encryption Key:"
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        val btnBestCfIp = findViewById<Button>(R.id.btn_best_cf_ip)
        btnBestCfIp.setOnClickListener { buttonView ->
            val btn = buttonView as Button
            btn.text = "Scanning ..."
            btn.isEnabled = false

            val selectedCdn = findViewById<Spinner>(R.id.spinner_editor_cdn).selectedItem?.toString() ?: "CloudX"
            val spinnerTunnelProtocol = findViewById<Spinner>(R.id.spinner_tunnel_protocol)
            val selectedTunnelProtocol = spinnerTunnelProtocol?.selectedItem?.toString() ?: "vaydns"
            val spinnerEditorPort = findViewById<Spinner>(R.id.spinner_editor_port)
            val selectedPortStr = spinnerEditorPort?.selectedItem?.toString() ?: "443"
            val selectedPort = selectedPortStr.toLongOrNull() ?: 443L

            if (selectedTunnelProtocol.lowercase() in listOf("vless-ws", "vless-grpc", "vless-httpupgrade", "vless-xhttp")) {
                val supported = Mobile.cdnSupportsProtocol(selectedCdn, selectedTunnelProtocol)
                if (!supported) {
                    Toast.makeText(this, "CDN '$selectedCdn' does not support protocol '$selectedTunnelProtocol'!", Toast.LENGTH_LONG).show()
                    btn.text = "Get best IP from Vault"
                    btn.isEnabled = true
                    return@setOnClickListener
                }
                val portSupported = Mobile.cdnSupportsPort(selectedCdn, selectedPort)
                if (!portSupported) {
                    Toast.makeText(this, "CDN '$selectedCdn' does not support port '$selectedPortStr'!", Toast.LENGTH_LONG).show()
                    btn.text = "Get best IP from Vault"
                    btn.isEnabled = true
                    return@setOnClickListener
                }
            }

            val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("vault_ips_json", "[]") ?: "[]"
            val allIpsList = mutableListOf<String>()

            try {
                val jsonArray = org.json.JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val ipCdn = obj.optString("cdn", "CloudX")
                    if (ipCdn.equals(selectedCdn, ignoreCase = true)) {
                        val rawIp = obj.getString("ip")
                        val decryptedIp = CryptoHelper.decrypt(rawIp)
                        if (decryptedIp.isNotBlank()) allIpsList.add(decryptedIp)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            val savedIps = allIpsList.joinToString(",")
            if (savedIps.isBlank()) {
                Toast.makeText(this, "No IPs found for $selectedCdn in Global Settings!", Toast.LENGTH_SHORT).show()
                btn.text = "Get best IP from Vault"
                btn.isEnabled = true
                return@setOnClickListener
            }

            Toast.makeText(this, "Racing $selectedCdn IPs in background...", Toast.LENGTH_SHORT).show()
            Thread {
                val isDefault = editingConfigId?.startsWith("default_") == true
                val cIndex = if (isDefault) editingConfigId?.removePrefix("default_")?.toLongOrNull() ?: -1L else -1L
                val currentDomain = findViewById<EditText>(R.id.et_domain)?.text?.toString()?.trim() ?: ""
                val result = Mobile.getFastestCloudflareIP(
                    isDefault, cIndex, savedIps, currentDomain, selectedCdn, selectedPort.toLong(), selectedTunnelProtocol
                )

                runOnUiThread {
                    btn.text = "Get best IP from Vault"
                    btn.isEnabled = true
                    if (result.isNotEmpty() && result.contains("|")) {
                        val parts = result.split("|")
                        val bestIp = parts[0]
                        val latency = parts[1]
                        realVlessIp = bestIp
                        val mappedWinner = mobile.Mobile.encryptIP(bestIp)
                        findViewById<EditText>(R.id.et_vless_ip).setText(mappedWinner)
                        Toast.makeText(this@ConfigEditorActivity, "Winner: $mappedWinner (${latency}ms)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@ConfigEditorActivity, "All IPs failed the Layer 7 Handshake.", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()

        editingConfigId = intent.getStringExtra("CONFIG_ID")
        val isDefault = editingConfigId?.startsWith("default_") == true

        val currentProtocolEarly = if (editingConfigId != null) {
            if (isDefault) {
                getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                    .getString("${editingConfigId}_tunnelProtocol", null) ?: "vaydns"
            } else {
                val configs = loadAllConfigs(this)
                val config = configs.find { it.id == editingConfigId }
                config?.tunnelProtocol ?: "vaydns"
            }
        } else {
            "vaydns"
        }

        val ssMethods = arrayOf("chacha20-ietf-poly1305", "aes-128-gcm", "aes-256-gcm", "xchacha20-ietf-poly1305")
        val ssAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ssMethods)
        ssAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSsMethod.adapter = ssAdapter

        val recordTypes = arrayOf("TXT", "NULL", "CNAME", "A", "AAAA", "MX", "NS", "SRV", "CAA")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, recordTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRecordType.adapter = adapter

        if (editingConfigId != null && !isDefault) {
            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
            val configsString = sharedPref.getString("configs", "[]") ?: "[]"
            try {
                val jsonArray = JSONArray(configsString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.getString("id") == editingConfigId) {
                        sshUserCache = obj.optString("sshUser", "")
                        sshPassCache = obj.optString("sshPass", "")
                        ssPassCache = obj.optString("ssPass", "")
                        basicUserCache = obj.optString("basicUser", "")
                        basicPassCache = obj.optString("basicPass", "")

                        val activeAuth = obj.optString("authProtocol", "socks").lowercase()
                        val legacyUser = obj.optString("user", "")
                        val legacyPass = obj.optString("pass", "")

                        if (activeAuth == "ssh" && sshUserCache.isEmpty() && sshPassCache.isEmpty()) {
                            sshUserCache = legacyUser
                            sshPassCache = legacyPass
                        } else if (activeAuth == "shadowsocks" && ssPassCache.isEmpty()) {
                            ssPassCache = legacyPass
                        } else if ((activeAuth == "socks" || activeAuth == "basic") && basicUserCache.isEmpty() && basicPassCache.isEmpty()) {
                            basicUserCache = legacyUser
                            basicPassCache = legacyPass
                        }
                        currentAuthMode = activeAuth
                        break
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        setupMultipathData(editingConfigId ?: "new_temp_config")

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
                                    swUseDefaultResolvers.isChecked = false
                                }
                                .setNegativeButton("Cancel") { _, _ -> swUseDefaultResolvers.isChecked = false }
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
                if (rtIndex >= 0) spRecordType.setSelection(rtIndex)

                etIdleTimeout.setText(mobile.Mobile.getDefaultConfigIdleTimeout(index))
                etKeepAlive.setText(mobile.Mobile.getDefaultConfigKeepAlive(index))

                etClientIdSize.setText(mobile.Mobile.getDefaultConfigClientIdSize(index).toString())
                swDnstt.isChecked = mobile.Mobile.getDefaultConfigDnsttCompatible(index)

                val ssMethod = mobile.Mobile.getDefaultConfigMethod(index)
                val user = ""
                val pass = ""
                val useSshKey = mobile.Mobile.getDefaultConfigUseSshKey(index)

                val nativeProto = mobile.Mobile.getDefaultConfigProtocol(index)
                val authProto = if (nativeProto == "ssh" || nativeProto == "shadowsocks") nativeProto else "basic"

                etUser.setText(user)
                etPass.setText(pass)

                rgProxyProtocol.check(R.id.rb_proxy_socks)

                when(authProto.lowercase()) {
                    "ssh" -> rgAuthProtocol.check(R.id.rb_auth_ssh)
                    "shadowsocks" -> rgAuthProtocol.check(R.id.rb_auth_shadowsocks)
                    else -> rgAuthProtocol.check(R.id.rb_auth_socks)
                }

                swSshKey.isChecked = useSshKey
                swSshKey.isEnabled = false
                etPass.transformationMethod = android.text.method.HideReturnsTransformationMethod.getInstance()
                swAuth.isChecked = false
                swAuth.isEnabled = false

                val defaultProxyType = mobile.Mobile.getDefaultConfigProxy(index)
                val savedProxyType = prefs.getString("${editingConfigId}_localProxyProtocol", defaultProxyType) ?: defaultProxyType

                if (savedProxyType.lowercase() == "http") {
                    rgProxyProtocol.check(R.id.rb_proxy_http)
                } else {
                    rgProxyProtocol.check(R.id.rb_proxy_socks)
                }

                tvProxyProtocolLabel.visibility = View.VISIBLE
                rgProxyProtocol.visibility = View.VISIBLE

                for (i in 0 until rgProxyProtocol.childCount) {
                    val v = rgProxyProtocol.getChildAt(i)
                    v.isEnabled = true
                    v.alpha = 1.0f
                }

                for (i in 0 until rgAuthProtocol.childCount) {
                    val v = rgAuthProtocol.getChildAt(i)
                    v.isEnabled = false
                    v.alpha = 0.5f
                }

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

                spRecordType.visibility = View.GONE
                etIdleTimeout.visibility = View.GONE
                etKeepAlive.visibility = View.GONE
                etClientIdSize.visibility = View.GONE
                swDnstt.visibility = View.GONE
                swAuth.visibility = View.GONE
                swSshKey.visibility = View.GONE
                spSsMethod.visibility = View.GONE
                spMasterDnsMethod.visibility = View.GONE
                tvMasterDnsMethodLabel?.visibility = View.GONE
                etUser.visibility = View.GONE
                etPass.visibility = View.GONE
                rgProxyProtocol.visibility = View.VISIBLE
                rgAuthProtocol.visibility = View.GONE
                tvAuthProtocolLabel.visibility = View.GONE

                val parentLayout = etName.parent as ViewGroup
                for (i in 0 until parentLayout.childCount) {
                    val view = parentLayout.getChildAt(i)
                    if (view is TextView && view !is Button && view !is EditText) {
                        val txt = view.text.toString()
                        val forbiddenLabels = listOf(
                            "Tunnel Domain:", "Server Public Key:", "Encryption Key:",
                            "Following parameters", "Record Type:",
                            "Idle Timeout:", "Keep Alive:", "Tunnel Encryption Method:"
                        )
                        if (forbiddenLabels.any { txt.contains(it) }) {
                            view.visibility = View.GONE
                        }
                    }
                }
            } else {
                val configs = loadAllConfigs(this)
                val config = configs.find { it.id == editingConfigId }
                tvProxyProtocolLabel.visibility = View.GONE
                rgProxyProtocol.visibility = View.GONE

                if (config != null) {
                    val rtIndex = recordTypes.indexOf(config.recordType.uppercase())

                    loadConfigForEditing(
                        etName, config.name, etDomain, config.domain, etPubkey, config.pubkey,
                        etDns, config.dnsAddress, rgMode, config.mode, spRecordType, rtIndex,
                        etIdleTimeout, config.idleTimeout, etKeepAlive, config.keepAlive,
                        etClientIdSize, config.clientIdSize, etMtu, config.mtu,
                        swDnstt, config.dnsttCompatible, swAuth, config.useAuth, swSshKey, config.useSshKey,
                        rgProxyProtocol, config.localProxyProtocol, rgAuthProtocol, config.authProtocol,
                        spSsMethod, config.ssMethod, spMasterDnsMethod, config.masterDnsMethod,
                        etUser, config.user, etPass, config.pass, tvUserLabel, tvPassLabel,
                        config.useMultiDomains, config.domainIndex,
                        spSlipstreamCongestion, swSlipstreamAuthoritative, swSlipstreamGso,
                        config.slipstreamCongestion, config.slipstreamAuthoritative,
                        config.slipstreamGso
                    )
                }
                toolbar.title = "Edit Config"
            }
        } else {
            toolbar.title = "Add New Config"
            rgMode.check(R.id.rb_udp)
            etDns.setText(lastUdp)
            etIdleTimeout.setText("10s")
            etKeepAlive.setText("2s")
            etClientIdSize.setText("2")
            tvProxyProtocolLabel.visibility = View.GONE
            rgProxyProtocol.visibility = View.GONE
            swAuth.isChecked = false
            rgProxyProtocol.check(R.id.rb_proxy_socks)
            rgAuthProtocol.check(R.id.rb_auth_socks)
            swSshKey.isChecked = false

            etUser.isEnabled = false
            etPass.isEnabled = false
            swSshKey.isEnabled = false

            etUser.visibility = View.VISIBLE
            etPass.visibility = View.VISIBLE
            swSshKey.visibility = View.VISIBLE
            spSsMethod.visibility = View.VISIBLE
            spMasterDnsMethod.visibility = View.GONE
            tvMasterDnsMethodLabel?.visibility = View.GONE
            tvUserLabel.visibility = View.VISIBLE
            tvPassLabel.visibility = View.VISIBLE

            for (i in 0 until rgAuthProtocol.childCount) {
                val v = rgAuthProtocol.getChildAt(i)
                v.isEnabled = false
                v.alpha = 0.5f
            }
        }

        swSshKey.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etUser.setText("User")
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
                tvUserLabel.text = "User:"
                etUser.hint = "Optional"
                tvPassLabel.text = "Password:"
                etPass.hint = "Optional"
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT
                etPass.minLines = 1
                etPass.maxLines = 1
                etPass.gravity = android.view.Gravity.CENTER_VERTICAL
                etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
            }
        }

        etDomain.addTextChangedListener(object: TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (editingConfigId?.startsWith("default_") != true) updateDomainRadioGroup()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_udp -> etDns.setText(lastUdp)
                R.id.rb_tcp -> etDns.setText(lastTcp)
                R.id.rb_tls -> etDns.setText(lastDot)
                R.id.rb_https -> etDns.setText(lastDoh)
            }
        }

        rgAuthProtocol.setOnCheckedChangeListener { _, checkedId ->
            if (isInitializing) return@setOnCheckedChangeListener

            val typedUser = etUser.text.toString()
            val typedPass = etPass.text.toString()
            when (currentAuthMode) {
                "ssh" -> { sshUserCache = typedUser; sshPassCache = typedPass }
                "shadowsocks" -> { ssPassCache = typedPass }
                "socks", "basic" -> { basicUserCache = typedUser; basicPassCache = typedPass }
            }

            when (checkedId) {
                R.id.rb_auth_ssh -> {
                    currentAuthMode = "ssh"
                    val isAuthOn = swAuth.isChecked
                    swSshKey.isEnabled = isAuthOn
                    swSshKey.alpha = if (isAuthOn) 1.0f else 0.3f

                    tvUserLabel.visibility = View.VISIBLE
                    etUser.visibility = View.VISIBLE
                    tvSsMethodLabel.visibility = View.GONE
                    spSsMethod.visibility = View.GONE

                    etUser.setText(sshUserCache)
                    etPass.setText(sshPassCache)
                }
                R.id.rb_auth_shadowsocks -> {
                    currentAuthMode = "shadowsocks"
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false
                    swSshKey.alpha = 0.3f
                    tvUserLabel.visibility = View.GONE
                    etUser.visibility = View.GONE
                    tvSsMethodLabel.visibility = View.VISIBLE
                    spSsMethod.visibility = View.VISIBLE

                    etUser.setText("")
                    etPass.setText(ssPassCache)
                }
                else -> { // Basic / Socks
                    currentAuthMode = "socks"
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false
                    swSshKey.alpha = 0.3f
                    tvUserLabel.visibility = View.VISIBLE
                    etUser.visibility = View.VISIBLE
                    tvSsMethodLabel.visibility = View.GONE
                    spSsMethod.visibility = View.GONE

                    etUser.setText(basicUserCache)
                    etPass.setText(basicPassCache)
                }
            }
        }

        swAuth.setOnCheckedChangeListener { _, isChecked ->
            etUser.isEnabled = isChecked
            etPass.isEnabled = isChecked

            for (i in 0 until rgAuthProtocol.childCount) {
                val v = rgAuthProtocol.getChildAt(i)
                v.isEnabled = isChecked
                v.alpha = if (isChecked) 1.0f else 0.5f
            }

            if (isChecked && rgAuthProtocol.checkedRadioButtonId == R.id.rb_auth_ssh) {
                swSshKey.isEnabled = true
                swSshKey.alpha = 1.0f
            } else {
                swSshKey.isEnabled = false
                swSshKey.alpha = 0.3f
            }
        }

        val spinnerTunnelProtocol = findViewById<Spinner>(R.id.spinner_tunnel_protocol)
        val spinnerVlessProtocol = findViewById<Spinner>(R.id.spinner_vless_protocol)
        val layoutVlessProtocol = findViewById<LinearLayout>(R.id.layout_vless_protocol)

        val baseSupportedProtocols = if (isDefault) {
            val nativeIndex = editingConfigId?.removePrefix("default_")?.toLongOrNull() ?: 0L
            val types = mobile.Mobile.getDefaultConfigType(nativeIndex).split(",").map { it.trim().lowercase() }
            if (types.isEmpty() || types[0] == "") listOf("vaydns", "masterdns", "slipstream") else types
        } else {
            listOf("vaydns", "masterdns", "slipstream")
        }

        val tpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, baseSupportedProtocols)
        tpAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTunnelProtocol.adapter = tpAdapter

        val currentProtocol = currentProtocolEarly
        val pIndex = baseSupportedProtocols.indexOf(currentProtocol)
        if (pIndex >= 0) spinnerTunnelProtocol.setSelection(pIndex)

        // =================================================================
        // UX LOCK: Prevent protocol swapping on existing configs
        // =================================================================
        if (editingConfigId != null && !isDefault) {
            // High-Contrast Lock: Keep enabled for readability, but block all taps
            spinnerTunnelProtocol.isEnabled = true
            spinnerTunnelProtocol.setOnTouchListener { _, _ -> true }
            spinnerTunnelProtocol.alpha = 1.0f
            findViewById<TextView>(R.id.tv_tunnel_protocol_label)?.alpha = 1.0f

            // High-Contrast Lock for VLESS spinner
            spinnerVlessProtocol.isEnabled = true
            spinnerVlessProtocol.setOnTouchListener { _, _ -> true }
            spinnerVlessProtocol.alpha = 1.0f
        } else {
            // New Config: Enable selection and remove the touch blocker
            spinnerTunnelProtocol.isEnabled = true
            spinnerTunnelProtocol.setOnTouchListener(null)
            spinnerTunnelProtocol.alpha = 1.0f
            findViewById<TextView>(R.id.tv_tunnel_protocol_label)?.alpha = 1.0f

            spinnerVlessProtocol.isEnabled = true
            spinnerVlessProtocol.setOnTouchListener(null)
            spinnerVlessProtocol.alpha = 1.0f
        }

        fun updateVlessProtocolSpinner(selectedCdn: String): Boolean {
            val vlessProtosInConfig = baseSupportedProtocols.filter { it.startsWith("vless") }
            val supportedByCdn = vlessProtosInConfig.filter { mobile.Mobile.cdnSupportsProtocol(selectedCdn, it) }

            if (supportedByCdn.isEmpty()) return false

            val currentVlessProto = spinnerVlessProtocol.selectedItem?.toString() ?: currentProtocol
            val vlessAdapter = ArrayAdapter(this@ConfigEditorActivity, android.R.layout.simple_spinner_item, supportedByCdn)
            vlessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerVlessProtocol.adapter = vlessAdapter

            if (supportedByCdn.contains(currentVlessProto)) {
                spinnerVlessProtocol.setSelection(supportedByCdn.indexOf(currentVlessProto))
            } else if (supportedByCdn.contains(currentProtocol)) {
                spinnerVlessProtocol.setSelection(supportedByCdn.indexOf(currentProtocol))
            } else {
                spinnerVlessProtocol.setSelection(0)
            }
            return true
        }

        val tvVlessIpLabel = findViewById<TextView>(R.id.tv_vless_ip_label)
        val etVlessIp = findViewById<EditText>(R.id.et_vless_ip)

        etVlessIp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val input = s.toString().trim()
                if (input.isNotEmpty()) {
                    var decrypted = mobile.Mobile.decryptIP(input)
                    if (decrypted.isEmpty() || decrypted == input) {
                        decrypted = input
                    }
                    realVlessIp = decrypted
                } else {
                    realVlessIp = ""
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val spinnerEditorCdn = findViewById<Spinner>(R.id.spinner_editor_cdn)
        val layoutEditorCdn = findViewById<LinearLayout>(R.id.layout_editor_cdn)
        val spinnerEditorPort = findViewById<Spinner>(R.id.spinner_editor_port)
        val layoutEditorPort = findViewById<LinearLayout>(R.id.layout_editor_port)

        val cdnList = mutableListOf<String>()
        if (isDefault) {
            val nativeIndex = editingConfigId?.removePrefix("default_")?.toLongOrNull() ?: 0L
            val configCloudsStr = mobile.Mobile.getDefaultConfigClouds(nativeIndex)
            if (configCloudsStr.isNotEmpty()) {
                cdnList.addAll(configCloudsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }

        if (cdnList.isEmpty()) {
            val cdnCount = mobile.Mobile.getCdnCount()
            for (i in 0 until cdnCount) {
                val name = mobile.Mobile.getCdnName(i)
                if (name.isNotEmpty()) cdnList.add(name)
            }
        }
        if (cdnList.isEmpty()) {
            cdnList.add("CloudX")
            cdnList.add("CloudY")
            cdnList.add("CloudZ")
            cdnList.add("CloudV")
        }
        val cdnAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cdnList)
        cdnAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEditorCdn.adapter = cdnAdapter

        val currentConfigPort = if (isDefault) {
            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getInt("${editingConfigId}_vlessPort", 443)
        } else {
            val configs = loadAllConfigs(this)
            val config = configs.find { it.id == editingConfigId }
            config?.vlessPort ?: 443
        }

        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        val globalOverride = tunnelPrefs.getBoolean("global_protocol_override", false)
        val globalCdn = tunnelPrefs.getString("selected_cdn", "CloudX") ?: "CloudX"

        val currentConfigCdn = if (isDefault) {
            getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${editingConfigId}_cdn", "CloudX") ?: "CloudX"
        } else {
            getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
                .getString("${editingConfigId}_cdn", "CloudX") ?: "CloudX"
        }

        fun updatePortSpinner(selectedCdn: String, targetPort: Int) {
            val portsCsv = mobile.Mobile.getCdnPortsCsv(selectedCdn)
            val cdnFilteredPorts = if (portsCsv.isNotEmpty()) {
                portsCsv.split(",").map { it.trim() }
            } else {
                listOf("443")
            }

            val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cdnFilteredPorts)
            portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerEditorPort.adapter = portAdapter

            val targetPortStr = targetPort.toString()
            if (cdnFilteredPorts.contains(targetPortStr)) {
                spinnerEditorPort.setSelection(cdnFilteredPorts.indexOf(targetPortStr))
            } else if (cdnFilteredPorts.contains("443")) {
                spinnerEditorPort.setSelection(cdnFilteredPorts.indexOf("443"))
            } else {
                spinnerEditorPort.setSelection(0)
            }
        }

        val cdnToSelect = if (globalOverride) globalCdn else currentConfigCdn
        var cdnIndex = cdnList.indexOf(cdnToSelect)

        if (cdnIndex < 0 && cdnList.isNotEmpty()) cdnIndex = 0

        if (cdnIndex >= 0) spinnerEditorCdn.setSelection(cdnIndex)

        updateVlessProtocolSpinner(cdnToSelect)
        updatePortSpinner(cdnToSelect, currentConfigPort)

        var isInitialCdnSetup = true

        spinnerEditorCdn.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedCdn = parent.getItemAtPosition(position).toString()

                val masterProto = spinnerTunnelProtocol.selectedItem?.toString()?.lowercase()?.trim() ?: ""
                if (masterProto.startsWith("vless")) {
                    val success = updateVlessProtocolSpinner(selectedCdn)
                    if (!success) {
                        Toast.makeText(this@ConfigEditorActivity, "CDN '$selectedCdn' does not support any VLESS protocols for this config. Reverting to CloudX.", Toast.LENGTH_LONG).show()
                        val cloudXIndex = cdnList.indexOf("CloudX")
                        if (cloudXIndex >= 0 && selectedCdn != "CloudX") {
                            spinnerEditorCdn.setSelection(cloudXIndex)
                        }
                        return
                    }
                }

                val currentSelectedPort = spinnerEditorPort.selectedItem?.toString()?.toIntOrNull() ?: currentConfigPort
                updatePortSpinner(selectedCdn, currentSelectedPort)

                if (isInitialCdnSetup) {
                    isInitialCdnSetup = false
                    return
                }

                realVlessIp = ""
                etVlessIp.setText("")

                val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
                val jsonString = prefs.getString("vault_ips_json", "[]") ?: "[]"

                var firstIp = ""
                var fallbackIp = ""

                try {
                    val jsonArray = org.json.JSONArray(jsonString)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val ipCdn = obj.optString("cdn", "CloudX")

                        if (ipCdn.equals(selectedCdn, ignoreCase = true)) {
                            val rawIp = obj.getString("ip")
                            val decryptedIp = CryptoHelper.decrypt(rawIp)

                            if (fallbackIp.isEmpty()) fallbackIp = decryptedIp

                            val isChecked = obj.optBoolean("isChecked", false)
                            if (isChecked) {
                                firstIp = decryptedIp
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (firstIp.isEmpty() && fallbackIp.isNotEmpty()) {
                    firstIp = fallbackIp
                }

                if (firstIp.isNotEmpty()) {
                    realVlessIp = firstIp
                    etVlessIp.setText(mobile.Mobile.encryptIP(firstIp))
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        val currentVlessIp = if (isDefault) {
            val encrypted = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                .getString("${editingConfigId}_vlessIp", "") ?: ""
            CryptoHelper.decrypt(encrypted)
        } else {
            val configs = loadAllConfigs(this)
            val config = configs.find { it.id == editingConfigId }
            config?.vlessIp ?: ""
        }

        realVlessIp = currentVlessIp
        if (currentVlessIp.isNotEmpty()) {
            etVlessIp.setText(mobile.Mobile.encryptIP(currentVlessIp))
        } else {
            etVlessIp.setText("")
        }

        if (!mobile.Mobile.isOfficialBuild()) {
            findViewById<TextView>(R.id.tv_tunnel_protocol_label)?.visibility = View.GONE
            spinnerTunnelProtocol.visibility = View.GONE
            tvVlessIpLabel.visibility = View.GONE
            etVlessIp.visibility = View.GONE
        }

        // =================================================================
        // DYNAMIC TUNNEL PROTOCOL LISTENER
        // =================================================================
        spinnerTunnelProtocol.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                val selected = parent.getItemAtPosition(position).toString().lowercase().trim()
                val isVaydns = selected == "vaydns"
                val isMasterDns = selected == "masterdns"
                val isSlipstream = selected == "slipstream"
                val isDnsBase = isVaydns || isMasterDns || isSlipstream
                val visibilityState = if (isDnsBase) View.VISIBLE else View.GONE
                val isVless = selected.startsWith("vless")

                val tvModeLabel = findViewById<TextView>(R.id.tv_mode_label)
                val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
                val rbUdp = findViewById<RadioButton>(R.id.rb_udp)
                val rbTcp = findViewById<RadioButton>(R.id.rb_tcp)
                val rbTls = findViewById<RadioButton>(R.id.rb_tls)
                val rbHttps = findViewById<RadioButton>(R.id.rb_https)

                if (isVless) {
                    tvVlessIpLabel.visibility = View.VISIBLE
                    etVlessIp.visibility = View.VISIBLE
                    btnBestCfIp.visibility = View.VISIBLE
                    layoutEditorCdn.visibility = View.VISIBLE
                    layoutVlessProtocol.visibility = View.VISIBLE
                    layoutEditorPort.visibility = View.VISIBLE

                    val selectedCdn = spinnerEditorCdn.selectedItem?.toString() ?: "CloudX"
                    val success = updateVlessProtocolSpinner(selectedCdn)
                    if (!success) {
                        val cloudXIndex = cdnList.indexOf("CloudX")
                        if (cloudXIndex >= 0) spinnerEditorCdn.setSelection(cloudXIndex)
                    }

                    if (isDefault) {
                        etVlessIp.isEnabled = false
                        etVlessIp.alpha = 0.5f
                    } else {
                        etVlessIp.isEnabled = true
                        etVlessIp.alpha = 1.0f
                    }
                } else {
                    tvVlessIpLabel.visibility = View.GONE
                    etVlessIp.visibility = View.GONE
                    btnBestCfIp.visibility = View.GONE
                    layoutEditorCdn.visibility = View.GONE
                    layoutVlessProtocol.visibility = View.GONE
                    layoutEditorPort.visibility = View.GONE
                }

                if (!isDefault) {
                    // =================================================================
                    // CUSTOM CONFIGS: Toggle all fields dynamically
                    // =================================================================
                    val vaydnsFields = listOf<View?>(
                        etDomain, switchMultiDomain, etPubkey,
                        etDns, (etDns.parent as? ViewGroup),
                        btnSelectMultipath, spRecordType, etIdleTimeout,
                        etKeepAlive, etClientIdSize, etMtu, swDnstt, swAuth, swSshKey,
                        rgProxyProtocol, rgAuthProtocol, spSsMethod, etUser, etPass,
                        layoutMultipathControls, swUseDefaultResolvers,

                        findViewById(R.id.tv_user_label), findViewById(R.id.tv_pass_label),
                        findViewById(R.id.tv_multipath_label), findViewById(R.id.tv_multipath_desc),
                        findViewById(R.id.tv_multipath_status), findViewById(R.id.tv_proxy_protocol_label),
                        findViewById(R.id.tv_auth_protocol_label), findViewById(R.id.tv_ss_method_label),
                        findViewById(R.id.tv_mtu_label), findViewById(R.id.tv_domain_label),
                        findViewById(R.id.tv_pubkey_label), findViewById(R.id.tv_dns_label),
                        findViewById(R.id.tv_record_type_label),
                        findViewById(R.id.tv_idle_timeout_label), findViewById(R.id.tv_keep_alive_label),
                        findViewById(R.id.tv_client_id_size_label)
                    )

                    for (v in vaydnsFields) {
                        v?.visibility = visibilityState
                        v?.isEnabled = isDnsBase
                        v?.alpha = if (isDnsBase) 1.0f else 0.3f
                    }

                    if (!isDnsBase) {
                        swAuth.isChecked = false
                        swSshKey.isChecked = false
                        layoutSlipstreamParams.visibility = View.GONE
                        tvMasterDnsMethodLabel?.visibility = View.GONE
                        spMasterDnsMethod.visibility = View.GONE
                    } else if (isSlipstream) {
                        // =================================================================
                        // SLIPSTREAM LOGIC
                        // =================================================================
                        layoutSlipstreamParams.visibility = View.VISIBLE
                        switchMultiDomain.visibility = View.GONE
                        etDomain.hint = "t.example.com"

                        tvPubkeyLabel?.text = "Public Certificate:"
                        etPubkey.hint = "Optional"

                        tvPubkeyLabel?.visibility = View.GONE
                        layoutSlipstreamParams.visibility = View.VISIBLE
                        switchMultiDomain.visibility = View.GONE

                        val certSwitch = findViewById<SwitchCompat>(R.id.sw_slipstream_cert)
                        if (certSwitch?.isChecked == true) {
                            etPubkey.hint = "Paste Public Certificate (PEM) here..."
                            etPubkey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            etPubkey.minLines = 15
                            etPubkey.maxLines = 60
                            etPubkey.setHorizontallyScrolling(false)
                            etPubkey.gravity = android.view.Gravity.TOP
                        } else {
                            etPubkey.visibility = View.GONE
                        }

                        spRecordType.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_record_type_label)?.visibility = View.GONE
                        etIdleTimeout.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_idle_timeout_label)?.visibility = View.GONE
                        etClientIdSize.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_client_id_size_label)?.visibility = View.GONE
                        swDnstt.visibility = View.GONE

                        tvMasterDnsMethodLabel?.visibility = View.GONE
                        spMasterDnsMethod.visibility = View.GONE

                        // Force UDP Mode
                        tvModeLabel?.visibility = View.GONE
                        rgMode?.visibility = View.GONE
                        rgMode?.check(R.id.rb_udp)

                        // Force Local Proxy Protocol
                        rgProxyProtocol.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_proxy_protocol_label)?.visibility = View.GONE
                        rgProxyProtocol.check(R.id.rb_proxy_socks)

                        // Default Keep Alive logic
                        if (etKeepAlive.text.toString() == "2s" || etKeepAlive.text.toString().isEmpty()) {
                            etKeepAlive.setText("5s")
                        }

                        // Retain Auth Block
                        swAuth.visibility = View.VISIBLE
                        rgAuthProtocol.visibility = View.VISIBLE
                        findViewById<TextView>(R.id.tv_auth_protocol_label)?.visibility = View.VISIBLE
                        swSshKey.visibility = View.VISIBLE

                        val isAuthOn = swAuth.isChecked
                        val authProtoId = rgAuthProtocol.checkedRadioButtonId
                        val tvUserLabelLocal = findViewById<TextView>(R.id.tv_user_label)

                        if (authProtoId == R.id.rb_auth_shadowsocks) {
                            tvUserLabelLocal?.visibility = View.GONE
                            etUser.visibility = View.GONE
                            tvSsMethodLabel.visibility = View.VISIBLE
                            spSsMethod.visibility = View.VISIBLE
                        } else {
                            tvUserLabelLocal?.visibility = View.VISIBLE
                            etUser.visibility = View.VISIBLE
                            tvSsMethodLabel.visibility = View.GONE
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
                    } else if (isMasterDns) {
                        // =================================================================
                        // MASTERDNS LOGIC
                        // =================================================================
                        switchMultiDomain.visibility = View.GONE
                        etDomain.hint = "t.example.com"

                        spRecordType.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_record_type_label)?.visibility = View.GONE
                        etIdleTimeout.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_idle_timeout_label)?.visibility = View.GONE
                        etKeepAlive.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_keep_alive_label)?.visibility = View.GONE
                        etClientIdSize.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_client_id_size_label)?.visibility = View.GONE
                        swDnstt.visibility = View.GONE
                        layoutSlipstreamParams.visibility = View.GONE
                        switchMultiDomain.visibility = View.GONE
                        // Force proxy protocol to SOCKS5 and disable changing
                        rgProxyProtocol.check(R.id.rb_proxy_socks)
                        for (i in 0 until rgProxyProtocol.childCount) {
                            val child = rgProxyProtocol.getChildAt(i)
                            child.isEnabled = false
                            child.alpha = 0.5f
                        }

                        tvMasterDnsMethodLabel?.visibility = View.VISIBLE
                        spMasterDnsMethod.visibility = View.VISIBLE

                        // DYNAMICALLY SHOW/HIDE ENCRYPTION KEY BASED ON "NONE"
                        if (spMasterDnsMethod.selectedItem?.toString().equals("None", ignoreCase = true)) {
                            tvPubkeyLabel?.visibility = View.GONE
                            etPubkey.visibility = View.GONE
                        } else {
                            tvPubkeyLabel?.visibility = View.VISIBLE
                            etPubkey.visibility = View.VISIBLE
                            tvPubkeyLabel?.text = "Encryption Key:"
                        }

                        etPubkey.hint = "Tunnel Encryption Key ..."
                        etPubkey.inputType = android.text.InputType.TYPE_CLASS_TEXT
                        etPubkey.minLines = 1
                        etPubkey.maxLines = 1
                        etPubkey.gravity = android.view.Gravity.CENTER_VERTICAL

                        // Show Auth block normally, but ensure SS spinner hides and MasterDNS cipher stays
                        swAuth.visibility = View.VISIBLE
                        rgAuthProtocol.visibility = View.VISIBLE
                        findViewById<TextView>(R.id.tv_auth_protocol_label)?.visibility = View.VISIBLE
                        swSshKey.visibility = View.VISIBLE

                        val isAuthOn = swAuth.isChecked
                        val authProtoId = rgAuthProtocol.checkedRadioButtonId
                        val tvUserLabelLocal = findViewById<TextView>(R.id.tv_user_label)

                        if (authProtoId == R.id.rb_auth_shadowsocks) {
                            tvUserLabelLocal?.visibility = View.GONE
                            etUser.visibility = View.GONE
                            tvSsMethodLabel.visibility = View.VISIBLE
                            spSsMethod.visibility = View.VISIBLE
                        } else {
                            tvUserLabelLocal?.visibility = View.VISIBLE
                            etUser.visibility = View.VISIBLE
                            tvSsMethodLabel.visibility = View.GONE
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

                    } else {
                        // =================================================================
                        // VAYDNS LOGIC
                        // =================================================================
                        layoutSlipstreamParams.visibility = View.GONE
                        etDomain.hint = "t.example.com,t.domain.com"
                        tvPubkeyLabel?.text = "Server Public Key:"

                        etPubkey.visibility = View.VISIBLE
                        etPubkey.inputType = android.text.InputType.TYPE_CLASS_TEXT
                        etPubkey.minLines = 1
                        etPubkey.maxLines = 1
                        etPubkey.gravity = android.view.Gravity.CENTER_VERTICAL
                        etPubkey.hint = "Your Server Key"

                        tvMasterDnsMethodLabel?.visibility = View.GONE
                        spMasterDnsMethod.visibility = View.GONE

                        swAuth.visibility = View.VISIBLE
                        rgAuthProtocol.visibility = View.VISIBLE
                        findViewById<TextView>(R.id.tv_auth_protocol_label)?.visibility = View.VISIBLE
                        swSshKey.visibility = View.VISIBLE

                        val isAuthOn = swAuth.isChecked
                        val authProtoId = rgAuthProtocol.checkedRadioButtonId
                        val tvUserLabelLocal = findViewById<TextView>(R.id.tv_user_label)

                        if (authProtoId == R.id.rb_auth_shadowsocks) {
                            tvUserLabelLocal?.visibility = View.GONE
                            etUser.visibility = View.GONE
                            tvSsMethodLabel.visibility = View.VISIBLE
                            spSsMethod.visibility = View.VISIBLE
                        } else {
                            tvUserLabelLocal?.visibility = View.VISIBLE
                            etUser.visibility = View.VISIBLE
                            tvSsMethodLabel.visibility = View.GONE
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

                        for (i in 0 until rgProxyProtocol.childCount) {
                            val child = rgProxyProtocol.getChildAt(i)
                            child.isEnabled = true
                            child.alpha = 1.0f
                        }
                    }

                } else {
                    // =================================================================
                    // OFFICIAL (DEFAULT) CONFIGS: Strict Lockdown
                    // =================================================================
                    val allowedDefaultFields = listOf<View?>(
                        etMtu, findViewById(R.id.tv_mtu_label),
                        switchMultiDomain, swUseDefaultResolvers,
                        findViewById(R.id.tv_domain_selector_label), findViewById(R.id.rg_domain_selector),
                        findViewById(R.id.tv_dns_label), etDns, (etDns.parent as? ViewGroup),
                        findViewById(R.id.tv_multipath_label), findViewById(R.id.tv_multipath_desc),
                        findViewById(R.id.tv_multipath_status), layoutMultipathControls, btnSelectMultipath,
                        findViewById(R.id.tv_proxy_protocol_label), rgProxyProtocol
                    )
                    for (v in allowedDefaultFields) {
                        v?.visibility = visibilityState
                    }

                    if (isMasterDns || isSlipstream) {
                        findViewById<RadioGroup>(R.id.rg_domain_selector)?.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_domain_selector_label)?.visibility = View.GONE
                        switchMultiDomain.visibility = View.GONE

                        rgProxyProtocol.check(R.id.rb_proxy_socks)
                        for (i in 0 until rgProxyProtocol.childCount) {
                            val child = rgProxyProtocol.getChildAt(i)
                            child.isEnabled = false
                            child.alpha = 0.5f
                        }
                    } else {
                        for (i in 0 until rgProxyProtocol.childCount) {
                            val child = rgProxyProtocol.getChildAt(i)
                            child.isEnabled = isDnsBase
                            child.alpha = if (isDnsBase) 1.0f else 0.5f
                        }
                    }

                    // Explicitly display Congestion and GSO for default Slipstream configs
                    if (isSlipstream) {
                        layoutSlipstreamParams.visibility = View.VISIBLE
                        // Hide the cert switch since default configs rely on the native vault
                        findViewById<SwitchCompat>(R.id.sw_slipstream_cert)?.visibility = View.GONE
                        // Hide Authoritative Mode for default configs
                        swSlipstreamAuthoritative.visibility = View.GONE
                        findViewById<TextView>(R.id.tv_slipstream_auth_warning)?.visibility = View.GONE
                    } else {
                        layoutSlipstreamParams.visibility = View.GONE
                    }

                    val forbiddenDefaultFields = listOf<View?>(
                        etDomain, etPubkey, spRecordType, etIdleTimeout,
                        etKeepAlive, etClientIdSize, swDnstt, swAuth, swSshKey,
                        rgAuthProtocol, spSsMethod, etUser, etPass,
                        spMasterDnsMethod, tvMasterDnsMethodLabel,

                        findViewById(R.id.tv_user_label), findViewById(R.id.tv_pass_label),
                        findViewById(R.id.tv_auth_protocol_label), findViewById(R.id.tv_ss_method_label),
                        findViewById(R.id.tv_domain_label), findViewById(R.id.tv_pubkey_label),
                        findViewById(R.id.tv_record_type_label), findViewById(R.id.tv_idle_timeout_label),
                        findViewById(R.id.tv_keep_alive_label), findViewById(R.id.tv_client_id_size_label)
                    )

                    for (v in forbiddenDefaultFields) {
                        v?.visibility = View.GONE
                    }
                }

                // =================================================================
                // TUNNEL MODE (UDP / TCP / DoT / DoH) DYNAMIC VISIBILITY
                // =================================================================

                if (isDnsBase) {
                    if (isMasterDns || isSlipstream) {
                        tvModeLabel?.visibility = View.GONE
                        rgMode?.visibility = View.GONE
                        rgMode?.check(R.id.rb_udp)
                    } else {
                        tvModeLabel?.visibility = View.VISIBLE
                        rgMode?.visibility = View.VISIBLE
                        rbUdp?.visibility = View.VISIBLE
                        rbTcp?.visibility = View.VISIBLE
                        rbTls?.visibility = View.VISIBLE
                        rbHttps?.visibility = View.VISIBLE

                        tvModeLabel?.isEnabled = true
                        tvModeLabel?.alpha = 1.0f
                        rgMode?.isEnabled = true
                        rgMode?.alpha = 1.0f
                    }
                } else {
                    tvModeLabel?.visibility = View.GONE
                    rgMode?.visibility = View.GONE
                }

                updateDnsFieldState()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        val btnSaveIcon = findViewById<ImageButton>(R.id.btn_save_icon)
        btnSaveIcon.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Config name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var selectedTunnelProtocol = spinnerTunnelProtocol.selectedItem.toString()
            val isDnsProto = selectedTunnelProtocol.lowercase().trim() == "dns"

            val dns_mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_udp -> "UDP"
                R.id.rb_tcp -> "TCP"
                R.id.rb_tls -> "DoT"
                R.id.rb_https -> "DoH"
                else -> if (isDnsProto) "TCP" else "UDP"
            }
            val mode = dns_mode.lowercase()

            if (selectedTunnelProtocol.lowercase().startsWith("vless")) {
                selectedTunnelProtocol = spinnerVlessProtocol.selectedItem?.toString() ?: selectedTunnelProtocol
            }

            val selectedCdn = findViewById<Spinner>(R.id.spinner_editor_cdn).selectedItem?.toString() ?: "CloudX"
            val selectedPortStr = spinnerEditorPort.selectedItem?.toString() ?: "443"
            val selectedPort = selectedPortStr.toLongOrNull() ?: 443L

            if (selectedTunnelProtocol.lowercase() in listOf("vless-ws", "vless-grpc", "vless-httpupgrade", "vless-xhttp")) {
                val supported = Mobile.cdnSupportsProtocol(selectedCdn, selectedTunnelProtocol)
                if (!supported) {
                    Toast.makeText(this, "Cannot Save: CDN '$selectedCdn' does not support protocol '$selectedTunnelProtocol'!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val portSupported = Mobile.cdnSupportsPort(selectedCdn, selectedPort)
                if (!portSupported) {
                    Toast.makeText(this, "Cannot Save: CDN '$selectedCdn' does not support port '$selectedPortStr'!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val ssMethod = spSsMethod.selectedItem.toString()
            val masterDnsMethodValue = spMasterDnsMethod.selectedItem.toString() // NEW
            val domain = etDomain.text.toString().trim()
            var pubkey = etPubkey.text.toString().trim()

            val swSlipstreamCertLocal = findViewById<SwitchCompat>(R.id.sw_slipstream_cert)

            // STRICT VALIDATION FOR SLIPSTREAM
            if (selectedTunnelProtocol.lowercase() == "slipstream") {
                if (swSlipstreamCertLocal?.isChecked == true) {
                    if (pubkey.isEmpty()) {
                        Toast.makeText(this, "Certificate cannot be empty when toggle is ON.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!isValidTLSCertificate(pubkey)) {
                        Toast.makeText(this, "Invalid Public Certificate. Please check your PEM data.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                } else {
                    pubkey = "" // Clear it if the switch is off
                }
            }

            val dns = etDns.text.toString().trim()
            val clientIdSize = etClientIdSize.text.toString().toLongOrNull() ?: 2L
            val configId = intent.getStringExtra("CONFIG_ID") ?: "user_${System.currentTimeMillis()}"
            val mtu = etMtu.text.toString().toLongOrNull() ?: 0L
            val slipCongestion = spSlipstreamCongestion.selectedItem?.toString() ?: "BBR"
            val slipAuth = swSlipstreamAuthoritative.isChecked
            val slipGso = swSlipstreamGso.isChecked

            if ( mtu != 0L && (mtu < 40 || mtu > 140)) {
                Toast.makeText(this, "Invalid MTU: Please enter a value between 40 and 140, or 0 for default.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val dnstt = swDnstt.isChecked
            val useSshKey = swSshKey.isChecked
            val useMultiDomains = switchMultiDomain.isChecked
            val localProxyProtocol = if (rgProxyProtocol.checkedRadioButtonId == R.id.rb_proxy_http) "http" else "socks5"
            val authProtocol = when (rgAuthProtocol.checkedRadioButtonId) {
                R.id.rb_auth_ssh -> "ssh"
                R.id.rb_auth_shadowsocks -> "shadowsocks"
                else -> "socks"
            }

            val useAuth = swAuth.isChecked || authProtocol == "shadowsocks" || authProtocol == "ssh"

            val finalUser = etUser.text.toString().trim()
            val finalPass = etPass.text.toString().trim()

            // STRICT VALIDATION FOR SSH PRIVATE KEY
            if (useAuth && authProtocol == "ssh" && useSshKey) {
                if (finalPass.isEmpty()) {
                    Toast.makeText(this, "SSH Private Key cannot be empty when toggle is ON.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isValidSSHPrivateKey(finalPass)) {
                    Toast.makeText(this, "Invalid SSH Private Key. Ensure it contains the '-----BEGIN...PRIVATE KEY-----' headers.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            when (currentAuthMode) {
                "ssh" -> { sshUserCache = finalUser; sshPassCache = finalPass }
                "shadowsocks" -> { ssPassCache = finalPass }
                "socks", "basic" -> { basicUserCache = finalUser; basicPassCache = finalPass }
            }

            val user = finalUser
            val pass = finalPass

            val rt = spRecordType.selectedItem.toString()
            val selectedVlessIp = realVlessIp.trim()
            val selectedDomainIndex = when (findViewById<RadioGroup>(R.id.rg_domain_selector).checkedRadioButtonId) {
                R.id.rb_domain_2 -> 1
                R.id.rb_domain_3 -> 2
                R.id.rb_domain_4 -> 3
                else -> 0
            }

            fun normalizeDuration(input: String, default: String): String {
                val raw = input.lowercase().trim()
                if (raw.isEmpty()) return default
                return when {
                    raw.endsWith("ms") || raw.endsWith("s") -> raw
                    raw.all { it.isDigit() } -> "${raw}s"
                    else -> default
                }
            }

            val idle = normalizeDuration(etIdleTimeout.text.toString(), "10s")
            val keep = normalizeDuration(etKeepAlive.text.toString(), "2s")

            when (rgMode.checkedRadioButtonId) {
                R.id.rb_udp -> lastUdp = dns
                R.id.rb_tcp -> lastTcp = dns
                R.id.rb_tls -> lastDot = dns
                R.id.rb_https -> lastDoh = dns
            }

            val manualAddrs = resolverEntries.filter { it.isManual }.map { it.address }
            java.io.File(filesDir, "manual_resolvers_$configId.txt").writeText(manualAddrs.joinToString("\n"))

            val selectedAddrs = resolverEntries
                .filter { it.isChecked && it.address.isNotEmpty() }
                .mapNotNull { sanitizeResolverInput(it.address, mode) }

            java.io.File(filesDir, "selected_multipath_$configId.txt").writeText(selectedAddrs.joinToString("\n"))

            saveOrUpdateConfig(
                configId,
                name, domain, pubkey, dns, mode, dns_mode, rt, idle, keep,
                clientIdSize, mtu, dnstt, useAuth, useSshKey, localProxyProtocol,
                authProtocol, ssMethod, masterDnsMethodValue, user, pass, useMultiDomains, selectedTunnelProtocol,
                selectedVlessIp, selectedDomainIndex, selectedCdn, selectedPort.toInt(), slipCongestion, slipAuth, slipGso
            )
            finish()
        }

        if (editingConfigId == null) {
            swSshKey.isChecked = false
            swSshKey.isEnabled = false
            swAuth.isChecked = false
        }

        updateDnsFieldState()
        isInitializing = false
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255
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

        val scanFile = java.io.File(filesDir, "resolvers_$configId.txt")
        if (scanFile.exists()) {
            scanFile.readLines().forEach { line ->
                val parts = line.split(",")
                val ip = parts[0].trim()
                val latencyVal = if (parts.size > 1) parts[1].trim() else ""

                if (ip.isNotEmpty()) {
                    resolverEntries.add(ResolverEntry(ip, isChecked = false, isManual = false, latency = latencyVal))
                }
            }
        }

        val manualFile = java.io.File(filesDir, "manual_resolvers_$configId.txt")
        val savedManuals = if (manualFile.exists()) manualFile.readLines() else emptyList()

        for (i in 0 until 20) {
            val addr = savedManuals.getOrNull(i) ?: ""
            resolverEntries.add(ResolverEntry(addr, isChecked = false, isManual = true, latency = ""))
        }

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
        etName: EditText, nameValue: String, etDomain: EditText, domainValue: String,
        etPubkey: EditText, pubkeyValue: String, etDns: EditText, dnsValue: String,
        rgMode: RadioGroup, modeValue: String, spRecordType: Spinner, rtIndex: Int,
        etIdleTimeout: EditText, idleValue: String, etKeepAlive: EditText, keepValue: String,
        etClientIdSize: EditText, clientIdValue: Long, etMtu: EditText, mtuValue: Long,
        swDnstt: SwitchCompat, dnsttValue: Boolean, swAuth: SwitchCompat, useAuth: Boolean,
        swSshKey: SwitchCompat, useSshKey: Boolean, rgProxyProtocol: RadioGroup, proxyProtocolValue: String,
        rgAuthProtocol: RadioGroup, authProtocolValue: String, spSsMethod: Spinner, ssMethodValue: String,
        spMasterDnsMethod: Spinner, masterDnsMethodValue: String,
        etUser: EditText, userValue: String, etPass: EditText, passValue: String,
        tvUserLabel: TextView, tvPassLabel: TextView, useMultiDomains: Boolean, domainIndex: Int,
        spSlipstreamCongestion: Spinner, swSlipstreamAuthoritative: SwitchCompat, swSlipstreamGso: SwitchCompat,
        slipCongestion: String, slipAuth: Boolean, slipGso: Boolean
    ) {
        etName.setText(nameValue)
        etDomain.setText(domainValue)
        switchMultiDomain.isChecked = useMultiDomains
        etPubkey.setText(pubkeyValue)
        etDns.setText(dnsValue)
        etIdleTimeout.setText(idleValue)
        etKeepAlive.setText(keepValue)
        etClientIdSize.setText(clientIdValue.toString())
        etMtu.setText(mtuValue.toString())

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

        val mdAdapter = spMasterDnsMethod.adapter as? ArrayAdapter<String>
        if (mdAdapter != null) {
            val mdIndex = mdAdapter.getPosition(masterDnsMethodValue)
            if (mdIndex >= 0) spMasterDnsMethod.setSelection(mdIndex)
        }

        etUser.isEnabled = useAuth
        etPass.isEnabled = useAuth

        for (i in 0 until rgAuthProtocol.childCount) {
            val v = rgAuthProtocol.getChildAt(i)
            v.isEnabled = useAuth
            v.alpha = if (useAuth) 1.0f else 0.5f
        }

        when (modeValue.lowercase()) {
            "tcp" -> { rgMode.check(R.id.rb_tcp); lastTcp = dnsValue }
            "dot" -> { rgMode.check(R.id.rb_tls); lastDot = dnsValue }
            "doh" -> { rgMode.check(R.id.rb_https); lastDoh = dnsValue }
            else -> { rgMode.check(R.id.rb_udp); lastUdp = dnsValue }
        }

        when (proxyProtocolValue.lowercase()) {
            "http" -> rgProxyProtocol.check(R.id.rb_proxy_http)
            else -> rgProxyProtocol.check(R.id.rb_proxy_socks)
        }

        val congAdapter = spSlipstreamCongestion.adapter as? ArrayAdapter<String>
        if (congAdapter != null) {
            val idx = congAdapter.getPosition(slipCongestion)
            if (idx >= 0) spSlipstreamCongestion.setSelection(idx)
        }
        swSlipstreamAuthoritative.isChecked = slipAuth
        swSlipstreamGso.isChecked = slipGso

        when (authProtocolValue.lowercase()) {
            "ssh" -> {
                rgAuthProtocol.check(R.id.rb_auth_ssh)
                swSshKey.isEnabled = useAuth
                swSshKey.alpha = if (useAuth) 1.0f else 0.3f
                swSshKey.isChecked = useSshKey
                etUser.setText(sshUserCache)
                etPass.setText(sshPassCache)
            }
            "shadowsocks" -> {
                rgAuthProtocol.check(R.id.rb_auth_shadowsocks)
                swSshKey.isEnabled = false
                swSshKey.alpha = 0.3f
                swSshKey.isChecked = false
                etUser.setText("")
                etPass.setText(ssPassCache)
            }
            else -> {
                rgAuthProtocol.check(R.id.rb_auth_socks)
                swSshKey.isEnabled = false
                swSshKey.alpha = 0.3f
                swSshKey.isChecked = false
                etUser.setText(basicUserCache)
                etPass.setText(basicPassCache)
            }
        }

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
            etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT
            etPass.minLines = 1
            etPass.maxLines = 1
            etPass.gravity = android.view.Gravity.CENTER_VERTICAL
            etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
        }

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

        swDnstt.isChecked = dnsttValue
        swAuth.isChecked = useAuth

        if (rtIndex >= 0) spRecordType.setSelection(rtIndex)
        etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()

        if (pubkeyValue.isNotEmpty()) {
            findViewById<SwitchCompat>(R.id.sw_slipstream_cert)?.isChecked = true
        }
    }

    private fun populateJsonObject(
        obj: JSONObject, name: String, domain: String, pubkey: String, dns: String,
        mode: String, dns_mode: String, recordType: String, idleTimeout: String, keepAlive: String,
        clientIdSize: Long, mtu: Long, dnsttCompatible: Boolean, useAuth: Boolean,
        useSshKey: Boolean, localProxyProtocolValue: String, authProtocolValue: String,
        ssMethod: String, masterDnsMethodValue: String, user: String, pass: String, useMultiDomains: Boolean,
        tunnelProtocol: String, vlessIp: String, domainIndex: Int, vlessPort: Int,
        slipCongestion: String, slipAuth: Boolean, slipGso: Boolean
    ) {
        obj.put("name", name)
        obj.put("domain", domain)
        obj.put("pubkey", pubkey)
        obj.put("dnsAddress", dns)
        obj.put("mode", mode)
        obj.put("dns_mode", dns_mode)
        obj.put("recordType", recordType)
        obj.put("idleTimeout", idleTimeout)
        obj.put("keepAlive", keepAlive)
        obj.put("clientIdSize", clientIdSize)
        obj.put("mtu", mtu)
        obj.put("dnsttCompatible", dnsttCompatible)
        obj.put("useAuth", useAuth)
        obj.put("useSshKey", useSshKey)
        obj.put("localProxyProtocol", localProxyProtocolValue)
        obj.put("authProtocol", authProtocolValue)
        obj.put("ssMethod", ssMethod)
        obj.put("masterDnsMethod", masterDnsMethodValue)
        obj.put("user", user)
        obj.put("pass", pass)
        obj.put("useMultiDomains", useMultiDomains)
        obj.put("domainIndex", domainIndex)
        obj.put("tunnelProtocol", tunnelProtocol)
        obj.put("vlessIp", vlessIp)
        obj.put("vlessPort", vlessPort)
        obj.put("sshUser", sshUserCache)
        obj.put("sshPass", sshPassCache)
        obj.put("ssPass", ssPassCache)
        obj.put("basicUser", basicUserCache)
        obj.put("basicPass", basicPassCache)
        obj.put("slipCongestion", slipCongestion)
        obj.put("slipAuth", slipAuth)
        obj.put("slipGso", slipGso)
    }

    private fun saveOrUpdateConfig(
        passedConfigId: String, name: String, domain: String, pubkey: String, dns: String,
        mode: String, dns_mode: String, recordType: String, idleTimeout: String, keepAlive: String,
        clientIdSize: Long, mtu: Long, dnsttCompatible: Boolean, useAuth: Boolean, useSshKey: Boolean,
        localProxyProtocolValue: String, authProtocolValue: String, ssMethod: String, masterDnsMethodValue: String,
        user: String, pass: String, useMultiDomains: Boolean, tunnelProtocol: String, selectedVlessIp: String,
        domainIndex: Int, selectedCdn: String, selectedPort: Int,
        slipCongestion: String, slipAuth: Boolean, slipGso: Boolean
    ) {
        if (editingConfigId?.startsWith("default_") == true) {
            val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("${editingConfigId}_name", name)
                putString("${editingConfigId}_dns", dns)
                putString("${editingConfigId}_mode", mode)
                putString("${editingConfigId}_dns_mode", dns_mode)
                putLong("${editingConfigId}_mtu", mtu)
                putBoolean("${editingConfigId}_useMultiDomains", useMultiDomains)
                putString("${editingConfigId}_tunnelProtocol", tunnelProtocol)
                putString("${editingConfigId}_vlessIp", CryptoHelper.encrypt(selectedVlessIp))
                putInt("${editingConfigId}_domainIndex", domainIndex)
                putString("${editingConfigId}_cdn", selectedCdn)
                putInt("${editingConfigId}_vlessPort", selectedPort)
                putString("${editingConfigId}_localProxyProtocol", localProxyProtocolValue)
                putString("${editingConfigId}_masterDnsMethod", masterDnsMethodValue)
                putString("${editingConfigId}_slipCongestion", slipCongestion)
                putBoolean("${editingConfigId}_slipAuth", slipAuth)
                putBoolean("${editingConfigId}_slipGso", slipGso)
            }.apply()
            finish()
            return
        }

        val sharedPref = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
        val configsString = sharedPref.getString("configs", "[]") ?: "[]"
        val jsonArray = JSONArray(configsString)
        val finalAssignedId: String

        if (editingConfigId != null) {
            finalAssignedId = editingConfigId!!
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("id") == editingConfigId) {
                    populateJsonObject(obj, name, domain, pubkey, dns, mode, dns_mode, recordType,
                        idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible, useAuth,
                        useSshKey, localProxyProtocolValue, authProtocolValue, ssMethod, masterDnsMethodValue, user, pass,
                        useMultiDomains, tunnelProtocol, selectedVlessIp, domainIndex, selectedPort,
                        slipCongestion, slipAuth, slipGso)
                    break
                }
            }
        } else {
            finalAssignedId = java.util.UUID.randomUUID().toString()
            val newObj = JSONObject()
            newObj.put("id", finalAssignedId)
            populateJsonObject(newObj, name, domain, pubkey, dns, mode, dns_mode, recordType,
                idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible, useAuth,
                useSshKey, localProxyProtocolValue, authProtocolValue, ssMethod, masterDnsMethodValue, user, pass,
                useMultiDomains, tunnelProtocol, selectedVlessIp, domainIndex, selectedPort,
                slipCongestion, slipAuth, slipGso)
            jsonArray.put(newObj)

            val tempManual = java.io.File(filesDir, "manual_resolvers_new_temp_config.txt")
            if (tempManual.exists()) tempManual.renameTo(java.io.File(filesDir, "manual_resolvers_${finalAssignedId}.txt"))
            val tempSelected = java.io.File(filesDir, "selected_multipath_new_temp_config.txt")
            if (tempSelected.exists()) tempSelected.renameTo(java.io.File(filesDir, "selected_multipath_${finalAssignedId}.txt"))
            val tempScanned = java.io.File(filesDir, "resolvers_new_temp_config.txt")
            if (tempScanned.exists()) tempScanned.renameTo(java.io.File(filesDir, "resolvers_${finalAssignedId}.txt"))
        }

        sharedPref.edit().putString("configs", jsonArray.toString()).apply()
        sharedPref.edit().putString("${finalAssignedId}_cdn", selectedCdn).apply()
        sharedPref.edit().putInt("${finalAssignedId}_vlessPort", selectedPort).apply()
    }

    override fun onResume() {
        super.onResume()
        val currentId = editingConfigId ?: "new_temp_config"
        setupMultipathData(currentId)
        updateDnsFieldState()
    }

    companion object {
        fun loadAllConfigs(context: Context): List<Config> {
            val sharedPref = context.getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
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
                        mtu = obj.optLong("mtu", 0L),
                        dnsttCompatible = obj.optBoolean("dnsttCompatible", false),
                        useAuth = obj.optBoolean("useAuth", false),
                        useSshKey = obj.optBoolean("useSshKey", false),
                        localProxyProtocol = obj.optString("localProxyProtocol", "socks5"),
                        authProtocol = obj.optString("authProtocol", "socks"),
                        ssMethod = obj.optString("ssMethod", "chacha20-ietf-poly1305"),
                        masterDnsMethod = obj.optString("masterDnsMethod", "XOR"), // NEW
                        user = obj.optString("user", ""),
                        pass = obj.optString("pass", ""),
                        useMultiDomains = obj.optBoolean("useMultiDomains", false),
                        domainIndex = obj.optInt("domainIndex", 0),
                        tunnelProtocol = obj.optString("tunnelProtocol", "vaydns"),
                        vlessIp = CryptoHelper.decrypt(obj.optString("vlessIp", "")),
                        vlessPort = obj.optInt("vlessPort", 443),
                        isDefault = false,
                        slipstreamCongestion = obj.optString("slipCongestion", "BBR"),
                        slipstreamAuthoritative = obj.optBoolean("slipAuth", false),
                        slipstreamGso = obj.optBoolean("slipGso", false),
                    )
                )
            }
            return list
        }

        fun saveAllConfigs(context: Context, configs: List<Config>) {
            val sharedPref = context.getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
            val array = JSONArray()

            configs.forEach { config ->
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
                        put("useAuth", config.useAuth)
                        put("useSshKey", config.useSshKey)
                        put("localProxyProtocol", config.localProxyProtocol)
                        put("authProtocol", config.authProtocol)
                        put("ssMethod", config.ssMethod)
                        put("masterDnsMethod", config.masterDnsMethod)
                        put("user", config.user)
                        put("pass", config.pass)
                        put("useMultiDomains", config.useMultiDomains)
                        put("tunnelProtocol", config.tunnelProtocol)
                        put("vlessIp", CryptoHelper.encrypt(config.vlessIp))
                        put("vlessPort", config.vlessPort)
                        put("domainIndex", config.domainIndex)
                        put("slipCongestion", config.slipstreamCongestion)
                        put("slipAuth", config.slipstreamAuthoritative)
                        put("slipGso", config.slipstreamGso)
                    }
                    array.put(obj)
                }
            }

            sharedPref.edit().putString("configs", array.toString()).apply()
        }
    }
}