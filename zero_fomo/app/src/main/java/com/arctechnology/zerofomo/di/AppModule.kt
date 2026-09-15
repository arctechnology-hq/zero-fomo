package com.arctechnology.zerofomo.di

import android.content.Context
import androidx.room.Room
import com.arctechnology.zerofomo.BuildConfig
import com.arctechnology.zerofomo.data.db.EventDao
import com.arctechnology.zerofomo.data.db.ZeroFomoDatabase
import com.arctechnology.zerofomo.data.location.Gazetteer
import com.arctechnology.zerofomo.data.location.GeocodingService
import com.arctechnology.zerofomo.data.location.PhotonApi
import com.arctechnology.zerofomo.data.location.PhotonGeocoder
import com.arctechnology.zerofomo.data.network.EventsApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ZeroFomoDatabase =
        Room.databaseBuilder(context, ZeroFomoDatabase::class.java, "zerofomo.db")
            .fallbackToDestructiveMigration()   // cache DB: feed re-syncs it
            .build()

    @Provides
    fun eventDao(db: ZeroFomoDatabase): EventDao = db.eventDao()

    @Provides
    @Singleton
    fun prefs(@ApplicationContext context: Context): android.content.SharedPreferences =
        context.getSharedPreferences("zerofomo_meta", Context.MODE_PRIVATE)

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides
    @Singleton
    fun eventsApi(client: OkHttpClient): EventsApi {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        return Retrofit.Builder()
            .baseUrl(BuildConfig.FEED_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EventsApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object GeoModule {
    /** Offline world gazetteer from the bundled assets. */
    @Provides
    @Singleton
    fun gazetteer(@ApplicationContext context: Context): Gazetteer =
        Gazetteer { path -> context.assets.open(path) }

    @Provides
    @Singleton
    fun photonApi(client: OkHttpClient): PhotonApi {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        return Retrofit.Builder()
            .baseUrl("https://photon.komoot.io/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PhotonApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    /** Postal codes and admin areas the offline gazetteer cannot answer go
     *  to Photon (OSM). Bind [NoOpGeocoder] instead for a fully offline build. */
    @Binds
    abstract fun geocoder(impl: PhotonGeocoder): GeocodingService
}
