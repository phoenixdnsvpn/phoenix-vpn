package net.vaydns.phoenix

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ResolverTransferActivity : AppCompatActivity() {
    private var isExportMode = true
    private val selectedIds = mutableSetOf<String>()

    private var officialConfigs = listOf<Config>()
    private val pendingImportData = mutableMapOf<String, List<String>>()
    private val transferItems = mutableListOf<ResolverTransferAdapter.TransferItem>()
    private lateinit var adapter: ResolverTransferAdapter

    private lateinit var btnExecute: Button
    private lateinit var rgImportMethod: RadioGroup

    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (jsonString != null) parseImportFile(jsonString)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to read file.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_resolver_transfer)

        isExportMode = intent.getStringExtra("MODE") == "EXPORT"
        officialConfigs = DefaultConfigProvider.getDefaultConfigs(this).filter { !it.freeScanner }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_transfer)
        toolbar.title = if (isExportMode) "Export Resolvers" else "Import Resolvers"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setNavigationOnClickListener { handleBackPress() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        val rv = findViewById<RecyclerView>(R.id.rv_config_selector)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ResolverTransferAdapter(transferItems) { id, isChecked ->
            if (isChecked) selectedIds.add(id) else selectedIds.remove(id)
        }
        rv.adapter = adapter

        btnExecute = findViewById(R.id.btn_execute)
        rgImportMethod = findViewById(R.id.rg_import_method)

        if (isExportMode) {
            setupExportMode()
        } else {
            setupImportMode()
        }

        findViewById<Button>(R.id.btn_check_all).setOnClickListener {
            selectedIds.addAll(transferItems.map { it.config.id })
            adapter.setAllChecked(true)
        }

        findViewById<Button>(R.id.btn_uncheck_all).setOnClickListener {
            selectedIds.clear()
            adapter.setAllChecked(false)
        }
    }

    private fun setupExportMode() {
        rgImportMethod.visibility = View.GONE
        btnExecute.text = "EXPORT SELECTED"
        transferItems.clear()

        officialConfigs.forEach { config ->
            val file = File(filesDir, "selected_multipath_${config.id}.txt")
            if (file.exists() && file.length() > 0) {
                val lines = file.readLines().filter { it.isNotBlank() }
                if (lines.isNotEmpty()) {
                    transferItems.add(ResolverTransferAdapter.TransferItem(config, lines.size))
                }
            }
        }

        if (transferItems.isEmpty()) {
            Toast.makeText(this, "No official configs have saved user resolvers.", Toast.LENGTH_LONG).show()
        }
        adapter.notifyDataSetChanged()

        btnExecute.setOnClickListener {
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Please select at least one config.", Toast.LENGTH_SHORT).show()
            } else {
                exportData()
            }
        }
    }

    private fun setupImportMode() {
        rgImportMethod.visibility = View.VISIBLE
        transferItems.clear()
        adapter.notifyDataSetChanged()

        updateImportButtonLabel()

        rgImportMethod.setOnCheckedChangeListener { _, _ ->
            updateImportButtonLabel()
        }

        // Button permanently acts as the Import/Paste trigger
        btnExecute.setOnClickListener {
            if (rgImportMethod.checkedRadioButtonId == R.id.rb_import_file) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                importLauncher.launch(intent)
            } else {
                showPasteJsonDialog()
            }
        }
    }

    private fun updateImportButtonLabel() {
        if (rgImportMethod.checkedRadioButtonId == R.id.rb_import_file) {
            btnExecute.text = "IMPORT FROM FILE"
        } else {
            btnExecute.text = "PASTE RESOLVER TO IMPORT"
        }
    }

    private fun showPasteJsonDialog() {
        val editText = EditText(this).apply {
            hint = "Paste copied JSON data here..."
            setLines(8)
            gravity = Gravity.TOP or Gravity.START
        }

        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(50, 20, 50, 0)
        }
        editText.layoutParams = params
        container.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Paste Resolvers")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val jsonStr = editText.text.toString().trim()
                if (jsonStr.isNotEmpty()) {
                    parseImportFile(jsonStr)
                } else {
                    Toast.makeText(this, "No data pasted.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseImportFile(jsonStr: String) {
        try {
            val arr = JSONObject(jsonStr).getJSONArray("configs")
            pendingImportData.clear()
            transferItems.clear()
            selectedIds.clear()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")

                val matchingConfig = officialConfigs.find { it.id == id }
                if (matchingConfig != null) {
                    val newResolvers = obj.getJSONArray("resolvers")
                    val ipList = mutableListOf<String>()
                    for (j in 0 until newResolvers.length()) {
                        val res = newResolvers.getString(j).trim()
                        if (res.isNotEmpty()) ipList.add(res)
                    }

                    if (ipList.isNotEmpty()) {
                        pendingImportData[id] = ipList
                        transferItems.add(ResolverTransferAdapter.TransferItem(matchingConfig, ipList.size))
                    }
                }
            }

            if (transferItems.isEmpty()) {
                Toast.makeText(this, "Data contains no matching official configs for this device.", Toast.LENGTH_LONG).show()
            } else {
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "Loaded ${transferItems.size} configs. Review selection and press Back to save.", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Invalid format or corrupted data.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBackPress() {
        if (!isExportMode && pendingImportData.isNotEmpty()) {
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Warning: You have not selected any config.", Toast.LENGTH_LONG).show()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Cancel Import?")
                    .setMessage("You haven't selected any configs to save. Do you want to exit without importing?")
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .setNegativeButton("Stay", null)
                    .show()
            } else {
                commitImport()
            }
        } else {
            finish()
        }
    }

    private fun commitImport() {
        var totalImported = 0
        selectedIds.forEach { id ->
            val importedIps = pendingImportData[id] ?: return@forEach

            val targetFile = File(filesDir, "selected_multipath_$id.txt")
            val existingLines = if (targetFile.exists()) targetFile.readLines().filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()
            val existingBaseIps = existingLines.map { it.split(":").first() }.toSet()

            val newIpsForThisConfig = mutableListOf<String>()
            for (res in importedIps) {
                if (!existingBaseIps.contains(res.split(":").first())) {
                    existingLines.add(res)
                    newIpsForThisConfig.add(res)
                    totalImported++
                }
            }
            targetFile.writeText(existingLines.joinToString("\n"))

            if (newIpsForThisConfig.isNotEmpty()) {
                val scannedFile = File(filesDir, "resolvers_$id.txt")
                val scannedLines = if (scannedFile.exists()) scannedFile.readLines().toMutableList() else mutableListOf()
                val scannedBaseIps = scannedLines.map { it.split(",").firstOrNull()?.split(":")?.first() ?: "" }.toSet()

                newIpsForThisConfig.forEach { ip ->
                    if (!scannedBaseIps.contains(ip.split(":").first())) {
                        scannedLines.add("$ip,")
                    }
                }
                scannedFile.writeText(scannedLines.joinToString("\n"))
            }
        }

        Toast.makeText(this, "Import Complete. Added $totalImported new IPs.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun exportData() {
        val arr = JSONArray()
        selectedIds.forEach { id ->
            val file = File(filesDir, "selected_multipath_$id.txt")
            if (file.exists()) {
                val lines = file.readLines().filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    arr.put(JSONObject().put("id", id).put("resolvers", JSONArray(lines)))
                }
            }
        }

        if (arr.length() == 0) {
            Toast.makeText(this, "No user-saved resolvers found in selected configs.", Toast.LENGTH_SHORT).show()
            return
        }

        val json = JSONObject().put("configs", arr).toString()
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(share, "Export Resolvers"))
    }
}