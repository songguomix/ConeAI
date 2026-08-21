package com.cone.agent.di

import com.cone.agent.BuildConfig
import com.cone.agent.data.remote.CleartextCredentialGuard
import com.cone.agent.data.remote.LlmApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // Never let a credential travel unencrypted to a public address (cleartext stays allowed
            // app-wide only for local/LAN model servers). Inherited by every client derived from this
            // one via newBuilder(), including the desktop-remote client.
            .addNetworkInterceptor(CleartextCredentialGuard())
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    // Keep secrets out of logcat even in debug builds.
                    redactHeader("Authorization")
                    redactHeader("x-api-key")
                    redactHeader("x-remote-pass")
                },
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            // Placeholder base url; every call supplies a full @Url so any provider works.
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideLlmApi(retrofit: Retrofit): LlmApi = retrofit.create(LlmApi::class.java)
}
