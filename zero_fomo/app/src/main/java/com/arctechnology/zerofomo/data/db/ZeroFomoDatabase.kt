package com.arctechnology.zerofomo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [EventEntity::class, FavoriteEntity::class, SubmissionEntity::class],
    version = 3,   // v2: countryCode + market; v3: submissions (cache DB, destructive migration is fine)
    exportSchema = true,
)
abstract class ZeroFomoDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun submissionDao(): SubmissionDao
}
