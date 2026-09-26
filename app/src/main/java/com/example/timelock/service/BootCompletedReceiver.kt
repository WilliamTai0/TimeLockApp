package com.example.timelock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.timelock.util.XiaomiPermissionHelper

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // 只有在已經擁有 UsageStats 與 Overlay 權限時才自動重新啟動監控服務
            if (XiaomiPermissionHelper.hasUsageStatsPermission(context) &&
                XiaomiPermissionHelper.hasOverlayPermission(context)
            ) {
                AppMonitorService.start(context)
            }
        }
    }
}
