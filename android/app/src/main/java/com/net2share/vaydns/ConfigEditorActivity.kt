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
        val rgProtocol = findViewById<RadioGroup>(R.id.rg_protocol)
        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val btnSave = findViewById<Button>(R.id.btn_save_config)
        val swSshKey = findViewById<SwitchCompat>(R.id.sw_ssh_key)
        val spSsMethod = findViewById<Spinner>(R.id.sp_ss_method)
        val tvUserLabel = findViewById<TextView>(R.id.tv_user_label)
        val tvPassLabel = findViewById<TextView>(R.id.tv_pass_label)
        val tvSsMethodLabel = findViewById<TextView>(R.id.tv_ss_method_label)
        val swUseDefaultResolvers = findViewById<SwitchCompat>(R.id.sw_use_default_resolvers)
        swUseDefaultResolvers.visibility = View.GONE

        // MULTIPATH BINDINGS
        tvMultipathStatus = findViewById(R.id.tv_multipath_status)
        btnSelectMultipath = findViewById(R.id.btn_select_multipath)
        layoutMultipathControls = findViewById(R.id.layout_multipath_controls)
        tvMultipathLabel = findViewById(R.id.tv_multipath_label)
        tvMultipathDesc = findViewById(R.id.tv_multipath_desc)

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

        // The clock icon now OPENS THE DIALOG
        btnSelectMultipath.setOnClickListener {
            showMultipathDialog()
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

                etName.setText(savedName)
                etName.isEnabled = true

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
                        //val index = editingConfigId!!.removePrefix("default_").toLongOrNull() ?: 0L

                        // Ask Go for the decrypted comma-separated string of resolvers for this config
                        //val defaultResolversStr = mobile.Mobile.getDefaultConfigResolvers(index)
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
                val user = "********"
                val pass = "********"
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
                //etName.isEnabled = false
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
                etMtu.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tv_mtu_label).visibility = View.VISIBLE

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
                        etMtu, config.mtu,
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
                R.id.rb_tcp -> etDns.setText(lastTcp)
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
                clientIdSize, mtu,dnstt, useAuth, useSshKey, protocol, ssMethod, user, pass
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

    private fun showMultipathDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val checkBoxColor = if (isDarkMode) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#2F4A6F")

        val brandColor = Color.parseColor("#2F4A6F") // VayDNS Brand Color
        val dynamicTextColor = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK
        )

        // 🔴 1. CREATE THE IMPORT BUTTON
        val btnImport = Button(this).apply {
            text = "IMPORT RESOLVERS / وارد کردن آی‌پی"
            setBackgroundColor(brandColor)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
            }

            setOnClickListener {
                val input = EditText(this@ConfigEditorActivity).apply {
                    hint = "Paste IPs (comma, space, or newline separated)..."
                    setLines(5)
                    gravity = android.view.Gravity.TOP
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                MaterialAlertDialogBuilder(this@ConfigEditorActivity)
                    .setTitle("Import Resolvers")
                    .setView(input)
                    .setPositiveButton("Import") { _, _ ->
                        val pasted = input.text.toString()

                        // Parse by spaces, commas, or newlines
                        val parsedLines = pasted.split(Regex("[\\s,;]+"))
                            .map { it.replace("\"", "").trim() }
                            .filter { it.isNotEmpty() }

                        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
                        val currentMode = when (rgMode?.checkedRadioButtonId) {
                            R.id.rb_tcp -> "tcp"; R.id.rb_tls -> "dot"; R.id.rb_https -> "doh"; else -> "udp"
                        }

                        // Sanitize and remove duplicates
                        val validIps = parsedLines.mapNotNull { sanitizeResolverInput(it, currentMode) }.distinct()

                        if (validIps.isNotEmpty()) {
                            // Enforce the 10 IP limit
                            val imported = validIps.take(20)

                            // Overwrite the manual slots in memory
                            var importIndex = 0
                            resolverEntries.filter { it.isManual }.forEach { entry ->
                                if (importIndex < imported.size) {
                                    entry.address = imported[importIndex]
                                    entry.isChecked = false // Reset check state
                                    importIndex++
                                } else {
                                    entry.address = "" // Clear unused rows so old junk isn't left behind
                                    entry.isChecked = false
                                }
                            }

                            // Notice if they pasted more than 10
                            if (validIps.size > 20) {
                                MaterialAlertDialogBuilder(this@ConfigEditorActivity)
                                    .setTitle("Limit Reached / محدودیت وارد کردن")
                                    .setMessage("Found ${validIps.size} valid IPs, but only a maximum of 10 can be imported.\n\nتعداد ${validIps.size} آی‌پی معتبر یافت شد، اما تنها ۱۰ مورد اول وارد شدند.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            } else {
                                Toast.makeText(this@ConfigEditorActivity, "Imported ${imported.size} IPs", Toast.LENGTH_SHORT).show()
                            }

                            // 🔴 REFRESH THE UI
                            multipathDialog?.dismiss()
                            showMultipathDialog()
                        } else {
                            Toast.makeText(this@ConfigEditorActivity, "No valid IPs found for $currentMode mode.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        container.addView(btnImport)

        resolverEntries.forEach { entry ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
            }

            // 1. Initialize EditText (No listener yet)
            val editText = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setText(entry.address)
                hint = if (entry.isManual) "Enter Manual IP..." else ""

                if (!entry.isManual) {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    isCursorVisible = false
                }

                textSize = 14f
                setSingleLine(true)
            }

            // 2. Initialize CheckBox (No listener yet)
            val checkBox = AppCompatCheckBox(this).apply {
                isChecked = entry.isChecked
                buttonTintList = ColorStateList.valueOf(checkBoxColor)
            }

            // 3. Set the EditText Listener (Now it can safely see checkBox)
            editText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val newText = s.toString().trim()
                    if (entry.address != newText) {
                        entry.address = newText

                        // Security: If user edits a verified IP, force uncheck
                        if (editText.hasFocus() && checkBox.isChecked) {
                            checkBox.tag = "ignore"
                            checkBox.isChecked = false
                            entry.isChecked = false
                            checkBox.tag = null
                            updateMultipathStatus()
                        }
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            // 4. Set the CheckBox Listener (Now it can safely see editText)
            checkBox.setOnCheckedChangeListener { buttonView, isChecked ->
                if (buttonView.tag == "ignore") return@setOnCheckedChangeListener

                if (isChecked) {
                    if (entry.address.isBlank()) {
                        buttonView.tag = "ignore"
                        buttonView.isChecked = false
                        buttonView.tag = null
                        Toast.makeText(this@ConfigEditorActivity, "Please enter an IP address first", Toast.LENGTH_SHORT).show()
                        return@setOnCheckedChangeListener
                    }

                    val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
                    val currentMode = when (rgMode?.checkedRadioButtonId) {
                        R.id.rb_tcp -> "tcp"; R.id.rb_tls -> "dot"; R.id.rb_https -> "doh"; else -> "udp"
                    }

                    val sanitized = sanitizeResolverInput(entry.address, currentMode)

                    if (sanitized != null) {
                        if (sanitized != entry.address) {
                            entry.address = sanitized
                            editText.setText(sanitized)
                            editText.setSelection(sanitized.length)
                        }

                        entry.isChecked = true
                        updateMultipathStatus()
                    } else {
                        buttonView.tag = "ignore"
                        buttonView.isChecked = false
                        buttonView.tag = null
                        Toast.makeText(this@ConfigEditorActivity, "Invalid IP for $currentMode mode.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    entry.isChecked = false
                    updateMultipathStatus()
                }
            }

            // 5. Add views to row
            row.addView(checkBox)
            row.addView(editText)

            // 6. Display Latency Label
            if (!entry.isManual && entry.latency.isNotEmpty()) {
                val tvLatency = TextView(this).apply {
                    text = "${entry.latency} ms"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)

                    val latInt = entry.latency.toIntOrNull() ?: 0
                    val textColor = when {
                        latInt <= 2000 -> Color.parseColor("#00C853")
                        latInt <= 6000 -> Color.parseColor("#FFB300")
                        else -> Color.parseColor("#F44336")
                    }
                    setTextColor(textColor)
                    setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
                }
                row.addView(tvLatency)
            }

            container.addView(row)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }

        /*val wrapper = android.widget.FrameLayout(this).apply {
            // Add explicit bottom padding (e.g., 24dp) to push the list away from the buttons
            val bottomPad = (24 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bottomPad)
            addView(scrollView)
        }*/

        // 3. ASSIGN THE DIALOG TO OUR VARIABLE
        fun handleDialogClose(triggerSave: Boolean) {
            val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
            val currentMode = when (rgMode?.checkedRadioButtonId) {
                R.id.rb_tcp -> "tcp"; R.id.rb_tls -> "dot"; R.id.rb_https -> "doh"; else -> "udp"
            }

            var wipedGarbage = false

            // Loop through all 10 manual rows and scrub them
            resolverEntries.filter { it.isManual }.forEach { entry ->
                if (entry.address.isNotBlank()) {
                    val sanitized = sanitizeResolverInput(entry.address, currentMode)

                    if (sanitized != null) {
                        entry.address = sanitized
                    } else {
                        entry.address = ""
                        entry.isChecked = false
                        wipedGarbage = true
                    }
                } else {
                    entry.isChecked = false
                }
            }

            updateMultipathStatus()

            // The isolated saving logic for resolvers
            val saveResolversAction = {
                if (triggerSave) {
                    val configId = intent.getStringExtra("CONFIG_ID") ?: "new_temp_config"

                    // 1. Save Manual Rows safely
                    val manualAddrs = resolverEntries.filter { it.isManual }.map {
                        if (it.address.isBlank()) "" else sanitizeResolverInput(it.address, currentMode) ?: ""
                    }
                    java.io.File(filesDir, "manual_resolvers_$configId.txt").writeText(manualAddrs.joinToString("\n"))

                    // 2. Save Selected Multipath
                    val selectedAddrs = resolverEntries
                        .filter { it.isChecked && it.address.isNotEmpty() }
                        .mapNotNull { sanitizeResolverInput(it.address, currentMode) }

                    java.io.File(filesDir, "selected_multipath_$configId.txt").writeText(selectedAddrs.joinToString("\n"))

                    Toast.makeText(this@ConfigEditorActivity, "Resolvers Saved!", Toast.LENGTH_SHORT).show()
                }
            }

            if (wipedGarbage) {
                MaterialAlertDialogBuilder(this@ConfigEditorActivity)
                    .setTitle("Invalid IPs Cleared / حذف آی‌پی‌های نامعتبر")
                    .setMessage("One or more of the manual IP addresses you entered were incorrectly formatted for $currentMode mode.\n\nThey have been automatically removed to prevent connection errors.\n\n---\n\nیک یا چند آی‌پی دستی که وارد کرده‌اید، فرمت نامعتبری برای حالت $currentMode داشتند.\n\nاین آی‌پی‌ها برای جلوگیری از خطای اتصال، به‌طور خودکار حذف شدند.")
                    .setPositiveButton("OK / تأیید") { _, _ ->
                        saveResolversAction() // Save after they acknowledge the warning
                    }
                    .show()
            } else {
                saveResolversAction() // Save immediately if no garbage was found
            }
        }

        // 4. ASSIGN THE DIALOG TO OUR VARIABLE
        multipathDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Select Multipath Resolvers")
            .setView(scrollView)
            .setPositiveButton("Save Resolvers") { _, _ ->
                handleDialogClose(true) // Primary Action: Scrub, save to txt files, and stay on screen
            }
            .setNegativeButton("Cancel") { _, _ ->
                handleDialogClose(false) // Secondary Action: Scrub and close without saving to txt
            }
            .show()
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
        etMtu.setText(mtuValue.toString())
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
        configId: String,
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
        protocol: String,
        ssMethod: String,
        user: String,
        pass: String
    ) {
        if (editingConfigId?.startsWith("default_") == true) {
            // Save only DNS and Mode to a separate preference file
            val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("${editingConfigId}_name", name)
                putString("${editingConfigId}_dns", dns)
                putString("${editingConfigId}_mode", mode)
                putLong("${editingConfigId}_mtu", mtu)
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
                        idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible, useAuth, useSshKey, protocol, ssMethod, user, pass)
                    break
                }
            }
        } else {
            val newObj = JSONObject()
            newObj.put("id", java.util.UUID.randomUUID().toString())
            populateJsonObject(newObj, name, domain, pubkey, dns, mode, recordType,
                idleTimeout, keepAlive, clientIdSize, mtu, dnsttCompatible, useAuth, useSshKey, protocol, ssMethod, user, pass)
            jsonArray.put(newObj)

            val tempManual = java.io.File(filesDir, "manual_resolvers_new_temp_config.txt")
            if (tempManual.exists()) tempManual.renameTo(java.io.File(filesDir, "manual_resolvers_$configId.txt"))

            val tempSelected = java.io.File(filesDir, "selected_multipath_new_temp_config.txt")
            if (tempSelected.exists()) tempSelected.renameTo(java.io.File(filesDir, "selected_multipath_$configId.txt"))
        }

        sharedPref.edit().putString("configs", jsonArray.toString()).apply()

//        Toast.makeText(this, "Config saved!", Toast.LENGTH_SHORT).show()
    }

    private fun populateJsonObject(
        obj: JSONObject, name: String, domain: String, pubkey: String, dns: String, mode: String,
        recordType: String, idleTimeout: String, keepAlive: String,
        clientIdSize: Long, mtu: Long, dnsttCompatible: Boolean, useAuth: Boolean, useSshKey: Boolean, protocol: String, ssMethod: String, user: String, pass: String
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
        obj.put("mtu", mtu)
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
                        mtu = obj.optLong("mtu", 0L),
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
                        put("mtu", config.mtu)
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