package com.flamyoad.instafetcher.di

import com.flamyoad.instafetcher.data.local.InstagramCookieManager
import com.flamyoad.instafetcher.data.remote.InstagramService
import com.flamyoad.instafetcher.data.repository.MediaRepository
import com.flamyoad.instafetcher.domain.usecase.DownloadMediaUseCase
import com.flamyoad.instafetcher.domain.usecase.ExtractMediaUrlUseCase
import com.flamyoad.instafetcher.ui.screens.home.HomeViewModel
import com.flamyoad.instafetcher.util.InstagramUrlParser
import com.flamyoad.instafetcher.worker.DownloadWorker
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

    // Services
    single { InstagramService(get(), get(), get()) }

    // Repositories
    single { MediaRepository(get(), get()) }

    // Use Cases
    factory { ExtractMediaUrlUseCase(get()) }
    factory { DownloadMediaUseCase(androidContext(), get()) }

    // ViewModels
    viewModel { HomeViewModel(get(), get(), get(), androidContext(), get()) }

    // Workers
    worker { DownloadWorker(get(), get(), get(), get()) }
}
