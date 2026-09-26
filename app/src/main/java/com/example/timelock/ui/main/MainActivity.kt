package com.example.timelock.ui.main

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.timelock.service.AppMonitorService
import com.example.timelock.ui.permissions.PermissionGuideScreen
import com.example.timelock.ui.theme.TimeLockAppTheme
import com.example.timelock.util.XiaomiPermissionHelper

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val obsidianColor = android.graphics.Color.parseColor("#111413")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(obsidianColor)
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = obsidianColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = false
            isAppearanceLightStatusBars = false
        }

        setContent {
            TimeLockAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var allGranted by remember {
                        mutableStateOf(
                            XiaomiPermissionHelper.hasUsageStatsPermission(this@MainActivity) &&
                                    XiaomiPermissionHelper.hasOverlayPermission(this@MainActivity)
                        )
                    }

                    if (allGranted) {
                        // 啟動前景監控服務
                        AppMonitorService.start(this@MainActivity)
                        MainNavigationScreen(
                            onAllPermissionsGranted = {
                                AppMonitorService.start(this@MainActivity)
                            }
                        )
                    } else {
                        PermissionGuideScreen(
                            onAllPermissionsGranted = {
                                allGranted = true
                                AppMonitorService.start(this@MainActivity)
                            }
                        )
                    }
                }
            }
        }
    }
}
