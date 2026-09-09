package net.vaydns.phoenix

import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WarpSettingsActivity : AppCompatActivity() {

    private lateinit var rgProtocolMode: RadioGroup
    private lateinit var rbProtoWireguard: RadioButton
    private lateinit var rbProtoQuic: RadioButton

    // New WARP Mode UI Elements
    private lateinit var tvWarpModeLabel: TextView
    private lateinit var rgWarpMode: RadioGroup
    private lateinit var rbWarpStandard: RadioButton
    private lateinit var rbWarpPlus: RadioButton

    private lateinit var etPublicKey: EditText
    private lateinit var etPrivateKey: EditText
    private lateinit var etEndpoint: EditText
    private lateinit var etCustomIp: EditText
    private lateinit var spPort: Spinner
    private lateinit var cbAllPorts: MaterialCheckBox
    private lateinit var swRandomEndpoint: SwitchCompat

    // MASQUE UI Elements
    private lateinit var llMasqueAdvanced: LinearLayout
    private lateinit var etSni: EditText
    private lateinit var cbHttp2: MaterialCheckBox

    private lateinit var etIpv4: EditText
    private lateinit var etIpv6: EditText
    private lateinit var tvReservedBytes: TextView
    private lateinit var rgEndpointMode: RadioGroup
    private lateinit var rbUseEndpoint: RadioButton
    private lateinit var rbUseIp: RadioButton
    private lateinit var btnGetKeys: Button
    private lateinit var btnGetWarpPlusKeys: Button
    private lateinit var btnGetBestIp: Button
    private lateinit var pbLoading: ProgressBar

    private var currentReservedBytes: String = "[0, 0, 0]"
    private var previousSelectionWasQuic: Boolean = false

    private var actualCustomWarpIp: String = ""

    private val wgPorts = arrayOf(2408, 500, 4500, 1701)
    private val masquePorts = arrayOf(443, 500, 1701, 4443, 4500, 8443, 8095)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warp_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setNavigationOnClickListener {
            attemptExit()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptExit()
            }
        })

        // Bind UI Elements
        tvWarpModeLabel = findViewById(R.id.tv_warp_mode_label)
        rgWarpMode = findViewById(R.id.rg_warp_mode)
        rbWarpStandard = findViewById(R.id.rb_warp_standard)
        rbWarpPlus = findViewById(R.id.rb_warp_plus)

        btnGetWarpPlusKeys = findViewById(R.id.btn_get_warp2_keys)
        rgProtocolMode = findViewById(R.id.rg_protocol_mode)
        rbProtoWireguard = findViewById(R.id.rb_proto_wireguard)
        rbProtoQuic = findViewById(R.id.rb_proto_quic)

        etPublicKey = findViewById(R.id.et_public_key)
        etPrivateKey = findViewById(R.id.et_private_key)
        etEndpoint = findViewById(R.id.et_endpoint)
        etCustomIp = findViewById(R.id.et_custom_ip)
        spPort = findViewById(R.id.sp_port)
        cbAllPorts = findViewById(R.id.cb_use_all_ports)
        swRandomEndpoint = findViewById(R.id.sw_random_endpoint)

        llMasqueAdvanced = findViewById(R.id.ll_masque_advanced)
        etSni = findViewById(R.id.et_sni)
        cbHttp2 = findViewById(R.id.cb_http2)

        etIpv4 = findViewById(R.id.et_ipv4)
        etIpv6 = findViewById(R.id.et_ipv6)
        tvReservedBytes = findViewById(R.id.tv_reserved_bytes)
        rgEndpointMode = findViewById(R.id.rg_endpoint_mode)
        rbUseEndpoint = findViewById(R.id.rb_use_endpoint)
        rbUseIp = findViewById(R.id.rb_use_ip)
        btnGetKeys = findViewById(R.id.btn_get_warp_keys)
        btnGetBestIp = findViewById(R.id.btn_get_best_ip)
        pbLoading = findViewById(R.id.pb_loading)

        // Make Key fields read-only visually (Managed directly by JSON files)
        etPublicKey.isEnabled = false
        etPrivateKey.isEnabled = false
        etIpv4.isEnabled = false
        etIpv6.isEnabled = false
        etCustomIp.isEnabled = false

        updateInputStates(rbUseIp.isChecked)
        rbUseEndpoint.setOnCheckedChangeListener { _, isChecked -> if (isChecked) updateInputStates(false) }
        rbUseIp.setOnCheckedChangeListener { _, isChecked -> if (isChecked) updateInputStates(true) }

        val tunnelPrefs = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE)
        val savedEngine = tunnelPrefs.getString("warp_engine", "wireguard") ?: "wireguard"
        previousSelectionWasQuic = savedEngine == "masque"

        if (previousSelectionWasQuic) {
            rbProtoQuic.isChecked = true
            loadQuicProfile()
        } else {
            rbProtoWireguard.isChecked = true
            loadWireguardProfile()
        }

        rgProtocolMode.setOnCheckedChangeListener { _, checkedId ->
            val isNowQuic = checkedId == R.id.rb_proto_quic
            if (previousSelectionWasQuic != isNowQuic) {
                saveCurrentState(previousSelectionWasQuic)
                previousSelectionWasQuic = isNowQuic
                if (isNowQuic) loadQuicProfile() else loadWireguardProfile()
            }
        }

        // Dynamically Swap UI Data when toggling WARP modes
        rgWarpMode.setOnCheckedChangeListener { _, checkedId ->
            val isWarpPlus = checkedId == R.id.rb_warp_plus
            if (isWarpPlus) {
                btnGetKeys.visibility = View.GONE
                btnGetWarpPlusKeys.visibility = View.VISIBLE
            } else {
                btnGetKeys.visibility = View.VISIBLE
                btnGetWarpPlusKeys.visibility = View.GONE
            }
            displayWarpKeys(isWarpPlus)
        }

        btnGetWarpPlusKeys.setOnClickListener {
            generateWireguardKeys2()
        }

        btnGetKeys.setOnClickListener {
            if (rbProtoQuic.isChecked) generateQuicKeys() else generateWireguardKeys()
        }

        btnGetBestIp.setOnClickListener {
            fetchBestWarpIp()
        }

        findViewById<Button>(R.id.btn_warp_ip_manager).setOnClickListener {
            val intent = Intent(this, WarpIpManagerActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setSpinnerAdapter(ports: Array<Int>, savedPort: String) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ports)
        spPort.adapter = adapter
        val portInt = savedPort.toIntOrNull() ?: ports[0]
        val position = ports.indexOf(portInt)
        spPort.setSelection(if (position >= 0) position else 0)
    }

    private fun updateInputStates(useIpMode: Boolean) {
        etCustomIp.isEnabled = false
        etEndpoint.isEnabled = !useIpMode
        spPort.isEnabled = true

        // Ensure Random Endpoint Switch hides and turns OFF when Custom IP is selected
        if (useIpMode) {
            swRandomEndpoint.visibility = View.GONE
            swRandomEndpoint.isChecked = false
        } else {
            swRandomEndpoint.visibility = View.VISIBLE
        }

        // Dynamically show/hide MASQUE vs WARP settings
        if (::rbProtoQuic.isInitialized && rbProtoQuic.isChecked) {
            llMasqueAdvanced.visibility = View.VISIBLE
            tvWarpModeLabel.visibility = View.GONE
            rgWarpMode.visibility = View.GONE
            btnGetWarpPlusKeys.visibility = View.GONE
            btnGetKeys.visibility = View.VISIBLE // Masque uses btnGetKeys
        } else {
            llMasqueAdvanced.visibility = View.GONE
            tvWarpModeLabel.visibility = View.VISIBLE
            rgWarpMode.visibility = View.VISIBLE

            // Sync buttons with the radio group
            val isWarpPlus = ::rbWarpPlus.isInitialized && rbWarpPlus.isChecked
            btnGetWarpPlusKeys.visibility = if (isWarpPlus) View.VISIBLE else View.GONE
            btnGetKeys.visibility = if (isWarpPlus) View.GONE else View.VISIBLE
        }
    }

    private fun setButtonState(hasKeys: Boolean, protocolName: String) {
        if (hasKeys) {
            btnGetKeys.isEnabled = false
            btnGetKeys.text = "$protocolName Keys Provisioned"
            btnGetKeys.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
        } else {
            btnGetKeys.isEnabled = true
            btnGetKeys.text = "Get $protocolName Keys"
            btnGetKeys.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
        }
    }

    private fun setWarp2ButtonState(hasKeys: Boolean) {
        if (hasKeys) {
            btnGetWarpPlusKeys.isEnabled = false
            btnGetWarpPlusKeys.text = "WARP+ Keys Provisioned"
            btnGetWarpPlusKeys.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
        } else {
            btnGetWarpPlusKeys.isEnabled = true
            btnGetWarpPlusKeys.text = "Get WARP+ Keys"
            btnGetWarpPlusKeys.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
        }
    }

    // Safely parse JSON and display the correct keys based on the active RadioButton
    private fun displayWarpKeys(isWarpPlus: Boolean) {
        var pubKey = ""
        var privKey = ""
        var ipv4 = "172.16.0.2/32"
        var ipv6 = ""
        var reserved = "[0, 0, 0]"

        val fileName = if (isWarpPlus) "warp2_keys.json" else "warp_keys.json"
        val file = File(filesDir, fileName)

        if (file.exists()) {
            try {
                val decrypted = mobile.Mobile.decryptText(file.readText())
                val json = JSONObject(decrypted)
                privKey = json.optString("private_key", "")
                pubKey = json.optString("public_key", json.optString("server_public_key", ""))
                ipv4 = json.optString("ipv4", "172.16.0.2/32")
                ipv6 = json.optString("ipv6", "")

                val reservedArr = json.optJSONArray("reserved")
                if (reservedArr != null) {
                    reserved = reservedArr.toString()
                } else if (json.has("reserved_bytes")) {
                    reserved = json.optString("reserved_bytes", "[0, 0, 0]")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        etPublicKey.setText(pubKey)
        etPrivateKey.setText(privKey)
        etIpv4.setText(ipv4)
        etIpv6.setText(ipv6)
        tvReservedBytes.text = "Reserved Bytes: $reserved"

        if (isWarpPlus) {
            setWarp2ButtonState(pubKey.isNotBlank() && privKey.isNotBlank())
        } else {
            setButtonState(pubKey.isNotBlank() && privKey.isNotBlank(), "WARP")
        }
    }

    private fun loadWireguardProfile() {
        tvReservedBytes.visibility = View.VISIBLE
        btnGetBestIp.visibility = View.VISIBLE
        rbUseEndpoint.isEnabled = true
        rbUseIp.isEnabled = true

        val prefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("connection_mode", "endpoint")
        val isWarpPlus = prefs.getBoolean("warp_plus", false)
        swRandomEndpoint.isChecked = prefs.getBoolean("random_endpoint", false)

        if (isWarpPlus) {
            rbWarpPlus.isChecked = true
            btnGetKeys.visibility = View.GONE
            btnGetWarpPlusKeys.visibility = View.VISIBLE
        } else {
            rbWarpStandard.isChecked = true
            btnGetKeys.visibility = View.VISIBLE
            btnGetWarpPlusKeys.visibility = View.GONE
        }

        if (mode == "ip") {
            rbUseIp.isChecked = true
            updateInputStates(true)
        } else {
            rbUseEndpoint.isChecked = true
            updateInputStates(false)
        }

        etEndpoint.setText(prefs.getString("endpoint", "engage.cloudflareclient.com"))

        val plainCustomIp = prefs.getString("custom_ip", "") ?: ""
        actualCustomWarpIp = plainCustomIp
        if (plainCustomIp.isNotEmpty()) {
            etCustomIp.setText(mobile.Mobile.encryptIP(plainCustomIp))
        } else {
            etCustomIp.setText("")
        }

        setSpinnerAdapter(wgPorts, prefs.getString("port", "2408") ?: "2408")

        // Load the keys dynamically from the files based on the current selection
        displayWarpKeys(isWarpPlus)
    }

    private fun loadQuicProfile() {
        tvReservedBytes.visibility = View.GONE
        btnGetBestIp.visibility = View.VISIBLE
        rbUseEndpoint.isEnabled = true
        rbUseIp.isEnabled = true

        val prefs = getSharedPreferences("UsqueProfilePrefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("connection_mode", "endpoint")
        swRandomEndpoint.isChecked = prefs.getBoolean("random_endpoint", false)

        if (mode == "ip") {
            rbUseIp.isChecked = true
            updateInputStates(true)
        } else {
            rbUseEndpoint.isChecked = true
            updateInputStates(false)
        }

        var pubKey = prefs.getString("public_key", "") ?: ""
        var privKey = prefs.getString("private_key", "") ?: ""
        var endpoint = prefs.getString("endpoint", "") ?: ""
        var port = prefs.getString("port", "443") ?: "443"
        var ipv4 = prefs.getString("ipv4", "172.16.0.2")
        var ipv6 = prefs.getString("ipv6", "") ?: ""

        // Load MASQUE Advanced settings
        var sni = prefs.getString("sni", "speed.cloudflare.com") ?: "speed.cloudflare.com"
        var useHttp2 = prefs.getBoolean("use_http2", false)

        if (pubKey.isEmpty() && privKey.isEmpty()) {
            val file = File(filesDir, "usque_config.json")
            if (file.exists()) {
                try {
                    val json = JSONObject(file.readText())
                    privKey = json.optString("private_key", "")
                    pubKey = json.optString("endpoint_pub_key", "")
                    port = json.optString("port", "443")
                    ipv4 = json.optString("ipv4", "172.16.0.2")
                    ipv6 = json.optString("ipv6", "")
                    endpoint = json.optString("endpoint_v4", "")
                } catch (e: Exception) {}
            }
        }

        etPublicKey.setText(pubKey)
        etPrivateKey.setText(privKey)

        etEndpoint.setText(endpoint)
        etSni.setText(sni)
        cbHttp2.isChecked = useHttp2

        val plainCustomIp = prefs.getString("custom_ip", "") ?: ""
        actualCustomWarpIp = plainCustomIp
        if (plainCustomIp.isNotEmpty()) {
            etCustomIp.setText(mobile.Mobile.encryptIP(plainCustomIp))
        } else {
            etCustomIp.setText("")
        }

        etIpv4.setText(ipv4)
        etIpv6.setText(ipv6)

        setSpinnerAdapter(masquePorts, port)

        setButtonState(pubKey.isNotBlank() && privKey.isNotBlank(), "MASQUE")
    }

    private fun generateWireguardKeys() {
        btnGetKeys.isEnabled = false
        pbLoading.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resultJson = mobile.Mobile.generateWarpProfile(filesDir.absolutePath)
                val json = JSONObject(resultJson)

                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    if (json.optString("status") == "success") {
                        val endpointRaw = json.optString("endpoint", "engage.cloudflareclient.com")
                        val endpointDomainOrIp = endpointRaw.substringBefore(":")
                        val generatedPort = if (endpointRaw.contains(":")) endpointRaw.substringAfter(":") else "2408"

                        etEndpoint.setText(endpointDomainOrIp)
                        setSpinnerAdapter(wgPorts, generatedPort)

                        // Refresh UI with the newly generated keys
                        displayWarpKeys(false)
                        Toast.makeText(this@WarpSettingsActivity, "WireGuard Keys generated!", Toast.LENGTH_SHORT).show()
                    } else {
                        setButtonState(false, "WireGuard")
                        showErrorDialog("WireGuard Registration Error", json.optString("message", "Failed"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    setButtonState(false, "WireGuard")
                }
            }
        }
    }

    private fun generateWireguardKeys2() {
        btnGetWarpPlusKeys.isEnabled = false
        pbLoading.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Create a temp directory to isolate the Go generator
                val tempDir = File(filesDir, "warp2_temp")
                if (!tempDir.exists()) tempDir.mkdirs()

                // 2. Generate keys into the isolated folder
                val resultJson = mobile.Mobile.generateWarpProfile(tempDir.absolutePath)
                val json = JSONObject(resultJson)

                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    if (json.optString("status") == "success") {

                        // 3. Move the successfully generated file to the main directory as warp2_keys.json
                        val generatedFile = File(tempDir, "warp_keys.json")
                        val finalWarp2File = File(filesDir, "warp2_keys.json")

                        if (generatedFile.exists()) {
                            generatedFile.copyTo(finalWarp2File, overwrite = true)
                            generatedFile.delete() // Cleanup
                        }

                        // Refresh UI with the newly generated secondary keys
                        displayWarpKeys(true)
                        Toast.makeText(this@WarpSettingsActivity, "WARP+ Keys successfully generated and isolated!", Toast.LENGTH_SHORT).show()
                    } else {
                        setWarp2ButtonState(false)
                        showErrorDialog("WARP+ Error", json.optString("message", "Failed to provision keys."))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    setWarp2ButtonState(false)
                }
            }
        }
    }

    private fun generateQuicKeys() {
        btnGetKeys.isEnabled = false
        pbLoading.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resultJson = mobile.Mobile.registerUsque(filesDir.absolutePath)
                val json = JSONObject(resultJson)

                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    if (json.has("private_key")) {
                        etPrivateKey.setText(json.optString("private_key"))
                        etPublicKey.setText(json.optString("endpoint_pub_key"))

                        val pinnedIp = json.optString("endpoint_v4", "")
                        etEndpoint.setText(pinnedIp)
                        setSpinnerAdapter(masquePorts, "443")

                        etIpv4.setText(json.optString("ipv4", "172.16.0.2"))
                        etIpv6.setText(json.optString("ipv6", ""))

                        setButtonState(true, "MASQUE")
                        Toast.makeText(this@WarpSettingsActivity, "MASQUE enrolled successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        setButtonState(false, "MASQUE")
                        showErrorDialog("MASQUE Enrollment Error", json.optString("message", "Failed"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pbLoading.visibility = View.GONE
                    setButtonState(false, "MASQUE")
                }
            }
        }
    }

    private fun showErrorDialog(title: String, msg: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun fetchBestWarpIp() {
        val isQuic = rbProtoQuic.isChecked
        val targetPort = spPort.selectedItem?.toString()?.toLongOrNull() ?: if (isQuic) 443L else 2408L
        val useAllPorts = cbAllPorts.isChecked
        val activeProtocolName = if (isQuic) "MASQUE" else "WARP"

        btnGetBestIp.isEnabled = false
        btnGetBestIp.text = "Scanning..."
        btnGetBestIp.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val vaultPrefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
                val jsonString = vaultPrefs.getString("vault_ips_json", "[]") ?: "[]"
                val ipList = mutableListOf<String>()

                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optString("cdn", "WARP").equals(activeProtocolName, ignoreCase = true)) {
                        val encryptedVaultIp = obj.optString("ip", "")
                        if (encryptedVaultIp.isNotEmpty()) {
                            ipList.add(CryptoHelper.decrypt(encryptedVaultIp))
                        }
                    }
                }

                if (ipList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@WarpSettingsActivity, "No $activeProtocolName IPs found in vault.", Toast.LENGTH_LONG).show()
                        resetBestIpButton()
                    }
                    return@launch
                }

                val ipsCSV = ipList.joinToString(",")
                var bestIpRaw = ""

                if (isQuic) {
                    bestIpRaw = mobile.Mobile.getBestMasqueIp(filesDir.absolutePath, ipsCSV, targetPort, useAllPorts)
                } else {
                    val wgPrefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)

                    // Always test using the primary keys for the scanner
                    val file = File(filesDir, "warp_keys.json")
                    var wgPrivKey = ""
                    var wgPubKey = ""
                    var wgReserved = "[0, 0, 0]"

                    if (file.exists()) {
                        try {
                            val decrypted = mobile.Mobile.decryptText(file.readText())
                            val json = JSONObject(decrypted)
                            wgPrivKey = json.optString("private_key", "")
                            wgPubKey = json.optString("public_key", json.optString("server_public_key", ""))
                            val resArr = json.optJSONArray("reserved")
                            if (resArr != null) {
                                wgReserved = resArr.toString()
                            }
                        } catch (e: Exception) {}
                    }

                    if (wgPrivKey.isEmpty() || wgPubKey.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@WarpSettingsActivity, "Generate WireGuard keys first.", Toast.LENGTH_LONG).show()
                            resetBestIpButton()
                        }
                        return@launch
                    }

                    var r1 = 0L; var r2 = 0L; var r3 = 0L
                    try {
                        val cleanArr = wgReserved.replace("[", "").replace("]", "").split(",")
                        if (cleanArr.size >= 3) {
                            r1 = cleanArr[0].trim().toLong(); r2 = cleanArr[1].trim().toLong(); r3 = cleanArr[2].trim().toLong()
                        }
                    } catch (e: Exception) {}

                    val engineType = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE).getString("tun_engine", "xray") ?: "xray"
                    bestIpRaw = mobile.Mobile.getBestWarpIp(64, ipsCSV, targetPort, useAllPorts, wgPrivKey, wgPubKey, r1, r2, r3, engineType)
                }

                withContext(Dispatchers.Main) {
                    if (bestIpRaw.isNotEmpty()) {
                        val actualIp = if (bestIpRaw.contains(":")) bestIpRaw.substringBefore(":") else bestIpRaw
                        val actualPort = if (bestIpRaw.contains(":")) bestIpRaw.substringAfter(":") else targetPort.toString()

                        val portsArray = if (isQuic) masquePorts else wgPorts
                        val portPosition = portsArray.indexOf(actualPort.toIntOrNull() ?: targetPort.toInt())
                        if (portPosition >= 0) {
                            spPort.setSelection(portPosition)
                        }

                        actualCustomWarpIp = actualIp
                        etCustomIp.setText(mobile.Mobile.encryptIP(actualIp))
                        rbUseIp.isChecked = true

                        Toast.makeText(this@WarpSettingsActivity, "Best Endpoint Set Successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@WarpSettingsActivity, "No responsive endpoints found.", Toast.LENGTH_LONG).show()
                    }
                    resetBestIpButton()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { resetBestIpButton() }
            }
        }
    }

    private fun resetBestIpButton() {
        btnGetBestIp.isEnabled = true
        btnGetBestIp.text = "Get Best IP"
        btnGetBestIp.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2F4A6F"))
    }

    private fun safeAtomicWrite(targetFile: File, content: String) {
        val tempFile = File(targetFile.parent, targetFile.name + ".tmp")
        try {
            tempFile.writeText(content)
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.renameTo(targetFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun saveCurrentState(isQuic: Boolean) {
        val mode = if (rbUseIp.isChecked) "ip" else "endpoint"

        val activeEndpointDomain = etEndpoint.text.toString().trim()
        val activeCustomIp = actualCustomWarpIp
        val activePort = spPort.selectedItem?.toString() ?: if (isQuic) "443" else "2408"
        val activeTarget = if (mode == "ip" && activeCustomIp.isNotEmpty()) activeCustomIp else activeEndpointDomain

        if (isQuic) {
            val sniVal = etSni.text.toString().trim()
            val useHttp2Val = cbHttp2.isChecked

            // Masque retains storing keys in preferences
            val pubKey = etPublicKey.text.toString().trim()
            val privKey = etPrivateKey.text.toString().trim()
            val ipv4 = etIpv4.text.toString().trim()
            val ipv6 = etIpv6.text.toString().trim()

            getSharedPreferences("UsqueProfilePrefs", Context.MODE_PRIVATE).edit()
                .putString("connection_mode", mode)
                .putBoolean("random_endpoint", swRandomEndpoint.isChecked)
                .putString("endpoint", activeEndpointDomain)
                .putString("custom_ip", activeCustomIp)
                .putString("port", activePort)
                .putString("private_key", privKey)
                .putString("public_key", pubKey)
                .putString("ipv4", ipv4)
                .putString("ipv6", ipv6)
                .putString("sni", sniVal)
                .putBoolean("use_http2", useHttp2Val)
                .apply()

            try {
                val usqueFile = File(filesDir, "usque_config.json")
                if (usqueFile.exists()) {
                    val usqueJson = JSONObject(usqueFile.readText())
                    usqueJson.put("port", activePort)
                    usqueJson.put("endpoint_v4", activeTarget)
                    usqueJson.remove("endpoint_v6")
                    safeAtomicWrite(usqueFile, usqueJson.toString())
                }
            } catch (e: Exception) {}

        } else {
            // WARP updates the shared preferences but does NOT write the UI keys back to the file.
            getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE).edit()
                .putString("connection_mode", mode)
                .putBoolean("random_endpoint", swRandomEndpoint.isChecked)
                .putString("endpoint", activeEndpointDomain)
                .putString("custom_ip", activeCustomIp)
                .putString("port", activePort)
                .putBoolean("warp_plus", rbWarpPlus.isChecked)
                .apply()

            // Synchronize the Endpoint and Port to both JSON files
            val updateWarpFile = { fileName: String ->
                try {
                    val keyFile = File(filesDir, fileName)
                    if (keyFile.exists()) {
                        val decrypted = mobile.Mobile.decryptText(keyFile.readText())
                        val warpJson = JSONObject(decrypted)
                        warpJson.put("endpoint", activeTarget)
                        warpJson.put("port", activePort)
                        val encryptedOutput = mobile.Mobile.encryptText(warpJson.toString())
                        safeAtomicWrite(keyFile, encryptedOutput)
                    }
                } catch (e: Exception) {}
            }

            updateWarpFile("warp_keys.json")
            updateWarpFile("warp2_keys.json")
        }
    }

    private fun saveAndFinish() {
        val isQuic = rbProtoQuic.isChecked
        getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE).edit()
            .putString("warp_engine", if (isQuic) "masque" else "wireguard").apply()

        saveCurrentState(isQuic)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    // Checks conditions before allowing the user to leave the screen
    private fun attemptExit() {
        if (rgWarpMode.visibility == View.VISIBLE && rbWarpPlus.isChecked) {
            val key2File = File(filesDir, "warp2_keys.json")
            if (!key2File.exists()) {
                showErrorDialog(
                    "WARP+ Keys Missing",
                    "You have enabled WARP+ (Dual Layer) but have not generated the secondary keys. Please tap 'Get WARP+ Keys' or select 'Standard WARP' to proceed.\n\n" +
                            "شما WARP+ را فعال کرده‌اید اما کلیدهای ثانویه را تولید نکرده‌اید. لطفاً برای ادامه روی گزینه «دریافت کلیدهای WARP+» ضربه بزنید یا حالت استاندارد را انتخاب کنید."
                )
                return // Stops the exit, keeps them on the screen
            }
        }
        saveAndFinish()
    }
}