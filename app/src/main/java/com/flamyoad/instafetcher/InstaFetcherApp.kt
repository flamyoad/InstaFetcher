package com.flamyoad.instafetcher

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.flamyoad.instafetcher.di.appModule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

class InstaFetcherApp : Application(), Configuration.Provider, ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidLogger()
            androidContext(this@InstaFetcherApp)
            workManagerFactory()
            modules(appModule)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val originalRequest = chain.request()
                        val newRequest: Request = originalRequest.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            .header("Referer", "https://www.instagram.com/")
                            .build()
                        chain.proceed(newRequest)
                    }
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
