package com.example.timelock.domain.model

data class UsageStatSummary(
    val averageExpectedDurationMs: Double = 0.0,
    val averageActualDurationMs: Double = 0.0,
    val totalCompletedSessions: Int = 0,
    val timeoutCount: Int = 0,
    val timeoutRate: Double = 0.0
) {
    val averageExpectedMinutes: Int
        get() = (averageExpectedDurationMs / 60_000).toInt()

    val averageActualMinutes: Int
        get() = (averageActualDurationMs / 60_000).toInt()
}
