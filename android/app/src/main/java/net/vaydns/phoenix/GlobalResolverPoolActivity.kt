package net.vaydns.phoenix

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.OutputStream

class GlobalResolverPoolActivity : AppCompatActivity() {

    private val protocols = listOf("vaydns", "masterdns", "stormdns", "cottendns", "slipstream")
    private var currentProtocol = "vaydns"
    private var lastSelectedSpinnerPosition = 0

    private val resolverEntries = mutableListOf<ConfigEditorActivity.ResolverEntry>()
    private lateinit var adapter: CheckBoxResolverAdapter
    private var isCheckAllActive = true
    private var initialSnapshot: String = ""
    private var pendingExportText: String = ""

    private val createFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri -> writeExportToFile(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_resolver_pool)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_global_pool)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setNavigationOnClickListener { handleBackPressWithWarning() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackPressWithWarning() }
        })

        // Setup Spinner
        val spinner = findViewById<Spinner>(R.id.spinner_pool_protocol)
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, protocols)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = spinnerAdapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == lastSelectedSpinnerPosition) return

                if (captureCurrentSnapshot() != initialSnapshot) {
                    MaterialAlertDialogBuilder(this@GlobalResolverPoolActivity)
                        .setTitle("Unsaved Changes")
                        .setMessage("Discard unsaved changes for ${protocols[lastSelectedSpinnerPosition]}?")
                        .setPositiveButton("Discard") { _, _ ->
                            lastSelectedSpinnerPosition = position
                            currentProtocol = protocols[position]
                            loadDataPipeline()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            spinner.setSelection(lastSelectedSpinnerPosition)
                        }
                        .show()
                } else {
                    lastSelectedSpinnerPosition = position
                    currentProtocol = protocols[position]
                    loadDataPipeline()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.rv_resolvers)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // We initialize the adapter WITHOUT attaching the ItemTouchHelper (No Dragging)
        adapter = CheckBoxResolverAdapter(
            entries = resolverEntries,
            tunnelMode = "udp", // Global Pools are generally UDP payloads
            onStatusChanged = { updateSubtitleCount(); updateToggleAllButtonState() },
            sanitizePointer = { ip, _ -> sanitizeInput(ip) },
            onStartDrag = { },
            showDragHandle = false
        )
        recyclerView.adapter = adapter

        // Setup Actions
        findViewById<ImageButton>(R.id.btn_import_resolvers).setOnClickListener { showImportDialog() }
        findViewById<ImageButton>(R.id.btn_save_resolvers).setOnClickListener { executeSaveSequence() }
        findViewById<ImageButton>(R.id.btn_export_resolvers).setOnClickListener { exportResolvers() }

        val btnToggleAll = findViewById<ImageButton>(R.id.btn_toggle_all_resolvers)
        btnToggleAll.setOnClickListener {
            resolverEntries.forEach { entry ->
                if (entry.address.isNotBlank() && sanitizeInput(entry.address) != null) {
                    entry.isChecked = isCheckAllActive
                } else if (!isCheckAllActive) {
                    entry.isChecked = false
                }
            }
            isCheckAllActive = !isCheckAllActive
            Toast.makeText(this, if (isCheckAllActive) "Unchecked All" else "Checked All", Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
            updateSubtitleCount()
        }

        findViewById<ImageButton>(R.id.btn_delete_resolvers).setOnClickListener {
            val checkedItems = resolverEntries.filter { it.isChecked && it.address.isNotEmpty() }
            if (checkedItems.isEmpty()) {
                Toast.makeText(this, "Select at least one resolver to delete.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Delete Selected?")
                .setMessage("Remove the selected resolvers from this pool?")
                .setPositiveButton("Delete") { _, _ ->
                    val iterator = resolverEntries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.isChecked && entry.address.isNotEmpty()) {
                            iterator.remove() // Drops items completely from the list
                        }
                    }
                    adapter.notifyDataSetChanged()
                    updateSubtitleCount()
                    updateToggleAllButtonState()
                    Toast.makeText(this, "Removed from view (Tap SAVE to confirm)", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadDataPipeline()
    }

    private fun loadDataPipeline() {
        resolverEntries.clear()

        // 1. Load the full pool list
        val poolFile = File(filesDir, "global_resolvers_${currentProtocol}.txt")
        if (poolFile.exists()) {
            poolFile.readLines().forEach { line ->
                val parts = line.split(",")
                val ip = parts.getOrNull(0)?.trim() ?: ""
                val lat = parts.getOrNull(1)?.trim() ?: ""
                if (ip.isNotEmpty()) {
                    resolverEntries.add(ConfigEditorActivity.ResolverEntry(ip, isChecked = false, isManual = false, latency = lat))
                }
            }
        }

        // 2. Load the checked states (NEW)
        val selectedFile = File(filesDir, "selected_global_resolvers_${currentProtocol}.txt")
        val selections = if (selectedFile.exists()) {
            selectedFile.readLines().map { it.trim() }.toSet()
        } else emptySet()

        // 3. Apply checkmarks to matching IPs
        resolverEntries.forEach { entry ->
            val entryAddr = entry.address.trim().lowercase()
            val isMatch = selections.any { saved ->
                saved.trim().lowercase() == entryAddr ||
                        saved.trim().lowercase().split(":").first() == entryAddr.split(":").first()
            }
            if (isMatch) entry.isChecked = true
        }

        initialSnapshot = captureCurrentSnapshot()
        adapter.notifyDataSetChanged()
        updateSubtitleCount()
        updateToggleAllButtonState()
    }

    private fun captureCurrentSnapshot(): String {
        return resolverEntries.joinToString("|") { "${it.address}:${it.isChecked}" }
    }

    private fun handleBackPressWithWarning() {
        if (captureCurrentSnapshot() != initialSnapshot) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Discard Changes?")
                .setMessage("You have unsaved changes. Do you want to discard them and exit?")
                .setPositiveButton("Discard & Exit") { _, _ -> finish() }
                .setNegativeButton("Keep Editing", null)
                .show()
        } else {
            finish()
        }
    }

    private fun executeSaveSequence() {
        var formattingErrorsEncountered = false

        // Drop invalid entries
        val iterator = resolverEntries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.address.isNotBlank()) {
                val clean = sanitizeInput(entry.address)
                if (clean != null) {
                    entry.address = clean
                } else {
                    iterator.remove()
                    formattingErrorsEncountered = true
                }
            } else {
                iterator.remove()
            }
        }

        // 1. Save the full pool list
        val poolPayload = resolverEntries.map { "${it.address},${it.latency}" }
        val poolFile = File(filesDir, "global_resolvers_${currentProtocol}.txt")

        try {
            java.io.FileOutputStream(poolFile).use { fos ->
                fos.write(poolPayload.joinToString("\n").toByteArray())
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: Exception) {
            android.util.Log.e("VAY_ERROR", "Failed to force write to pool file: ${poolFile.name}", e)
        }

        // 2. Save the checked states (NEW)
        val selectedPayload = resolverEntries.filter { it.isChecked && it.address.isNotEmpty() }.map { it.address }
        val selectedFile = File(filesDir, "selected_global_resolvers_${currentProtocol}.txt")

        try {
            java.io.FileOutputStream(selectedFile).use { fos ->
                fos.write(selectedPayload.joinToString("\n").toByteArray())
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: Exception) {
            android.util.Log.e("VAY_ERROR", "Failed to force write to selected file: ${selectedFile.name}", e)
        }

        initialSnapshot = captureCurrentSnapshot()

        if (formattingErrorsEncountered) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Invalid IPs Cleared")
                .setMessage("Incorrectly formatted entries were auto-removed.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
        } else {
            Toast.makeText(this, "Global Pool saved successfully!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Paste IPs (comma, space, or newline separated)..."
            setLines(5)
            setPadding(45, 45, 45, 45)
            gravity = android.view.Gravity.TOP
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Import Resolvers")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val parsed = input.text.toString().split(Regex("[\\s,;]+")).map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }
                val validIps = parsed.mapNotNull { sanitizeInput(it) }.distinct()

                if (validIps.isNotEmpty()) {
                    // Check against existing so we don't import duplicates
                    val existingIps = resolverEntries.map { it.address }
                    val newIps = validIps.filter { !existingIps.contains(it) }

                    newIps.forEach { ip ->
                        resolverEntries.add(ConfigEditorActivity.ResolverEntry(ip, isChecked = false, isManual = false, latency = ""))
                    }

                    adapter.notifyDataSetChanged()
                    updateSubtitleCount()
                    updateToggleAllButtonState()
                    Toast.makeText(this, "Imported ${newIps.size} new IPs", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No valid IPs found.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportResolvers() {
        val selectedAddrs = resolverEntries.filter { it.isChecked && it.address.isNotEmpty() }.map { it.address }
        if (selectedAddrs.isEmpty()) {
            Toast.makeText(this, "No resolvers selected to export", Toast.LENGTH_SHORT).show()
            return
        }

        pendingExportText = selectedAddrs.joinToString("\n")
        val options = arrayOf("Share via...", "Save to File")
        MaterialAlertDialogBuilder(this)
            .setTitle("Export Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Phoenix Global Resolvers ($currentProtocol)")
                            putExtra(Intent.EXTRA_TEXT, pendingExportText)
                        }
                        startActivity(Intent.createChooser(intent, "Export Resolvers via"))
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, "global_pool_${currentProtocol}.txt")
                        }
                        createFileLauncher.launch(intent)
                    }
                }
            }
            .show()
    }

    private fun writeExportToFile(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream: OutputStream ->
                outputStream.write(pendingExportText.toByteArray())
                outputStream.flush()
                Toast.makeText(this, "File saved successfully!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateToggleAllButtonState() {
        val validEntriesCount = resolverEntries.count { it.address.isNotBlank() }
        val checkedCount = resolverEntries.count { it.isChecked }
        isCheckAllActive = checkedCount < validEntriesCount
    }

    private fun updateSubtitleCount() {
        val totalResolvers = resolverEntries.size
        supportActionBar?.subtitle = "Total IPs: $totalResolvers"
    }

    private fun sanitizeInput(input: String): String? {
        val lines = input.split(Regex("[\\s,;]+")).map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (!line.startsWith("http://") && !line.startsWith("https://") && isValidIpv4WithOptionalPort(line)) return line
        }
        return null
    }

    private fun isValidIpv4WithOptionalPort(input: String): Boolean {
        val core = if (input.contains(":")) input.split(":").first() else input
        val parts = core.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }
}