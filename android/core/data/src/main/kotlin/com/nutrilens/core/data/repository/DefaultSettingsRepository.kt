package com.nutrilens.core.data.repository

import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.data.image.MealImageStore
import com.nutrilens.core.database.dao.FoodCatalogDao
import com.nutrilens.core.database.dao.MealDao
import com.nutrilens.core.database.dao.SyncOperationDao
import com.nutrilens.core.datastore.UserPreferencesStore
import com.nutrilens.core.data.mapper.toDomain
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.repository.AuthRepository
import com.nutrilens.core.model.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSettingsRepository @Inject constructor(
    private val preferences: UserPreferencesStore,
    private val authRepository: AuthRepository,
    private val mealDao: MealDao,
    private val foodCatalogDao: FoodCatalogDao,
    private val syncOperationDao: SyncOperationDao,
    private val imageStore: MealImageStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsRepository {

    override val language: Flow<AppLanguage> = preferences.language

    override val storeImagesRemotely: Flow<Boolean> = preferences.storeImagesRemotely

    override suspend fun setLanguage(language: AppLanguage) {
        preferences.setLanguage(language)
        // Kept in step with the account so a second device, and any
        // server-rendered content, agree with what the user chose here.
        authRepository.pushProfileUpdate(locale = language.tag)
    }

    override suspend fun setStoreImagesRemotely(enabled: Boolean) {
        preferences.setStoreImagesRemotely(enabled)
    }

    override suspend fun clearLocalData() = withContext(ioDispatcher) {
        // Photographs go too. Clearing meal rows while leaving the images on
        // disk would keep the most sensitive part of the record.
        mealDao.clear()
        foodCatalogDao.clear()
        syncOperationDao.clear()
        imageStore.clear()
    }

    /**
     * Serialise every meal on this device.
     *
     * Built from the local database rather than fetched, so an export works
     * offline and includes meals that have not yet reached the server -- which
     * are precisely the ones a user would be most upset to find missing.
     *
     * Image *paths* are deliberately excluded: they are device-local filesystem
     * locations that mean nothing outside this installation, and a file the
     * user may share should not carry them.
     */
    override suspend fun exportDataAsJson(): Outcome<String> = withContext(ioDispatcher) {
        try {
            val meals = mealDao.observeAll().first().map { it.toDomain() }
            val payload = JsonObject(
                mapOf(
                    "schema_version" to JsonPrimitive(EXPORT_SCHEMA_VERSION),
                    "exported_at" to JsonPrimitive(
                        DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()),
                    ),
                    "meal_count" to JsonPrimitive(meals.size),
                    "estimates_are_approximate" to JsonPrimitive(true),
                    "meals" to JsonArray(meals.map(::encodeMeal)),
                ),
            )
            Outcome.success(prettyJson.encodeToString(JsonObject.serializer(), payload))
        } catch (e: Exception) {
            Outcome.failure(AppError.DeviceError("The export could not be produced."))
        }
    }

    private fun encodeMeal(meal: Meal): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive(meal.id),
            "consumed_at" to JsonPrimitive(
                DateTimeFormatter.ISO_INSTANT.format(meal.consumedAt),
            ),
            "timezone" to JsonPrimitive(meal.timeZone.id),
            "meal_type" to JsonPrimitive(meal.mealType.wireValue),
            "notes" to (meal.notes?.let(::JsonPrimitive) ?: JsonNull),
            "total_mass_g" to JsonPrimitive(meal.totalMassGrams),
            "total_energy_kcal" to (meal.totalEnergyKcal?.let(::JsonPrimitive) ?: JsonNull),
            "sync_state" to JsonPrimitive(meal.syncState.name),
            "items" to JsonArray(
                meal.items.map { item ->
                    JsonObject(
                        mapOf(
                            "display_name" to JsonPrimitive(item.displayName),
                            "category" to JsonPrimitive(item.category.wireValue),
                            "estimated_volume_ml" to JsonPrimitive(item.estimatedVolumeMl),
                            "estimated_mass_g" to JsonPrimitive(item.estimatedMassGrams),
                            "density_g_per_ml" to JsonPrimitive(item.densityGramsPerMl),
                            "density_source" to JsonPrimitive(item.densitySource),
                            "is_fallback_density" to JsonPrimitive(item.isFallbackDensity),
                            "recognition_confidence" to
                                JsonPrimitive(item.recognitionConfidence),
                            "portion_confidence" to JsonPrimitive(item.portionConfidence),
                            "energy_kcal" to (item.energyKcal?.let(::JsonPrimitive) ?: JsonNull),
                            "was_user_corrected" to JsonPrimitive(item.wasUserCorrected),
                            "original_mass_g" to
                                (item.originalMassGrams?.let(::JsonPrimitive) ?: JsonNull),
                            "original_display_name" to
                                (item.originalDisplayName?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                },
            ),
        ),
    )

    private companion object {
        const val EXPORT_SCHEMA_VERSION = "1.0.0"

        // Pretty-printed: the file is for a person to read, not for a machine.
        val prettyJson = Json { prettyPrint = true }
    }
}
