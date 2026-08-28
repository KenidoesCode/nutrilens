package com.nutrilens.core.model

/**
 * Human-facing bucketing of a confidence score.
 *
 * The UI must never present an estimate as a measurement, so every numeric
 * confidence also carries a band that the presentation layer can localise and
 * colour. The thresholds match the ML package's bands exactly; a divergence
 * would mean the app and the server disagreed about how sure they are.
 */
enum class ConfidenceBand {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {
        const val MEDIUM_THRESHOLD = 0.55f
        const val HIGH_THRESHOLD = 0.80f

        fun fromScore(score: Float): ConfidenceBand = when {
            score < MEDIUM_THRESHOLD -> LOW
            score < HIGH_THRESHOLD -> MEDIUM
            else -> HIGH
        }
    }
}
