package com.example.timelock.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_sessions",
    indices = [Index(value = ["packageName", "isActive"])]
)
data class UsageSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0,
    val packageName: String,
    val expectedDurationMs: Long,
    val actualDurationMs: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val accumulatedUsageMs: Long = 0L,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val lastHeartbeatTime: Long = 0L,
    val isTimeout: Boolean = false,
    val isActive: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val isQuickReply: Boolean = false
)
