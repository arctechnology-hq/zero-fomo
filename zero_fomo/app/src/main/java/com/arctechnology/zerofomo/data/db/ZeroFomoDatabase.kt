package com.arctechnology.zerofomo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [EventEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ZeroFomoDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
}
