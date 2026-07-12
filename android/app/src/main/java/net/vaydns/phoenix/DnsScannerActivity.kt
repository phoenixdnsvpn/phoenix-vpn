package net.vaydns.phoenix

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.random.Random
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import mobile.Mobile
import android.util.Log
import android.os.Handler
import android.os.Looper
class DnsScannerActivity : AppCompatActivity() {

    private lateinit var switchPublicDns: Switch
    private lateinit var etDomain: EditText
    private lateinit var rgProxy: RadioGroup
    private lateinit var switchConservative: Switch
    private lateinit var tvResolversCount: TextView
    private lateinit var etNumResolvers: EditText
    private lateinit var switchRandom: Switch
    private lateinit var etWorkers: EditText
    private lateinit var etTunnelWait: EditText
    private lateinit var etUdpTimeout: EditText
    private lateinit var etProbeTimeout: EditText
    private lateinit var etRetries: EditText
    private lateinit var btnStartScan: Button

    private var selectedIdleTimeout = "10s"
    private var selectedKeepAlive = "2s"
    private var selectedClientIdSize = 2L
    private var selectedDomain = ""
    private var selectedPubkey = ""
    private var selectedRecordType = "TXT"
    private var selectedMode = "udp"
    private var selectedDnsAddress = "udp"
    private var configId = ""
    private var selectedMtu = 0L
    private var selectedProtocol = "socks"
    private var selectedSsMethod = "chacha20-ietf-poly1305"
    private var selectedUseAuth = false
    private var selectedUser = "none"
    private var selectedPass = "none"
    private var resolversList: List<String> = emptyList()
    private var isDefaultConfig = false
    private var domainIndex = 0
    private lateinit var tvE2eModeLabel: TextView
    private lateinit var rgE2eMode: RadioGroup
    private lateinit var tvQuickScanModeLabel: TextView
    private lateinit var rgQuickScanMode: RadioGroup
    private lateinit var switchCidrMode: Switch
    private lateinit var layoutCidrSelection: LinearLayout
    private lateinit var tvSelectedCidr: TextView
    private lateinit var btnSelectCidr: ImageButton
    private var loadedCidrs: List<String> = emptyList()
    private var selectedCidrs = mutableSetOf<String>()
    private lateinit var switchSkipQuickCheck: Switch
    private lateinit var rgTunnelMode: RadioGroup
    private var lightE2EEnabled = true
    private var isQuickScanner = false
    private lateinit var rgResolverSource: RadioGroup
    private lateinit var rbSourceDefault: RadioButton
    private lateinit var rbSourceCustom: RadioButton
    private lateinit var rbSourcePaste: RadioButton
    private lateinit var rbSourceEncrypted: RadioButton
    private lateinit var btnActionResolvers: Button
    private var hasEncryptedResolvers = false
    // Keeps memory records for the Copy & Paste text strings
    private var rawPastedResolversText: String = ""

    // Launcher contract to handle processing the results returned from the new window
    private val pasteResolversLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            rawPastedResolversText = data.getStringExtra("RESULT_RAW_TEXT") ?: ""
            val returnedList = data.getStringArrayListExtra("RESULT_RESOLVERS_LIST") ?: arrayListOf()

