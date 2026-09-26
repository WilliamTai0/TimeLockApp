package com.example.timelock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "restricted_apps")
data class RestrictedAppEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isRestricted: Boolean = true,
    val addedAt: Long = System.currentTimeMillis()
)
