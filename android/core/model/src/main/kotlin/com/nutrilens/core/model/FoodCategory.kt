package com.nutrilens.core.model

/**
 * Physical state of a food item.
 *
 * Drives portion geometry and density lookup, and is the same vocabulary the
 * backend and the ML pipeline use. [wireValue] is the on-the-wire spelling and
 * is deliberately independent of the enum constant name so a Kotlin rename can
 * never silently change the API contract.
 */
enum class FoodCategory(val wireValue: String) {
    SOLID("solid"),
    SEMISOLID("semisolid"),
    LIQUID("liquid"),
    ;

    companion object {
        fun fromWire(value: String): FoodCategory? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }

        /** Falls back to [SOLID] so an unknown category never drops a food item. */
        fun fromWireOrDefault(value: String): FoodCategory = fromWire(value) ?: SOLID
    }
}
