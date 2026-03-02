package com.domainvoyager.app.data

data class AppSettings(
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val repeatIntervalMinutes: Int = 15,
    val isAutoRepeatEnabled: Boolean = false
)
