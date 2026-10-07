package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mobile.Mobile

class DefaultConfigEditorActivity : AppCompatActivity() {

    private var editingConfigId: String? = null
    private lateinit var switchMultiDomain: SwitchCompat
    private var configIndex: Long = 0L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_default_config_editor)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_editor)
        toolbar.setNavigationOnClickListener { finish() }

        editingConfigId = intent.getStringExtra("CONFIG_ID")
        if (editingConfigId == null || !editingConfigId!!.startsWith("default_")) {
            Toast.makeText(this, "Invalid default configuration ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        configIndex = editingConfigId!!.removePrefix("default_").toLongOrNull() ?: 0L

        val etName = findViewById<EditText>(R.id.et_config_name)
        switchMultiDomain = findViewById(R.id.switch_multi_domain)
        val etDns = findViewById<EditText>(R.id.et_dns)
        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
        val rbAuto = findViewById<RadioButton>(R.id.rb_auto)
        val etMtu = findViewById<EditText>(R.id.et_mtu)
        val etMaxMtu = findViewById<EditText>(R.id.et_max_mtu)
        val etParallelism = findViewById<EditText>(R.id.et_parallelism)
        val tvMtuLabel = findViewById<TextView>(R.id.tv_mtu_label)
        val swUseDefaultResolvers = findViewById<SwitchCompat>(R.id.sw_use_default_resolvers)
        val rgProxyProtocol = findViewById<RadioGroup>(R.id.rg_proxy_protocol)
        val spinnerTunnelProtocol = findViewById<Spinner>(R.id.spinner_tunnel_protocol)
        val spinnerEditorCdn = findViewById<Spinner>(R.id.spinner_editor_cdn)
        val layoutEditorCdn = findViewById<LinearLayout>(R.id.layout_editor_cdn)
        val spinnerEditorPort = findViewById<Spinner>(R.id.spinner_editor_port)
        val layoutEditorPort = findViewById<LinearLayout>(R.id.layout_editor_port)
        val layoutSlipstreamParams = findViewById<LinearLayout>(R.id.layout_slipstream_params)
        val spSlipstreamCongestion = findViewById<Spinner>(R.id.sp_slipstream_congestion)
        val swSlipstreamGso = findViewById<SwitchCompat>(R.id.sw_slipstream_gso)
        val layoutMultipathControls = findViewById<LinearLayout>(R.id.layout_multipath_controls)
        val layoutExtendedMtu = findViewById<LinearLayout>(R.id.layout_extended_mtu)
        val tvRecordTypeLabel = findViewById<TextView>(R.id.tv_record_type_label)
        val spRecordType = findViewById<Spinner>(R.id.sp_record_type)
        val layoutCompression = findViewById<LinearLayout>(R.id.layout_compression)
        val spUploadCompression = findViewById<Spinner>(R.id.sp_upload_compression)
        val spDownloadCompression = findViewById<Spinner>(R.id.sp_download_compression)

        val spinnerVlessProtocol = findViewById<Spinner>(R.id.spinner_vless_protocol)
        val layoutVlessProtocol = findViewById<LinearLayout>(R.id.layout_vless_protocol)
        val tvVlessIpLabel = findViewById<TextView>(R.id.tv_vless_ip_label)
        val etVlessIp = findViewById<EditText>(R.id.et_vless_ip)
        val btnBestCfIp = findViewById<Button>(R.id.btn_best_cf_ip)

        etVlessIp.isEnabled = false
        etVlessIp.alpha = 0.5f

        tvMultipathStatus = findViewById(R.id.tv_multipath_status)
        btnSelectMultipath = findViewById(R.id.btn_select_multipath)

        val congestionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("BBR", "DCUBIC"))
        congestionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSlipstreamCongestion.adapter = congestionAdapter

        setupMultipathData(editingConfigId!!)

        btnSelectMultipath.setOnClickListener {
            val mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_tcp -> "tcp"
                R.id.rb_tls -> "dot"
                R.id.rb_https -> "doh"
                R.id.rb_auto -> "auto"
                else -> "udp"
            }
            val intent = Intent(this, MultipathResolverActivity::class.java).apply {
                putExtra("CONFIG_ID", editingConfigId!!)
                putExtra("TUNNEL_MODE", mode)
            }
            startActivity(intent)
        }

        // Setup Compression Adapters
        val compressionOptions = arrayOf("OFF", "ZSTD", "LZ4", "ZLIB")
        val compAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, compressionOptions)
        compAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spUploadCompression.adapter = compAdapter
        spDownloadCompression.adapter = compAdapter

        val originalName = Mobile.getDefaultConfigName(configIndex).ifEmpty { "Official Server ${configIndex + 1}" }
        val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
        val savedName = prefs.getString("${editingConfigId}_name", originalName)
        val savedDns = prefs.getString("${editingConfigId}_dns", "8.8.8.8:53")
        val savedMode = prefs.getString("${editingConfigId}_mode", "udp")
        val savedMtu = prefs.getLong("${editingConfigId}_mtu", 0L)
        val savedUseMulti = prefs.getBoolean("${editingConfigId}_useMultiDomains", false)
        val savedDomainIndex = prefs.getInt("${editingConfigId}_domainIndex", 0)

        // Load Record Type
        val defaultRecordType = Mobile.getDefaultConfigRecordType(configIndex)
        var savedRecordType = prefs.getString("${editingConfigId}_recordType", defaultRecordType.ifEmpty { "TXT" }) ?: "TXT"

        // Load Compression
        val savedUpComp = prefs.getInt("${editingConfigId}_upCompression", 2)
        val savedDownComp = prefs.getInt("${editingConfigId}_downCompression", 2)
        spUploadCompression.setSelection(savedUpComp.coerceIn(0, 3))
        spDownloadCompression.setSelection(savedDownComp.coerceIn(0, 3))

        // Dynamically set fallback for Max MTU based on default protocol
        val rawTypes = Mobile.getDefaultConfigType(configIndex).split(",").map { it.trim().lowercase() }
        val supportedProtocols = if (rawTypes.isEmpty() || rawTypes[0] == "") listOf("vaydns", "masterdns", "stormdns", "cottendns", "slipstream") else rawTypes
        val currentProto = prefs.getString("${editingConfigId}_tunnelProtocol", null) ?: supportedProtocols.first()
        val isExtendedProto = currentProto in listOf("masterdns", "stormdns", "cottendns")
        val savedMaxMtu = prefs.getLong("${editingConfigId}_maxMtu", if (isExtendedProto) 200L else 140L)
        val savedParallelism = prefs.getLong("${editingConfigId}_parallelism", 32L)

        val encryptedVlessIp = prefs.getString("${editingConfigId}_vlessIp", "") ?: ""
        realVlessIp = CryptoHelper.decrypt(encryptedVlessIp)
        if (realVlessIp.isNotEmpty()) {
            etVlessIp.setText(Mobile.encryptIP(realVlessIp))
        } else {
            etVlessIp.setText("")
        }

        val domainCount = Mobile.getDefaultConfigDomainCount(configIndex).toInt()
        val rbs = arrayOf(
            findViewById<RadioButton>(R.id.rb_domain_1),
            findViewById<RadioButton>(R.id.rb_domain_2),
            findViewById<RadioButton>(R.id.rb_domain_3),
            findViewById<RadioButton>(R.id.rb_domain_4)
        )
        for (i in 0..3) {
            if (i < domainCount) {
                rbs[i].isEnabled = true
                rbs[i].text = "Domain ${i + 1}"
            } else {
                rbs[i].isEnabled = false
                rbs[i].text = "Unused"
            }
        }
        val selectedId = when (savedDomainIndex) {
            1 -> R.id.rb_domain_2
            2 -> R.id.rb_domain_3
            3 -> R.id.rb_domain_4
            else -> R.id.rb_domain_1
        }
        findViewById<RadioGroup>(R.id.rg_domain_selector).check(selectedId)

        etName.setText(savedName)
        switchMultiDomain.isChecked = savedUseMulti

        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val currentText = s?.toString() ?: ""
                if (!currentText.startsWith(originalName)) {
                    etName.setText(originalName)
                    etName.setSelection(originalName.length)
                }
            }
        })

        etVlessIp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val input = s.toString().trim()
                if (input.isNotEmpty()) {
                    var decrypted = Mobile.decryptIP(input)
                    if (decrypted.isEmpty() || decrypted == input) decrypted = input
                    realVlessIp = decrypted
                } else {
                    realVlessIp = ""
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnBestCfIp.setOnClickListener { buttonView ->
            val btn = buttonView as Button
            btn.text = "Scanning ..."
            btn.isEnabled = false

            val selectedCdn = spinnerEditorCdn.selectedItem?.toString() ?: "Cloudflare"
            var selectedTunnelProtocol = spinnerTunnelProtocol.selectedItem?.toString() ?: "vless-ws"
            if (selectedTunnelProtocol.lowercase().startsWith("vless")) {
                selectedTunnelProtocol = spinnerVlessProtocol.selectedItem?.toString() ?: selectedTunnelProtocol
            }
            val selectedPortStr = spinnerEditorPort.selectedItem?.toString() ?: "443"
            val selectedPort = selectedPortStr.toLongOrNull() ?: 443L

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

            val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
            val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"
            val allIpsList = mutableListOf<String>()

            try {
                val jsonArray = org.json.JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val ipCdn = obj.optString("cdn", "Cloudflare")
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
                val result = Mobile.getFastestCloudflareIP(true, configIndex, savedIps, "", selectedCdn, selectedPort, selectedTunnelProtocol)
                runOnUiThread {
                    btn.text = "Get best IP from Vault"
                    btn.isEnabled = true
                    if (result.isNotEmpty() && result.contains("|")) {
                        val parts = result.split("|")
                        val bestIp = parts[0]
                        val latency = parts[1]
                        realVlessIp = bestIp
                        val mappedWinner = Mobile.encryptIP(bestIp)
                        etVlessIp.setText(mappedWinner)
                        Toast.makeText(this@DefaultConfigEditorActivity, "Winner: $mappedWinner (${latency}ms)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@DefaultConfigEditorActivity, "All IPs failed the Layer 7 Handshake.", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        swUseDefaultResolvers.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val defaultResolversStr = Mobile.getDefaultConfigDisplayResolvers(configIndex)
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
        when (savedMode) {
            "tcp" -> rgMode.check(R.id.rb_tcp)
            "dot" -> rgMode.check(R.id.rb_tls)
            "doh" -> rgMode.check(R.id.rb_https)
            "auto" -> rgMode.check(R.id.rb_auto)
            else -> rgMode.check(R.id.rb_udp)
        }
        etMtu.setText(savedMtu.toString())
        etMaxMtu.setText(savedMaxMtu.toString())
        etParallelism.setText(savedParallelism.toString())

        // val rawTypes = Mobile.getDefaultConfigType(configIndex).split(",").map { it.trim().lowercase() }
        // val supportedProtocols = if (rawTypes.isEmpty() || rawTypes[0] == "") listOf("vaydns", "masterdns", "stormdns", "cottendns", "slipstream") else rawTypes
        val tpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedProtocols)
        tpAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTunnelProtocol.adapter = tpAdapter

        // val currentProto = prefs.getString("${editingConfigId}_tunnelProtocol", null) ?: supportedProtocols.first()
        val pIndex = supportedProtocols.indexOf(currentProto)
        if (pIndex >= 0) spinnerTunnelProtocol.setSelection(pIndex)

        val cdnList = mutableListOf<String>()
        val configCloudsStr = Mobile.getDefaultConfigClouds(configIndex)
        if (configCloudsStr.isNotEmpty()) {
            cdnList.addAll(configCloudsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (cdnList.isEmpty()) {
            for (i in 0 until Mobile.getCdnCount()) {
                val name = Mobile.getCdnName(i)
                if (name.isNotEmpty()) cdnList.add(name)
            }
        }
        if (cdnList.isEmpty()) cdnList.addAll(listOf("Cloudflare", "CloudY", "CloudZ", "CloudV"))
        val cdnAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cdnList)
        cdnAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEditorCdn.adapter = cdnAdapter

        val currentConfigCdn = prefs.getString("${editingConfigId}_cdn", "Cloudflare") ?: "Cloudflare"
        val cdnIndex = cdnList.indexOf(currentConfigCdn)
        if (cdnIndex >= 0) spinnerEditorCdn.setSelection(cdnIndex)

        val currentConfigPort = prefs.getInt("${editingConfigId}_vlessPort", 443)

        fun updatePortSpinner(selectedCdn: String, targetPort: Int) {
            val portsCsv = Mobile.getCdnPortsCsv(selectedCdn)
            val cdnFilteredPorts = if (portsCsv.isNotEmpty()) portsCsv.split(",").map { it.trim() } else listOf("443")
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
        updatePortSpinner(currentConfigCdn, currentConfigPort)

        var isInitialCdnSetup = true

        spinnerEditorCdn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedCdn = parent.getItemAtPosition(position).toString()

                val masterProto = spinnerTunnelProtocol.selectedItem?.toString()?.lowercase()?.trim() ?: ""
                if (masterProto.startsWith("vless")) {
                    updateVlessProtocolSpinner(selectedCdn)
                }

                val currentSelectedPort = spinnerEditorPort.selectedItem?.toString()?.toIntOrNull() ?: currentConfigPort
                updatePortSpinner(selectedCdn, currentSelectedPort)

                if (isInitialCdnSetup) {
                    isInitialCdnSetup = false
                    return
                }

                realVlessIp = ""
                etVlessIp.setText("")

                val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
                val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"
                var firstIp = ""
                var fallbackIp = ""

                try {
                    val jsonArray = org.json.JSONArray(jsonString)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val ipCdn = obj.optString("cdn", "Cloudflare")

                        if (ipCdn.equals(selectedCdn, ignoreCase = true)) {
                            val rawIp = obj.getString("ip")
                            val decryptedIp = CryptoHelper.decrypt(rawIp)

                            if (fallbackIp.isEmpty()) fallbackIp = decryptedIp
                            if (obj.optBoolean("isChecked", false)) {
                                firstIp = decryptedIp
                                break
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }

                if (firstIp.isEmpty() && fallbackIp.isNotEmpty()) {
                    firstIp = fallbackIp
                }

                if (firstIp.isNotEmpty()) {
                    realVlessIp = firstIp
                    etVlessIp.setText(Mobile.encryptIP(firstIp))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerTunnelProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selected = parent.getItemAtPosition(position).toString().lowercase().trim()
                val isVaydns = selected == "vaydns"
                val isMasterDns = selected == "masterdns"
                val isStormDns = selected == "stormdns"
                val isCottenDns = selected == "cottendns"
                val isSlipstream = selected == "slipstream"
                val isExtendedDns = isMasterDns || isStormDns || isCottenDns
                val isDnsBase = isVaydns || isExtendedDns || isSlipstream
                val isVless = selected.startsWith("vless")

                val dnsVisibility = if (isDnsBase) View.VISIBLE else View.GONE
                val vlessVisibility = if (isVless) View.VISIBLE else View.GONE

                // 1. CDN, Port, Protocol, and Raced IP selectors (Only for VLESS)
                layoutEditorCdn.visibility = vlessVisibility
                layoutEditorPort.visibility = vlessVisibility
                layoutVlessProtocol.visibility = vlessVisibility
                tvVlessIpLabel.visibility = vlessVisibility
                etVlessIp.visibility = vlessVisibility
                btnBestCfIp.visibility = vlessVisibility

                if (isVless) {
                    val selectedCdn = spinnerEditorCdn.selectedItem?.toString() ?: "Cloudflare"
                    updateVlessProtocolSpinner(selectedCdn)
                }

                // 2. DNS Server, Resolvers, Proxy Protocol (Only for DNS-based)
                findViewById<TextView>(R.id.tv_dns_label)?.visibility = dnsVisibility
                swUseDefaultResolvers.visibility = dnsVisibility
                etDns.visibility = dnsVisibility

                findViewById<TextView>(R.id.tv_multipath_label)?.visibility = dnsVisibility
                findViewById<TextView>(R.id.tv_multipath_desc)?.visibility = dnsVisibility
                layoutMultipathControls.visibility = dnsVisibility

                val tvProxyLabel = findViewById<TextView>(R.id.tv_proxy_protocol_label)
                tvProxyLabel?.visibility = dnsVisibility
                rgProxyProtocol.visibility = dnsVisibility

                // 3. Extended MTU & Compression Visibility
                findViewById<TextView>(R.id.tv_mtu_label)?.visibility = dnsVisibility
                etMtu.visibility = dnsVisibility
                layoutExtendedMtu.visibility = if (isExtendedDns) View.VISIBLE else View.GONE
                layoutCompression.visibility = if (isExtendedDns) View.VISIBLE else View.GONE

                val tvMtuLabel = findViewById<TextView>(R.id.tv_mtu_label)
                if (isExtendedDns) {
                    tvMtuLabel?.text = "Min. Upload MTU:"
                    etMtu.hint = "40 < Min MTU < 200"
                    etMaxMtu.hint = "40 < Min MTU < 200"
                    if (etMtu.text.toString() == "0") etMtu.setText("40")
                    if (etMaxMtu.text.toString() == "140") etMaxMtu.setText("200")
                }else{
                    tvMtuLabel?.text = "Upload MTU:"
                    etMtu.hint = "40 < MTU < 140"
                }
                // 4. Record Type Population
                if (isDnsBase && isMasterDns && isStormDns && isCottenDns) {
                    tvRecordTypeLabel.visibility = View.VISIBLE
                    spRecordType.visibility = View.VISIBLE

                    val recordTypes = when (selected) {
                        "masterdns" -> arrayOf("TXT")
                        "stormdns" -> arrayOf("TXT", "NS", "CNAME", "SRV", "ROTATE")
                        "cottendns" -> arrayOf("TXT", "CNAME", "A", "AAAA", "NULL", "MX", "NS", "PTR", "SRV", "SVCB", "CAA", "NAPTR", "SOA", "HTTPS", "ROTATE")
                        else -> arrayOf("TXT", "NULL", "CNAME", "A", "AAAA", "MX", "NS", "SRV", "CAA")
                    }
                    val rtAdapter = ArrayAdapter(this@DefaultConfigEditorActivity, android.R.layout.simple_spinner_item, recordTypes)
                    rtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spRecordType.adapter = rtAdapter

                    val rtIndex = recordTypes.indexOf(savedRecordType.uppercase())
                    if (rtIndex >= 0) {
                        spRecordType.setSelection(rtIndex)
                    }
                } else {
                    tvRecordTypeLabel.visibility = View.GONE
                    spRecordType.visibility = View.GONE
                }

                // 5. Tunnel Mode (Auto for CottenDNS)
                val tvModeLabel = findViewById<TextView>(R.id.tv_mode_label)
                val rgModeGroup = findViewById<RadioGroup>(R.id.rg_mode)
                if (isVaydns || isCottenDns) {
                    tvModeLabel?.visibility = View.VISIBLE
                    rgModeGroup?.visibility = View.VISIBLE

                    val rbAuto = findViewById<RadioButton>(R.id.rb_auto)
                    if (isCottenDns) {
                        rbAuto?.visibility = View.VISIBLE
                    } else {
                        rbAuto?.visibility = View.GONE
                        if (rgModeGroup?.checkedRadioButtonId == R.id.rb_auto) {
                            rgModeGroup.check(R.id.rb_udp) // Fallback if Auto unsupported
                        }
                    }
                } else {
                    tvModeLabel?.visibility = View.GONE
                    rgModeGroup?.visibility = View.GONE
                }

                // 6. Domain Selector / Multi-domain (Vaydns only)
                findViewById<TextView>(R.id.tv_domain_selector_label)?.visibility = if (isVaydns) View.VISIBLE else View.GONE
                findViewById<RadioGroup>(R.id.rg_domain_selector)?.visibility = if (isVaydns) View.VISIBLE else View.GONE
                switchMultiDomain.visibility = if (isVaydns) View.VISIBLE else View.GONE

                // 7. Slipstream params
                layoutSlipstreamParams.visibility = if (isSlipstream) View.VISIBLE else View.GONE

                // 8. Proxy Protocol Management
                if (isVless) {
                    rgProxyProtocol.check(R.id.rb_proxy_socks)
                    for (i in 0 until rgProxyProtocol.childCount) {
                        rgProxyProtocol.getChildAt(i).isEnabled = false
                        rgProxyProtocol.getChildAt(i).alpha = 0.5f
                    }
                } else {
                    for (i in 0 until rgProxyProtocol.childCount) {
                        rgProxyProtocol.getChildAt(i).isEnabled = true
                        rgProxyProtocol.getChildAt(i).alpha = 1.0f
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        val defaultProxyType = Mobile.getDefaultConfigProxy(configIndex)
        val savedProxyType = prefs.getString("${editingConfigId}_localProxyProtocol", defaultProxyType) ?: defaultProxyType
        if (savedProxyType.lowercase() == "http") {
            rgProxyProtocol.check(R.id.rb_proxy_http)
        } else {
            rgProxyProtocol.check(R.id.rb_proxy_socks)
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_udp -> etDns.setText(lastUdp)
                R.id.rb_tcp -> etDns.setText(lastTcp)
                R.id.rb_tls -> etDns.setText(lastDot)
                R.id.rb_https -> etDns.setText(lastDoh)
                R.id.rb_auto -> etDns.setText(lastUdp)
            }
        }

        val btnSaveIcon = findViewById<ImageButton>(R.id.btn_save_icon)
        btnSaveIcon.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Config name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var selectedTunnelProtocol = spinnerTunnelProtocol.selectedItem.toString()
            if (selectedTunnelProtocol.lowercase().startsWith("vless")) {
                selectedTunnelProtocol = spinnerVlessProtocol.selectedItem?.toString() ?: selectedTunnelProtocol
            }

            val dns = etDns.text.toString().trim()
            val mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_tcp -> "tcp"
                R.id.rb_tls -> "dot"
                R.id.rb_https -> "doh"
                R.id.rb_auto -> "auto"
                else -> "udp"
            }
            val dns_mode = when (mode) {
                "tcp" -> "TCP"
                "dot" -> "DoT"
                "doh" -> "DoH"
                "auto" -> "AUTO"
                else -> "UDP"
            }

            val mtuValue = etMtu.text.toString().toLongOrNull() ?: 0L
            val maxMtu = etMaxMtu.text.toString().toLongOrNull() ?: 140L
            val parallelism = etParallelism.text.toString().toLongOrNull() ?: 32L

            val lowerProto = selectedTunnelProtocol.lowercase()
            val isSavingExtendedDns = lowerProto == "masterdns" || lowerProto == "stormdns" || lowerProto == "cottendns"

            if (isSavingExtendedDns) {
                if (mtuValue < 40L || mtuValue > 200L) {
                    Toast.makeText(this, "Invalid Min. Upload MTU: Engine requires a value between 40 and 200", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (maxMtu < 40L || maxMtu > 200L) {
                    Toast.makeText(this, "Invalid Max. Upload MTU: Engine requires a value between 40 and 200.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (maxMtu <= mtuValue) {
                    Toast.makeText(this, "Invalid Upload MTU: Max MTU must be strictly greater than Min MTU.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (parallelism < 8L || parallelism > 512L) {
                    Toast.makeText(this, "Invalid Parallelism: Must be between 8 and 512.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            } else if (lowerProto == "vaydns") {
                if (mtuValue != 0L && (mtuValue < 40L || mtuValue > 140L)) {
                    Toast.makeText(this, "Invalid Upload MTU: VayDNS requires a value between 40 and 140, or 0 for default.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val useMultiDomains = switchMultiDomain.isChecked
            val selectedCdn = spinnerEditorCdn.selectedItem?.toString() ?: "Cloudflare"
            val selectedPort = spinnerEditorPort.selectedItem?.toString()?.toIntOrNull() ?: 443
            val localProxyProtocol = if (rgProxyProtocol.checkedRadioButtonId == R.id.rb_proxy_http) "http" else "socks5"
            val domainIndex = when (findViewById<RadioGroup>(R.id.rg_domain_selector).checkedRadioButtonId) {
                R.id.rb_domain_2 -> 1
                R.id.rb_domain_3 -> 2
                R.id.rb_domain_4 -> 3
                else -> 0
            }
            val slipCongestion = spSlipstreamCongestion.selectedItem?.toString() ?: "BBR"
            val slipGso = swSlipstreamGso.isChecked

            // Extract Record Type dynamically
            val recordType = if (spRecordType.visibility == View.VISIBLE) spRecordType.selectedItem?.toString() ?: "TXT" else "TXT"

            val manualAddrs = resolverEntries.filter { it.isManual }.map { it.address }
            java.io.File(filesDir, "manual_resolvers_$editingConfigId.txt").writeText(manualAddrs.joinToString("\n"))

            val selectedAddrs = resolverEntries.filter { it.isChecked && it.address.isNotEmpty() }.map { it.address }
            java.io.File(filesDir, "selected_multipath_$editingConfigId.txt").writeText(selectedAddrs.joinToString("\n"))

            prefs.edit().apply {
                putString("${editingConfigId}_name", name)
                putString("${editingConfigId}_dns", dns)
                putString("${editingConfigId}_mode", mode)
                putString("${editingConfigId}_dns_mode", dns_mode)
                putLong("${editingConfigId}_mtu", mtuValue)
                putLong("${editingConfigId}_maxMtu", maxMtu)
                putLong("${editingConfigId}_parallelism", parallelism)
                putBoolean("${editingConfigId}_useMultiDomains", useMultiDomains)
                putString("${editingConfigId}_tunnelProtocol", selectedTunnelProtocol)
                putString("${editingConfigId}_vlessIp", CryptoHelper.encrypt(realVlessIp.trim()))
                putInt("${editingConfigId}_domainIndex", domainIndex)
                putString("${editingConfigId}_cdn", selectedCdn)
                putInt("${editingConfigId}_vlessPort", selectedPort)
                putString("${editingConfigId}_localProxyProtocol", localProxyProtocol)
                putString("${editingConfigId}_slipCongestion", slipCongestion)
                putBoolean("${editingConfigId}_slipGso", slipGso)

                // Add the new fields
                putString("${editingConfigId}_recordType", recordType)
                putInt("${editingConfigId}_upCompression", spUploadCompression.selectedItemPosition)
                putInt("${editingConfigId}_downCompression", spDownloadCompression.selectedItemPosition)
            }.apply()

            finish()
        }
    }

    private fun updateVlessProtocolSpinner(selectedCdn: String): Boolean {
        val baseSupportedProtocols = Mobile.getDefaultConfigType(configIndex).split(",").map { it.trim().lowercase() }
        val vlessProtosInConfig = baseSupportedProtocols.filter { it.startsWith("vless") }
        val supportedByCdn = vlessProtosInConfig.filter { Mobile.cdnSupportsProtocol(selectedCdn, it) }

        if (supportedByCdn.isEmpty()) return false

        val spinnerVlessProtocol = findViewById<Spinner>(R.id.spinner_vless_protocol)
        val currentVlessProto = spinnerVlessProtocol.selectedItem?.toString() ?: ""
        val vlessAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, supportedByCdn)
        vlessAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVlessProtocol.adapter = vlessAdapter

        if (supportedByCdn.contains(currentVlessProto)) {
            spinnerVlessProtocol.setSelection(supportedByCdn.indexOf(currentVlessProto))
        } else {
            spinnerVlessProtocol.setSelection(0)
        }
        return true
    }

    private fun setupMultipathData(configId: String) {
        resolverEntries.clear()
        val scanFile = java.io.File(filesDir, "resolvers_$configId.txt")
        if (scanFile.exists()) {
            scanFile.readLines().forEach { line ->
                val parts = line.split(",")
                val ip = parts[0].trim()
                if (ip.isNotEmpty()) resolverEntries.add(ResolverEntry(ip, isChecked = false, isManual = false, latency = if (parts.size > 1) parts[1].trim() else ""))
            }
        }
        val manualFile = java.io.File(filesDir, "manual_resolvers_$configId.txt")
        val savedManuals = if (manualFile.exists()) manualFile.readLines() else emptyList()
        for (i in 0 until 20) {
            resolverEntries.add(ResolverEntry(savedManuals.getOrNull(i) ?: "", isChecked = false, isManual = true, latency = ""))
        }
        val selectedFile = java.io.File(filesDir, "selected_multipath_$configId.txt")
        val currentSelections = if (selectedFile.exists()) selectedFile.readLines().toSet() else emptySet()
        resolverEntries.forEach { entry -> if (currentSelections.contains(entry.address)) entry.isChecked = true }
        tvMultipathStatus.text = "${resolverEntries.count { it.isChecked }} IPs selected"
    }

    override fun onResume() {
        super.onResume()
        setupMultipathData(editingConfigId!!)
    }
}
