package com.nutrilens.core.network

import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.network.dto.ApiErrorEnvelopeDto
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Translates HTTP responses and transport failures into domain outcomes.
 *
 * One place decides what a failure means, so every repository reports the same
 * error for the same cause and the UI can rely on the taxonomy. Server messages
 * are used only where they are safe to display; codes drive behaviour.
 */
class ApiErrorMapper(private val json: Json) {

    /** Run [call], mapping both HTTP errors and transport failures. */
    suspend fun <T : Any> execute(call: suspend () -> Response<T>): Outcome<T> = try {
        val response = call()
        if (response.isSuccessful) {
            val body = response.body()
            when {
                body != null -> Outcome.success(body)
                // A 204 with a Unit-typed call is a success with no body.
                response.code() == 204 -> @Suppress("UNCHECKED_CAST")
                Outcome.success(Unit as T)
                else -> Outcome.failure(AppError.ServerError("EMPTY_RESPONSE"))
            }
        } else {
            Outcome.failure(mapErrorResponse(response))
        }
    } catch (e: UnknownHostException) {
        // No DNS: almost always genuinely offline rather than a server fault.
        Outcome.failure(AppError.Offline)
    } catch (e: SocketTimeoutException) {
        Outcome.failure(AppError.Timeout)
    } catch (e: IOException) {
        Outcome.failure(AppError.Offline)
    } catch (e: Exception) {
        // Serialization failures and the like: a real defect, but the user must
        // still get a usable app rather than a crash.
        Outcome.failure(AppError.ServerError(e::class.simpleName))
    }

    /** Same as [execute] for calls whose success carries no body. */
    suspend fun executeUnit(call: suspend () -> Response<Unit>): Outcome<Unit> = try {
        val response = call()
        if (response.isSuccessful) {
            Outcome.success(Unit)
        } else {
            Outcome.failure(mapErrorResponse(response))
        }
    } catch (e: UnknownHostException) {
        Outcome.failure(AppError.Offline)
    } catch (e: SocketTimeoutException) {
        Outcome.failure(AppError.Timeout)
    } catch (e: IOException) {
        Outcome.failure(AppError.Offline)
    } catch (e: Exception) {
        Outcome.failure(AppError.ServerError(e::class.simpleName))
    }

    private fun mapErrorResponse(response: Response<*>): AppError {
        val envelope = parseEnvelope(response)
        val code = envelope?.error?.code
        val message = envelope?.error?.message

        return when (code) {
            CODE_INVALID_CREDENTIALS -> AppError.InvalidCredentials
            CODE_TOKEN_EXPIRED, CODE_TOKEN_INVALID, CODE_NOT_AUTHENTICATED ->
                AppError.SessionExpired
            CODE_EMAIL_ALREADY_REGISTERED -> AppError.EmailAlreadyRegistered
            CODE_WEAK_PASSWORD -> AppError.WeakPassword(message)
            CODE_INVALID_IMAGE -> AppError.InvalidImage(message)
            CODE_IMAGE_TOO_LARGE -> AppError.ImageTooLarge
            CODE_UNSUPPORTED_MEDIA_TYPE -> AppError.InvalidImage(message)
            CODE_ANALYSIS_FAILED -> AppError.AnalysisFailed(message)
            CODE_NOT_FOUND -> AppError.NotFound
            CODE_RATE_LIMITED -> AppError.RateLimited(retryAfterSeconds(response))
            // Fall back to the status code when the body was missing or
            // unparseable, so a proxy's bare 502 still maps sensibly.
            else -> mapByStatus(response.code(), code)
        }
    }

    private fun mapByStatus(status: Int, code: String?): AppError = when (status) {
        401 -> AppError.SessionExpired
        403 -> AppError.SessionExpired
        404 -> AppError.NotFound
        409 -> AppError.EmailAlreadyRegistered
        413 -> AppError.ImageTooLarge
        415 -> AppError.InvalidImage(null)
        429 -> AppError.RateLimited(DEFAULT_RETRY_AFTER_SECONDS)
        else -> AppError.ServerError(code ?: "HTTP_$status")
    }

    private fun parseEnvelope(response: Response<*>): ApiErrorEnvelopeDto? = try {
        response.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
            json.decodeFromString<ApiErrorEnvelopeDto>(it)
        }
    } catch (e: Exception) {
        // A non-JSON error body (a gateway's HTML page, say) is not itself an
        // error to report; the status code still classifies the failure.
        null
    }

    private fun retryAfterSeconds(response: Response<*>): Int =
        response.headers()[HEADER_RETRY_AFTER]?.toIntOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS

    private companion object {
        const val HEADER_RETRY_AFTER = "Retry-After"
        const val DEFAULT_RETRY_AFTER_SECONDS = 60

        const val CODE_INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val CODE_TOKEN_EXPIRED = "TOKEN_EXPIRED"
        const val CODE_TOKEN_INVALID = "TOKEN_INVALID"
        const val CODE_NOT_AUTHENTICATED = "NOT_AUTHENTICATED"
        const val CODE_EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED"
        const val CODE_WEAK_PASSWORD = "WEAK_PASSWORD"
        const val CODE_INVALID_IMAGE = "INVALID_IMAGE"
        const val CODE_IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
        const val CODE_UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE"
        const val CODE_ANALYSIS_FAILED = "ANALYSIS_FAILED"
        const val CODE_NOT_FOUND = "NOT_FOUND"
        const val CODE_RATE_LIMITED = "RATE_LIMITED"
    }
}
