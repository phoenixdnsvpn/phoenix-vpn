package com.net2share.vaydns

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import mobile.Mobile

class ConfigEditorActivity : AppCompatActivity() {

    private var editingConfigId: String? = null

    // Remember last values for each mode
    private var lastUdp = "8.8.8.8:53"
    private var lastDot = "8.8.8.8:853"
    private var lastDoh = "https://dns.google/dns-query"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

//        Mobile.getDefaultConfigCount()
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_editor)

        toolbar.setNavigationOnClickListener {
            finish() // Closes this window and returns to the Main Menu
        }

//        supportActionBar?.setDisplayHomeAsUpEnabled(true)
//        supportActionBar?.title = "Config parameters"

        val etName = findViewById<EditText>(R.id.et_config_name)
        val etDomain = findViewById<EditText>(R.id.et_domain)
        val etPubkey = findViewById<EditText>(R.id.et_pubkey)
        val etDns = findViewById<EditText>(R.id.et_dns)
        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
        val btnSave = findViewById<Button>(R.id.btn_save_config)

        editingConfigId = intent.getStringExtra("CONFIG_ID")
        val isDefault = editingConfigId?.startsWith("default_") == true

        if (editingConfigId != null) {
//            supportActionBar?.title = "Edit Config"
            if (isDefault) {
                val index = editingConfigId!!.removePrefix("default_").toLongOrNull() ?: 0L
//                etName.setText(DefaultConfigs.getConfigName(index))

                etName.setText(mobile.Mobile.getDefaultConfigName(index))
                etDomain.setText(mobile.Mobile.getDefaultConfigDomain(index))
                etPubkey.setText(mobile.Mobile.getDefaultConfigPubkey(index))

                val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
                val savedDns = prefs.getString("${editingConfigId}_dns", "8.8.8.8:53")
                val savedMode = prefs.getString("${editingConfigId}_mode", "udp")

                etDns.setText(savedDns)
                when(savedMode) {
                    "dot" -> rgMode.check(R.id.rb_tls)
                    "doh" -> rgMode.check(R.id.rb_https)
                    else -> rgMode.check(R.id.rb_udp)
                }

                // Visual protection for defaults
                etName.isEnabled = false
                etDomain.isEnabled = false
                etPubkey.isEnabled = false
                etDomain.setText("----------")
                etPubkey.setText("----------")
                toolbar.title = "Edit Default Parameters"
            } else {
                loadConfigForEditing(editingConfigId!!, etName, etDomain, etPubkey, etDns, rgMode)
                toolbar.title = "Edit Config"
            }
        } else {
//            supportActionBar?.title = "Add New Config"
            // 3. ADD NEW CONFIG MODE
            toolbar.title = "Add New Config"
            // Use your "Last Used" memory variables
            rgMode.check(R.id.rb_udp)
            etDns.setText(lastUdp)
        }

        // Smart mode switching with value memory
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_udp -> etDns.setText(lastUdp)
                R.id.rb_tls -> etDns.setText(lastDot)
                R.id.rb_https -> etDns.setText(lastDoh)
            }
        }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Config name is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val domain = etDomain.text.toString().trim()
            val pubkey = etPubkey.text.toString().trim()
            val dns = etDns.text.toString().trim()

            val mode = when (rgMode.checkedRadioButtonId) {
                R.id.rb_tls -> "dot"
                R.id.rb_https -> "doh"
                else -> "udp"
            }

            // Save the current value for future switching
            when (rgMode.checkedRadioButtonId) {
                R.id.rb_udp -> lastUdp = dns
                R.id.rb_tls -> lastDot = dns
                R.id.rb_https -> lastDoh = dns
            }

            saveOrUpdateConfig(name, domain, pubkey, dns, mode)
            finish()
        }
    }

    private fun loadConfigForEditing(
        id: String,
        etName: EditText,
        etDomain: EditText,
        etPubkey: EditText,
        etDns: EditText,
        rgMode: RadioGroup
    ) {
        val configs = loadAllConfigs(this)
        val config = configs.find { it.id == id } ?: return

        etName.setText(config.name)
        etDomain.setText(config.domain)
        etPubkey.setText(config.pubkey)
        etDns.setText(config.dnsAddress)

        when (config.mode) {
            "dot" -> {
                rgMode.check(R.id.rb_tls)
                lastDot = config.dnsAddress
            }
            "doh" -> {
                rgMode.check(R.id.rb_https)
                lastDoh = config.dnsAddress
            }
            else -> {
                rgMode.check(R.id.rb_udp)
                lastUdp = config.dnsAddress
            }
        }
    }

    private fun saveOrUpdateConfig(name: String, domain: String, pubkey: String, dns: String, mode: String) {

        if (editingConfigId?.startsWith("default_") == true) {
            // Save only DNS and Mode to a separate preference file
            val prefs = getSharedPreferences("DefaultOverrides", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("${editingConfigId}_dns", dns)
                putString("${editingConfigId}_mode", mode)
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
                    obj.put("name", name)
                    obj.put("domain", domain)
                    obj.put("pubkey", pubkey)
                    obj.put("dnsAddress", dns)
                    obj.put("mode", mode)
                    break
                }
            }
        } else {
            val newObj = JSONObject().apply {
                put("id", java.util.UUID.randomUUID().toString())
                put("name", name)
                put("domain", domain)
                put("pubkey", pubkey)
                put("dnsAddress", dns)
                put("mode", mode)
            }
            jsonArray.put(newObj)
        }

        sharedPref.edit().putString("configs", jsonArray.toString()).apply()
//        Toast.makeText(this, "Config saved!", Toast.LENGTH_SHORT).show()
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
                if (!config.isDefault) {
                    val obj = JSONObject().apply {
                        put("id", config.id)
                        put("name", config.name)
                        put("domain", config.domain)
                        put("pubkey", config.pubkey)
                        put("dnsAddress", config.dnsAddress)
                        put("mode", config.mode)
                    }
                    array.put(obj)
                }
            }
            sharedPref.edit().putString("configs", array.toString()).apply()
        }
    }
}