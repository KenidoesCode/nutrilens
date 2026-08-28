package com.nutrilens.core.designsystem.theme

import androidx.compose.ui.unit.dp

/** Spacing and sizing constants, so screens do not invent their own. */
object Dimens {
    val spaceExtraSmall = 4.dp
    val spaceSmall = 8.dp
    val spaceMedium = 16.dp
    val spaceLarge = 24.dp
    val spaceExtraLarge = 32.dp

    val cornerSmall = 8.dp
    val cornerMedium = 16.dp
    val cornerLarge = 24.dp

    /**
     * The Material accessibility minimum. Every interactive element meets it,
     * including icon-only buttons, which are the ones usually missed.
     */
    val minimumTouchTarget = 48.dp

    val cameraShutterSize = 76.dp
    val confidenceBarHeight = 6.dp
}
