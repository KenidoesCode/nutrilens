package com.nutrilens.core.data.mapper

import com.nutrilens.core.database.entity.MealEntity
import com.nutrilens.core.database.entity.MealItemEntity
import com.nutrilens.core.database.entity.MealWithItems
import com.nutrilens.core.model.FoodCategory
import com.nutrilens.core.model.Meal
import com.nutrilens.core.model.MealItem
import com.nutrilens.core.model.MealType
import com.nutrilens.core.model.SyncState
import com.nutrilens.core.network.dto.MealCreateDto
import com.nutrilens.core.network.dto.MealDto
import com.nutrilens.core.network.dto.MealItemCreateDto
import com.nutrilens.core.network.dto.MealItemDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Translation between the three representations of a meal: the domain model,
 * the Room row and the API payload.
 *
 * All of it lives here so the boundaries stay one-directional -- no entity
 * knows about a DTO, and the domain knows about neither.
 */

// --- database -> domain --------------------------------------------------

fun MealWithItems.toDomain(): Meal = Meal(
    id = meal.id,
    consumedAt = Instant.ofEpochMilli(meal.consumedAtEpochMillis),
    timeZone = parseZone(meal.timeZoneId),
    mealType = MealType.fromWire(meal.mealType),
    items = items.sortedBy { it.position }.map(MealItemEntity::toDomain),
    notes = meal.notes,
    imagePath = meal.localImagePath,
    syncState = parseSyncState(meal.syncState),
    remoteId = meal.remoteId,
    createdAt = Instant.ofEpochMilli(meal.createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(meal.updatedAtEpochMillis),
)

fun MealItemEntity.toDomain(): MealItem = MealItem(
    id = id,
    displayName = displayName,
    foodKey = foodKey,
    category = FoodCategory.fromWireOrDefault(category),
    estimatedVolumeMl = estimatedVolumeMl,
    estimatedMassGrams = estimatedMassGrams,
    densityGramsPerMl = densityGramsPerMl,
    densitySource = densitySource,
    isFallbackDensity = isFallbackDensity,
    recognitionConfidence = recognitionConfidence,
    portionConfidence = portionConfidence,
    portionMethod = portionMethod,
    energyKcal = energyKcal,
    proteinGrams = proteinGrams,
    carbohydrateGrams = carbohydrateGrams,
    fatGrams = fatGrams,
    wasUserCorrected = wasUserCorrected,
    originalMassGrams = originalMassGrams,
    originalDisplayName = originalDisplayName,
)

// --- domain -> database --------------------------------------------------

fun Meal.toEntity(idempotencyKey: String, syncAttempts: Int = 0): MealEntity = MealEntity(
    id = id,
    consumedAtEpochMillis = consumedAt.toEpochMilli(),
    timeZoneId = timeZone.id,
    mealType = mealType.wireValue,
    notes = notes,
    localImagePath = imagePath,
    totalMassGrams = items.takeIf { it.isNotEmpty() }?.let { totalMassGrams },
    totalEnergyKcal = totalEnergyKcal,
    syncState = syncState.name,
    remoteId = remoteId,
    idempotencyKey = idempotencyKey,
    syncAttempts = syncAttempts,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)

fun MealItem.toEntity(mealId: String, position: Int): MealItemEntity = MealItemEntity(
    id = id,
    mealId = mealId,
    displayName = displayName,
    foodKey = foodKey,
    category = category.wireValue,
    estimatedVolumeMl = estimatedVolumeMl,
    estimatedMassGrams = estimatedMassGrams,
    densityGramsPerMl = densityGramsPerMl,
    densitySource = densitySource,
    isFallbackDensity = isFallbackDensity,
    recognitionConfidence = recognitionConfidence,
    portionConfidence = portionConfidence,
    portionMethod = portionMethod,
    energyKcal = energyKcal,
    proteinGrams = proteinGrams,
    carbohydrateGrams = carbohydrateGrams,
    fatGrams = fatGrams,
    wasUserCorrected = wasUserCorrected,
    originalMassGrams = originalMassGrams,
    originalDisplayName = originalDisplayName,
    position = position,
)

// --- network -> domain ---------------------------------------------------

fun MealDto.toDomain(localImagePath: String? = null): Meal = Meal(
    id = id,
    consumedAt = Instant.parse(consumedAt),
    timeZone = parseZone(timezone),
    mealType = MealType.fromWire(mealType),
    items = items.map(MealItemDto::toDomain),
    notes = notes,
    imagePath = localImagePath,
    // Anything that came from the server is by definition already synced.
    syncState = SyncState.SYNCED,
    remoteId = id,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

fun MealItemDto.toDomain(): MealItem = MealItem(
    id = id,
    displayName = displayName,
    foodKey = foodKey,
    category = FoodCategory.fromWireOrDefault(category),
    estimatedVolumeMl = estimatedVolumeMl,
    estimatedMassGrams = estimatedMassGrams,
    densityGramsPerMl = densityGramsPerMl,
    densitySource = densitySource,
    isFallbackDensity = densitySource.startsWith("category-default"),
    recognitionConfidence = recognitionConfidence,
    portionConfidence = portionConfidence,
    energyKcal = energyKcal,
    proteinGrams = proteinGrams,
    carbohydrateGrams = carbohydrateGrams,
    fatGrams = fatGrams,
    wasUserCorrected = wasUserCorrected,
    originalMassGrams = originalMassGrams,
    originalDisplayName = originalDisplayName,
)

// --- domain -> network ---------------------------------------------------

fun Meal.toCreateDto(idempotencyKey: String, predictionId: String? = null): MealCreateDto =
    MealCreateDto(
        consumedAt = ISO_INSTANT.format(consumedAt),
        timezone = timeZone.id,
        mealType = mealType.wireValue,
        items = items.map(MealItem::toCreateDto),
        notes = notes,
        idempotencyKey = idempotencyKey,
        clientRecordedAt = ISO_INSTANT.format(createdAt),
        predictionId = predictionId,
    )

fun MealItem.toCreateDto(): MealItemCreateDto = MealItemCreateDto(
    displayName = displayName,
    category = category.wireValue,
    estimatedVolumeMl = estimatedVolumeMl,
    recognitionConfidence = recognitionConfidence,
    portionConfidence = portionConfidence,
    portionMethod = portionMethod,
    foodKey = foodKey,
    wasUserCorrected = wasUserCorrected,
    originalDisplayName = originalDisplayName,
)

private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * Resolve a stored zone id, falling back to the device's zone.
 *
 * A zone that the device's tzdb does not know (an old database on a new
 * device, or the reverse) must not crash the timeline. Falling back shifts the
 * displayed local time, which is visible and recoverable; throwing would make
 * the meal unreadable.
 */
private fun parseZone(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (e: Exception) {
    ZoneId.systemDefault()
}

/** Tolerate an unrecognised persisted state by treating it as needing upload. */
private fun parseSyncState(value: String): SyncState =
    runCatching { SyncState.valueOf(value) }.getOrDefault(SyncState.PENDING)
