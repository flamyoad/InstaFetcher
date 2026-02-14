package com.sakanaclient.cloud.di

import com.sakanaclient.cloud.data.local.InstagramCookieManager
import com.sakanaclient.cloud.data.local.DownloadPreferences
import com.sakanaclient.cloud.data.remote.InstagramService
import com.sakanaclient.cloud.data.repository.MediaRepository
import com.sakanaclient.cloud.domain.usecase.DownloadMediaUseCase
import com.sakanaclient.cloud.domain.usecase.ExtractMediaUrlUseCase
import com.sakanaclient.cloud.ui.screens.home.HomeViewModel
import com.sakanaclient.cloud.ui.screens.settings.SettingsViewModel
import com.sakanaclient.cloud.util.InstagramUrlParser
import com.sakanaclient.cloud.worker.DownloadWorker
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

val appModule = module {
    // Json serializer
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    // HttpClient
    single {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(get())
            }
            install(Logging) {
                level = LogLevel.BODY
            }
            engine {
                connectTimeout = 30_000
                socketTimeout = 30_000
            }
        }
    }

    // Utilities
    single { InstagramUrlParser() }
    
    // Cookie Manager (secure storage for Instagram session)
    single { InstagramCookieManager(androidContext()) }
    
    // Download Preferences (for download settings)
    single { DownloadPreferences(androidContext()) }

    // Services
    single { InstagramService(get(), get(), get()) }

    // Repositories
    single { MediaRepository(get(), get()) }

    // Use Cases
    factory { ExtractMediaUrlUseCase(get()) }
    factory { DownloadMediaUseCase(androidContext(), get()) }

    // ViewModels
    viewModel { HomeViewModel(get(), get(), get(), androidContext(), get(), get()) }
    viewModel { SettingsViewModel(get()) }

    // Workers
    worker { DownloadWorker(get(), get(), get(), get(), get()) }
}
