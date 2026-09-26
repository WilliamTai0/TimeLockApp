package com.example.timelock.domain.usecase

import com.example.timelock.data.local.dao.UsageSessionDao
import com.example.timelock.domain.model.UsageStatSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class CalculateStatisticsUseCase(
    private val usageSessionDao: UsageSessionDao
) {
    fun execute(): Flow<UsageStatSummary> {
        return combine(
            usageSessionDao.getAverageExpectedDurationFlow(),
            usageSessionDao.getAverageActualDurationFlow(),
            usageSessionDao.getTotalCompletedSessionsCountFlow(),
            usageSessionDao.getTimeoutSessionsCountFlow()
        ) { avgExpected, avgActual, totalCount, timeoutCount ->
            val rate = if (totalCount == 0) {
                0.0
            } else {
                (timeoutCount.toDouble() / totalCount.toDouble()) * 100.0
            }

            UsageStatSummary(
                averageExpectedDurationMs = avgExpected ?: 0.0,
                averageActualDurationMs = avgActual ?: 0.0,
                totalCompletedSessions = totalCount,
                timeoutCount = timeoutCount,
                timeoutRate = rate
            )
        }
    }
}
