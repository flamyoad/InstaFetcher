package com.sakanaclient.cloud.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.sakanaclient.cloud.data.model.MediaType
import com.sakanaclient.cloud.data.local.DownloadPreferences
import com.sakanaclient.cloud.domain.usecase.DownloadMediaUseCase
import com.sakanaclient.cloud.domain.usecase.DownloadResult
import com.sakanaclient.cloud.domain.usecase.ExtractMediaUrlUseCase
import com.sakanaclient.cloud.data.model.MediaResult
import kotlinx.coroutines.delay

/**
 * WorkManager worker for downloading Instagram media in the background.
 * Supports progress notifications and reliable downloads even when app is closed.
 */
class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters,
    private val extractMediaUrlUseCase: ExtractMediaUrlUseCase,
    private val downloadMediaUseCase: DownloadMediaUseCase,
    private val downloadPreferences: DownloadPreferences
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_SHORTCODE = "shortcode"
        const val KEY_DOWNLOAD_ALL = "download_all"
        const val KEY_CAROUSEL_INDEX = "carousel_index"
        
        const val KEY_RESULT_SUCCESS = "success"
        const val KEY_RESULT_MESSAGE = "message"
        const val KEY_RESULT_FILENAME = "filename"
        
        private const val NOTIFICATION_CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)
        val shortcode = inputData.getString(KEY_SHORTCODE)
        val downloadAll = inputData.getBoolean(KEY_DOWNLOAD_ALL, false)
        val carouselIndex = inputData.getInt(KEY_CAROUSEL_INDEX, -1)
        
        if (url.isNullOrBlank() && shortcode.isNullOrBlank()) {
            return Result.failure(
                Data.Builder()
                    .putBoolean(KEY_RESULT_SUCCESS, false)
                    .putString(KEY_RESULT_MESSAGE, "No URL or shortcode provided")
                    .build()
            )
        }
        
        // Show foreground notification
        setForeground(createForegroundInfo("Starting download..."))
        
        return try {
            // Fetch media info
            val mediaResult = extractMediaUrlUseCase(url ?: "https://www.instagram.com/p/$shortcode/")
            
            when (mediaResult) {
                is MediaResult.Success -> {
                    val media = mediaResult.media
                    
                    when {
                        downloadAll && media.carouselMedia.isNotEmpty() -> {
                            // Download all carousel items
                            var successCount = 0
                            var failCount = 0
                            val delayMs = downloadPreferences.getDownloadDelayMs()
                            
                            media.carouselMedia.forEachIndexed { index, item ->
                                // Add delay between downloads (except for the first one)
                                if (index > 0 && delayMs > 0) {
                                    delay(delayMs)
                                }
                                
                                setForeground(createForegroundInfo("Downloading item ${index + 1}/${media.carouselMedia.size}..."))
                                
                                val filename = "instagram_${media.shortcode}_${index + 1}_${System.currentTimeMillis()}"
                                val result = when (item.mediaType) {
                                    MediaType.VIDEO -> {
                                        item.videoUrl?.let { downloadMediaUseCase.downloadVideo(it, filename) }
                                            ?: DownloadResult.Error("No video URL")
                                    }
                                    else -> downloadMediaUseCase.downloadImage(item.displayUrl, filename)
                                }
                                
                                when (result) {
                                    is DownloadResult.Success -> successCount++
                                    is DownloadResult.Error -> failCount++
                                }
                            }
                            
                            val message = when {
                                failCount == 0 -> "All $successCount items downloaded!"
                                successCount == 0 -> "Failed to download items"
                                else -> "$successCount downloaded, $failCount failed"
                            }
                            
                            Result.success(
                                Data.Builder()
                                    .putBoolean(KEY_RESULT_SUCCESS, successCount > 0)
                                    .putString(KEY_RESULT_MESSAGE, message)
                                    .build()
                            )
                        }
                        carouselIndex >= 0 && carouselIndex < media.carouselMedia.size -> {
                            // Download specific carousel item
                            val item = media.carouselMedia[carouselIndex]
                            val filename = "instagram_${media.shortcode}_${carouselIndex + 1}_${System.currentTimeMillis()}"
                            
                            setForeground(createForegroundInfo("Downloading item ${carouselIndex + 1}..."))
                            
                            val result = when (item.mediaType) {
                                MediaType.VIDEO -> {
                                    item.videoUrl?.let { downloadMediaUseCase.downloadVideo(it, filename) }
                                        ?: DownloadResult.Error("No video URL")
                                }
                                else -> downloadMediaUseCase.downloadImage(item.displayUrl, filename)
                            }
                            
                            handleDownloadResult(result, filename)
                        }
                        else -> {
                            // Download single media
                            val filename = "instagram_${media.shortcode}_${System.currentTimeMillis()}"
                            
                            setForeground(createForegroundInfo("Downloading media..."))
                            
                            val result = when (media.mediaType) {
                                MediaType.VIDEO -> {
                                    media.videoUrl?.let { downloadMediaUseCase.downloadVideo(it, filename) }
                                        ?: DownloadResult.Error("No video URL available")
                                }
                                else -> downloadMediaUseCase.downloadImage(media.displayUrl, filename)
                            }
                            
                            handleDownloadResult(result, filename)
                        }
                    }
                }
                is MediaResult.Error -> {
                    Result.failure(
                        Data.Builder()
                            .putBoolean(KEY_RESULT_SUCCESS, false)
                            .putString(KEY_RESULT_MESSAGE, mediaResult.message)
                            .build()
                    )
                }
                is MediaResult.Loading -> {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Result.failure(
                Data.Builder()
                    .putBoolean(KEY_RESULT_SUCCESS, false)
                    .putString(KEY_RESULT_MESSAGE, "Download failed: ${e.message}")
                    .build()
            )
        }
    }

    private fun handleDownloadResult(result: DownloadResult, filename: String): Result {
        return when (result) {
            is DownloadResult.Success -> {
                Result.success(
                    Data.Builder()
                        .putBoolean(KEY_RESULT_SUCCESS, true)
                        .putString(KEY_RESULT_MESSAGE, "Downloaded successfully!")
                        .putString(KEY_RESULT_FILENAME, filename)
                        .build()
                )
            }
            is DownloadResult.Error -> {
                Result.failure(
                    Data.Builder()
                        .putBoolean(KEY_RESULT_SUCCESS, false)
                        .putString(KEY_RESULT_MESSAGE, result.message)
                        .build()
                )
            }
        }
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SakanaClient")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
            }
            
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
