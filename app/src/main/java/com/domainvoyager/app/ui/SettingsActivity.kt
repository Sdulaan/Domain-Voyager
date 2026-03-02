package com.domainvoyager.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.domainvoyager.app.data.AppSettings
import com.domainvoyager.app.databinding.ActivitySettingsBinding
import com.domainvoyager.app.utils.PreferencesManager
import com.domainvoyager.app.utils.TelegramHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PreferencesManager
    private val telegramHelper = TelegramHelper()

    private val repeatIntervals = listOf(15, 30, 60)
    private val repeatLabels = listOf("15 minutes", "30 minutes", "60 minutes")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        prefs = PreferencesManager(this)

        setupRepeatDropdown()
        loadSettings()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnTestTelegram.setOnClickListener { testTelegram() }
    }

    private fun setupRepeatDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, repeatLabels)
        binding.spinnerRepeatInterval.setAdapter(adapter)
        binding.spinnerRepeatInterval.setOnItemClickListener { _, _, position, _ ->
            // position stored implicitly
        }
    }

    private fun loadSettings() {
        val settings = prefs.loadSettings()
        binding.etBotToken.setText(settings.telegramBotToken)
        binding.etChatId.setText(settings.telegramChatId)
        binding.switchAutoRepeat.isChecked = settings.isAutoRepeatEnabled

        val intervalIndex = repeatIntervals.indexOf(settings.repeatIntervalMinutes)
        if (intervalIndex >= 0) {
            binding.spinnerRepeatInterval.setText(repeatLabels[intervalIndex], false)
        } else {
            binding.spinnerRepeatInterval.setText(repeatLabels[0], false)
        }
    }

    private fun saveSettings() {
        val botToken = binding.etBotToken.text.toString().trim()
        val chatId = binding.etChatId.text.toString().trim()
        val autoRepeat = binding.switchAutoRepeat.isChecked

        if (botToken.isBlank()) {
            binding.etBotToken.error = "Bot token is required"
            return
        }
        if (chatId.isBlank()) {
            binding.etChatId.error = "Chat ID is required"
            return
        }

        val selectedLabel = binding.spinnerRepeatInterval.text.toString()
        val intervalIndex = repeatLabels.indexOf(selectedLabel)
        val intervalMinutes = if (intervalIndex >= 0) repeatIntervals[intervalIndex] else 15

        val settings = AppSettings(
            telegramBotToken = botToken,
            telegramChatId = chatId,
            repeatIntervalMinutes = intervalMinutes,
            isAutoRepeatEnabled = autoRepeat
        )
        prefs.saveSettings(settings)
        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show()
    }

    private fun testTelegram() {
        val botToken = binding.etBotToken.text.toString().trim()
        val chatId = binding.etChatId.text.toString().trim()

        if (botToken.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "Enter Bot Token and Chat ID first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnTestTelegram.isEnabled = false
        binding.btnTestTelegram.text = "Testing..."

        lifecycleScope.launch {
            val (success, message) = withContext(Dispatchers.IO) {
                val result = telegramHelper.testConnection(botToken, chatId)
                if (result.first) {
                    // Send a test message
                    telegramHelper.sendMessage(
                        botToken, chatId,
                        "🤖 <b>Domain Voyager</b> - Test message successful! Your bot is connected."
                    )
                }
                result
            }

            binding.btnTestTelegram.isEnabled = true
            binding.btnTestTelegram.text = "Test Connection"
            binding.tvTestResult.text = message
            binding.tvTestResult.visibility = android.view.View.VISIBLE

            val color = if (success) android.graphics.Color.parseColor("#4CAF50")
            else android.graphics.Color.parseColor("#F44336")
            binding.tvTestResult.setTextColor(color)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
