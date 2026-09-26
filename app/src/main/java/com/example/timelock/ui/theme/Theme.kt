package com.example.timelock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Base Surfaces - Botanical Obsidian (暖性黑曜石，微調 0.01 chroma 偏向松針深綠，遠離 Slate-900 藍灰)
val ObsidianBackground = Color(0xFF111413)
val ObsidianSurface = Color(0xFF171C1A)
val ObsidianSurfaceVariant = Color(0xFF222926)
val ObsidianSurfaceContainer = Color(0xFF1E2421)
val ObsidianSurfaceContainerHigh = Color(0xFF27302C)
val ObsidianSurfaceContainerHighest = Color(0xFF313B36)

// Brand Primary - Mineral Sage (鼠尾草綠，清醒、自律、自然生長感)
val SagePrimary = Color(0xFF34D399)
val OnSagePrimary = Color(0xFF003824)
val SagePrimaryContainer = Color(0xFF0F4733)
val OnSagePrimaryContainer = Color(0xFFA7F3D0)

// Secondary - Serene Mint
val SageSecondary = Color(0xFF5EEAD4)
val OnSageSecondary = Color(0xFF003831)
val SageSecondaryContainer = Color(0xFF114E45)
val OnSageSecondaryContainer = Color(0xFF99F6E4)

// Tertiary / Warning - Warm Amber (時間提示、冷卻期)
val AmberTertiary = Color(0xFFFBBF24)
val OnAmberTertiary = Color(0xFF422006)
val AmberTertiaryContainer = Color(0xFF713F12)
val OnAmberTertiaryContainer = Color(0xFFFEF3C7)

// Error / Timeout - Terracotta Coral (超時、強制阻斷)
val CoralError = Color(0xFFF87171)
val OnCoralError = Color(0xFF450A0A)
val CoralErrorContainer = Color(0xFF7F1D1D)
val OnCoralErrorContainer = Color(0xFFFEE2E2)

// Typography & Contrast (確保高對比度，徹底消除灰字泛白發飄)
val TextPrimary = Color(0xFFF0F4F1)       // ~14.5:1 on background
val TextSecondary = Color(0xFFA3B0A9)     // ~6.1:1 on background / surface
val TextTertiary = Color(0xFF75837C)      // ~4.5:1 on background
val HairlineBorder = Color(0xFF2E3833)

// App-wide Semantic Palette
object TimeLockPalette {
    val Urgent = CoralError
    val Cooldown = AmberTertiary
    val Disciplined = SagePrimary
    val XiaomiContainer = Color(0xFF341C20)
    val XiaomiBadge = Color(0xFFFB7185)
    val PillBackground = Color(0xFF161B19)
    val PillBorder = Color(0xFF303A35)
}

private val DarkColorScheme = darkColorScheme(
    primary = SagePrimary,
    onPrimary = OnSagePrimary,
    primaryContainer = SagePrimaryContainer,
    onPrimaryContainer = OnSagePrimaryContainer,
    secondary = SageSecondary,
    onSecondary = OnSageSecondary,
    secondaryContainer = SageSecondaryContainer,
    onSecondaryContainer = OnSageSecondaryContainer,
    tertiary = AmberTertiary,
    onTertiary = OnAmberTertiary,
    tertiaryContainer = AmberTertiaryContainer,
    onTertiaryContainer = OnAmberTertiaryContainer,
    error = CoralError,
    onError = OnCoralError,
    errorContainer = CoralErrorContainer,
    onErrorContainer = OnCoralErrorContainer,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    surfaceContainerHighest = ObsidianSurfaceContainerHighest,
    outline = HairlineBorder,
    outlineVariant = Color(0xFF252D29)
)

@Composable
fun TimeLockAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
