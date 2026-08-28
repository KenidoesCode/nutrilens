package com.nutrilens.core.database.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * A meal and its items, fetched in one query.
 *
 * Room resolves the relation with a second batched query rather than a join,
 * so a meal list never degenerates into one query per row.
 */
data class MealWithItems(
    @Embedded val meal: MealEntity,
    @Relation(parentColumn = "id", entityColumn = "mealId")
    val items: List<MealItemEntity>,
)
