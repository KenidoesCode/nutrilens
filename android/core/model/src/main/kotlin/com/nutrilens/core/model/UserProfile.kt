package com.nutrilens.core.model

import java.time.ZoneId

/** The signed-in account, as the app needs it. */
data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String?,
    val timeZone: ZoneId,
    val language: AppLanguage,
)

/** Languages the interface ships in. */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    TELUGU("te"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag?.take(2)?.lowercase() } ?: ENGLISH
    }
}

/** A food from the catalog, used when correcting a misidentified item. */
data class FoodCatalogItem(
    val foodKey: String,
    val displayName: String,
    val displayNameTelugu: String?,
    val category: FoodCategory,
    val densityGramsPerMl: Double,
    val energyKcalPer100g: Double?,
) {
    fun localisedName(language: AppLanguage): String = when (language) {
        AppLanguage.TELUGU -> displayNameTelugu ?: displayName
        AppLanguage.ENGLISH -> displayName
    }
}
