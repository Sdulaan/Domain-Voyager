package com.domainvoyager.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "domains")
data class Domain(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val url: String,
    val isActive: Boolean = true,
    val lastVisited: Long = 0L,
    val visitCount: Int = 0,
    val status: String = "Pending" // Pending, Visiting, Success, Failed
)
