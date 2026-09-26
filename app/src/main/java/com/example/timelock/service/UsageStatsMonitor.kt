package com.example.timelock.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

class UsageStatsMonitor(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // 跨輪詢週期保存的狀態：精準追蹤每個 Package 內部活躍的 Activity 數量
    private val resumedActivitiesCount = mutableMapOf<String, MutableSet<String>>()
    private var currentlyResumedPackage: String = "com.timelock.home_or_background"

    /**
     * 輪詢前景運行的 App 包名。
     * 改為狀態機串流模式，確保能精準捕捉內部換頁與滑動退出桌面的事件。
     */
    fun observeForegroundPackage(intervalMs: Long = 500L): Flow<String> = flow {
        var lastEmittedPackage = ""
        var lastQueriedTime = System.currentTimeMillis() - 2000L // 初始查詢過去 2 秒

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val usageEvents = usageStatsManager.queryEvents(lastQueriedTime, now)
            lastQueriedTime = now // 更新下一次的起點，確保每個事件只處理一次

            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val cls = event.className ?: continue
                val type = event.eventType

                val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND
                } else {
                    type == UsageEvents.Event.MOVE_TO_FOREGROUND
                }

                val isBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    type == UsageEvents.Event.ACTIVITY_PAUSED ||
                            type == UsageEvents.Event.ACTIVITY_STOPPED ||
                            type == UsageEvents.Event.MOVE_TO_BACKGROUND
                } else {
                    type == UsageEvents.Event.MOVE_TO_BACKGROUND
                }

                if (isForeground) {
                    val set = resumedActivitiesCount.getOrPut(pkg) { mutableSetOf() }
                    set.add(cls)
                    currentlyResumedPackage = pkg
                } else if (isBackground) {
                    val set = resumedActivitiesCount.getOrPut(pkg) { mutableSetOf() }
                    set.remove(cls)
                    if (pkg == currentlyResumedPackage && set.isEmpty()) {
                        // 當前前景 App 的所有畫面都退出了，代表真正離開了該 App
                        currentlyResumedPackage = "com.timelock.home_or_background"
                    }
                }
            }

            if (currentlyResumedPackage != lastEmittedPackage && currentlyResumedPackage != context.packageName) {
                lastEmittedPackage = currentlyResumedPackage
                emit(currentlyResumedPackage)
            }

            delay(intervalMs)
        }
    }.flowOn(Dispatchers.Default)
}
