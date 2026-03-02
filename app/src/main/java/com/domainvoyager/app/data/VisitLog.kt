package com.domainvoyager.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "visit_logs")
data class VisitLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val domainUrl: String,
    val visitTime: Long = System.currentTimeMillis(),
    val status: String, // Success, Failed
    val screenshotPath: String = "",
    val telegramSent: Boolean = false,
    val errorMessage: String = ""
)
