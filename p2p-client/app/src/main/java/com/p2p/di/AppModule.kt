package com.p2p.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.p2p.data.local.AppDatabase
import com.p2p.data.remote.ApiService
import com.p2p.data.remote.TokenAuthenticator
import com.p2p.domain.signaling.SignalingClient
import com.p2p.domain.webrtc.WebRTCManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Named("baseUrl")
    fun provideBaseUrl(): String = "https://triddov.ru/"

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "p2p_messenger_db"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideUserDao(db: AppDatabase) = db.userDao()

    @Provides
    @Singleton
    fun provideContactDao(db: AppDatabase) = db.contactDao()

    @Provides
    @Singleton
    fun provideChatDao(db: AppDatabase) = db.chatDao()

    @Provides
    @Singleton
    fun provideMessageDao(db: AppDatabase) = db.messageDao()

    @Provides
    @Singleton
    fun provideSignalOwnIdentityDao(db: AppDatabase) = db.signalOwnIdentityDao()

    @Provides
    @Singleton
    fun provideSignalTrustedIdentityDao(db: AppDatabase) = db.signalTrustedIdentityDao()

    @Provides
    @Singleton
    fun provideSignalPreKeyDao(db: AppDatabase) = db.signalPreKeyDao()

    @Provides
    @Singleton
    fun provideSignalSignedPreKeyDao(db: AppDatabase) = db.signalSignedPreKeyDao()

    @Provides
    @Singleton
    fun provideSignalSessionDao(db: AppDatabase) = db.signalSessionDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenAuthenticator: TokenAuthenticator): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(
        okHttpClient: OkHttpClient,
        gson: Gson,
        @Named("baseUrl") baseUrl: String
    ): ApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideWebRTCManager(
        @ApplicationContext context: Context,
        scope: CoroutineScope
    ): WebRTCManager {
        return WebRTCManager(context, scope)
    }

    @Provides
    @Singleton
    fun provideSignalingClient(
        scope: CoroutineScope,
        okHttpClient: OkHttpClient,
        @Named("baseUrl") baseUrl: String
    ): SignalingClient {
        // baseUrl использует https://, для WebSocket нужен wss://
        val wsUrl = baseUrl.replace("https://", "wss://").replace("http://", "ws://")
        return SignalingClient(
            serverUrl = wsUrl.trimEnd('/'),
            scope = scope,
            httpClient = okHttpClient
        )
    }
}
