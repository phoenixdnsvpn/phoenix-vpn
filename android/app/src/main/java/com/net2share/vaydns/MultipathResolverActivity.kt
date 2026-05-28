package com.net2share.vaydns

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Collections

class MultipathResolverActivity : AppCompatActivity() {

    private lateinit var configId: String
    private lateinit var tunnelMode: String
    private val resolverEntries = mutableListOf<ConfigEditorActivity.ResolverEntry>()
    private lateinit var adapter: CheckBoxResolverAdapter
    private var isCheckAllActive = true
    private var initialSnapshot: String = "" // Freezes original map array configuration

    //private lateinit var cbImportResolvers: SwitchCompat
    //private lateinit var cbExportResolvers: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_multipath_resolver)

        configId = intent.getStringExtra("CONFIG_ID") ?: "new_temp_config"
        tunnelMode = intent.getStringExtra("TUNNEL_MODE") ?: "udp"

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_resolvers)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 🟢 WARNING CHECK 1: Clicked the header bar navigation icon
        toolbar.setNavigationOnClickListener { handleBackPressWithWarning() }

        // 🟢 WARNING CHECK 2: Triggered Android physical back buttons/gestures
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressWithWarning()
            }
        })

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0 // Drag direction
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition

                // Swap items in your source list
                Collections.swap(resolverEntries, fromPosition, toPosition)

                // Tell the adapter to update the UI
                adapter.notifyItemMoved(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // We aren't implementing swipe, so leave empty
            }

            // Optional: Make the item look "lifted" when being dragged
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                    viewHolder?.itemView?.alpha = 0.5f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
            }
        })

        setupDataPipeline()
        initialSnapshot = captureCurrentSnapshot() // Freeze snapshot configuration state immediately

        val recyclerView = findViewById<RecyclerView>(R.id.rv_resolvers)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CheckBoxResolverAdapter(
            entries = resolverEntries,
            tunnelMode = tunnelMode,
            onStatusChanged = { updateSubtitleCount(); updateToggleAllButtonState() },
            sanitizePointer = { ip, mode -> sanitizeInput(ip, mode) },
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) }
        )
        //adapter = CheckBoxResolverAdapter(resolverEntries, tunnelMode, { updateSubtitleCount(); updateToggleAllButtonState() }, { ip, mode -> sanitizeInput(ip, mode) })
        recyclerView.adapter = adapter
        itemTouchHelper.attachToRecyclerView(recyclerView)

        findViewById<Button>(R.id.btn_import_resolvers).setOnClickListener { showImportDialog() }

        // Toggle All Button Actions Bar Core Engine
        val btnToggleAll = findViewById<Button>(R.id.btn_toggle_all_resolvers)
        btnToggleAll.setOnClickListener {
            resolverEntries.forEach { entry ->
                if (entry.address.isNotBlank() && sanitizeInput(entry.address, tunnelMode) != null) {
                    entry.isChecked = isCheckAllActive
                } else if (!isCheckAllActive) {
                    entry.isChecked = false
                }
            }
            isCheckAllActive = !isCheckAllActive
            btnToggleAll.text = if (isCheckAllActive) "CHECK ALL" else "UNCHECK ALL"
            adapter.notifyDataSetChanged()
            updateSubtitleCount()
        }

        findViewById<Button>(R.id.btn_save_resolvers).setOnClickListener { executeSaveSequence() }

        findViewById<Button>(R.id.btn_export_resolvers).setOnClickListener { exportResolvers() }

        // DELETIONS ARE STAGED IN RAM ONLY (Allows Undo via Discard)
        findViewById<Button>(R.id.btn_delete_resolvers).setOnClickListener {
            val checkedItems = resolverEntries.filter { it.isChecked && it.address.isNotEmpty() }

            if (checkedItems.isEmpty()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("No Selection / هیچ انتخابی انجام نشده")
                    .setMessage(
                        "You must check at least one resolver to delete.\n\n" +
                                "لطفاً حداقل یک رِزولور را برای حذف انتخاب کنید."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("Delete Selected? / حذف موارد انتخاب‌شده؟")
                .setMessage(
                    "Are you sure you want to remove the selected resolvers from this view?\n\n" +
                            "آیا مطمئن هستید که می‌خواهید رِزولورهای انتخاب‌شده را از این نما حذف کنید؟"
                )
                .setPositiveButton("Delete") { _, _ ->
                    val iterator = resolverEntries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.isChecked && entry.address.isNotEmpty()) {
                            if (entry.isManual) {
                                // Clear manual slots to preserve your 20-row template grid space
                                entry.address = ""
                                entry.isChecked = false
                            } else {
                                // Drop scanned results directly out of live memory
                                iterator.remove()
                            }
                        }
                    }

                    // Refresh UI components instantly
                    adapter.notifyDataSetChanged()
                    updateSubtitleCount()
                    updateToggleAllButtonState()

                    Toast.makeText(this, "Removed from view (Tap SAVE to confirm) / از لیست نما حذف شد", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        updateSubtitleCount()
        updateToggleAllButtonState()
    }

    private fun captureCurrentSnapshot(): String {
        // Serializes fields linearly to quickly check for structural edits later
        return resolverEntries.joinToString("|") { "${it.address}:${it.isChecked}" }
    }

    private fun handleBackPressWithWarning() {
        if (captureCurrentSnapshot() != initialSnapshot) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Discard Changes? / صرف‌نظر از تغییرات؟")
                .setMessage(
                    "You have unsaved changes. Do you want to discard them and exit?\n\n" +
                            "تغییرات ذخیره نشده‌ای دارید. آیا می‌خواهید آن‌ها را نادیده گرفته و خارج شوید؟"
                )
                .setPositiveButton("Discard & Exit") { _, _ -> finish() }
                .setNegativeButton("Keep Editing", null)
                .show()
        } else {
            finish()
        }
    }

    private fun updateToggleAllButtonState() {
        val btnToggleAll = findViewById<Button>(R.id.btn_toggle_all_resolvers)
        val validEntriesCount = resolverEntries.count { it.address.isNotBlank() && sanitizeInput(it.address, tunnelMode) != null }
        val checkedCount = resolverEntries.count { it.isChecked }

        isCheckAllActive = checkedCount < validEntriesCount
        btnToggleAll.text = if (isCheckAllActive) "CHECK ALL" else "UNCHECK ALL"
    }

    private fun updateSubtitleCount() {
        val totalSelected = resolverEntries.count { it.isChecked }
        supportActionBar?.subtitle = "$totalSelected IPs selected"
    }

    private fun setupDataPipeline() {
        resolverEntries.clear()

        // 1. Read scanner inputs
        val scanFile = File(filesDir, "resolvers_$configId.txt")
        if (scanFile.exists()) {
            scanFile.readLines().forEach { line ->
                val parts = line.split(",")
                val ip = parts.getOrNull(0)?.trim() ?: ""
                val lat = parts.getOrNull(1)?.trim() ?: ""
                if (ip.isNotEmpty()) {
                    resolverEntries.add(ConfigEditorActivity.ResolverEntry(ip, isChecked = false, isManual = false, latency = lat))
                }
            }
        }

        // 2. Load manual inputs
        val manualFile = File(filesDir, "manual_resolvers_$configId.txt")
        val savedManuals = if (manualFile.exists()) manualFile.readLines() else emptyList()
        for (i in 0 until 20) {
            val addr = savedManuals.getOrNull(i) ?: ""
            resolverEntries.add(ConfigEditorActivity.ResolverEntry(addr, isChecked = false, isManual = true, latency = ""))
        }

        // 3. Sync checklist state maps
        val selectedFile = File(filesDir, "selected_multipath_$configId.txt")
        if (!selectedFile.exists()) {
            Toast.makeText(this, "File does not exist", Toast.LENGTH_SHORT).show()
        }

        val selections = if (selectedFile.exists()) {
            val lines = selectedFile.readLines().map { it.trim() }
            // Notification logic for empty file
            if (lines.isEmpty()) {
                Toast.makeText(this, "Zero resolvers read", Toast.LENGTH_SHORT).show()
            }
            lines.toSet()
        } else emptySet()

        resolverEntries.forEach { entry ->
            // Normalize both for comparison (removing ports if necessary)
            val entryAddr = entry.address.trim().lowercase()

            // Check if the saved list contains this address
            // We check exact match AND we verify the base IP matches (ignores port differences)
            val isMatch = selections.any { saved ->
                saved.trim().lowercase() == entryAddr ||
                        saved.trim().lowercase().split(":").first() == entryAddr.split(":").first()
            }

            if (isMatch) entry.isChecked = true
        }
    }

    private fun exportResolvers() {
        // This gathers ALL checked IPs from the full list (scanned + manual)
        val selectedAddrs = resolverEntries
            .filter { it.isChecked && it.address.isNotEmpty() }
            .map { it.address }

        if (selectedAddrs.isEmpty()) {
            Toast.makeText(this, "No resolvers selected to export", Toast.LENGTH_SHORT).show()
            return
        }

        val exportText = selectedAddrs.joinToString("\n")

        // Create the Share Intent
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "VayDNS Resolvers")
            putExtra(Intent.EXTRA_TEXT, exportText)
        }

        // Trigger the system share sheet
        startActivity(Intent.createChooser(intent, "Export Resolvers via"))
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
                val validIps = parsed.mapNotNull { sanitizeInput(it, tunnelMode) }.distinct()

                if (validIps.isNotEmpty()) {
                    val imported = validIps.take(20)
                    var targetIdx = 0
                    resolverEntries.filter { it.isManual }.forEach { entry ->
                        if (targetIdx < imported.size) {
                            entry.address = imported[targetIdx++]
                            entry.isChecked = true
                        } else {
                            entry.address = ""
                            entry.isChecked = false
                        }
                    }
                    adapter.notifyDataSetChanged()
                    updateSubtitleCount()
                    updateToggleAllButtonState()
                    Toast.makeText(this, "Imported ${imported.size} IPs", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No valid IPs found for $tunnelMode mode.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeSaveSequence() {
        var formattingErrorsEncountered = false

        resolverEntries.filter { it.isManual }.forEach { entry ->
            if (entry.address.isNotBlank()) {
                val clean = sanitizeInput(entry.address, tunnelMode)
                if (clean != null) {
                    entry.address = clean
                } else {
                    entry.address = ""
                    entry.isChecked = false
                    formattingErrorsEncountered = true
                }
            } else {
                entry.isChecked = false
            }
        }

        val manualPayload = resolverEntries.filter { it.isManual }.map { it.address }
        safeWriteToFile(File(filesDir, "manual_resolvers_$configId.txt"), manualPayload.joinToString("\n"))

        // 2. Persist updated scanned lines (Commits any scanned deletions cleanly!)
        val scannedPayload = resolverEntries.filter { !it.isManual }.map { "${it.address},${it.latency}" }
        safeWriteToFile(File(filesDir, "resolvers_$configId.txt"), scannedPayload.joinToString("\n"))

        // 3. Persist check state map configurations
        val selectedPayload = resolverEntries.filter { it.isChecked && it.address.isNotEmpty() }.map { it.address }
        safeWriteToFile(File(filesDir, "selected_multipath_$configId.txt"), selectedPayload.joinToString("\n"))

        // ONE-SHOT DISK COMMITS FOR MANUAL, SCANNED, AND SELECTIONS
        // 1. Persist manual text lines
        /**val manualPayload = resolverEntries.filter { it.isManual }.map { it.address }
        File(filesDir, "manual_resolvers_$configId.txt").writeText(manualPayload.joinToString("\n"))

        // 2. Persist updated scanned lines (Commits any scanned deletions cleanly!)
        val scannedPayload = resolverEntries.filter { !it.isManual }.map { "${it.address},${it.latency}" }
        File(filesDir, "resolvers_$configId.txt").writeText(scannedPayload.joinToString("\n"))

        // 3. Persist check state map configurations
        val selectedPayload = resolverEntries.filter { it.isChecked && it.address.isNotEmpty() }.map { it.address }
        File(filesDir, "selected_multipath_$configId.txt").writeText(selectedPayload.joinToString("\n"))*/

        if (formattingErrorsEncountered) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Invalid IPs Cleared")
                .setMessage("Incorrectly formatted entries for ${tunnelMode.uppercase()} mode were auto-removed to avoid failures.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
        } else {
            Toast.makeText(this, "All changes saved successfully! / تمام تغییرات ذخیره شدند", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun safeWriteToFile(file: File, content: String) {
        try {
            java.io.FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray())
                fos.flush()
                // Force physical write to storage to prevent data loss
                fos.fd.sync()
            }
        } catch (e: Exception) {
            android.util.Log.e("VAY_ERROR", "Failed to force write to file: ${file.name}", e)
        }
    }

    private fun sanitizeInput(input: String, mode: String): String? {
        val lines = input.split(Regex("[\\s,;]+")).map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (mode.lowercase() == "doh") {
                if (line.startsWith("https://") || isValidIpv4WithOptionalPort(line)) return line
            } else {
                if (!line.startsWith("http://") && !line.startsWith("https://") && isValidIpv4WithOptionalPort(line)) return line
            }
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