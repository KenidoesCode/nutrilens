package com.nutrilens.core.designsystem.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Slate40,
    onSecondary = Color.White,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate10,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = NeutralLight,
    onBackground = NeutralDark,
    surface = NeutralLight,
    onSurface = NeutralDark,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = Slate10,
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green10,
    primaryContainer = Green30,
    onPrimaryContainer = Green90,
    secondary = Slate80,
    onSecondary = Slate10,
    secondaryContainer = Slate40,
    onSecondaryContainer = Slate90,
    tertiary = Amber80,
    onTertiary = Amber10,
    tertiaryContainer = Amber40,
    onTertiaryContainer = Amber90,
    error = Red80,
    onError = Red10,
    errorContainer = Red40,
    onErrorContainer = Red90,
    background = NeutralDark,
    onBackground = NeutralLight,
    surface = NeutralDark,
    onSurface = NeutralLight,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = Slate80,
)

/** Confidence colours resolved for the active theme. */
data class NutriLensConfidenceColors(
    val high: Color,
    val medium: Color,
    val low: Color,
)

val LocalConfidenceColors = staticCompositionLocalOf {
    NutriLensConfidenceColors(
        high = ConfidenceColors.highLight,
        medium = ConfidenceColors.mediumLight,
        low = ConfidenceColors.lowLight,
    )
}

@Composable
fun NutriLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You colours are off by default.
     *
     * A wallpaper-derived palette can put the confidence colours arbitrarily
     * close together, and those carry meaning here. Correctness of the signal
     * outranks personalisation.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    val confidenceColors = if (darkTheme) {
        NutriLensConfidenceColors(
            high = ConfidenceColors.highDark,
            medium = ConfidenceColors.mediumDark,
            low = ConfidenceColors.lowDark,
        )
    } else {
        NutriLensConfidenceColors(
            high = ConfidenceColors.highLight,
            medium = ConfidenceColors.mediumLight,
            low = ConfidenceColors.lowLight,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalConfidenceColors provides confidenceColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NutriLensTypography,
            content = content,
        )
    }
}
