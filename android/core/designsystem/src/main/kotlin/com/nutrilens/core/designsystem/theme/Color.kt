package com.nutrilens.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * Confidence colours are the load-bearing part: they are how the interface
 * tells the truth about uncertainty at a glance. They are never used alone --
 * every confidence indicator also carries text and a content description, so
 * the meaning survives colour blindness and screen readers.
 */

internal val Green40 = Color(0xFF2E6B4F)
internal val Green80 = Color(0xFF9FD5B8)
internal val Green90 = Color(0xFFBFEBD3)
internal val Green10 = Color(0xFF00210F)
internal val Green30 = Color(0xFF14523A)

internal val Amber40 = Color(0xFF7A5900)
internal val Amber80 = Color(0xFFF0C048)
internal val Amber90 = Color(0xFFFFDF9E)
internal val Amber10 = Color(0xFF261A00)

internal val Slate40 = Color(0xFF4A6357)
internal val Slate80 = Color(0xFFB1CCBD)
internal val Slate90 = Color(0xFFCDE9D8)
internal val Slate10 = Color(0xFF072016)

internal val Red40 = Color(0xFFB3261E)
internal val Red80 = Color(0xFFF2B8B5)
internal val Red90 = Color(0xFFF9DEDC)
internal val Red10 = Color(0xFF410E0B)

internal val NeutralLight = Color(0xFFFBFDF8)
internal val NeutralDark = Color(0xFF191C1A)
internal val SurfaceLight = Color(0xFFF3F5F1)
internal val SurfaceDark = Color(0xFF232825)

/** Colours that encode how sure the app is about a number. */
object ConfidenceColors {
    val highLight = Color(0xFF2E6B4F)
    val highDark = Color(0xFF9FD5B8)

    val mediumLight = Color(0xFF8A6100)
    val mediumDark = Color(0xFFF0C048)

    val lowLight = Color(0xFF8E4B2E)
    val lowDark = Color(0xFFFFB59A)
}
