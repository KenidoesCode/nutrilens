package com.nutrilens.core.model

import java.time.LocalTime

/** The kind of eating occasion. */
enum class MealType(val wireValue: String) {
    BREAKFAST("breakfast"),
    LUNCH("lunch"),
    DINNER("dinner"),
    SNACK("snack"),
    BEVERAGE("beverage"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String): MealType =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() } ?: OTHER

        /**
         * A sensible default for a meal being logged at [time].
         *
         * Only ever a pre-selection: the user can always change it, and the
         * stored timestamp -- not this guess -- is what the analytics use.
         */
        fun suggestFor(time: LocalTime): MealType = when (time.hour) {
            in 4..10 -> BREAKFAST
            in 11..15 -> LUNCH
            in 16..17 -> SNACK
            in 18..22 -> DINNER
            else -> SNACK
        }
    }
}
