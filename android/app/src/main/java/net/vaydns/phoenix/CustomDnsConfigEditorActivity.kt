package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class CustomDnsConfigEditorActivity : AppCompatActivity() {

    private var editingConfigId: String? = null
    private lateinit var switchMultiDomain: SwitchCompat
    private var sshUserCache = ""
    private var sshPassCache = ""
    private var ssPassCache = ""
    private var basicUserCache = ""
    private var basicPassCache = ""
    private var currentAuthMode = "socks"
    private var isInitializing = true
    private var activeTunnelProtocol = "vaydns"
    private var tomlFilenameCache = ""

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

    private val tomlFilePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                importTomlFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_dns_config_editor)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_editor)
        toolbar.setNavigationOnClickListener { finish() }

        // Completely hide the Protocol Mode selector layout for Custom Configs
        findViewById<LinearLayout>(R.id.layout_tunnel_protocol).visibility = View.GONE

        val etName = findViewById<EditText>(R.id.et_config_name)
        val etDomain = findViewById<EditText>(R.id.et_domain)
        switchMultiDomain = findViewById(R.id.switch_multi_domain)

        val etPubkey = findViewById<EditText>(R.id.et_pubkey)
        etPubkey.setHorizontallyScrolling(false)

        val etDns = findViewById<EditText>(R.id.et_dns)
        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
        val rbAuto = findViewById<RadioButton>(R.id.rb_auto)
        val spRecordType = findViewById<Spinner>(R.id.sp_record_type)
        val etIdleTimeout = findViewById<EditText>(R.id.et_idle_timeout)
        val etKeepAlive = findViewById<EditText>(R.id.et_keep_alive)
        val etClientIdSize = findViewById<EditText>(R.id.et_client_id_size)
        val etMtu = findViewById<EditText>(R.id.et_mtu)
        val etMaxMtu = findViewById<EditText>(R.id.et_max_mtu)
        val etParallelism = findViewById<EditText>(R.id.et_parallelism)
        val layoutCompression = findViewById<LinearLayout>(R.id.layout_compression)
        val spUploadCompression = findViewById<Spinner>(R.id.sp_upload_compression)
        val spDownloadCompression = findViewById<Spinner>(R.id.sp_download_compression)
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
        val tvPubkeyLabel = findViewById<TextView>(R.id.tv_pubkey_label)
        val tvMasterDnsMethodLabel = findViewById<TextView>(R.id.tv_masterdns_method_label)
        val tvMtuLabel = findViewById<TextView>(R.id.tv_mtu_label)

        val layoutSlipstreamParams = findViewById<LinearLayout>(R.id.layout_slipstream_params)
        val spSlipstreamCongestion = findViewById<Spinner>(R.id.sp_slipstream_congestion)
        val swSlipstreamAuthoritative = findViewById<SwitchCompat>(R.id.sw_slipstream_authoritative)
        val swSlipstreamGso = findViewById<SwitchCompat>(R.id.sw_slipstream_gso)
        val swSlipstreamCert = findViewById<SwitchCompat>(R.id.sw_slipstream_cert)
        val tvSlipstreamAuthWarning = findViewById<TextView>(R.id.tv_slipstream_auth_warning)

        val layoutExtendedMtu = findViewById<LinearLayout>(R.id.layout_extended_mtu)
        val layoutCottendnsPreset = findViewById<LinearLayout>(R.id.layout_cottendns_preset)
        val spCottendnsPreset = findViewById<Spinner>(R.id.sp_cottendns_preset)

        val layoutTomlContainer = findViewById<LinearLayout>(R.id.layout_toml_container)
        val swUseToml = findViewById<SwitchCompat>(R.id.sw_use_toml)
        val layoutTomlImport = findViewById<LinearLayout>(R.id.layout_toml_import)
        val tvTomlFilename = findViewById<TextView>(R.id.tv_toml_filename)
        val btnImportToml = findViewById<ImageButton>(R.id.btn_import_toml)

        val spMasterDnsMethod = findViewById<Spinner>(R.id.sp_masterdns_method)
        tvMultipathStatus = findViewById(R.id.tv_multipath_status)
        btnSelectMultipath = findViewById(R.id.btn_select_multipath)

        // Adapters
        val congestionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("BBR", "DCUBIC"))
        congestionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSlipstreamCongestion.adapter = congestionAdapter

        val masterDnsMethods = arrayOf("None", "XOR", "Chacha20", "AES-128-GCM", "AES-192-GCM", "AES-256-GCM")
        val masterDnsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, masterDnsMethods)
        masterDnsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMasterDnsMethod.adapter = masterDnsAdapter

        val ssMethods = arrayOf("chacha20-ietf-poly1305", "aes-128-gcm", "aes-256-gcm", "xchacha20-ietf-poly1305")
        val ssAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ssMethods)
        ssAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSsMethod.adapter = ssAdapter

        val presetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, arrayOf("default", "speed", "survival", "tcp-survival"))
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spCottendnsPreset.adapter = presetAdapter

        val compressionOptions = arrayOf("OFF", "ZSTD", "LZ4", "ZLIB")
        val compAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, compressionOptions)
        compAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spUploadCompression.adapter = compAdapter
        spDownloadCompression.adapter = compAdapter

        etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()

        swSlipstreamAuthoritative.setOnCheckedChangeListener { _, isChecked ->
            tvSlipstreamAuthWarning.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        swSlipstreamCert.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etPubkey.visibility = View.VISIBLE
                etPubkey.hint = "Paste Public Certificate (PEM) here..."
                etPubkey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                etPubkey.minLines = 6
                etPubkey.maxLines = 15
                etPubkey.setHorizontallyScrolling(false)
            } else {
                etPubkey.visibility = View.GONE
            }
        }

        swUseToml.setOnCheckedChangeListener { _, isChecked ->
            layoutTomlImport.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnImportToml.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            tomlFilePickerLauncher.launch(intent)
        }

        editingConfigId = intent.getStringExtra("CONFIG_ID") ?: "user_${System.currentTimeMillis()}"
        val isNewConfig = intent.getBooleanExtra("IS_NEW_CONFIG", true)

        activeTunnelProtocol = intent.getStringExtra("PROTOCOL_TYPE")?.lowercase() ?: "vaydns"
        var loadedObj: JSONObject? = null

        if (!isNewConfig && editingConfigId != null) {
            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
            val configsString = sharedPref.getString("configs", "[]") ?: "[]"
            try {
                val jsonArray = JSONArray(configsString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.getString("id") == editingConfigId) {
                        loadedObj = obj
                        activeTunnelProtocol = obj.optString("tunnelProtocol", "vaydns").lowercase()
                        break
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        val recordTypes = when (activeTunnelProtocol) {
            "masterdns" -> arrayOf("TXT")
            "stormdns" -> arrayOf("TXT", "NS", "CNAME", "SRV", "ROTATE")
            "cottendns" -> arrayOf("TXT", "CNAME", "A", "AAAA", "NULL", "MX", "NS", "PTR", "SRV", "SVCB", "CAA", "NAPTR", "SOA", "HTTPS", "ROTATE")
            else -> arrayOf("TXT", "NULL", "CNAME", "A", "AAAA", "MX", "NS", "SRV", "CAA")
        }
        val rtAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, recordTypes)
        rtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRecordType.adapter = rtAdapter

        val isExtendedDns = activeTunnelProtocol == "masterdns" || activeTunnelProtocol == "stormdns" || activeTunnelProtocol == "cottendns"

        if (isExtendedDns) {
            tvMtuLabel.text = "Min. Upload MTU:"
            etMtu.hint = "40 < Min MTU < 200"
            etMaxMtu.hint = "40 < Max MTU < 200"
            etParallelism.hint = "8 < Parallelism < 512"
            layoutExtendedMtu.visibility = View.VISIBLE
            layoutCompression.visibility = View.VISIBLE
            layoutTomlContainer.visibility = View.VISIBLE

            etDomain.hint = "t.example.com"

            if (activeTunnelProtocol == "cottendns") {
                layoutCottendnsPreset.visibility = View.VISIBLE
                rgMode.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tv_mode_label).visibility = View.VISIBLE
                rbAuto.visibility = View.VISIBLE
            } else {
                layoutCottendnsPreset.visibility = View.GONE
                rgMode.visibility = View.GONE
                findViewById<TextView>(R.id.tv_mode_label).visibility = View.GONE
                rgMode.check(R.id.rb_udp)
            }

            layoutSlipstreamParams.visibility = View.GONE
            switchMultiDomain.visibility = View.GONE
            spRecordType.visibility = if (activeTunnelProtocol == "masterdns") View.GONE else View.VISIBLE
            findViewById<TextView>(R.id.tv_record_type_label).visibility = if (activeTunnelProtocol == "masterdns") View.GONE else View.VISIBLE
            etIdleTimeout.visibility = View.GONE
            findViewById<TextView>(R.id.tv_idle_timeout_label).visibility = View.GONE
            etKeepAlive.visibility = View.GONE
            findViewById<TextView>(R.id.tv_keep_alive_label).visibility = View.GONE
            etClientIdSize.visibility = View.GONE
            findViewById<TextView>(R.id.tv_client_id_size_label).visibility = View.GONE
            swDnstt.visibility = View.GONE
            tvMasterDnsMethodLabel.visibility = View.VISIBLE
            spMasterDnsMethod.visibility = View.VISIBLE
            tvPubkeyLabel.visibility = View.VISIBLE
            tvPubkeyLabel.text = "Encryption Key:"
            etPubkey.visibility = View.VISIBLE

        } else if (activeTunnelProtocol == "slipstream") {
            layoutSlipstreamParams.visibility = View.VISIBLE
            switchMultiDomain.visibility = View.GONE
            spRecordType.visibility = View.GONE
            findViewById<TextView>(R.id.tv_record_type_label).visibility = View.GONE
            etIdleTimeout.visibility = View.GONE
            findViewById<TextView>(R.id.tv_idle_timeout_label).visibility = View.GONE
            etClientIdSize.visibility = View.GONE
            findViewById<TextView>(R.id.tv_client_id_size_label).visibility = View.GONE
            swDnstt.visibility = View.GONE
            tvMasterDnsMethodLabel.visibility = View.GONE
            spMasterDnsMethod.visibility = View.GONE
            rgMode.visibility = View.GONE
            findViewById<TextView>(R.id.tv_mode_label).visibility = View.GONE
            rgMode.check(R.id.rb_udp)

            tvMtuLabel.text = "Upload MTU:"
            etMtu.hint = "40 < MTU < 140"
            etDomain.hint = "t.example.com"
            layoutExtendedMtu.visibility = View.GONE
            layoutTomlContainer.visibility = View.GONE
            layoutCottendnsPreset.visibility = View.GONE

            if (swSlipstreamCert.isChecked) {
                tvPubkeyLabel.visibility = View.VISIBLE
                tvPubkeyLabel.text = "Public Certificate:"
                etPubkey.visibility = View.VISIBLE
            } else {
                tvPubkeyLabel.visibility = View.GONE
                etPubkey.visibility = View.GONE
            }
        } else {
            // VayDNS
            layoutSlipstreamParams.visibility = View.GONE
            switchMultiDomain.visibility = View.VISIBLE
            spRecordType.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_record_type_label).visibility = View.VISIBLE
            etIdleTimeout.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_idle_timeout_label).visibility = View.VISIBLE
            etKeepAlive.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_keep_alive_label).visibility = View.VISIBLE
            etClientIdSize.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_client_id_size_label).visibility = View.VISIBLE
            swDnstt.visibility = View.VISIBLE
            tvMasterDnsMethodLabel.visibility = View.GONE
            spMasterDnsMethod.visibility = View.GONE
            rgMode.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_mode_label).visibility = View.VISIBLE
            rbAuto.visibility = View.GONE

            tvMtuLabel.text = "Upload MTU:"
            etMtu.hint = "40 < MTU < 140"
            etDomain.hint = "t.example.com, s.example.net"
            layoutExtendedMtu.visibility = View.GONE
            layoutTomlContainer.visibility = View.GONE
            layoutCottendnsPreset.visibility = View.GONE

            tvPubkeyLabel.visibility = View.VISIBLE
            tvPubkeyLabel.text = "Server Public Key:"
            etPubkey.visibility = View.VISIBLE
            etPubkey.hint = "Your Server Key"
        }

        spMasterDnsMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedMethod = parent.getItemAtPosition(position).toString()
                if (activeTunnelProtocol == "masterdns" || activeTunnelProtocol == "stormdns" || activeTunnelProtocol == "cottendns") {
                    if (selectedMethod.equals("None", ignoreCase = true)) {
                        tvPubkeyLabel.visibility = View.GONE
                        etPubkey.visibility = View.GONE
                    } else {
                        tvPubkeyLabel.visibility = View.VISIBLE
                        etPubkey.visibility = View.VISIBLE
                        tvPubkeyLabel.text = "Encryption Key:"
                        etPubkey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        etPubkey.minLines = 2
                        etPubkey.setHorizontallyScrolling(false)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        if (loadedObj != null) {
            toolbar.title = "Edit DNS Config"
            etName.setText(loadedObj.optString("name", ""))
            etDomain.setText(loadedObj.optString("domain", ""))
            switchMultiDomain.isChecked = loadedObj.optBoolean("useMultiDomains", false)
            etPubkey.setText(loadedObj.optString("pubkey", ""))
            etDns.setText(loadedObj.optString("dnsAddress", "8.8.8.8:53"))

            val modeValue = loadedObj.optString("mode", "udp")
            when (modeValue.lowercase()) {
                "tcp" -> { rgMode.check(R.id.rb_tcp); lastTcp = etDns.text.toString() }
                "dot" -> { rgMode.check(R.id.rb_tls); lastDot = etDns.text.toString() }
                "doh" -> { rgMode.check(R.id.rb_https); lastDoh = etDns.text.toString() }
                "auto" -> { rgMode.check(R.id.rb_auto) }
                else -> { rgMode.check(R.id.rb_udp); lastUdp = etDns.text.toString() }
            }

            val rtIndex = recordTypes.indexOf(loadedObj.optString("recordType", "TXT").uppercase())
            if (rtIndex >= 0) spRecordType.setSelection(rtIndex)

            etIdleTimeout.setText(loadedObj.optString("idleTimeout", "10s"))
            etKeepAlive.setText(loadedObj.optString("keepAlive", "2s"))
            etClientIdSize.setText(loadedObj.optString("clientIdSize", "2"))
            etMtu.setText(loadedObj.optLong("mtu", if(isExtendedDns) 40L else 0L).toString())
            etMaxMtu.setText(loadedObj.optLong("maxMtu", if(isExtendedDns) 200L else 140L).toString())
            etParallelism.setText(loadedObj.optLong("parallelism", 32L).toString())

            val upComp = loadedObj.optInt("upCompression", 2)
            val downComp = loadedObj.optInt("downCompression", 2)
            spUploadCompression.setSelection(upComp.coerceIn(0, 3))
            spDownloadCompression.setSelection(downComp.coerceIn(0, 3))

            val preset = loadedObj.optString("cottenPreset", "default")
            val pIndex = (0 until presetAdapter.count).firstOrNull { presetAdapter.getItem(it) == preset } ?: 0
            spCottendnsPreset.setSelection(pIndex)

            val useToml = loadedObj.optBoolean("useToml", false)
            swUseToml.isChecked = useToml
            layoutTomlImport.visibility = if (useToml) View.VISIBLE else View.GONE

            tomlFilenameCache = loadedObj.optString("tomlFilename", "")
            if (tomlFilenameCache.isNotEmpty()) {
                tvTomlFilename.text = "Custom file loaded"
            }

            swDnstt.isChecked = loadedObj.optBoolean("dnsttCompatible", false)

            val proxyProto = loadedObj.optString("localProxyProtocol", "socks5")
            rgProxyProtocol.check(if (proxyProto.lowercase() == "http") R.id.rb_proxy_http else R.id.rb_proxy_socks)

            swAuth.isChecked = loadedObj.optBoolean("useAuth", false)
            val authProto = loadedObj.optString("authProtocol", "socks")
            when (authProto.lowercase()) {
                "ssh" -> rgAuthProtocol.check(R.id.rb_auth_ssh)
                "shadowsocks" -> rgAuthProtocol.check(R.id.rb_auth_shadowsocks)
                else -> rgAuthProtocol.check(R.id.rb_auth_socks)
            }

            val methodIndex = ssMethods.indexOf(loadedObj.optString("ssMethod", "chacha20-ietf-poly1305"))
            if (methodIndex >= 0) spSsMethod.setSelection(methodIndex)

            swSshKey.isChecked = loadedObj.optBoolean("useSshKey", false)

            val mdIndex = masterDnsMethods.indexOf(loadedObj.optString("masterDnsMethod", "XOR"))
            if (mdIndex >= 0) spMasterDnsMethod.setSelection(mdIndex)

            val cIndex = arrayOf("BBR", "DCUBIC").indexOf(loadedObj.optString("slipCongestion", "BBR"))
            if (cIndex >= 0) spSlipstreamCongestion.setSelection(cIndex)

            swSlipstreamAuthoritative.isChecked = loadedObj.optBoolean("slipAuth", false)
            swSlipstreamGso.isChecked = loadedObj.optBoolean("slipGso", false)

            etUser.setText(loadedObj.optString("user", ""))
            etPass.setText(loadedObj.optString("pass", ""))

            sshUserCache = loadedObj.optString("sshUser", "")
            sshPassCache = loadedObj.optString("sshPass", "")
            ssPassCache = loadedObj.optString("ssPass", "")
            basicUserCache = loadedObj.optString("basicUser", "")
            basicPassCache = loadedObj.optString("basicPass", "")
            currentAuthMode = authProto

            if (loadedObj.optString("pubkey", "").isNotEmpty() && activeTunnelProtocol == "slipstream") {
                swSlipstreamCert.isChecked = true
            }

        } else {
            toolbar.title = "Add New DNS Config"
            rgMode.check(R.id.rb_udp)
            etDns.setText(lastUdp)
            etIdleTimeout.setText("10s")
            etKeepAlive.setText(if (activeTunnelProtocol == "slipstream") "5s" else "2s")
            etClientIdSize.setText("2")
            swAuth.isChecked = false
            rgProxyProtocol.check(R.id.rb_proxy_socks)
            rgAuthProtocol.check(R.id.rb_auth_socks)
            swSshKey.isChecked = false
            etMtu.setText(if (isExtendedDns) "40" else "0")
            etMaxMtu.setText(if (isExtendedDns) "200" else "140")
            etParallelism.setText(if (isExtendedDns) "32" else "32")
        }

        // Automate DNSTT parameters when toggled
        swDnstt.setOnCheckedChangeListener { _, isChecked ->
            val txtIndex = recordTypes.indexOf("TXT")
            if (txtIndex >= 0) spRecordType.setSelection(txtIndex)

            if (isChecked) {
                etIdleTimeout.setText("2m")
                etKeepAlive.setText("10s")
                etClientIdSize.setText("8")
            } else {
                etIdleTimeout.setText("10s")
                etKeepAlive.setText("2s")
                etClientIdSize.setText("2")
            }
        }

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
                putExtra("CONFIG_ID", editingConfigId)
                putExtra("TUNNEL_MODE", mode)
            }
            startActivity(intent)
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

        swSshKey.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                etUser.setText("User")
                etUser.isEnabled = true
                etPass.isEnabled = true
                tvPassLabel.text = "SSH Private Key:"
                etPass.hint = "Paste Private Key here"
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                etPass.minLines = 6
                etPass.setHorizontallyScrolling(false)
                etPass.gravity = Gravity.TOP
            } else {
                tvUserLabel.text = "User:"
                etUser.hint = "Optional"
                tvPassLabel.text = "Password:"
                etPass.hint = "Optional"
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT
                etPass.minLines = 1
                etPass.gravity = Gravity.CENTER_VERTICAL
            }
        }

        swAuth.setOnCheckedChangeListener { _, isChecked ->
            etUser.isEnabled = isChecked
            etPass.isEnabled = isChecked
            for (i in 0 until rgAuthProtocol.childCount) {
                rgAuthProtocol.getChildAt(i).isEnabled = isChecked
            }
            swSshKey.isEnabled = isChecked && rgAuthProtocol.checkedRadioButtonId == R.id.rb_auth_ssh
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
                    swSshKey.isEnabled = swAuth.isChecked
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
                    tvUserLabel.visibility = View.GONE
                    etUser.visibility = View.GONE
                    tvSsMethodLabel.visibility = View.VISIBLE
                    spSsMethod.visibility = View.VISIBLE
                    etUser.setText("")
                    etPass.setText(ssPassCache)
                }
                else -> {
                    currentAuthMode = "socks"
                    swSshKey.isChecked = false
                    swSshKey.isEnabled = false
                    tvUserLabel.visibility = View.VISIBLE
                    etUser.visibility = View.VISIBLE
                    tvSsMethodLabel.visibility = View.GONE
                    spSsMethod.visibility = View.GONE
                    etUser.setText(basicUserCache)
                    etPass.setText(basicPassCache)
                }
            }
        }

        val btnSaveIcon = findViewById<ImageButton>(R.id.btn_save_icon)
        btnSaveIcon.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Config name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val domain = etDomain.text.toString().trim()
            val pubkey = etPubkey.text.toString().trim()

            if (domain.isEmpty()) {
                Toast.makeText(this, "Tunnel Domain is required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (activeTunnelProtocol == "slipstream" && swSlipstreamCert.isChecked && pubkey.isEmpty()) {
                Toast.makeText(this, "Certificate cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (activeTunnelProtocol == "vaydns") {
                val hexRegex = "^[0-9a-fA-F]{64}$".toRegex()
                if (!hexRegex.matches(pubkey)) {
                    Toast.makeText(this, "Invalid Public Key. VayDNS requires exactly 64 hex characters.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val mtuValue = etMtu.text.toString().toLongOrNull() ?: 0L
            val maxMtu = etMaxMtu.text.toString().toLongOrNull() ?: 140L
            val parallelism = etParallelism.text.toString().toLongOrNull() ?: 32L

            val isSavingExtendedDns = activeTunnelProtocol == "masterdns" || activeTunnelProtocol == "stormdns" || activeTunnelProtocol == "cottendns"

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
            } else if (activeTunnelProtocol == "vaydns") {
                if (mtuValue != 0L && (mtuValue < 40L || mtuValue > 140L)) {
                    Toast.makeText(this, "Invalid Upload MTU: VayDNS requires a value between 40 and 140, or 0 for default.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val dns = etDns.text.toString().trim()
            val mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_udp -> "udp"
                R.id.rb_tcp -> "tcp"
                R.id.rb_tls -> "dot"
                R.id.rb_https -> "doh"
                R.id.rb_auto -> "auto"
                else -> "udp"
            }

            val dns_mode = when(mode) {
                "udp" -> "UDP"
                "tcp" -> "TCP"
                "dot" -> "DoT"
                "doh" -> "DoH"
                "auto" -> "AUTO"
                else -> "UDP"
            }

            val useMultiDomains = switchMultiDomain.isChecked
            val localProxyProtocol = if (rgProxyProtocol.checkedRadioButtonId == R.id.rb_proxy_http) "http" else "socks5"
            val slipCongestion = spSlipstreamCongestion.selectedItem?.toString() ?: "BBR"
            val slipGso = swSlipstreamGso.isChecked

            val manualAddrs = resolverEntries.filter { it.isManual }.map { it.address }
            File(filesDir, "manual_resolvers_$editingConfigId.txt").writeText(manualAddrs.joinToString("\n"))

            val selectedAddrs = resolverEntries
                .filter { it.isChecked && it.address.isNotEmpty() }
                .mapNotNull { sanitizeResolverInput(it.address, mode) }
            File(filesDir, "selected_multipath_$editingConfigId.txt").writeText(selectedAddrs.joinToString("\n"))

            val newObj = JSONObject().apply {
                put("id", editingConfigId)
                put("name", name)
                put("domain", domain)
                put("pubkey", if (activeTunnelProtocol == "slipstream" && !swSlipstreamCert.isChecked) "" else pubkey)
                put("dnsAddress", dns)
                put("mode", mode)
                put("dns_mode", dns_mode)
                put("recordType", spRecordType.selectedItem?.toString() ?: "TXT")
                put("idleTimeout", etIdleTimeout.text.toString().ifEmpty { "10s" })
                put("keepAlive", etKeepAlive.text.toString().ifEmpty { "2s" })
                put("clientIdSize", etClientIdSize.text.toString().toLongOrNull() ?: 2L)
                put("mtu", mtuValue)
                put("maxMtu", maxMtu)
                put("parallelism", parallelism)
                put("upCompression", spUploadCompression.selectedItemPosition)
                put("downCompression", spDownloadCompression.selectedItemPosition)
                put("cottenPreset", spCottendnsPreset.selectedItem?.toString() ?: "default")
                put("useToml", swUseToml.isChecked)
                put("tomlFilename", tomlFilenameCache)
                put("dnsttCompatible", swDnstt.isChecked)
                put("useMultiDomains", switchMultiDomain.isChecked)
                put("tunnelProtocol", activeTunnelProtocol)
                put("localProxyProtocol", localProxyProtocol)
                put("useAuth", swAuth.isChecked)

                val authProto = when (rgAuthProtocol.checkedRadioButtonId) {
                    R.id.rb_auth_ssh -> "ssh"
                    R.id.rb_auth_shadowsocks -> "shadowsocks"
                    else -> "socks"
                }
                put("authProtocol", authProto)
                put("useSshKey", swSshKey.isChecked)
                put("ssMethod", spSsMethod.selectedItem.toString())
                put("masterDnsMethod", spMasterDnsMethod.selectedItem.toString())
                put("user", etUser.text.toString().trim())
                put("pass", etPass.text.toString().trim())
                put("slipCongestion", spSlipstreamCongestion.selectedItem.toString())
                put("slipAuth", swSlipstreamAuthoritative.isChecked)
                put("slipGso", swSlipstreamGso.isChecked)

                put("sshUser", sshUserCache)
                put("sshPass", sshPassCache)
                put("ssPass", ssPassCache)
                put("basicUser", basicUserCache)
                put("basicPass", basicPassCache)
            }

            val sharedPref = getSharedPreferences("PhoenixVpnPrefs", Context.MODE_PRIVATE)
            val configsString = sharedPref.getString("configs", "[]") ?: "[]"
            val jsonArray = JSONArray(configsString)

            var found = false
            for (i in 0 until jsonArray.length()) {
                if (jsonArray.getJSONObject(i).getString("id") == editingConfigId) {
                    newObj.put("orderIndex", jsonArray.getJSONObject(i).optInt("orderIndex", 0))
                    jsonArray.put(i, newObj)
                    found = true
                    break
                }
            }

            val finalArrayToSave = if (!found) {
                // Insert new item at the TOP
                val newArray = JSONArray()
                newObj.put("orderIndex", 0)
                newArray.put(newObj)

                for (i in 0 until jsonArray.length()) {
                    val oldObj = jsonArray.getJSONObject(i)
                    oldObj.put("orderIndex", i + 1)
                    newArray.put(oldObj)
                }

                val tempManual = File(filesDir, "manual_resolvers_new_temp_config.txt")
                if (tempManual.exists()) tempManual.renameTo(File(filesDir, "manual_resolvers_${editingConfigId}.txt"))
                val tempSelected = File(filesDir, "selected_multipath_new_temp_config.txt")
                if (tempSelected.exists()) tempSelected.renameTo(File(filesDir, "selected_multipath_${editingConfigId}.txt"))

                newArray
            } else {
                jsonArray
            }

            sharedPref.edit().putString("configs", finalArrayToSave.toString()).apply()
            finish()
        }

        isInitializing = false
    }

    private fun importTomlFile(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val filename = "config_${editingConfigId}.toml"
                val file = File(filesDir, filename)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }

                var originalName = "imported.toml"
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) originalName = it.getString(nameIndex)
                    }
                }
                findViewById<TextView>(R.id.tv_toml_filename).text = originalName
                tomlFilenameCache = filename
                Toast.makeText(this, "Toml file imported", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to import file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMultipathData(configId: String) {
        resolverEntries.clear()
        val scanFile = File(filesDir, "resolvers_$configId.txt")
        if (scanFile.exists()) {
            scanFile.readLines().forEach { line ->
                val parts = line.split(",")
                val ip = parts[0].trim()
                if (ip.isNotEmpty()) resolverEntries.add(ResolverEntry(ip, isChecked = false, isManual = false, latency = if (parts.size > 1) parts[1].trim() else ""))
            }
        }
        val manualFile = File(filesDir, "manual_resolvers_$configId.txt")
        val savedManuals = if (manualFile.exists()) manualFile.readLines() else emptyList()
        for (i in 0 until 20) {
            resolverEntries.add(ResolverEntry(savedManuals.getOrNull(i) ?: "", isChecked = false, isManual = true, latency = ""))
        }
        val selectedFile = File(filesDir, "selected_multipath_$configId.txt")
        val currentSelections = if (selectedFile.exists()) selectedFile.readLines().toSet() else emptySet()
        resolverEntries.forEach { entry -> if (currentSelections.contains(entry.address)) entry.isChecked = true }
        tvMultipathStatus.text = "${resolverEntries.count { it.isChecked }} IPs selected"
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun isValidIpv4WithOptionalPort(input: String): Boolean {
        if (input.contains(":")) {
            val parts = input.split(":")
            val port = parts[1].toIntOrNull()
            return parts.size == 2 && isValidIpv4(parts[0]) && port != null && port in 1..65535
        }
        return isValidIpv4(input)
    }

    private fun sanitizeResolverInput(input: String, mode: String): String? {
        val parsedLines = input.split(Regex("[\\s,;]+")).map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }
        for (trimmed in parsedLines) {
            when (mode.lowercase()) {
                "doh" -> if (trimmed.startsWith("https://") || isValidIpv4WithOptionalPort(trimmed)) return trimmed
                else -> if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && isValidIpv4WithOptionalPort(trimmed)) return trimmed
            }
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        setupMultipathData(editingConfigId ?: "new_temp_config")
    }
}