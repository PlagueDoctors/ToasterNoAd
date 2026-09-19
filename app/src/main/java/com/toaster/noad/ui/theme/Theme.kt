package com.toaster.noad.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * NoAd 主题色板
 * - 默认深色，品牌色（翡翠青）跨深浅色保持一致
 * - 不启用 Material You 动态色彩，确保品牌色稳定
 */
private val NoAdDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldOnContainer,
    secondary = CyanSecondary,
    onSecondary = CyanOnSecondary,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = CyanOnContainer,
    tertiary = AccentTertiary,
    error = CoralError,
    onError = CoralOnError,
    errorContainer = CoralErrorContainer,
    onErrorContainer = CoralOnErrorContainer,
    background = DeepSpace,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = SlateOutline,
    outlineVariant = SlateOutline,
)

private val NoAdLightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryLight,
    onPrimary = EmeraldOnPrimaryLight,
    secondary = CyanSecondaryLight,
    onSecondary = CyanOnSecondaryLight,
    tertiary = AccentTertiary,
    error = CoralErrorLight,
    onError = CoralOnErrorLight,
    background = DeepSpaceLight,
    onBackground = TextPrimaryLight,
    surface = SlateSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SlateSurfaceLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = SlateOutline,
)

@Composable
fun NoAdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) NoAdDarkColorScheme else NoAdLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
