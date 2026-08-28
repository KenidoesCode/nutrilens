package com.nutrilens.core.network.api

import com.nutrilens.core.network.dto.AnalysisResponseDto
import com.nutrilens.core.network.dto.FoodDto
import com.nutrilens.core.network.dto.LoginRequestDto
import com.nutrilens.core.network.dto.LogoutRequestDto
import com.nutrilens.core.network.dto.MealCreateDto
import com.nutrilens.core.network.dto.MealDto
import com.nutrilens.core.network.dto.MealItemDto
import com.nutrilens.core.network.dto.MealPageDto
import com.nutrilens.core.network.dto.NutritionTotalsDto
import com.nutrilens.core.network.dto.PortionCorrectionDto
import com.nutrilens.core.network.dto.RangePatternDto
import com.nutrilens.core.network.dto.RefreshRequestDto
import com.nutrilens.core.network.dto.RegisterRequestDto
import com.nutrilens.core.network.dto.RenameItemDto
import com.nutrilens.core.network.dto.SyncPullResponseDto
import com.nutrilens.core.network.dto.SyncPushRequestDto
import com.nutrilens.core.network.dto.SyncPushResponseDto
import com.nutrilens.core.network.dto.TokenResponseDto
import com.nutrilens.core.network.dto.UserResponseDto
import com.nutrilens.core.network.dto.UserUpdateRequestDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The versioned HTTP contract.
 *
 * Every method returns `Response<T>` rather than a bare body: the status code
 * and the server's structured error are needed to distinguish "your session
 * expired" from "you are rate limited", and swallowing them into an exception
 * would lose exactly the information the UI needs.
 */
interface NutriLensApi {

    // --- auth ------------------------------------------------------------

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): Response<TokenResponseDto>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): Response<TokenResponseDto>

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): Response<TokenResponseDto>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequestDto): Response<Unit>

    // --- profile ---------------------------------------------------------

    @GET("api/v1/users/me")
    suspend fun getProfile(): Response<UserResponseDto>

    @PATCH("api/v1/users/me")
    suspend fun updateProfile(@Body body: UserUpdateRequestDto): Response<UserResponseDto>

    @DELETE("api/v1/users/me")
    suspend fun deleteAccount(): Response<Unit>

    // --- meals -----------------------------------------------------------

    @POST("api/v1/meals")
    suspend fun createMeal(@Body body: MealCreateDto): Response<MealDto>

    @GET("api/v1/meals")
    suspend fun listMeals(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): Response<MealPageDto>

    @GET("api/v1/meals/{mealId}")
    suspend fun getMeal(@Path("mealId") mealId: String): Response<MealDto>

    @DELETE("api/v1/meals/{mealId}")
    suspend fun deleteMeal(@Path("mealId") mealId: String): Response<Unit>

    @PATCH("api/v1/meals/items/{itemId}/portion")
    suspend fun correctPortion(
        @Path("itemId") itemId: String,
        @Body body: PortionCorrectionDto,
    ): Response<MealItemDto>

    @PATCH("api/v1/meals/items/{itemId}/name")
    suspend fun renameItem(
        @Path("itemId") itemId: String,
        @Body body: RenameItemDto,
    ): Response<MealItemDto>

    @DELETE("api/v1/meals/items/{itemId}")
    suspend fun removeItem(@Path("itemId") itemId: String): Response<Unit>

    // --- analysis --------------------------------------------------------

    /**
     * Analyse a meal photograph.
     *
     * `storeImage` reflects the user's privacy setting: when false the server
     * runs inference and discards the bytes rather than retaining them.
     */
    @Multipart
    @POST("api/v1/analysis/meal-image")
    suspend fun analyzeMealImage(
        @Part image: MultipartBody.Part,
        @Part("store_image") storeImage: RequestBody,
        @Part("reference_name") referenceName: RequestBody? = null,
        @Part("reference_real_area_cm2") referenceRealAreaCm2: RequestBody? = null,
        @Part("reference_image_area_ratio") referenceImageAreaRatio: RequestBody? = null,
    ): Response<AnalysisResponseDto>

    // --- analytics -------------------------------------------------------

    @GET("api/v1/analytics/range")
    suspend fun getRangePattern(
        @Query("start_day") startDay: String,
        @Query("end_day") endDay: String,
    ): Response<RangePatternDto>

    @GET("api/v1/analytics/nutrition")
    suspend fun getNutritionTotals(
        @Query("start_day") startDay: String,
        @Query("end_day") endDay: String,
    ): Response<NutritionTotalsDto>

    // --- catalog ---------------------------------------------------------

    @GET("api/v1/foods")
    suspend fun searchFoods(
        @Query("q") query: String? = null,
        @Query("limit") limit: Int = 200,
    ): Response<List<FoodDto>>

    // --- sync ------------------------------------------------------------

    @POST("api/v1/sync/push")
    suspend fun pushSync(@Body body: SyncPushRequestDto): Response<SyncPushResponseDto>

    @GET("api/v1/sync/pull")
    suspend fun pullSync(
        @Query("since") since: String? = null,
        @Query("limit") limit: Int = 100,
    ): Response<SyncPullResponseDto>
}
