package com.example.ui.theme

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkEmeraldPrimary,
    onPrimary = DarkEmeraldOnPrimary,
    primaryContainer = DarkEmeraldPrimaryContainer,
    onPrimaryContainer = DarkEmeraldOnPrimaryContainer,
    secondary = DarkSlateSecondary,
    onSecondary = DarkSlateOnSecondary,
    secondaryContainer = DarkSlateSecondaryContainer,
    onSecondaryContainer = DarkSlateOnSecondaryContainer,
    tertiary = DarkAmberTertiary,
    onTertiary = DarkAmberOnTertiary,
    tertiaryContainer = DarkAmberTertiaryContainer,
    onTertiaryContainer = DarkAmberOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldPrimaryContainer,
    onPrimaryContainer = EmeraldOnPrimaryContainer,
    secondary = SlateSecondary,
    onSecondary = SlateOnSecondary,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = SlateOnSecondaryContainer,
    tertiary = AmberTertiary,
    onTertiary = AmberOnTertiary,
    tertiaryContainer = AmberTertiaryContainer,
    onTertiaryContainer = AmberOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline
)

@Composable
fun BanglaStoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    brandPrimaryHex: String? = null,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val parsedBrandColor = parseBrandHexOrNull(brandPrimaryHex)
    val colorScheme = if (parsedBrandColor != null) {
        if (darkTheme) {
            val darkPrimary = lerp(parsedBrandColor, Color.White, 0.35f)
            val darkOnPrimary = if (darkPrimary.luminance() > 0.45f) Color(0xFF0A1412) else Color.White
            val darkContainer = lerp(parsedBrandColor, Color(0xFF0D1514), 0.55f)
            val darkOnContainer = lerp(parsedBrandColor, Color.White, 0.82f)
            baseScheme.copy(
                primary = darkPrimary,
                onPrimary = darkOnPrimary,
                primaryContainer = darkContainer,
                onPrimaryContainer = darkOnContainer
            )
        } else {
            val lightOnPrimary = if (parsedBrandColor.luminance() > 0.52f) Color(0xFF101820) else Color.White
            val lightContainer = lerp(parsedBrandColor, Color.White, 0.82f)
            val lightOnContainer = lerp(parsedBrandColor, Color.Black, 0.55f)
            baseScheme.copy(
                primary = parsedBrandColor,
                onPrimary = lightOnPrimary,
                primaryContainer = lightContainer,
                onPrimaryContainer = lightOnContainer
            )
        }
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

fun parseBrandHexOrNull(rawHex: String?): Color? {
    val clean = rawHex?.trim().orEmpty()
    if (clean.isEmpty()) return null
    val normalized = if (clean.startsWith("#")) clean else "#$clean"
    if (normalized.length != 7 && normalized.length != 9) return null
    return runCatching { Color(AndroidColor.parseColor(normalized)) }.getOrNull()
}
