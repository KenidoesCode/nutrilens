package com.nutrilens.core.data.repository

import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.common.network.ConnectivityObserver
import com.nutrilens.core.data.mapper.toDomain
import com.nutrilens.core.datastore.UserPreferencesStore
import com.nutrilens.core.model.AnalysisResult
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Outcome
import com.nutrilens.core.model.repository.AnalysisRepository
import com.nutrilens.core.network.ApiErrorMapper
import com.nutrilens.core.network.api.NutriLensApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meal-image analysis.
 *
 * Recognition runs server-side, so this is the one repository that genuinely
 * requires connectivity. It says so plainly rather than queueing: a user
 * waiting for their meal to be analysed needs to know now that it will not
 * happen, so they can log it by hand instead.
 */
@Singleton
class DefaultAnalysisRepository @Inject constructor(
    private val api: NutriLensApi,
    private val errorMapper: ApiErrorMapper,
    private val connectivity: ConnectivityObserver,
    private val preferences: UserPreferencesStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AnalysisRepository {

    override suspend fun analyzeMealImage(imagePath: String): Outcome<AnalysisResult> =
        withContext(ioDispatcher) {
            if (!connectivity.isCurrentlyOnline()) {
                return@withContext Outcome.failure(AppError.Offline)
            }

            val file = File(imagePath)
            if (!file.isFile) {
                return@withContext Outcome.failure(
                    AppError.InvalidImage("The captured image is no longer available."),
                )
            }

            val storeRemotely = preferences.storeImagesRemotely.first()

            val part = MultipartBody.Part.createFormData(
                name = "image",
                filename = file.name,
                body = file.asRequestBody(JPEG_MEDIA_TYPE),
            )

            errorMapper.execute {
                api.analyzeMealImage(
                    image = part,
                    storeImage = storeRemotely.toString().toRequestBody(TEXT_MEDIA_TYPE),
                )
            }.map { it.toDomain() }
        }

    private companion object {
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        val TEXT_MEDIA_TYPE = "text/plain".toMediaType()
    }
}
