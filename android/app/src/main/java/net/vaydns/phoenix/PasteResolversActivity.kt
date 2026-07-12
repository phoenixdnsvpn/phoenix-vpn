package net.vaydns.phoenix

import android.app.Activity
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class PasteResolversActivity : AppCompatActivity() {

    private lateinit var etInput: EditText
    private var tunnelMode: String = "udp"
    private var isCidrMode: Boolean = false
    private var initialSnapshot: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_paste_resolvers)

        tunnelMode = intent.getStringExtra("TUNNEL_MODE") ?: "udp"
        isCidrMode = intent.getBooleanExtra("IS_CIDR_MODE", false)
        val currentText = intent.getStringExtra("CURRENT_TEXT") ?: ""

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_paste_resolvers)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (isCidrMode) "Paste CIDR Blocks" else "Paste Static Resolvers"

        etInput = findViewById(R.id.et_raw_resolvers_input)

        // 🟢 UPDATED: Persian Translation combined smoothly into the hint setup
        etInput.hint = "Paste IPs or CIDRs here (separated by spaces, commas, semicolons, or lines)...\n" +
                "آی‌پی‌ها یا بلاک‌های CIDR را اینجا وارد کنید (جدا شده با فاصله، کاما، سمیکالن یا خط جدید)..."

        etInput.setText(currentText)
        initialSnapshot = currentText.trim()

        toolbar.setNavigationOnClickListener { handleBackPressWithWarning() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressWithWarning()
            }
        })

        // Full width row action clean filter implementation
        findViewById<Button>(R.id.btn_clean_paste).setOnClickListener {
            val rawText = etInput.text.toString()
            val cleanedList = processTextPayload(rawText)
            etInput.setText(cleanedList.joinToString("\n"))
            Toast.makeText(this, "Found ${cleanedList.size} valid, unique entries.", Toast.LENGTH_SHORT).show()
        }

        // SAVE BUTTON: Exports directly to device storage environment
        findViewById<Button>(R.id.btn_save_paste).setOnClickListener {
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
                        val cleanedForSave = rawText.split(Regex("[\\s,;]+"))
                            .map { it.replace("\"", "").trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()

                        saveToDownloadsFolder(name, cleanedForSave.joinToString("\n"))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // IMPORT BUTTON: Packages data and returns code results securely back to caller process
        findViewById<Button>(R.id.btn_import_paste).setOnClickListener {
            val rawText = etInput.text.toString()
            val hasCidr = rawText.split(Regex("[\\s,;]+")).any { it.contains("/") }

            // Guardrail logic checkpoint
            if (!isCidrMode && hasCidr) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("CIDR Block Detected / بلوک CIDR شناسایی شد")
                    .setMessage(
                        "You pasted a CIDR block, but the scanner is in 'Static Resolvers' mode.\n" +
                                "Please tap 'Save' to save this list, then enable the 'Use CIDR Block' option and import your saved file.\n\n" +
                                "شما یک بلوک CIDR را جای‌گذاری کرده‌اید، اما اسکنر در حالت 'Static Resolvers' (آی‌پی‌های ثابت) قرار دارد.\n" +
                                "لطفاً برای ذخیره این لیست روی 'Save' تپ کنید، سپس گزینه 'Use CIDR Block' را فعال کرده و فایل ذخیره شده خود را وارد کنید."
                    )
                    .setPositiveButton("OK / تأیید", null)
                    .show()
                return@setOnClickListener
            }

            val finalCleanedList = processTextPayload(rawText)
            if (finalCleanedList.isNotEmpty()) {
                val returnIntent = Intent().apply {
                    putExtra("RESULT_RESOLVERS_LIST", ArrayList(finalCleanedList))
                    putExtra("RESULT_RAW_TEXT", rawText)
                }
                setResult(Activity.RESULT_OK, returnIntent)
                finish()
            } else {
                Toast.makeText(this, "No valid entries found to import.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun processTextPayload(rawText: String): List<String> {
        val parsedLines = rawText.split(Regex("[\\s,;]+"))
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return if (isCidrMode) {
            parsedLines.filter { isValidCidr(it) }
        } else {
            validateAndFilterResolversLocal(parsedLines, tunnelMode)
        }
    }

    private fun handleBackPressWithWarning() {
        if (etInput.text.toString().trim() != initialSnapshot) {
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

    private fun saveToDownloadsFolder(fileName: String, content: String) {
        try {
            val safeName = if (fileName.endsWith(".txt")) fileName else "$fileName.txt"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Phoenix")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    Toast.makeText(this, "Saved to Downloads/Phoenix/$safeName", Toast.LENGTH_LONG).show()
                }
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloadsDir, "Phoenix").apply { if (!exists()) mkdirs() }
                File(dir, safeName).writeText(content)
                Toast.makeText(this, "Saved to Downloads/Phoenix/$safeName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Existing structural matching filters
    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
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

    private fun isValidCidr(cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false
        val prefix = parts[1].toIntOrNull()
        return isValidIpv4(parts[0]) && prefix != null && prefix in 0..32
    }

    private fun validateAndFilterResolversLocal(rawList: List<String>, mode: String): List<String> {
        val sanitized = mutableListOf<String>()
        for (line in rawList) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains("/")) {
                if (isValidCidr(trimmed)) sanitized.add(trimmed)
            } else {
                when (mode.lowercase()) {
                    "doh" -> if (trimmed.startsWith("https://") || isValidIpv4WithOptionalPort(trimmed)) sanitized.add(trimmed)
                    else -> if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && isValidIpv4WithOptionalPort(trimmed)) sanitized.add(trimmed)
                }
            }
        }
        return sanitized
    }
}