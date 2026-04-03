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

class ConfigEditorActivity : AppCompatActivity() {

    private var editingConfigId: String? = null   // null = new config

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        val etName = findViewById<EditText>(R.id.et_config_name)
        val etDomain = findViewById<EditText>(R.id.et_domain)
        val etPubkey = findViewById<EditText>(R.id.et_pubkey)
        val etDns = findViewById<EditText>(R.id.et_dns)
        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)
        val btnSave = findViewById<Button>(R.id.btn_save_config)

        // If opened for editing, pre-fill the fields
        editingConfigId = intent.getStringExtra("CONFIG_ID")
        if (editingConfigId != null) {
            loadConfigForEditing(editingConfigId!!, etName, etDomain, etPubkey, etDns, rgMode)
            supportActionBar?.title = "Edit Config"
        } else {
            supportActionBar?.title = "Add New Config"
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

            saveOrUpdateConfig(name, domain, pubkey, dns, mode)
            finish()   // go back to main screen
        }
    }

    private fun loadConfigForEditing(id: String, etName: EditText, etDomain: EditText,
                                     etPubkey: EditText, etDns: EditText, rgMode: RadioGroup) {
        val configs = loadAllConfigs(this)
        val config = configs.find { it.id == id } ?: return

        etName.setText(config.name)
        etDomain.setText(config.domain)
        etPubkey.setText(config.pubkey)
        etDns.setText(config.dnsAddress)

        when (config.mode) {
            "dot" -> rgMode.check(R.id.rb_tls)
            "doh" -> rgMode.check(R.id.rb_https)
            else -> rgMode.check(R.id.rb_udp)
        }
    }

    private fun saveOrUpdateConfig(name: String, domain: String, pubkey: String,
                                   dns: String, mode: String) {
        val sharedPref = getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
        val jsonArray = JSONArray(sharedPref.getString("configs", "[]"))

        if (editingConfigId != null) {
            // Update existing
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
            // Add new
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
        Toast.makeText(this, "Config saved!", Toast.LENGTH_SHORT).show()
    }

    companion object {
        // Helper used by MainActivity too
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
                        mode = obj.getString("mode")
                    )
                )
            }
            return list
        }

        fun saveAllConfigs(context: Context, configs: List<Config>) {
            val sharedPref = context.getSharedPreferences("VayDNS_Settings", Context.MODE_PRIVATE)
            val array = JSONArray()
            configs.forEach { config ->
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
            sharedPref.edit().putString("configs", array.toString()).apply()
        }
    }
}