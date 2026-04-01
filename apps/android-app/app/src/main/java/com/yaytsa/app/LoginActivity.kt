package com.yaytsa.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yaytsa.app.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            EncryptedSharedPreferences.create(
                this,
                "yaytsa_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Corrupted keystore — delete and recreate
            getSharedPreferences("yaytsa_prefs", MODE_PRIVATE).edit().clear().apply()
            deleteSharedPreferences("yaytsa_prefs")
            EncryptedSharedPreferences.create(
                this,
                "yaytsa_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sharedPreferences = createEncryptedPrefs()

        val isRemembered = sharedPreferences.getBoolean("remember", true)
        binding.cbRemember.isChecked = isRemembered
        if (isRemembered) {
            val savedHost = sharedPreferences.getString("host", "") ?: ""
            binding.etHost.setText(savedHost)

            // Auto-connect if host is saved
            if (savedHost.isNotEmpty()) {
                startActivity(Intent(this, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_HOST_URL, savedHost)
                })
                finish()
                return
            }
        }

        binding.btnConnect.setOnClickListener {
            val host = binding.etHost.text.toString().trim()

            if (host.isEmpty()) {
                Toast.makeText(this, R.string.error_empty_host, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var finalHost = host
            if (!finalHost.startsWith("http://") && !finalHost.startsWith("https://")) {
                finalHost = "https://${'$'}finalHost"
            }

            val remember = binding.cbRemember.isChecked

            sharedPreferences.edit().apply {
                putBoolean("remember", remember)
                if (remember) {
                    putString("host", finalHost)
                } else {
                    remove("host")
                }
                apply()
            }

            startActivity(Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_HOST_URL, finalHost)
            })
            finish()
        }
    }
}
