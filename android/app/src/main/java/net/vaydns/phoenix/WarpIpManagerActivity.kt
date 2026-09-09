package net.vaydns.phoenix

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.widget.ProgressBar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WarpIpManagerActivity : AppCompatActivity() {

    // Internal Data Class tailored for WARP/MASQUE Vault format
    data class WarpIpEntry(var address: String, var port: Int, var isChecked: Boolean, var latencyMs: Int = -1, var protocol: String = "WARP")

    private val ipEntries = mutableListOf<WarpIpEntry>()
    private lateinit var adapter: WarpIpAdapter
    private var isCheckAllActive = true

    private var targetProtocol = "WARP"
    private var targetPort = 2408
    private var displayAllPorts = false

    private val hiddenOtherIps = mutableListOf<JSONObject>()

    private val wgPorts = arrayOf("2408", "500", "4500", "1701")
    private val masquePorts = arrayOf("443", "500", "1701", "4443", "4500", "8443", "8095")
    private val portList = mutableListOf<String>()

    private lateinit var spinnerPort: Spinner
    private lateinit var cbAllPorts: MaterialCheckBox

    // UI state to prevent recursive firing
    private var isRevertingPort = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warp_ip_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_warp_manager)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
                val totalValidIps = ipEntries.count { it.address.isNotBlank() }

                if (validCheckedCount > 1) {
                    MaterialAlertDialogBuilder(this@WarpIpManagerActivity)
                        .setTitle("Multiple IPs Selected")
                        .setMessage("While all IPs will be stored in the vault, you must check exactly ONE IP to act as your active connection before exiting.\n\nدر حالی که تمامی آی‌پی‌ها در برنامه ذخیره خواهند شد، لطفاً قبل از خروج، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Discard / لغو") { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .show()
                    return
                }

                if (validCheckedCount == 0 && totalValidIps > 0) {
                    MaterialAlertDialogBuilder(this@WarpIpManagerActivity)
                        .setTitle("No IP Selected")
                        .setMessage("Please check exactly ONE IP to act as your active connection before exiting.\n\nلطفاً قبل از خروج، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                        .setPositiveButton("OK", null)
                        .setNegativeButton("Discard / لغو") { _, _ ->
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                        .show()
                    return
                }

                saveIpsAndSyncToVPN()
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val recycler = findViewById<RecyclerView>(R.id.recycler_warp_ips)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = WarpIpAdapter(ipEntries) { }
        recycler.adapter = adapter

        val rgProtocol = findViewById<RadioGroup>(R.id.rg_manager_protocol)
        val rbWireguard = findViewById<RadioButton>(R.id.rb_manager_wireguard)
        val rbMasque = findViewById<RadioButton>(R.id.rb_manager_masque)
        spinnerPort = findViewById(R.id.spinner_manager_port)
        cbAllPorts = findViewById(R.id.cb_manager_all_ports)

        // Read active engine to pre-select correct radio
        val activeEngine = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE).getString("warp_engine", "wireguard")
        if (activeEngine == "masque") {
            rbMasque.isChecked = true
            targetProtocol = "MASQUE"
            targetPort = 443
        } else {
            rbWireguard.isChecked = true
            targetProtocol = "WARP"
            targetPort = 2408
        }

        updatePortSpinner()

        rgProtocol.setOnCheckedChangeListener { _, checkedId ->
            saveIpsAndSyncToVPN()
            targetProtocol = if (checkedId == R.id.rb_manager_masque) "MASQUE" else "WARP"
            updatePortSpinner()
        }

        spinnerPort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (isRevertingPort) {
                    isRevertingPort = false
                    return
                }
                val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
                if (validCheckedCount > 1) {
                    Toast.makeText(this@WarpIpManagerActivity, "Cannot switch Ports: Multiple IPs checked.", Toast.LENGTH_SHORT).show()
                    isRevertingPort = true
                    spinnerPort.setSelection(portList.indexOf(targetPort.toString()).coerceAtLeast(0))
                    return
                }
                saveIpsAndSyncToVPN()
                targetPort = portList[position].toIntOrNull() ?: if (targetProtocol == "MASQUE") 443 else 2408
                loadSavedIps()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        cbAllPorts.setOnCheckedChangeListener { _, isChecked ->
            displayAllPorts = isChecked
            spinnerPort.isEnabled = !isChecked
            loadSavedIps()
        }

        // Toolbar Action Icons

        findViewById<ImageButton>(R.id.btn_import_warp_ips).setOnClickListener {
            showImportDialog()
        }

        findViewById<ImageButton>(R.id.btn_toggle_all_warp).setOnClickListener {
            ipEntries.forEach { it.isChecked = isCheckAllActive }
            isCheckAllActive = !isCheckAllActive
            val actionText = if (isCheckAllActive) "Unchecked All" else "Checked All"
            Toast.makeText(this, actionText, Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
        }

        findViewById<ImageButton>(R.id.btn_delete_warp).setOnClickListener {
            ipEntries.removeAll { it.isChecked }
            if (ipEntries.isEmpty()) {
                ipEntries.add(WarpIpEntry("", targetPort, false, -1, targetProtocol))
            }
            adapter.notifyDataSetChanged()
        }

        // Help Icon Listener
        findViewById<ImageButton>(R.id.btn_warp_help).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Help")
                .setMessage(
                    "The imported IPs will be mapped to different IPs using App encryption algorithm for display purpose. The actual IP remain as the original in vault.\n\n" +
                            "آی‌پی‌های وارد شده برای نمایش  با استفاده از الگوریتم رمزگذاری برنامه به آی‌پی‌های دیگری تبدیل می‌شوند. آی‌پی واقعی به صورت اصلی در حافظه باقی می‌ماند."
                )
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<ImageButton>(R.id.btn_save_warp).setOnClickListener {
            val validCheckedCount = ipEntries.count { it.isChecked && it.address.isNotBlank() }
            val totalValidIps = ipEntries.count { it.address.isNotBlank() }

            // SHIELD 1: Prevent saving if multiple IPs are active
            if (validCheckedCount > 1) {
                MaterialAlertDialogBuilder(this@WarpIpManagerActivity)
                    .setTitle("Multiple IPs Selected")
                    .setMessage("While all IPs will be stored in the vault, you must check exactly ONE IP to act as your active connection before saving.\n\nدر حالی که تمامی آی‌پی‌ها در برنامه ذخیره خواهند شد، لطفاً قبل از ذخیره، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // SHIELD 2: Prevent saving if zero IPs are active (and list isn't empty)
            if (validCheckedCount == 0 && totalValidIps > 0) {
                MaterialAlertDialogBuilder(this@WarpIpManagerActivity)
                    .setTitle("No IP Selected")
                    .setMessage("Please check exactly ONE IP to act as your active connection before saving.\n\nلطفاً قبل از ذخیره، دقیقاً یک آی‌پی را به عنوان اتصال فعال خود انتخاب کنید.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // If it passes both checks, save the Vault and synchronize the single active IP
            saveIpsAndSyncToVPN()
            Toast.makeText(this, "Vault Saved & Synced!", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton>(R.id.btn_ping_warp).setOnClickListener {
            val checkedEntries = ipEntries.filter { it.isChecked && it.address.isNotBlank() }

            if (checkedEntries.isEmpty()) {
                Toast.makeText(this, "Please check the IPs you want to ping.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // --- START VISUAL LOADING STATE ---
            val progressSpinner = findViewById<ProgressBar>(R.id.progress_ping_warp)
            val btnSort = findViewById<ImageButton>(R.id.btn_warp_sort)
            val btnHelp = findViewById<ImageButton>(R.id.btn_warp_help)

            it.isEnabled = false
            btnSort.isEnabled = false // Prevent sorting mid-scan
            btnHelp.isEnabled = false // Prevent dialogs mid-scan
            it.visibility = View.INVISIBLE
            progressSpinner.visibility = View.VISIBLE

            Toast.makeText(this, "Pinging ${checkedEntries.size} IPs...", Toast.LENGTH_SHORT).show()

            // Reset latencies to "-1" so the user knows which ones drop/fail during this new test
            checkedEntries.forEach { entry -> entry.latencyMs = -1 }
            adapter.notifyDataSetChanged()

            val engineType = getSharedPreferences("TunnelSettingsPrefs", Context.MODE_PRIVATE).getString("warp_engine", "xray") ?: "xray"
            val keys = getWarpKeysForScanner()

            // Run heavy scanning in the background
            lifecycleScope.launch(Dispatchers.IO) {
                // Combine the Target IP and Port for the CSV (e.g. "1.1.1.1:2408")
                val ipsCSV = checkedEntries.joinToString(",") { entry ->
                    val realIp = mobile.Mobile.decryptIP(entry.address)
                    "$realIp:${entry.port}"
                }
                var resultJson = "[]"

                if (targetProtocol == "MASQUE") {
                    resultJson = mobile.Mobile.pingMasqueIps(
                        ipsCSV,
                        1000L, // Handshake Timeout
                        2500L  // Max Timeout
                    )
                } else {
                    resultJson = mobile.Mobile.runWarpScanner(
                        checkedEntries.size.toLong(),
                        ipsCSV,
                        targetPort.toLong(),
                        displayAllPorts,
                        keys["privKey"] as String,
                        keys["pubKey"] as String,
                        keys["r1"] as Long,
                        keys["r2"] as Long,
                        keys["r3"] as Long,
                        engineType,
                        5L // Workers
                    )
                }

                // Parse the JSON and update the RecyclerView on the Main Thread
                withContext(Dispatchers.Main) {
                    // --- END VISUAL LOADING STATE ---
                    it.isEnabled = true
                    btnSort.isEnabled = true
                    btnHelp.isEnabled = true
                    it.visibility = View.VISIBLE
                    progressSpinner.visibility = View.GONE

                    try {
                        val jsonArr = org.json.JSONArray(resultJson)
                        var successCount = 0

                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.getJSONObject(i)
                            val returnedRealIp = obj.getString("ip")
                            val port = obj.getInt("port")
                            val latency = obj.getInt("latency_ms")

                            // Encrypt the returned Real IP so it matches the Fake IP in our UI list
                            val mappedIp = mobile.Mobile.encryptIP(returnedRealIp)

                            val entry = ipEntries.find { e -> e.address == mappedIp && e.port == port }
                            if (entry != null) {
                                entry.latencyMs = latency
                                successCount++
                            }
                        }
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this@WarpIpManagerActivity, "Ping complete! $successCount successful.", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@WarpIpManagerActivity, "Ping failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<ImageButton>(R.id.btn_export_warp).setOnClickListener {
            // Grab ONLY the checked IPs
            val checkedIps = ipEntries.filter { it.address.isNotBlank() && it.isChecked }

            if (checkedIps.isEmpty()) {
                Toast.makeText(this, "Please check the IPs you want to export.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val validIpsText = checkedIps.joinToString("\n") { entry ->
                // The entry already holds the Fake/Mapped IP
                val combinedString = "${entry.address}:${entry.port}"

                android.util.Base64.encodeToString(
                    combinedString.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
            }

            Toast.makeText(this, "${checkedIps.size} IPs are to be exported.", Toast.LENGTH_SHORT).show()

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Saved $targetProtocol IPs")
                putExtra(Intent.EXTRA_TEXT, validIpsText)
            }
            startActivity(Intent.createChooser(shareIntent, "Share IPs via"))
        }

        // Sort Icon Listener
        findViewById<ImageButton>(R.id.btn_warp_sort).setOnClickListener {
            val checkedCount = ipEntries.count { it.isChecked }
            if (checkedCount == 0) {
                Toast.makeText(this, "Please check the IPs you want to sort.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Sort Logic:
            // 1st Priority: Checked items move to the top
            // 2nd Priority: Valid Latencies (lowest ms first). Failed (-1) or untested pushed to the bottom of the checked block.
            ipEntries.sortWith(compareByDescending<WarpIpEntry> { it.isChecked }
                .thenBy { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE })

            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Sorted $checkedCount IPs by latency", Toast.LENGTH_SHORT).show()
        }

    }

    private fun getWarpKeysForScanner(): Map<String, Any> {
        var privKey = ""
        var pubKey = ""
        var r1 = 0L; var r2 = 0L; var r3 = 0L
        val file = File(filesDir, "warp_keys.json")
        if (file.exists()) {
            try {
                val decrypted = mobile.Mobile.decryptText(file.readText())
                val json = JSONObject(decrypted)
                privKey = json.optString("private_key", "")
                pubKey = json.optString("public_key", json.optString("server_public_key", ""))

                val resArr = json.optJSONArray("reserved")
                if (resArr != null && resArr.length() >= 3) {
                    r1 = resArr.getLong(0)
                    r2 = resArr.getLong(1)
                    r3 = resArr.getLong(2)
                } else if (json.has("reserved_bytes")) {
                    val cleanArr = json.optString("reserved_bytes", "[0, 0, 0]").replace("[", "").replace("]", "").split(",")
                    if (cleanArr.size >= 3) {
                        r1 = cleanArr[0].trim().toLongOrNull() ?: 0L
                        r2 = cleanArr[1].trim().toLongOrNull() ?: 0L
                        r3 = cleanArr[2].trim().toLongOrNull() ?: 0L
                    }
                }
            } catch (e: Exception) {}
        }
        return mapOf("privKey" to privKey, "pubKey" to pubKey, "r1" to r1, "r2" to r2, "r3" to r3)
    }

    private fun updatePortSpinner() {
        portList.clear()
        if (targetProtocol == "MASQUE") {
            portList.addAll(masquePorts)
            if (targetPort !in masquePorts.map { it.toInt() }) targetPort = 443
        } else {
            portList.addAll(wgPorts)
            if (targetPort !in wgPorts.map { it.toInt() }) targetPort = 2408
        }

        val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, portList)
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        isRevertingPort = true
        spinnerPort.adapter = portAdapter

        var newIndex = portList.indexOf(targetPort.toString())
        if (newIndex == -1) newIndex = 0

        targetPort = portList[newIndex].toInt()
        isRevertingPort = true
        spinnerPort.setSelection(newIndex)

        loadSavedIps()
    }

    private fun loadSavedIps() {
        ipEntries.clear()
        hiddenOtherIps.clear()

        val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("vault_ips_json", "[]") ?: "[]"

        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val rawIp = obj.getString("ip")

                // 1. Get the REAL IP from the secure Vault
                val realIp = CryptoHelper.decrypt(rawIp)

                val isChecked = obj.getBoolean("isChecked")
                val latency = obj.optInt("latency", -1)
                val cdn = obj.optString("cdn", "WARP")
                val port = obj.optInt("port", if (cdn == "MASQUE") 443 else 2408)

                if (cdn.equals(targetProtocol, ignoreCase = true)) {
                    if (displayAllPorts || port == targetPort) {
                        if (realIp.isNotBlank()) {
                            // 2. IMMEDIATELY map it to a Fake IP for UI memory
                            val fakeIp = mobile.Mobile.encryptIP(realIp)
                            ipEntries.add(WarpIpEntry(fakeIp, port, isChecked, latency, cdn))
                        }
                    } else {
                        hiddenOtherIps.add(obj)
                    }
                } else {
                    hiddenOtherIps.add(obj)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (ipEntries.isNotEmpty() && ipEntries.none { it.isChecked }) {
            ipEntries.first().isChecked = true
        }

        if (ipEntries.isEmpty()) {
            ipEntries.add(WarpIpEntry("", targetPort, true, -1, targetProtocol))
        }

        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun saveIpsAndSyncToVPN() {
        val prefs = getSharedPreferences("CloudflareVault", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()

        for (hiddenObj in hiddenOtherIps) {
            jsonArray.put(hiddenObj)
        }

        var activeRealIp: String? = null
        var activePort: Int = -1

        for (entry in ipEntries) {
            if (entry.address.isNotBlank()) {
                // 1. Decrypt the Fake UI IP back to the Real IP
                val realIp = mobile.Mobile.decryptIP(entry.address)

                val obj = JSONObject()
                // 2. Encrypt the REAL IP for secure Vault storage
                obj.put("ip", CryptoHelper.encrypt(realIp))
                obj.put("isChecked", entry.isChecked)
                obj.put("latency", entry.latencyMs)
                obj.put("cdn", entry.protocol)
                obj.put("port", entry.port)
                jsonArray.put(obj)

                // Capture the Real IP if it is the active connection
                if (entry.isChecked) {
                    activeRealIp = realIp
                    activePort = entry.port
                }
            }
        }

        // Save to Vault
        prefs.edit().putString("vault_ips_json", jsonArray.toString()).apply()

        // SYNCHRONIZE ACTIVE IP TO JSON CONFIGS FOR GO BACKEND
        if (activeRealIp != null) {
            if (targetProtocol == "MASQUE") {
                val uPrefs = getSharedPreferences("UsqueProfilePrefs", Context.MODE_PRIVATE)
                uPrefs.edit()
                    .putString("custom_ip", activeRealIp)
                    .putString("port", activePort.toString())
                    .putString("connection_mode", "ip")
                    .apply()

                try {
                    val uFile = File(filesDir, "usque_config.json")
                    if (uFile.exists()) {
                        val json = JSONObject(uFile.readText())
                        json.put("endpoint_v4", activeRealIp)
                        json.put("port", activePort)
                        json.remove("endpoint_v6")
                        safeAtomicWrite(uFile, json.toString())
                    }
                } catch (e: Exception) {}
            } else {
                val wPrefs = getSharedPreferences("WarpProfilePrefs", Context.MODE_PRIVATE)
                wPrefs.edit()
                    .putString("custom_ip", activeRealIp)
                    .putString("port", activePort.toString())
                    .putString("connection_mode", "ip")
                    .apply()

                val updateWarpFile = { fileName: String ->
                    try {
                        val keyFile = File(filesDir, fileName)
                        if (keyFile.exists()) {
                            val decrypted = mobile.Mobile.decryptText(keyFile.readText())
                            val json = JSONObject(decrypted)
                            json.put("endpoint", activeRealIp) // Save REAL IP to Go config
                            json.put("port", activePort)
                            safeAtomicWrite(keyFile, mobile.Mobile.encryptText(json.toString()))
                        }
                    } catch (e: Exception) {}
                }

                updateWarpFile("warp_keys.json")
                updateWarpFile("warp2_keys.json")
            }
        }
    }

    private fun safeAtomicWrite(targetFile: File, content: String) {
        val tempFile = File(targetFile.parent, targetFile.name + ".tmp")
        try {
            tempFile.writeText(content)
            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.renameTo(targetFile)
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Paste IPs (e.g. 162.159.192.1:2408 or Base64)...\nآی‌پی‌ها را اینجا جای‌گذاری کنید..."
            setLines(5)
            setPadding(45, 45, 45, 45)
            gravity = android.view.Gravity.TOP
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Import $targetProtocol IPs")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val parsed = input.text.toString()
                    .split(Regex("[\\s,;]+"))
                    .map { it.replace("\"", "").trim() }
                    .filter { it.isNotEmpty() }

                var imported = 0
                if (ipEntries.size == 1 && ipEntries[0].address.isBlank()) ipEntries.clear()

                for (token in parsed) {
                    var finalIp = ""
                    var port = targetPort

                    // SMART ROUTING: BASE64 vs PLAINTEXT
                    // Base64 strings of "MappedIP:Port" will NEVER contain a dot (.) or colon (:) or bracket ([)
                    if (!token.contains(".") && !token.contains(":") && !token.contains("[")) {
                        // It is a Base64 Encoded "MappedIP:Port" string
                        try {
                            // A. Decode Base64 back into "MappedIP:Port"
                            val decodedBytes = android.util.Base64.decode(token, android.util.Base64.DEFAULT)
                            val mappedIpPortString = String(decodedBytes, Charsets.UTF_8)

                            if (mappedIpPortString.contains(":")) {
                                val parts = mappedIpPortString.split(":")
                                finalIp = parts[0] // Store the Fake IP directly
                                val extractedPort = parts[1].toIntOrNull()
                                if (extractedPort != null) port = extractedPort
                            }
                        } catch (e: Exception) {
                            // Decoding failed or input was garbage, skip it
                        }
                    } else {
                        // It is a user-provided Plain IP
                        var rawIpPart = token

                        // EXTRACT PORT (Safely handles IPv4:Port and [IPv6]:Port)
                        if (token.contains(":")) {
                            val lastColonIndex = token.lastIndexOf(":")
                            val portCandidate = token.substring(lastColonIndex + 1)

                            if (portCandidate.toIntOrNull() != null) {
                                val basePart = token.substring(0, lastColonIndex)
                                if (basePart.contains(".")) {
                                    rawIpPart = basePart
                                    port = portCandidate.toInt()
                                } else if (token.startsWith("[")) {
                                    rawIpPart = basePart.removePrefix("[").removeSuffix("]")
                                    port = portCandidate.toInt()
                                } else {
                                    rawIpPart = token // Plain IPv6 address with no port
                                }
                            } else if (token.startsWith("[")) {
                                rawIpPart = token.removePrefix("[").removeSuffix("]")
                            }
                        }

                        // Sanity check the Plain Output
                        if (isValidIp(rawIpPart)) {
                            finalIp = mobile.Mobile.encryptIP(rawIpPart)
                        }
                    }

                    // SAVE TO LIST (If valid and unique)
                    val cleanFinalIp = finalIp.trim()
                    if (cleanFinalIp.isNotEmpty() && ipEntries.none { it.address == cleanFinalIp && it.port == port }) {
                        ipEntries.add(WarpIpEntry(cleanFinalIp, port, false, -1, targetProtocol))
                        imported++
                    }
                }

                adapter.notifyDataSetChanged()
                Toast.makeText(this, "Imported $imported valid IPs", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    // =========================================================================
    // EMBEDDED ADAPTER - Reuses item_cdn_ip.xml layout for simplicity
    // =========================================================================
    inner class WarpIpAdapter(
        private val entries: List<WarpIpEntry>,
        private val onStatusChanged: () -> Unit
    ) : RecyclerView.Adapter<WarpIpAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkBox: CheckBox = view.findViewById(R.id.cb_cf_ip)
            val editText: EditText = view.findViewById(R.id.et_cf_ip_address)
            val tvLatency: TextView = view.findViewById(R.id.tv_cf_latency)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cdn_ip, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]

            // Format Display as EncryptedIP:Port (entry.address is ALREADY the Fake IP)
            val displayStr = if (entry.address.isNotBlank()) {
                "${entry.address}:${entry.port}"
            } else ""

            holder.editText.setText(displayStr)
            holder.editText.isEnabled = false
            holder.editText.isFocusable = false
            holder.editText.isFocusableInTouchMode = false
            holder.editText.setBackgroundColor(Color.TRANSPARENT)

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = entry.isChecked

            if (entry.latencyMs > 0) {
                holder.tvLatency.text = "${entry.latencyMs} ms"
                when {
                    entry.latencyMs < 500 -> holder.tvLatency.setTextColor(Color.parseColor("#4CAF50"))
                    entry.latencyMs < 1500 -> holder.tvLatency.setTextColor(Color.parseColor("#FF9800"))
                    else -> holder.tvLatency.setTextColor(Color.parseColor("#F44336"))
                }
            } else {
                holder.tvLatency.text = "---"
                holder.tvLatency.setTextColor(Color.GRAY)
            }

            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                entry.isChecked = isChecked
                onStatusChanged()
            }
        }

        override fun getItemCount() = entries.size
    }
}