            if (switchCidrMode.isChecked) {
                loadedCidrs = returnedList.filter { isValidCidr(it) }
                tvResolversCount.text = "Loaded pasted CIDR blocks: ${loadedCidrs.size}"
                selectedCidrs.clear()
                tvSelectedCidr.text = "Tap right icon to select CIDR ->"
            } else {
                resolversList = returnedList
                tvResolversCount.text = "Loaded pasted resolvers: ${resolversList.size}"
                val currentThreshold = etNumResolvers.text.toString().toIntOrNull() ?: 5000
                if (returnedList.size < currentThreshold) {
                    etNumResolvers.setText(returnedList.size.toString())
                }
            }
        }
    }
    private val customResolverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadCustomResolversFromUri(uri)
        } else {
            Toast.makeText(this, "No file selected.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_scanner)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_scanner)
        toolbar.setNavigationOnClickListener {
            finish()
        }
        // Get data from selected config
        selectedDnsAddress = intent.getStringExtra("DNS_ADDRESS") ?: ""
        isDefaultConfig = intent.getBooleanExtra("IS_DEFAULT", false)
        selectedDomain = intent.getStringExtra("DOMAIN") ?: ""
        selectedPubkey = intent.getStringExtra("PUBKEY") ?: ""
        configId = intent.getStringExtra("CONFIG_ID") ?: ""
        domainIndex = intent.getIntExtra("DOMAIN_INDEX", 0)
        selectedRecordType = intent.getStringExtra("RECORD_TYPE") ?: "TXT"
        selectedIdleTimeout = intent.getStringExtra("IDLE_TIMEOUT") ?: "10s"
        selectedKeepAlive = intent.getStringExtra("KEEP_ALIVE") ?: "2s"
        selectedClientIdSize = intent.getLongExtra("CLIENT_ID_SIZE", 2L)
        selectedMode = intent.getStringExtra("MODE") ?: "udp"
        selectedMtu = intent.getLongExtra("MTU", 0L)
        selectedProtocol = intent.getStringExtra("PROTOCOL") ?: "socks"
        selectedSsMethod = intent.getStringExtra("SS_METHOD") ?: "chacha20-ietf-poly1305"
        selectedUseAuth = intent.getBooleanExtra("USE_AUTH", false)
        selectedUser = intent.getStringExtra("USER") ?: "none"
        selectedPass = intent.getStringExtra("PASS") ?: "none"
        isQuickScanner = intent.getBooleanExtra("IS_QUICK_SCANNER", false)

        initViews()

        rgTunnelMode = findViewById(R.id.rg_tunnel_mode)
        val tvTunnelModeLabel = findViewById<TextView>(R.id.tv_tunnel_mode_label)

        rgTunnelMode.visibility = android.view.View.VISIBLE
        tvTunnelModeLabel?.visibility = android.view.View.VISIBLE

        // 2. Pre-select the tunnel mode based on the intent broadcast
        when (selectedMode.lowercase()) {
            "tcp" -> rgTunnelMode.check(R.id.rb_mode_tcp)
            "dot" -> rgTunnelMode.check(R.id.rb_mode_dot)
            "doh" -> rgTunnelMode.check(R.id.rb_mode_doh)
            else -> rgTunnelMode.check(R.id.rb_mode_udp)
        }
        // Toggle visibility based on Scanner Mode
        if (isQuickScanner) {
            //rgTunnelMode?.visibility = android.view.View.VISIBLE
            //tvTunnelModeLabel?.visibility = android.view.View.VISIBLE
            etDomain?.isEnabled = false

            // HIDE Conservative Scan (Irrelevant for Light E2E / Quick Scan)
            switchConservative.visibility = android.view.View.GONE
            switchConservative.isChecked = false

            // HIDE E2E Mode selection and force Light E2E for Quick Scanner
            tvE2eModeLabel.visibility = android.view.View.GONE
            rgE2eMode.visibility = android.view.View.GONE
            rgE2eMode.check(R.id.rb_light_e2e)

            // SHOW Quick Scanner Mode selection
            tvQuickScanModeLabel.visibility = android.view.View.VISIBLE
            rgQuickScanMode.visibility = android.view.View.VISIBLE

            // HIDE UNUSED QUICK SCAN PARAMETERS
            findViewById<TextView>(R.id.tv_tunnel_wait_label)?.visibility = android.view.View.GONE
            etTunnelWait.visibility = android.view.View.GONE

            findViewById<TextView>(R.id.tv_probe_timeout_label)?.visibility = android.view.View.GONE
            etProbeTimeout.visibility = android.view.View.GONE

            findViewById<TextView>(R.id.tv_retries_label)?.visibility = android.view.View.GONE
            etRetries.visibility = android.view.View.GONE

        } else {
            //rgTunnelMode?.visibility = android.view.View.GONE
            //tvTunnelModeLabel?.visibility = android.view.View.GONE

            //  SHOW Conservative Scan for True E2E
            switchConservative.visibility = android.view.View.VISIBLE
            // SHOW E2E Mode selection for standard DNS Scanner
            tvE2eModeLabel.visibility = android.view.View.VISIBLE
            rgE2eMode.visibility = android.view.View.VISIBLE

            // HIDE Quick Scanner Mode selection
            tvQuickScanModeLabel.visibility = android.view.View.GONE
            rgQuickScanMode.visibility = android.view.View.GONE

            // Ensure they are visible for normal Full E2E Scans
            findViewById<TextView>(R.id.tv_tunnel_wait_label)?.visibility = android.view.View.VISIBLE
            etTunnelWait.visibility = android.view.View.VISIBLE

            findViewById<TextView>(R.id.tv_probe_timeout_label)?.visibility = android.view.View.VISIBLE
            etProbeTimeout.visibility = android.view.View.VISIBLE

            findViewById<TextView>(R.id.tv_retries_label)?.visibility = android.view.View.VISIBLE
            etRetries.visibility = android.view.View.VISIBLE
        }

        toolbar.title = "DNS Resolver Scanner"
//        toolbar.subtitle = if (selectedDomain.contains("-")) "Protected Config" else selectedDomain

        if (selectedDomain.isEmpty() || selectedPubkey.isEmpty()) {
            Toast.makeText(this, "No config selected. Please go back and select a config.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        supportActionBar?.title = "DNS Resolver Scanner"
//        supportActionBar?.subtitle = selectedDomain

        etWorkers.setText(getWorkerCountForMode())

        setupListeners()

        etWorkers.setText(getWorkerCountForMode())

        /**if (isQuickScanner) {
            etWorkers.setText("40")
        } else if (!switchConservative.isChecked) {
            etWorkers.setText(if (rgE2eMode.checkedRadioButtonId == R.id.rb_light_e2e) "40" else "20")
        }*/

        // Set initial view components state to match Custom Resolvers default
        btnActionResolvers.text = "IMPORT RESOLVERS"
        btnActionResolvers.isEnabled = true
        tvResolversCount.text = "Loaded resolvers: 0"

        showBatteryPermissionDialogIfNecessary()

    }

    private fun initViews() {
        switchPublicDns = findViewById(R.id.switch_public_dns)
        etDomain = findViewById(R.id.et_domain)
        rgProxy = findViewById(R.id.rg_proxy)
        switchConservative = findViewById(R.id.switch_conservative)
        tvResolversCount = findViewById(R.id.tv_resolvers_count)
        etNumResolvers = findViewById(R.id.et_num_resolvers)
        switchRandom = findViewById(R.id.switch_random)
        etWorkers = findViewById(R.id.et_workers)
        etTunnelWait = findViewById(R.id.et_tunnel_wait)
        etUdpTimeout = findViewById(R.id.et_udp_timeout)
        etProbeTimeout = findViewById(R.id.et_probe_timeout)
        etRetries = findViewById(R.id.et_retries)
        btnStartScan = findViewById(R.id.btn_start_scan)
        switchCidrMode = findViewById(R.id.switch_cidr_mode)
        layoutCidrSelection = findViewById(R.id.layout_cidr_selection)
        tvSelectedCidr = findViewById(R.id.tv_selected_cidr)
        btnSelectCidr = findViewById(R.id.btn_select_cidr)
        tvE2eModeLabel = findViewById(R.id.tv_e2e_mode_label)
        rgE2eMode = findViewById(R.id.rg_e2e_mode)
        tvQuickScanModeLabel = findViewById(R.id.tv_quick_scan_mode_label)
        rgQuickScanMode = findViewById(R.id.rg_quick_scan_mode)
        rgResolverSource = findViewById(R.id.rg_resolver_source)
        rbSourceDefault = findViewById(R.id.rb_source_default)
        rbSourceCustom = findViewById(R.id.rb_source_custom)
        rbSourcePaste = findViewById(R.id.rb_source_paste)
        rbSourceEncrypted = findViewById(R.id.rb_source_encrypted)
        btnActionResolvers = findViewById(R.id.btn_action_resolvers)

        // CHECK FOR ENCRYPTED RESOLVERS
        hasEncryptedResolvers = false
        if (isDefaultConfig) {
            val count = Mobile.getDefaultConfigCount()
            for (i in 0 until count) {
                if (Mobile.getDefaultConfigDisplayResolvers(i.toLong()).isNotEmpty()) {
                    hasEncryptedResolvers = true
                    break
                }
            }
        }

        // Disable the radio button if none exist
        if (!hasEncryptedResolvers) {
            rbSourceEncrypted.isEnabled = false
        }

        // Set domain from config
        etDomain = findViewById(R.id.et_domain)
        if (isDefaultConfig) {
            // HIDE the real domain from the User Interface
            etDomain.setText("----------")
            etDomain.isEnabled = false

        } else {
            // Show real domain for custom user configs
            etDomain.setText(selectedDomain)
        }

        rbSourceDefault.visibility = android.view.View.VISIBLE
        // SCANNER SOURCES CLEANUP
        val configCount = mobile.Mobile.getDefaultConfigCount()
        if (configCount == 0L) {
            rbSourceEncrypted.visibility = android.view.View.GONE
            // Optional: If the user was previously on Encrypted, revert to Default
            if (rgResolverSource.checkedRadioButtonId == R.id.rb_source_encrypted) {
                rgResolverSource.check(R.id.rb_source_default)
            }
        } else {
            rbSourceEncrypted.visibility = android.view.View.VISIBLE
        }
    }

    private fun showBatteryPermissionDialogIfNecessary() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        if (!isIgnoring) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Background Activity Required / نیاز به فعالیت در پس‌زمینه")
                .setMessage("To prevent the scan from stopping, you must enable one setting manually:\n\n" +
                        "1. Tap 'Open Settings' below.\n" +
                        "2. Tap on 'Battery' (or Battery usage).\n" +
                        "3. Enable 'Allow background activity'.\n\n" +
                        "--------------------------------------------------\n\n" +
                        "برای جلوگیری از توقف اسکن، باید یک تنظیم را به صورت دستی فعال کنید:\n\n" +
                        "۱. روی «Open Settings» در پایین بزنید.\n" +
                        "۲. روی گزینه «Battery» (باتری) یا «Battery usage» بزنید.\n" +
                        "۳. گزینه «Allow background activity» (اجازه فعالیت در پس‌زمینه) را فعال کنید.")
                .setPositiveButton("Open Settings / باز کردن تنظیمات") { dialog, _ ->
                    dialog.dismiss()

                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            // This takes the user to the App Info page (Uninstall/Force Stop)
                            // which is the only way to reach the Battery sub-menu on Realme.
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            startActivity(intent)
                        }
                    }, 400)
                }
                .setNegativeButton("Proceed Anyway / ادامه بدون تغییر", null)
                .show()
        }
    }

    private fun getWorkerCountForMode(): String {
        // 2. Otherwise, determine based on protocol
        val selectedModeLower = when (rgTunnelMode.checkedRadioButtonId) {
            R.id.rb_mode_tcp -> "tcp"
            R.id.rb_mode_dot -> "dot"
            R.id.rb_mode_doh -> "doh"
            else -> "udp"
        }
        if (switchConservative.isChecked) {
            return if (selectedModeLower == "udp") "10" else "20"
        }

        return if (selectedModeLower == "udp") "20" else "40"
    }

    private fun setupListeners() {

        btnStartScan.setOnClickListener {
            // Log to Logcat so you can see if the button is even working
            Log.d("VAY_DEBUG", "Start Scan Button Tapped")

            if (isVpnActive()) {
                AlertDialog.Builder(this)
                    .setTitle("VPN Active Detected")
                    .setMessage("Please disconnect your VPN first.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {

                startScan()
            }
        }

        //  DYNAMIC RESOLVER SOURCE LOGIC
        rgResolverSource.setOnCheckedChangeListener { _, checkedId ->
            // First, unlock UI in case it was locked by Encrypted mode
            switchPublicDns.isEnabled = true
            switchCidrMode.isEnabled = true
            etNumResolvers.isEnabled = true
            etNumResolvers.alpha = 1.0f

            when (checkedId) {
                R.id.rb_source_default -> {
                    btnActionResolvers.text = "DEFAULT RESOLVERS"
                    btnActionResolvers.isEnabled = false
                    if (switchCidrMode.isChecked) loadDefaultCidrs() else loadDefaultResolvers()
                }
                R.id.rb_source_custom -> {
                    btnActionResolvers.text = "IMPORT RESOLVERS"
                    btnActionResolvers.isEnabled = true
                }
                R.id.rb_source_paste -> {
                    btnActionResolvers.text = "PASTE RESOLVERS"
                    btnActionResolvers.isEnabled = true
                }
                R.id.rb_source_encrypted -> {
                    btnActionResolvers.text = "ENCRYPTED RESOLVERS"
                    btnActionResolvers.isEnabled = false
                    loadEncryptedResolvers()
                }
            }
        }

        btnActionResolvers.setOnClickListener {
            when (rgResolverSource.checkedRadioButtonId) {
                R.id.rb_source_custom -> chooseCustomResolvers()
                R.id.rb_source_paste -> {
                    //  Redirect straight to our high-performance full window Activity
                    val intent = Intent(this, PasteResolversActivity::class.java).apply {
                        putExtra("TUNNEL_MODE", selectedMode)
                        putExtra("IS_CIDR_MODE", switchCidrMode.isChecked)
                        putExtra("CURRENT_TEXT", rawPastedResolversText)
                    }
                    pasteResolversLauncher.launch(intent)
                }
            }
        }

        //  UPDATE CIDR MODE SWITCH
        switchCidrMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                layoutCidrSelection.visibility = android.view.View.VISIBLE
                rbSourceEncrypted.isEnabled = false // Disable encrypted in CIDR mode

                if (rgResolverSource.checkedRadioButtonId == R.id.rb_source_encrypted) {
                    rgResolverSource.check(R.id.rb_source_default)
                }
                if (rgResolverSource.checkedRadioButtonId == R.id.rb_source_default) {
                    loadDefaultCidrs()
                }
            } else {
                layoutCidrSelection.visibility = android.view.View.GONE
                rbSourceEncrypted.isEnabled = hasEncryptedResolvers // Re-enable if applicable

                if (rgResolverSource.checkedRadioButtonId == R.id.rb_source_default) {
                    loadDefaultResolvers()
                }
            }
        }

        rgE2eMode.setOnCheckedChangeListener { _, _ ->
            etWorkers.setText(getWorkerCountForMode())
        }

        btnSelectCidr.setOnClickListener { showCidrSelectionDialog() }

        // Update workers and wait when conservative mode changes
        switchConservative.setOnCheckedChangeListener { _, _ ->
            etWorkers.setText(getWorkerCountForMode())

            // Update other static fields if needed
            if (switchConservative.isChecked) {
                etTunnelWait.setText("5000")
                etUdpTimeout.setText("2000")
                etProbeTimeout.setText("8")
                etRetries.setText("2")
            } else {
                etTunnelWait.setText("3000")
                etUdpTimeout.setText("1000")
                etProbeTimeout.setText("15")
                etRetries.setText("1")
            }
        }

        rgTunnelMode.setOnCheckedChangeListener { _, _ ->
            etWorkers.setText(getWorkerCountForMode())
        }
    }

    private fun loadEncryptedResolvers() {
        val count = Mobile.getDefaultConfigCount()
        val allResolvers = mutableSetOf<String>()

        for (i in 0 until count) {
            val defaultResolversStr = Mobile.getDefaultConfigDisplayResolvers(i.toLong())
            if (defaultResolversStr.isNotEmpty()) {
                allResolvers.addAll(defaultResolversStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }

        if (allResolvers.isEmpty()) {
            Toast.makeText(this, "No encrypted resolvers found.", Toast.LENGTH_LONG).show()
            rgResolverSource.check(R.id.rb_source_default)
            return
        }

        // LOCK UI elements incompatible with the encrypted list
        switchPublicDns.isChecked = false
        switchPublicDns.isEnabled = false
        switchCidrMode.isChecked = false
        switchCidrMode.isEnabled = false
        etNumResolvers.isEnabled = false
        etNumResolvers.alpha = 0.5f

        resolversList = allResolvers.toList()
        tvResolversCount.text = "Loaded encrypted resolvers: ${resolversList.size}"
        Toast.makeText(this, "Isolated to encrypted resolvers only.", Toast.LENGTH_SHORT).show()
    }

    private fun loadDefaultResolvers() {
        try {
            val inputStream = assets.open("resolvers.txt")
            val content = inputStream.bufferedReader().use { it.readText() }
            resolversList = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

            tvResolversCount.text = "Loaded resolvers: ${resolversList.size}"
//            Toast.makeText(this, "Loaded ${resolversList.size} default resolvers", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load default resolvers", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseCustomResolvers() {
        try {
            // Use "text/*" or "*/*" depending on how broad you want the filter.
            // "text/*" filters for .txt files.
            customResolverPickerLauncher.launch("text/*")
        } catch (e: Exception) {
            Toast.makeText(this, "No file manager found to pick files.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun loadCustomResolversFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val content = inputStream.bufferedReader().use { it.readText() }

                // Extract and remove duplicates
                val parsedLines = content.split(Regex("[\\s,;]+"))
                    .map { it.replace("\"", "").trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()

                if (parsedLines.isNotEmpty()) {
                    if (switchCidrMode.isChecked) {
                        // FIX: Filter out IPv6 and invalid data upfront for CIDRs!
                        loadedCidrs = parsedLines.filter { isValidCidr(it) }
                        tvResolversCount.text = "Loaded custom CIDR blocks: ${loadedCidrs.size}"

                        selectedCidrs.clear()
                        tvSelectedCidr.text = "Tap right icon to select CIDR ->"

                        Toast.makeText(this, "Loaded ${loadedCidrs.size} valid CIDR blocks", Toast.LENGTH_SHORT).show()
                    } else {
                        // FIX: Filter out IPv6 and invalid data upfront for Static IPs!
                        resolversList = validateAndFilterResolvers(parsedLines, selectedMode)
                        tvResolversCount.text = "Loaded custom resolvers: ${resolversList.size}"

                        etNumResolvers.setText(resolversList.size.toString())
                        Toast.makeText(this, "Loaded ${resolversList.size} valid resolvers", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "File is empty or invalid format.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Could not open file.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadCustomResolversFromUri2(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val content = inputStream.bufferedReader().use { it.readText() }
                //android.util.Log.d("VAY_SCANNER", "RAW CONTENT READ:\n$content")
                //val parsedLines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val parsedLines = content.split(Regex("[\\s,;]+"))
                    .map { it.replace("\"", "").trim() }
                    .filter { it.isNotEmpty() }

                //android.util.Log.d("VAY_SCANNER", "PARSED LINES FOUND: ${parsedLines.size}")
                //android.util.Log.d("VAY_SCANNER", "PARSED DATA: $parsedLines")

                if (parsedLines.isNotEmpty()) {
                    if (switchCidrMode.isChecked) {
                        loadedCidrs = parsedLines
                        tvResolversCount.text = "Loaded custom CIDR blocks: ${loadedCidrs.size}"

                        // Clear the set instead of nulling a single string
                        selectedCidrs.clear()
                        tvSelectedCidr.text = "Tap right icon to select CIDR ->"

                        Toast.makeText(this, "Loaded ${loadedCidrs.size} custom CIDR blocks", Toast.LENGTH_SHORT).show()
                    } else {
                        resolversList = parsedLines
                        tvResolversCount.text = "Loaded custom resolvers: ${resolversList.size}"
                        etNumResolvers.setText(parsedLines.size.toString())

                        //val currentThreshold = etNumResolvers.text.toString().toIntOrNull() ?: 5000
                        //if (parsedLines.size < currentThreshold) {
                        //    etNumResolvers.setText(parsedLines.size.toString())
                        //}
                        Toast.makeText(this, "Loaded ${resolversList.size} custom resolvers", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "File is empty or invalid format.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Could not open file.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadDefaultCidrs() {
        try {
            val inputStream = assets.open("ir-cidrs.txt")
            val content = inputStream.bufferedReader().use { it.readText() }
            loadedCidrs = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
            tvResolversCount.text = "Loaded CIDR blocks: ${loadedCidrs.size}"

            // Clear the set instead of nulling a single string
            selectedCidrs.clear()
            tvSelectedCidr.text = "Tap right icon to select CIDR ->"

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load CIDR blocks (ir-cidrs.txt)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCidrSelectionDialog() {
        if (loadedCidrs.isEmpty()) {
            Toast.makeText(this, "No CIDR blocks loaded", Toast.LENGTH_SHORT).show()
            return
        }

        // --- 1. STATE VARIABLES ---
        var currentMaxResolvers = 32768L
        val maxOptions = listOf("4096", "8192", "16384", "32768", "65536", "131072", "262144")
        val displayCidrs = loadedCidrs.toMutableList()
        val adapterStrings = mutableListOf<String>()

        // 2. Custom Title Layout
        val customTitleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, (8 * resources.displayMetrics.density).toInt())
        }

        val mainTitle = TextView(this).apply {
            text = "Select CIDR Blocks"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this@DnsScannerActivity, android.R.attr.textColorPrimary, android.graphics.Color.BLACK))
        }

        val statusLabel = TextView(this).apply {
            text = "Total selected: 0"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val maxRowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val tvMaxLabel = TextView(this).apply {
            text = "Max. number of resolvers: "
            textSize = 14f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this@DnsScannerActivity, android.R.attr.textColorSecondary, android.graphics.Color.GRAY))
        }

        val tvMaxVal = TextView(this).apply {
            text = currentMaxResolvers.toString()
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this@DnsScannerActivity, android.R.attr.colorPrimary, android.graphics.Color.BLUE))
        }

        val btnSelectMax = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_recent_history)
            background = null
            setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
        }

        maxRowLayout.addView(tvMaxLabel)
        maxRowLayout.addView(tvMaxVal)
        maxRowLayout.addView(btnSelectMax)
        customTitleLayout.addView(mainTitle)
        customTitleLayout.addView(statusLabel)
        customTitleLayout.addView(maxRowLayout)

        // 3. ListView & Adapter Setup
        val customListView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            divider = null
        }

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.select_dialog_multichoice, adapterStrings) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent) as CheckedTextView
                view.minHeight = 0; view.minimumHeight = 0
                view.setPadding((24 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(), (24 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt())
                view.textSize = 15f
                return view
            }
        }
        customListView.adapter = adapter

        // --- SYNC & SORT LOGIC ---
        fun syncDisplayList() {
            val selected = loadedCidrs.filter { selectedCidrs.contains(it) }
            val unselected = loadedCidrs.filter { !selectedCidrs.contains(it) }

            displayCidrs.clear()
            displayCidrs.addAll(selected)
            displayCidrs.addAll(unselected)

            adapterStrings.clear()
            adapterStrings.addAll(displayCidrs.map { cidr ->
                val size = getCidrInfo(cidr)?.second ?: 0L
                "$cidr  ($size)"
            })

            adapter.notifyDataSetChanged()

            var totalSize = 0L
            for (cidr in selected) totalSize += getCidrInfo(cidr)?.second ?: 0L
            statusLabel.text = "Total selected: $totalSize"

            val primaryColor = com.google.android.material.color.MaterialColors.getColor(this@DnsScannerActivity, android.R.attr.colorPrimary, android.graphics.Color.BLUE)
            statusLabel.setTextColor(if (totalSize > currentMaxResolvers) android.graphics.Color.RED else primaryColor)

            for (i in displayCidrs.indices) {
                customListView.setItemChecked(i, selectedCidrs.contains(displayCidrs[i]))
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setCustomTitle(customTitleLayout)
            .setView(customListView)
            .setPositiveButton("OK") { _, _ ->
                if (selectedCidrs.isNotEmpty()) {
                    var totalSize = 0L
                    for (cidr in selectedCidrs) totalSize += getCidrInfo(cidr)?.second ?: 0L
                    tvSelectedCidr.text = "${selectedCidrs.size} blocks selected"
                    tvResolversCount.text = "Available IPs: $totalSize"
                    etNumResolvers.setText(totalSize.toString())
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Check", null)
            .create()

        btnSelectMax.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Max Resolvers")
                .setItems(maxOptions.toTypedArray()) { _, which ->
                    currentMaxResolvers = maxOptions[which].toLong()
                    tvMaxVal.text = currentMaxResolvers.toString()
                    syncDisplayList() // Refresh label color if needed
                }
                .show()
        }

        customListView.setOnItemClickListener { _, _, position, _ ->
            val clickedCidr = displayCidrs[position]
            if (customListView.isItemChecked(position)) selectedCidrs.add(clickedCidr)
            else selectedCidrs.remove(clickedCidr)
            syncDisplayList()
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).text = "Check"
        }

        dialog.setOnShowListener {
            val btnCheck = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
            btnCheck.setOnClickListener {
                if (btnCheck.text == "Check") {
                    selectedCidrs.clear()

                    // 🔴 DIVERSIFIED RANDOM SELECTION LOGIC
                    // Threshold is 50% of the target (e.g., 4096 if target is 8192)
                    val threshold = currentMaxResolvers / 2

                    // Filter: Only include blocks smaller than the threshold
                    val diversifiedPool = loadedCidrs.filter { cidr ->
                        val size = getCidrInfo(cidr)?.second ?: 0L
                        size < threshold
                    }

                    // If no small blocks exist, fallback to the full list to avoid empty results
                    val finalPool = if (diversifiedPool.isNotEmpty()) diversifiedPool else loadedCidrs
                    val shuffled = finalPool.shuffled()

                    var runningSum = 0L
                    for (cidr in shuffled) {
                        val size = getCidrInfo(cidr)?.second ?: 0L
                        if (runningSum + size <= currentMaxResolvers) {
                            selectedCidrs.add(cidr)
                            runningSum += size
                        }
                    }
                    btnCheck.text = "Uncheck"
                } else {
                    selectedCidrs.clear()
                    btnCheck.text = "Check"
                }
                syncDisplayList()
            }
        }

        dialog.show()
        syncDisplayList()
    }

    private fun showPasteResolversDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val etInput = EditText(this).apply {
            hint = "Paste IPs or CIDRs here (separated by spaces, commas, semicolons, or lines)..."
            minLines = 8
            maxLines = 12
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        // --- Custom Action Buttons Layout ---
        val actionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val primaryColor = com.google.android.material.color.MaterialColors.getColor(this@DnsScannerActivity, android.R.attr.colorPrimary, android.graphics.Color.BLUE)
        val onPrimaryColor = com.google.android.material.color.MaterialColors.getColor(this@DnsScannerActivity, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE)

        val btnClean = Button(this).apply {
            text = "Clean"
            backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            setTextColor(onPrimaryColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
        }

        val btnSave = Button(this).apply {
            text = "Save"
            backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            setTextColor(onPrimaryColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }

        actionLayout.addView(btnClean)
        actionLayout.addView(btnSave)
        container.addView(etInput)
        container.addView(actionLayout)

        val titleText = if (switchCidrMode.isChecked) "Paste CIDR Blocks" else "Paste Static Resolvers"

        // Create the Dialog but DO NOT show it yet
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleText)
            .setView(container)
            .setPositiveButton("Import", null) // Set to null so we can override the auto-close behavior
            .setNegativeButton("Cancel", null)
            .create()

        // --- Override the Import Button behavior to prevent auto-closing ---
        dialog.setOnShowListener {
            val btnImport = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btnImport.setOnClickListener {
                val rawText = etInput.text.toString()

                // 🚨 1. THE CIDR GUARDRAIL 🚨
                val hasCidr = rawText.split(Regex("[\\s,;]+")).any { it.contains("/") }

                if (!switchCidrMode.isChecked && hasCidr) {
                    // Block the import, warn the user, and keep the main dialog open!
                    MaterialAlertDialogBuilder(this@DnsScannerActivity)
                        .setTitle("CIDR Block Detected / بلوک CIDR شناسایی شد")
                        .setMessage("""
                            You pasted a CIDR block, but the scanner is in 'Static Resolvers' mode.
                            Please tap 'Save' to save this list, then enable the 'Use CIDR Block' option and import your saved file.
                            
                            شما یک بلوک CIDR را جای‌گذاری کرده‌اید، اما اسکنر در حالت 'Static Resolvers' (آی‌پی‌های ثابت) قرار دارد.
                            لطفاً برای ذخیره این لیست روی 'Save' تپ کنید، سپس گزینه 'Use CIDR Block' را فعال کرده و فایل ذخیره شده خود را وارد کنید.
                        """.trimIndent())
                        .setPositiveButton("OK / تأیید", null)
                        .show()
                    return@setOnClickListener // Abort the import!
                }

                // 2. Proceed with normal import logic
                val cleanedList = processPastedText(rawText)

                if (cleanedList.isNotEmpty()) {
                    if (switchCidrMode.isChecked) {
                        loadedCidrs = cleanedList
                        tvResolversCount.text = "Loaded pasted CIDR blocks: ${loadedCidrs.size}"
                        selectedCidrs.clear()
                        tvSelectedCidr.text = "Tap right icon to select CIDR ->"
                        Toast.makeText(this@DnsScannerActivity, "Imported ${loadedCidrs.size} CIDR blocks", Toast.LENGTH_SHORT).show()
                    } else {
                        resolversList = cleanedList
                        tvResolversCount.text = "Loaded pasted resolvers: ${resolversList.size}"

                        val currentThreshold = etNumResolvers.text.toString().toIntOrNull() ?: 5000
                        if (cleanedList.size < currentThreshold) {
                            etNumResolvers.setText(cleanedList.size.toString())
                        }
                        Toast.makeText(this@DnsScannerActivity, "Imported ${resolversList.size} resolvers", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss() // Data is safe, NOW we can close the dialog
                } else {
                    Toast.makeText(this@DnsScannerActivity, "No valid entries found to import.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // --- CLEAN BUTTON LOGIC ---
        btnClean.setOnClickListener {
            val rawText = etInput.text.toString()
            val cleanedList = processPastedText(rawText)
            etInput.setText(cleanedList.joinToString("\n")) // Formats into strict 1-per-line
            Toast.makeText(this@DnsScannerActivity, "Found ${cleanedList.size} valid, unique entries.", Toast.LENGTH_SHORT).show()
        }

        // --- SAVE BUTTON LOGIC ---
// --- SAVE BUTTON LOGIC ---
        btnSave.setOnClickListener {
            val rawText = etInput.text.toString()
            if (rawText.isBlank()) {
                Toast.makeText(this, "Nothing to save.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fileNameInput = EditText(this).apply {
                hint = "my_custom_resolvers"
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Save File")
                .setMessage("Enter a name for this text file:")
                .setView(fileNameInput)
                .setPositiveButton("Save") { _, _ ->
                    val name = fileNameInput.text.toString().trim()
                    if (name.isNotEmpty()) {

                        // We format the text (1-per-line, no duplicates)
                        // but DO NOT strip out CIDR blocks so they are preserved in the saved file
                        val cleanedForSave = rawText.split(Regex("[\\s,;]+"))
                            .map { it.replace("\"", "").trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()

                        val finalContent = cleanedForSave.joinToString("\n")

                        // Push directly to the public Downloads folder
                        saveToDownloads(name, finalContent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    private fun saveToDownloads(fileName: String, content: String) {
        try {
            val safeName = if (fileName.endsWith(".txt")) fileName else "$fileName.txt"

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Modern Android (API 29+): Uses MediaStore, NO storage permissions required!
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    // Groups them neatly in a Phoenix folder inside Downloads
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Phoenix")
                }

                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(content.toByteArray())
                    }
                    Toast.makeText(this, "Saved to Downloads/Phoenix/$safeName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to create file in Downloads", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Legacy Android (Android 9 and below)
                // Note: This requires <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" /> in your Manifest
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val vayDnsDir = java.io.File(downloadsDir, "Phoenix")
                if (!vayDnsDir.exists()) vayDnsDir.mkdirs()

                val file = java.io.File(vayDnsDir, safeName)
                file.writeText(content)
                Toast.makeText(this, "Saved to Downloads/Phoenix/$safeName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("VAY_SCANNER", "Error saving to Downloads", e)
        }
    }

    private fun processPastedText(rawText: String): List<String> {
        // 1. Flexible split (spaces, commas, tabs, lines)
        // 2. Remove quotes
        // 3. Remove duplicates natively
        val parsedLines = rawText.split(Regex("[\\s,;]+"))
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // 4. Run through strict structural validation
        return if (switchCidrMode.isChecked) {
            parsedLines.filter { isValidCidr(it) }
        } else {
            // Uses your existing validation logic for Static IPs and Ports
            validateAndFilterResolvers(parsedLines, selectedMode)
        }
    }

    // Checks if the IP is exactly 4 octets, all between 0 and 255
    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }

    // Checks if it's a valid IP, and if it has a port, checks if the port is 1-65535
    private fun isValidIpv4WithOptionalPort(input: String): Boolean {
        if (input.contains(":")) {
            val parts = input.split(":")
            if (parts.size != 2) return false
            val port = parts[1].toIntOrNull()
            return isValidIpv4(parts[0]) && port != null && port in 1..65535
        }
        return isValidIpv4(input)
    }

    // Checks if the IP is valid AND the subnet mask is between 0 and 32
    private fun isValidCidr(cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull()
        return isValidIpv4(parts[0]) && prefix != null && prefix in 0..32
    }
    private fun getCidrInfo(cidr: String): Pair<Long, Long>? {
        try {
            val parts = cidr.split("/")
            val ipStr = parts[0]
            val prefix = if (parts.size == 2) parts[1].toInt() else 32
            if (prefix !in 0..32) return null

            val ipParts = ipStr.split(".")
            if (ipParts.size != 4) return null

            var ipLong = 0L
            for (part in ipParts) {
                ipLong = (ipLong shl 8) + part.toLong()
            }

            val size = 1L shl (32 - prefix)
            val mask = (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            val baseIp = ipLong and mask

            return Pair(baseIp, size)
        } catch (e: Exception) {
            return null
        }
    }

    private fun longToIp(ipLong: Long): String {
        return "${(ipLong shr 24) and 255}.${(ipLong shr 16) and 255}.${(ipLong shr 8) and 255}.${ipLong and 255}"
    }

    private fun getIpsFromCidr(cidr: String, limit: Int, random: Boolean): List<String> {
        val info = getCidrInfo(cidr) ?: return emptyList()
        val baseIp = info.first
        val size = info.second
        val result = mutableListOf<String>()

        val actualLimit = minOf(limit.toLong(), size).toInt()

        if (random) {
            // Optimization for when limit equals size (return all shuffled)
            if (actualLimit.toLong() == size) {
                for (i in 0 until actualLimit) {
                    result.add(longToIp(baseIp + i.toLong()))
                }
                return result.shuffled()
            }

            val indices = mutableSetOf<Long>()
            while (indices.size < actualLimit) {
                indices.add(Random.nextLong(0, size))
            }
            for (index in indices) {
                result.add(longToIp(baseIp + index))
            }
        } else {
            for (i in 0 until actualLimit) {
                result.add(longToIp(baseIp + i.toLong()))
            }
        }
        return result
    }

    private fun startScan() {
        if (isVpnActive()) {
            AlertDialog.Builder(this)
                .setTitle("VPN Active")
                .setMessage("Please disconnect the VPN before running a scan to get accurate latency results.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        selectedMode = when (rgTunnelMode?.checkedRadioButtonId) {
            R.id.rb_mode_tcp -> "tcp"
            R.id.rb_mode_dot -> "dot"
            R.id.rb_mode_doh -> "doh"
            else -> "udp" // Default to UDP if nothing is selected
        }

        //var publicDnsList = emptyList<String>()
        // --- 1. APPLY PROTOCOL-SPECIFIC CEILINGS ---
        val selectedModeLower = selectedMode.lowercase()
        val modeLimit = when (selectedModeLower) {
            "doh" -> 8192
            "dot" -> 32768
            else -> 262144
        }
        val publicSize = if (switchPublicDns.isChecked) {
            if (selectedModeLower == "udp") 24 else 8
        } else 0

        val rawInputNum = etNumResolvers.text.toString().toIntOrNull() ?: 2000
        val totalIntended = rawInputNum + publicSize
        val threshold = modeLimit + publicSize

        Log.d("VAY_DEBUG", "Intended: $totalIntended | Threshold: $threshold")

        if (totalIntended > threshold) {
            // DIALOG FIRST: No heavy processing has happened yet!
            MaterialAlertDialogBuilder(this)
                .setTitle("High Volume Scan")
                .setMessage("You requested $totalIntended resolvers. This exceeds the stable limit and will take a long time. Proceed?")
                .setPositiveButton("Confirm and Start") { _, _ ->
                    // Only process the list after confirmation
                    prepareAndLaunchScan(modeLimit)
                    prepareAndLaunchScan(totalIntended)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Within limits, process immediately
            prepareAndLaunchScan(rawInputNum)
        }
    }

    private fun prepareAndLaunchScan(numToTake: Int) {
        val useRandom = switchRandom.isChecked
        val baseResolvers = mutableListOf<String>()

        // --- 2. SEPARATED SOURCE SELECTION ---
        if (switchCidrMode.isChecked) {
            // --- CIDR MODE ---
            if (selectedCidrs.isEmpty()) {
                Toast.makeText(this, "Please select at least one CIDR block", Toast.LENGTH_SHORT).show()
                return
            }

            val allCidrIps = mutableListOf<String>()
            for (cidr in selectedCidrs) {
                val info = getCidrInfo(cidr)
                val size = info?.second?.toInt() ?: 0
                if (size > 0) {
                    // Generate IPs from block
                    allCidrIps.addAll(getIpsFromCidr(cidr, size, false))
                }
            }

            val sampled = if (useRandom) allCidrIps.shuffled().take(numToTake) else allCidrIps.take(numToTake)
            baseResolvers.addAll(sampled)

        } else {
            // --- STATIC MODE ---
            if (resolversList.isEmpty()) {
                Toast.makeText(this, "Please load resolvers first", Toast.LENGTH_SHORT).show()
                return
            }

            // Final validation before sampling
            val validatedStatic = validateAndFilterResolvers(resolversList, selectedMode)
            val sampled = if (useRandom) validatedStatic.shuffled().take(numToTake) else validatedStatic.take(numToTake)
            baseResolvers.addAll(sampled)
        }

        // --- 3. PROTOCOL FORMATTING ---
        //val modeLower = if (selectedMode.lowercase() == "dot") ":853" else ":53"
        var formattedResolvers = baseResolvers.map { res ->
            when (selectedMode.lowercase()) {
                //"doh" -> if (res.startsWith("https://")) res else "https://$res/dns-query"
                "doh" -> res
                "dot" -> if (res.contains(":")) res else "$res:853"
                else ->  if (res.contains(":")) res else "$res:53"
            }
        }.toMutableList()

        // --- 4. PUBLIC DNS INJECTION (FULL LIST PRESERVED) ---
        if (switchPublicDns.isChecked) {
            val publicDnsList = when (selectedMode.lowercase()) {
                "doh" -> listOf(
                    "https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query",
                    "https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query",
                    "https://9.9.9.9/dns-query", "https://149.112.112.112/dns-query",
                    "https://94.140.14.14/dns-query", "https://94.140.15.15/dns-query"
                )
                "dot" -> listOf(
                    "8.8.8.8:853", "8.8.4.4:853", "1.1.1.1:853", "1.0.0.1:853",
                    "9.9.9.9:853", "149.112.112.112:853", "94.140.14.14:853", "94.140.15.15:853"
                )
                else -> listOf(
                    "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
                    "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
                    "185.228.168.9", "185.228.169.9", "84.200.69.80", "84.200.70.40",
                    "193.110.81.0", "185.253.5.0", "64.6.64.6", "64.6.65.6", "209.244.0.3",
                    "209.244.0.4", "77.88.8.8", "77.88.8.1", "8.26.56.26", "8.20.247.20"
                ).map { if (it.contains(":")) it else "$it:53" }
            }
            formattedResolvers = (publicDnsList + formattedResolvers).distinct().toMutableList()
        }

        launchResultActivity(formattedResolvers)
    }

    // Helper to launch the result activity
    private fun launchResultActivity(finalResolvers: List<String>) {
        val workers = etWorkers.text.toString().toLongOrNull() ?: 10L
        var tunnelWait = etTunnelWait.text.toString().toLongOrNull() ?: 3000L
        if (tunnelWait < 500L) tunnelWait = 500L
        var udpTimeout = etUdpTimeout.text.toString().toLongOrNull() ?: 1000L
        if (udpTimeout < 500L) udpTimeout = 500L
        var probeTimeout = etProbeTimeout.text.toString().toLongOrNull() ?: 15000L
        if (probeTimeout < 5000L) probeTimeout = 5000L
        val retries = etRetries.text.toString().toLongOrNull() ?: 0L
        val proxyType = when (rgProxy.checkedRadioButtonId) {
            R.id.rb_socks5 -> "socks5"
            R.id.rb_http -> "http"
            else -> "socks5h"
        }

        val lightE2EEnabled = if (isQuickScanner) {
            rgQuickScanMode.checkedRadioButtonId == R.id.rb_light_e2e_quick
        } else {
            rgE2eMode.checkedRadioButtonId == R.id.rb_light_e2e
        }

        val engineQuickScan = if (isQuickScanner) {
            rgQuickScanMode.checkedRadioButtonId == R.id.rb_quick_dns_check
        } else {
            false // DNS Scanner never uses Quick DNS Check ping
        }

        val currentMode = selectedMode.lowercase()
        if (!lightE2EEnabled && (currentMode == "udp" || currentMode == "tcp")) {
            if (probeTimeout > 10000L) {
                probeTimeout = 10000L
            }
        }

        //val fastFailEnabled = rgE2eMode.checkedRadioButtonId == R.id.rb_light_e2e
        val baseDohUrl = if (selectedMode.lowercase() == "doh") selectedDnsAddress else ""

        val intent = Intent(this, DnsScannerResultActivity::class.java).apply {
            putExtra("BASE_DOH_URL", baseDohUrl)
            putExtra("IS_QUICK_SCANNER", isQuickScanner)
            putExtra("CONFIG_ID", configId)
            putExtra("DOMAIN", selectedDomain)
            putExtra("domainIndex", domainIndex)
            putExtra("PUBKEY", selectedPubkey)
            putExtra("MODE", selectedMode)
            //putExtra("RESOLVERS", finalResolvers.joinToString(","))
            putExtra("PROXY_TYPE", proxyType)
            putExtra("RECORD_TYPE", selectedRecordType)
            putExtra("IDLE_TIMEOUT", selectedIdleTimeout)
            putExtra("KEEP_ALIVE", selectedKeepAlive)
            putExtra("CLIENT_ID_SIZE", selectedClientIdSize)
            putExtra("MTU", selectedMtu)
            putExtra("PROTOCOL", selectedProtocol) // Backend encryption type
            putExtra("SS_METHOD", selectedSsMethod)
            putExtra("USE_AUTH", selectedUseAuth)
            putExtra("USER", selectedUser)
            putExtra("PASS", selectedPass)
            putExtra("WORKERS", workers)
            putExtra("TUNNEL_WAIT", tunnelWait)
            putExtra("UDP_TIMEOUT", udpTimeout)
            putExtra("PROBE_TIMEOUT", probeTimeout)
            putExtra("RETRIES", retries)
            putExtra("IS_DEFAULT_RESOLVERS", rgResolverSource.checkedRadioButtonId == R.id.rb_source_default)
//            putExtra("SKIP_QUICK_CHECK", switchSkipQuickCheck.isChecked)
            putExtra("LIGHT_E2E_ENABLED", lightE2EEnabled)
            putExtra("ENGINE_QUICK_SCAN", engineQuickScan)

            if (finalResolvers.size < 8216) {
                // ✅ SMALL LIST: Use Intent Extra (Faster)
                putExtra("RESOLVERS", finalResolvers.joinToString(","))
                putExtra("RESOLVERS_FILE", "")
            } else {
                // ✅ LARGE LIST: Use File (Safe from 1MB System Limit)
                val resolversFile = java.io.File(filesDir, "pending_scan_resolvers.txt")
                resolversFile.writeText(finalResolvers.joinToString(","))

                putExtra("RESOLVERS_FILE", resolversFile.absolutePath)
                putExtra("RESOLVERS", "") // Clear the intent buffer
            }
            putExtra("TOTAL_RESOLVERS", finalResolvers.size)
        }
        startActivity(intent)
    }

    private fun isVpnActive(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // activeNetwork is available from API 23+
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    private fun validateAndFilterResolvers(rawList: List<String>, mode: String): List<String> {
        val sanitized = mutableListOf<String>()
        var rejectedCount = 0

        for (line in rawList) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val isCidr = trimmed.contains("/")

            if (isCidr) {
                // --- STRICT CIDR ENFORCEMENT ---
                if (isValidCidr(trimmed)) {
                    sanitized.add(trimmed)
                } else {
                    rejectedCount++
                }
            } else {
                // --- STRICT STATIC ENTRY ENFORCEMENT ---
                when (mode.lowercase()) {
                    "doh" -> {
                        // DoH accepts URLs or Strict IPs
                        if (trimmed.startsWith("https://") || isValidIpv4WithOptionalPort(trimmed)) {
                            sanitized.add(trimmed)
                        } else {
                            rejectedCount++
                        }
                    }
                    "dot", "udp", "tcp" -> {
                        // Raw sockets reject HTTP URLs and require Strict IPs
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                            rejectedCount++
                        } else if (isValidIpv4WithOptionalPort(trimmed)) {
                            sanitized.add(trimmed)
                        } else {
                            rejectedCount++
                        }
                    }
                }
            }
        }

        if (rejectedCount > 0) {
            Toast.makeText(this, "Ignored $rejectedCount invalid or incompatible entries.", Toast.LENGTH_LONG).show()
        }
        return sanitized
    }
}