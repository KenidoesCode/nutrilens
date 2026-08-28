package com.nutrilens.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncPushOperationDto(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val operation: String,
    val meal: MealCreateDto? = null,
    @SerialName("meal_id") val mealId: String? = null,
)

@Serializable
data class SyncPushRequestDto(
    val operations: List<SyncPushOperationDto>,
)

@Serializable
data class SyncOperationResultDto(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val status: String,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
data class SyncPushResponseDto(
    val results: List<SyncOperationResultDto>,
    val applied: Int,
    val replayed: Int,
    val failed: Int,
    @SerialName("server_time") val serverTime: String,
)

@Serializable
data class SyncPullResponseDto(
    val meals: List<MealDto> = emptyList(),
    @SerialName("deleted_meal_ids") val deletedMealIds: List<String> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("server_time") val serverTime: String,
)

/** The server's uniform error envelope. */
@Serializable
data class ApiErrorEnvelopeDto(
    val error: ApiErrorDto,
)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String,
    val details: Map<String, String>? = null,
)
