package com.domainvoyager.app.utils

import android.content.Context
import android.content.SharedPreferences
import com.domainvoyager.app.data.AppSettings

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("domain_voyager_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_BOT_TOKEN       = "telegram_bot_token"
        const val KEY_CHAT_ID         = "telegram_chat_id"
        const val KEY_REPEAT_INTERVAL = "repeat_interval_minutes"
        const val KEY_AUTO_REPEAT     = "auto_repeat_enabled"
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().apply {
            putString(KEY_BOT_TOKEN,       settings.telegramBotToken)
            putString(KEY_CHAT_ID,         settings.telegramChatId)
            putInt(KEY_REPEAT_INTERVAL,    settings.repeatIntervalMinutes)
            putBoolean(KEY_AUTO_REPEAT,    settings.isAutoRepeatEnabled)
            apply()
        }
    }

    fun loadSettings(): AppSettings {
        return AppSettings(
            telegramBotToken      = prefs.getString(KEY_BOT_TOKEN, "") ?: "",
            telegramChatId        = prefs.getString(KEY_CHAT_ID, "") ?: "",
            repeatIntervalMinutes = prefs.getInt(KEY_REPEAT_INTERVAL, 15),
            isAutoRepeatEnabled   = prefs.getBoolean(KEY_AUTO_REPEAT, false)
        )
    }

    fun getTelegramBotToken(): String  = prefs.getString(KEY_BOT_TOKEN, "") ?: ""
    fun getTelegramChatId(): String    = prefs.getString(KEY_CHAT_ID, "") ?: ""
    fun getRepeatIntervalMinutes(): Int = prefs.getInt(KEY_REPEAT_INTERVAL, 15)
    fun isAutoRepeatEnabled(): Boolean  = prefs.getBoolean(KEY_AUTO_REPEAT, false)

    fun setAutoRepeat(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_REPEAT, enabled).apply()
    }
}