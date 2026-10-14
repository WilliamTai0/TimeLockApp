package com.example.timelock.service.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun FloatingCapsuleContent(
    remainingMsFlow: StateFlow<Long>,
    totalDurationMs: Long,
    onDragDelta: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit = {}
) {
    val remainingMs by remainingMsFlow.collectAsState()
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isIdle by remember { mutableStateOf(false) }

    LaunchedEffect(lastInteractionTime) {
        isIdle = false
        delay(3000L)
        isIdle = true
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isIdle) 0.35f else 0.90f,
        animationSpec = tween(durationMillis = 400),
        label = "capsule_alpha"
    )

    val minutes = (remainingMs / 1000) / 60
    val seconds = (remainingMs / 1000) % 60
    val timeString = String.format("%02d:%02d", minutes, seconds)
    val isUrgent = remainingMs in 1L..60_000L

    val backgroundColor = if (isUrgent) {
        androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
    } else {
        androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val borderColor = if (isUrgent) {
        androidx.compose.material3.MaterialTheme.colorScheme.error
    } else {
        androidx.compose.material3.MaterialTheme.colorScheme.outline
    }

    val textColor = if (isUrgent) {
        androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer
    } else {
        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 8.dp,
        modifier = Modifier
            .padding(4.dp)
            .alpha(animatedAlpha)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    lastInteractionTime = System.currentTimeMillis()
                    var isDrag = false
                    var totalDragX = 0f
                    var totalDragY = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            if (isDrag) onDragEnd()
                            break
                        }

                        if (!change.pressed) {
                            // 手指抬起！
                            lastInteractionTime = System.currentTimeMillis()
                            change.consume()
                            if (!isDrag) {
                                onTap()
                            } else {
                                onDragEnd()
                            }
                            break
                        }

                        val delta = change.positionChange()
                        totalDragX += delta.x
                        totalDragY += delta.y

                        // 超過 12 像素位移判定為拖曳移動
                        if (!isDrag && (kotlin.math.abs(totalDragX) > 12f || kotlin.math.abs(totalDragY) > 12f)) {
                            isDrag = true
                        }

                        if (isDrag) {
                            change.consume()
                            onDragDelta(delta.x.toInt(), delta.y.toInt())
                        }
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (isUrgent) "🔥" else "⏳",
                fontSize = 13.sp
            )
            Text(
                text = timeString,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
