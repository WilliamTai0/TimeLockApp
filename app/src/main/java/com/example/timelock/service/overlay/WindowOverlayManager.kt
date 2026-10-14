package com.example.timelock.service.overlay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.timelock.ui.theme.TimeLockAppTheme
import com.example.timelock.util.XiaomiPermissionHelper
import kotlinx.coroutines.flow.StateFlow

class WindowOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var currentOverlayView: View? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    private var currentFloatingView: View? = null
    private var floatingLifecycleOwner: OverlayLifecycleOwner? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            currentFloatingView?.post {
                recalculateAndSnapFloatingPill()
            }
        }

        override fun onLowMemory() {}
    }

    init {
        try {
            context.registerComponentCallbacks(componentCallbacks)
        } catch (_: Exception) {}
    }

    /**
     * 顯示「設定預期使用時間」視窗 (1-Tap 點選立即開始)
     */
    fun showTimeSetupOverlay(
        packageName: String,
        appName: String,
        onTimeSelected: (Int) -> Unit,
        onCancel: () -> Unit
    ) {
        if (!XiaomiPermissionHelper.hasOverlayPermission(context)) return
        dismissAllOverlays()

        val composeView = createComposeView {
            TimeSetupScreen(
                appName = appName,
                onConfirm = { minutes ->
                    dismissAllOverlays()
                    onTimeSelected(minutes)
                },
                onCancel = {
                    dismissAllOverlays()
                    onCancel()
                }
            )
        }

        addViewToWindow(composeView, isBlocking = false)
    }

    /**
     * 顯示超時全黑阻斷視窗
     */
    fun showBlockingOverlay(
        appName: String,
        onExitClicked: () -> Unit
    ) {
        if (!XiaomiPermissionHelper.hasOverlayPermission(context)) return
        dismissAllOverlays()

        val composeView = createComposeView {
            BlockingScreen(
                appName = appName,
                onExit = {
                    dismissAllOverlays()
                    onExitClicked()
                }
            )
        }

        addViewToWindow(composeView, isBlocking = true)
    }

    /**
     * 顯示全域冷卻阻斷視窗 (支援單一週期 60 秒快速回覆通道)
     */
    fun showCooldownOverlay(
        appName: String,
        remainingCooldownMs: Long,
        allowQuickReply: Boolean,
        onQuickReplyClicked: () -> Unit,
        onExitClicked: () -> Unit
    ) {
        if (!XiaomiPermissionHelper.hasOverlayPermission(context)) return
        dismissAllOverlays()

        val composeView = createComposeView {
            CooldownScreen(
                appName = appName,
                initialRemainingMs = remainingCooldownMs,
                allowQuickReply = allowQuickReply,
                onQuickReply = {
                    dismissAllOverlays()
                    onQuickReplyClicked()
                },
                onExit = {
                    dismissAllOverlays()
                    onExitClicked()
                }
            )
        }

        addViewToWindow(composeView, isBlocking = true)
    }

    /**
     * 顯示每日加鐘券確認視窗
     */
    fun showOvertimeDialog(
        canUseToday: Boolean,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit = {}
    ) {
        if (!XiaomiPermissionHelper.hasOverlayPermission(context)) return
        dismissAllOverlays()

        val composeView = createComposeView {
            OvertimeDialog(
                canUseToday = canUseToday,
                onConfirm = {
                    dismissAllOverlays()
                    onConfirm()
                },
                onDismiss = {
                    dismissAllOverlays()
                    onDismiss()
                }
            )
        }

        addViewToWindow(composeView, isBlocking = false)
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * 重新校正並貼邊懸浮膠囊位置 (螢幕旋轉或尺寸變更時)
     */
    private fun recalculateAndSnapFloatingPill() {
        val view = currentFloatingView ?: return
        val params = floatingParams ?: return
        val (screenWidth, screenHeight) = getRealScreenSize()
        val pillWidth = view.width.takeIf { it > 0 } ?: 260
        val pillHeight = view.height.takeIf { it > 0 } ?: 90

        val midX = screenWidth / 2
        params.x = if (params.x + pillWidth / 2 < midX) 24 else (screenWidth - pillWidth - 24).coerceAtLeast(24)
        params.y = params.y.coerceIn(60, (screenHeight - pillHeight - 60).coerceAtLeast(60))

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    /**
     * 顯示懸浮倒數膠囊 (Single Source of Truth，訂閱 StateFlow)
     */
    fun showFloatingTimer(
        remainingMsFlow: StateFlow<Long>,
        totalDurationMs: Long,
        onCapsuleTap: () -> Unit = {}
    ) {
        if (!XiaomiPermissionHelper.hasOverlayPermission(context)) return
        dismissFloatingTimer()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (screenWidth, _) = getRealScreenSize()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - 260) / 2
            y = 120
        }
        floatingParams = params

        val owner = OverlayLifecycleOwner().apply {
            performRestore(Bundle())
            start()
        }
        floatingLifecycleOwner = owner

        lateinit var composeViewRef: View

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TimeLockAppTheme {
                    FloatingCapsuleContent(
                        remainingMsFlow = remainingMsFlow,
                        totalDurationMs = totalDurationMs,
                        onDragDelta = { dx, dy ->
                            val (currentWidth, currentHeight) = getRealScreenSize()
                            val pillWidth = composeViewRef.width.takeIf { it > 0 } ?: 260
                            val pillHeight = composeViewRef.height.takeIf { it > 0 } ?: 90

                            params.x = (params.x + dx).coerceIn(16, (currentWidth - pillWidth - 16).coerceAtLeast(16))
                            params.y = (params.y + dy).coerceIn(60, (currentHeight - pillHeight - 60).coerceAtLeast(60))
                            try {
                                windowManager.updateViewLayout(composeViewRef, params)
                            } catch (_: Exception) {}
                        },
                        onDragEnd = {
                            val (currentWidth, _) = getRealScreenSize()
                            val pillWidth = composeViewRef.width.takeIf { it > 0 } ?: 260
                            val midX = currentWidth / 2

                            params.x = if (params.x + pillWidth / 2 < midX) 24 else currentWidth - pillWidth - 24
                            try {
                                windowManager.updateViewLayout(composeViewRef, params)
                            } catch (_: Exception) {}
                        },
                        onTap = onCapsuleTap
                    )
                }
            }
        }
        composeViewRef = composeView

        try {
            windowManager.addView(composeView, params)
            currentFloatingView = composeView
        } catch (_: Exception) {}
    }

    fun dismissFloatingTimer() {
        currentFloatingView?.let { view ->
            try {
                windowManager.removeView(view)
                floatingLifecycleOwner?.destroy()
            } catch (_: Exception) {}
            currentFloatingView = null
            floatingLifecycleOwner = null
            floatingParams = null
        }
    }

    fun dismissAllOverlays() {
        dismissFloatingTimer()
        currentOverlayView?.let { view ->
            try {
                windowManager.removeView(view)
                overlayLifecycleOwner?.destroy()
            } catch (_: Exception) {}
            currentOverlayView = null
            overlayLifecycleOwner = null
        }
    }

    fun release() {
        try {
            context.unregisterComponentCallbacks(componentCallbacks)
        } catch (_: Exception) {}
        dismissAllOverlays()
    }

    private fun addViewToWindow(view: View, isBlocking: Boolean) {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = if (isBlocking) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(view, params)
            currentOverlayView = view
        } catch (_: Exception) {}
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView {
        val owner = OverlayLifecycleOwner().apply {
            performRestore(Bundle())
            start()
        }
        overlayLifecycleOwner = owner

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TimeLockAppTheme {
                    content()
                }
            }
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun start() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }

        fun performRestore(savedState: Bundle?) {
            savedStateRegistryController.performRestore(savedState)
        }
    }
}
