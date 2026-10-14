package com.example.timelock.domain.model

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val isRestricted: Boolean,
    val pendingUnlockUntil: Long? = null
)
