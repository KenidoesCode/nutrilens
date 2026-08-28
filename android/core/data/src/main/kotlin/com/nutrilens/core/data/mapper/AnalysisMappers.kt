package com.nutrilens.core.data.mapper

import com.nutrilens.core.database.entity.FoodCatalogEntity
import com.nutrilens.core.model.AnalysisResult
import com.nutrilens.core.model.AnalyzedItem
import com.nutrilens.core.model.AppLanguage
import com.nutrilens.core.model.FoodCatalogItem
import com.nutrilens.core.model.FoodCategory
import com.nutrilens.core.model.UserProfile
import com.nutrilens.core.network.dto.AnalysisItemDto
import com.nutrilens.core.network.dto.AnalysisResponseDto
import com.nutrilens.core.network.dto.FoodDto
import com.nutrilens.core.network.dto.UserResponseDto
import java.time.ZoneId

fun AnalysisResponseDto.toDomain(): AnalysisResult = AnalysisResult(
    predictionId = predictionId,
    items = items.map(AnalysisItemDto::toDomain),
    engine = engine,
    modelVersion = modelVersion,
    processingMillis = processingMillis,
    warnings = warnings,
)

fun AnalysisItemDto.toDomain(): AnalyzedItem = AnalyzedItem(
    name = name,
    category = FoodCategory.fromWireOrDefault(category),
    confidence = confidence,
    estimatedVolumeMl = estimatedVolumeMl,
    estimatedMassGrams = estimatedMassGrams,
    portionConfidence = portionConfidence,
    portionMethod = portionMethod,
    overallConfidence = overallConfidence,
    densityGramsPerMl = densityGramsPerMl,
    densitySource = densitySource,
    isFallbackDensity = isFallbackDensity,
    energyKcal = nutrition?.energyKcal,
    proteinGrams = nutrition?.proteinGrams,
    carbohydrateGrams = nutrition?.carbohydrateGrams,
    fatGrams = nutrition?.fatGrams,
)

fun UserResponseDto.toDomain(): UserProfile = UserProfile(
    id = id,
    email = email,
    displayName = displayName,
    timeZone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault()),
    language = AppLanguage.fromTag(locale),
)

fun FoodDto.toEntity(cachedAtEpochMillis: Long): FoodCatalogEntity = FoodCatalogEntity(
    foodKey = foodKey,
    displayName = displayName,
    displayNameTelugu = displayNameTelugu,
    category = category,
    // The catalog can hold several densities for one food from different
    // sources; the cache keeps the most confident, which is what the offline
    // estimate should use.
    densityGramsPerMl = densities.maxByOrNull { it.confidence }?.densityGramsPerMl ?: 1.0,
    energyKcalPer100g = energyKcalPer100g,
    proteinGramsPer100g = proteinGramsPer100g,
    carbohydrateGramsPer100g = carbohydrateGramsPer100g,
    fatGramsPer100g = fatGramsPer100g,
    cachedAtEpochMillis = cachedAtEpochMillis,
)

fun FoodCatalogEntity.toDomain(): FoodCatalogItem = FoodCatalogItem(
    foodKey = foodKey,
    displayName = displayName,
    displayNameTelugu = displayNameTelugu,
    category = FoodCategory.fromWireOrDefault(category),
    densityGramsPerMl = densityGramsPerMl,
    energyKcalPer100g = energyKcalPer100g,
)
