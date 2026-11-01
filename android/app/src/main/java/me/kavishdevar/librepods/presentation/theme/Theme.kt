/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

val ColorScheme.sectionHeader: Color
    get() = onBackground.copy(alpha = 0.6f)

private val AppleDarkColorScheme = darkColorScheme(
    surfaceContainer = Color(0xFF000000), // for some reason background is not used as the background in gmail and settings app, but surfacecontainer, so using that
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceDim = Color(0x40888888),
    primary = Color(0xFF0091FF),
    secondaryContainer = Color(0xFF366AA8),
    onSecondaryContainer = Color(0xFF0091FF),
    onPrimary = Color(0xFFFFFFFF)
)

private val AppleLightColorScheme = lightColorScheme(
    surfaceContainer = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceDim = Color(0x40D9D9D9),
    secondaryContainer = Color(0xFF6BC0FF),
    onSecondaryContainer = Color(0xFF0088FF),
    primary = Color(0xFF0088FF),
    onPrimary = Color(0xFFFFFFFF)
)

@Composable
fun LibrePodsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    m3eEnabled: Boolean? = null,
    designSystem: DesignSystem = when (m3eEnabled) {
        true -> DesignSystem.Material
        false -> DesignSystem.Apple
        null -> DesignSystem.Miuix
    },
    content: @Composable () -> Unit
) {
    val miuixColors = if (darkTheme) miuixDarkColorScheme() else miuixLightColorScheme()
    val colorScheme = when {
        designSystem == DesignSystem.Miuix -> {
            // Bridge legacy Material surfaces to the same palette as native MIUIx controls.
            (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.tertiaryContainer,
                onPrimaryContainer = miuixColors.onTertiaryContainer,
                secondary = miuixColors.primary,
                onSecondary = miuixColors.onPrimary,
                secondaryContainer = miuixColors.secondaryVariant,
                onSecondaryContainer = miuixColors.onSecondaryVariant,
                background = miuixColors.surface,
                onBackground = miuixColors.onSurface,
                surface = miuixColors.background,
                onSurface = miuixColors.onBackground,
                surfaceVariant = miuixColors.secondaryVariant,
                onSurfaceVariant = miuixColors.onSurfaceVariantSummary,
                surfaceContainer = miuixColors.surface,
                surfaceContainerLow = miuixColors.background,
                surfaceContainerHigh = miuixColors.surfaceContainerHigh,
                surfaceContainerHighest = miuixColors.surfaceContainerHighest,
                outline = miuixColors.outline,
                outlineVariant = miuixColors.dividerLine,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer
            )
        }
        designSystem == DesignSystem.Material -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> AppleDarkColorScheme
        else -> AppleLightColorScheme
    }

    val miuixTextStyles = remember { defaultTextStyles() }
    val typography = when (designSystem) {
        DesignSystem.Apple -> AppleTypography
        DesignSystem.Material -> MaterialTypography
        DesignSystem.Miuix -> MaterialTypography.copy(
            bodyLarge = miuixTextStyles.main,
            bodyMedium = miuixTextStyles.body1,
            bodySmall = miuixTextStyles.body2,
            titleLarge = miuixTextStyles.title1,
            titleMedium = miuixTextStyles.title3,
            titleSmall = miuixTextStyles.title4,
            labelLarge = miuixTextStyles.button,
            labelMedium = miuixTextStyles.body1,
            labelSmall = miuixTextStyles.footnote1
        )
    }
    CompositionLocalProvider(LocalDesignSystem provides designSystem) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = typography
        ) {
            // Keep MIUIx's interaction feedback inside the legacy Material bridge.
            if (designSystem == DesignSystem.Miuix) {
                MiuixTheme(colors = miuixColors, textStyles = miuixTextStyles, content = content)
            } else content()
        }
    }
}
