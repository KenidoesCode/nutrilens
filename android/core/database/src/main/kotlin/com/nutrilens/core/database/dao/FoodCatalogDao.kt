package com.nutrilens.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.nutrilens.core.database.entity.FoodCatalogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodCatalogDao {

    /**
     * Substring search over both display names.
     *
     * Telugu is matched as well as English so a user working in Telugu can find
     * a food by the name they actually read on screen.
     */
    @Query(
        """
        SELECT * FROM food_catalog
        WHERE :query = ''
           OR displayName LIKE '%' || :query || '%'
           OR displayNameTelugu LIKE '%' || :query || '%'
           OR foodKey LIKE '%' || :query || '%'
        ORDER BY displayName ASC
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 50): Flow<List<FoodCatalogEntity>>

    @Query("SELECT * FROM food_catalog WHERE foodKey = :foodKey")
    suspend fun getByKey(foodKey: String): FoodCatalogEntity?

    @Query("SELECT COUNT(*) FROM food_catalog")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entries: List<FoodCatalogEntity>)

    @Query("DELETE FROM food_catalog")
    suspend fun clear()
}
