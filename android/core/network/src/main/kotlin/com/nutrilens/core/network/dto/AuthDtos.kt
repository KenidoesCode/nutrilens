package com.nutrilens.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types.
 *
 * Kept separate from the domain model on purpose: the API's shape is the
 * server's to change, and a rename there must not ripple into the UI. Mappers
 * translate at this boundary and nowhere else.
 */

@Serializable
data class RegisterRequestDto(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
    val timezone: String,
    val locale: String,
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class LogoutRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("access_expires_at") val accessExpiresAt: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
)

@Serializable
data class UserResponseDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
    val timezone: String,
    val locale: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class UserUpdateRequestDto(
    @SerialName("display_name") val displayName: String? = null,
    val timezone: String? = null,
    val locale: String? = null,
)
