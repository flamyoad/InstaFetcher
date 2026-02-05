package com.flamyoad.instafetcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.flamyoad.instafetcher.MainActivity
import com.flamyoad.instafetcher.data.model.InstagramMedia
import com.flamyoad.instafetcher.data.model.MediaResult
import com.flamyoad.instafetcher.data.model.MediaType
import com.flamyoad.instafetcher.domain.usecase.DownloadMediaUseCase
import com.flamyoad.instafetcher.domain.usecase.DownloadResult
import com.flamyoad.instafetcher.domain.usecase.ExtractMediaUrlUseCase
import com.flamyoad.instafetcher.util.InstagramUrlParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service for downloading Instagram media with progress notifications.
 * Supports multiple concurrent downloads.
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        
        const val ACTION_START_DOWNLOAD = "com.flamyoad.instafetcher.START_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "com.flamyoad.instafetcher.CANCEL_ALL"
        
        const val EXTRA_URLS = "extra_urls"
        const val EXTRA_SHARED_TEXT = "extra_shared_text"
        
        private const val NOTIFICATION_CHANNEL_ID = "download_progress_channel"
        private const val SUMMARY_NOTIFICATION_ID = 1000
        private const val NOTIFICATION_ID_OFFSET = 2000
        
        /**
         * Starts the download service with the given URLs.
         */
        fun startDownload(context: Context, urls: List<String>) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Starts the download service with shared text that may contain multiple URLs.
         */
        fun startDownloadFromSharedText(context: Context, sharedText: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_SHARED_TEXT, sharedText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    private val extractMediaUrlUseCase: ExtractMediaUrlUseCase by inject()
    private val downloadMediaUseCase: DownloadMediaUseCase by inject()
    private val urlParser: InstagramUrlParser by inject()
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = mutableMapOf<Int, DownloadTask>()
    private val downloadMutex = Mutex()
    private val notificationIdCounter = AtomicInteger(NOTIFICATION_ID_OFFSET)
    
    private var totalDownloads = 0
    private var completedDownloads = 0
    private var failedDownloads = 0
    
    private lateinit var notificationManager: NotificationManager
    
    data class DownloadTask(
        val id: Int,
        val url: String,
        val shortcode: String,
        var job: Job? = null,
        var status: DownloadStatus = DownloadStatus.QUEUED,
        var progress: Int = 0,
        var message: String = ""
    )
    
    enum class DownloadStatus {
        QUEUED,
        FETCHING,
        DOWNLOADING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                // Start as foreground service immediately
                startForeground(SUMMARY_NOTIFICATION_ID, createSummaryNotification())
                
                val urls = intent.getStringArrayListExtra(EXTRA_URLS)
                val sharedText = intent.getStringExtra(EXTRA_SHARED_TEXT)
                
                Log.d(TAG, "onStartCommand: urls=$urls, sharedText=${sharedText?.take(100)}")
                
                when {
                    urls != null && urls.isNotEmpty() -> {
                        Log.d(TAG, "Processing ${urls.size} URLs from list")
                        processUrls(urls)
                    }
                    sharedText != null -> {
                        val extractedUrls = urlParser.extractAllInstagramUrls(sharedText)
                        Log.d(TAG, "Extracted ${extractedUrls.size} URLs from shared text: $extractedUrls")
                        if (extractedUrls.isNotEmpty()) {
                            processUrls(extractedUrls)
                        } else {
                            showErrorNotification("No valid Instagram URLs found")
                            stopSelfIfDone()
                        }
                    }
                    else -> {
                        showErrorNotification("No URLs provided")
                        stopSelfIfDone()
                    }
                }
            }
            ACTION_CANCEL_ALL -> {
                cancelAllDownloads()
            }
        }
        
        return START_NOT_STICKY
    }
    
    private fun processUrls(urls: List<String>) {
        serviceScope.launch {
            downloadMutex.withLock {
                totalDownloads += urls.size
            }
            updateSummaryNotification()
            
            urls.forEach { url ->
                val shortcode = urlParser.extractShortcode(url) ?: url.takeLast(11)
                val notificationId = notificationIdCounter.getAndIncrement()
                
                val task = DownloadTask(
                    id = notificationId,
                    url = url,
                    shortcode = shortcode
                )
                
                downloadMutex.withLock {
                    activeDownloads[notificationId] = task
                }
                
                task.job = serviceScope.launch {
                    processDownload(task)
                }
            }
        }
    }
    
    private suspend fun processDownload(task: DownloadTask) {
        try {
            // Update status to fetching
            updateTaskStatus(task, DownloadStatus.FETCHING, "Fetching media info...")
            showDownloadNotification(task)
            
            Log.d(TAG, "Fetching media for ${task.shortcode}")
            
            when (val result = extractMediaUrlUseCase(task.url)) {
                is MediaResult.Success -> {
                    val media = result.media
                    downloadMedia(task, media)
                }
                is MediaResult.Error -> {
                    updateTaskStatus(task, DownloadStatus.FAILED, result.message)
                    onDownloadFailed(task)
                }
                is MediaResult.Loading -> {
                    // Shouldn't happen in this context
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing download", e)
            updateTaskStatus(task, DownloadStatus.FAILED, e.message ?: "Unknown error")
            onDownloadFailed(task)
        }
    }
    
    private suspend fun downloadMedia(task: DownloadTask, media: InstagramMedia) {
        updateTaskStatus(task, DownloadStatus.DOWNLOADING, "Downloading...")
        showDownloadNotification(task)
        
        // Track if we're downloading low-resolution content
        val isLowRes = media.isLowResolution
        if (isLowRes) {
            Log.w(TAG, "Downloading low-resolution image for ${task.shortcode} - embed may be disabled")
        }
        
        when (media.mediaType) {
            MediaType.CAROUSEL -> {
                // Download all carousel items
                var successCount = 0
                var failCount = 0
                val total = media.carouselMedia.size
                
                media.carouselMedia.forEachIndexed { index, item ->
                    val filename = "instagram_${media.shortcode}_${index + 1}_${System.currentTimeMillis()}"
                    task.progress = ((index.toFloat() / total) * 100).toInt()
                    task.message = "Downloading ${index + 1}/$total..."
                    showDownloadNotification(task)
                    
                    val downloadResult = when (item.mediaType) {
                        MediaType.VIDEO -> {
                            item.videoUrl?.let { downloadMediaUseCase.downloadVideo(it, filename) }
                                ?: DownloadResult.Error("No video URL")
                        }
                        else -> downloadMediaUseCase.downloadImage(item.displayUrl, filename)
                    }
                    
                    when (downloadResult) {
                        is DownloadResult.Success -> successCount++
                        is DownloadResult.Error -> failCount++
                    }
                }
                
                if (successCount > 0) {
                    val message = if (failCount == 0) {
                        "All $successCount items saved!"
                    } else {
                        "$successCount saved, $failCount failed"
                    }
                    updateTaskStatus(task, DownloadStatus.COMPLETED, message)
                    onDownloadCompleted(task, isLowRes)
                } else {
                    updateTaskStatus(task, DownloadStatus.FAILED, "Failed to download carousel")
                    onDownloadFailed(task)
                }
            }
            MediaType.VIDEO -> {
                val filename = "instagram_${media.shortcode}_${System.currentTimeMillis()}"
                val videoUrl = media.videoUrl
                
                if (videoUrl != null) {
                    when (val downloadResult = downloadMediaUseCase.downloadVideo(videoUrl, filename)) {
                        is DownloadResult.Success -> {
                            updateTaskStatus(task, DownloadStatus.COMPLETED, "Video saved!")
                            onDownloadCompleted(task, isLowRes)
                        }
                        is DownloadResult.Error -> {
                            updateTaskStatus(task, DownloadStatus.FAILED, downloadResult.message)
                            onDownloadFailed(task)
                        }
                    }
                } else {
                    updateTaskStatus(task, DownloadStatus.FAILED, "No video URL available")
                    onDownloadFailed(task)
                }
            }
            MediaType.IMAGE -> {
                val filename = "instagram_${media.shortcode}_${System.currentTimeMillis()}"
                
                when (val downloadResult = downloadMediaUseCase.downloadImage(media.displayUrl, filename)) {
                    is DownloadResult.Success -> {
                        val message = if (isLowRes) {
                            "Image saved (low-res only available)"
                        } else {
                            "Image saved!"
                        }
                        updateTaskStatus(task, DownloadStatus.COMPLETED, message)
                        onDownloadCompleted(task, isLowRes)
                    }
                    is DownloadResult.Error -> {
                        updateTaskStatus(task, DownloadStatus.FAILED, downloadResult.message)
                        onDownloadFailed(task)
                    }
                }
            }
        }
    }
    
    private suspend fun updateTaskStatus(task: DownloadTask, status: DownloadStatus, message: String) {
        downloadMutex.withLock {
            task.status = status
            task.message = message
        }
    }
    
    private suspend fun onDownloadCompleted(task: DownloadTask, isLowResolution: Boolean = false) {
        Log.d(TAG, "Download completed: ${task.shortcode}, isLowRes: $isLowResolution")
        downloadMutex.withLock {
            completedDownloads++
        }
        showCompletedNotification(task)
        
        val toastMessage = if (isLowResolution) {
            "Download completed (low-res: embed disabled for this post)"
        } else {
            "Download completed!"
        }
        showToast(toastMessage)
        updateSummaryNotification()
        stopSelfIfDone()
    }
    
    private suspend fun onDownloadFailed(task: DownloadTask) {
        Log.e(TAG, "Download failed: ${task.shortcode} - ${task.message}")
        downloadMutex.withLock {
            failedDownloads++
        }
        showFailedNotification(task)
        updateSummaryNotification()
        stopSelfIfDone()
    }
    
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopSelfIfDone() {
        serviceScope.launch {
            downloadMutex.withLock {
                if (completedDownloads + failedDownloads >= totalDownloads && totalDownloads > 0) {
                    Log.d(TAG, "All downloads done. Completed: $completedDownloads, Failed: $failedDownloads")
                    
                    // Show final summary notification
                    showFinalSummaryNotification()
                    
                    // Stop service
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }
    
    private fun cancelAllDownloads() {
        serviceScope.launch {
            downloadMutex.withLock {
                activeDownloads.values.forEach { task ->
                    task.job?.cancel()
                    task.status = DownloadStatus.CANCELLED
                }
                activeDownloads.clear()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cancelAllDownloads()
    }
    
    // ========== Notification Methods ==========
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress for Instagram media"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createSummaryNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val progressText = when {
            totalDownloads == 0 -> "Preparing downloads..."
            else -> "Downloading: ${completedDownloads + failedDownloads}/$totalDownloads"
        }
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("InstaFetcher")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Cancel All", cancelPendingIntent)
            .setProgress(totalDownloads, completedDownloads + failedDownloads, totalDownloads == 0)
            .setGroup("downloads")
            .setGroupSummary(true)
            .build()
    }
    
    private fun updateSummaryNotification() {
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, createSummaryNotification())
    }
    
    private fun showDownloadNotification(task: DownloadTask) {
        val statusText = when (task.status) {
            DownloadStatus.QUEUED -> "Queued"
            DownloadStatus.FETCHING -> "Fetching info..."
            DownloadStatus.DOWNLOADING -> task.message
            else -> task.message
        }
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("@${task.shortcode}")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, task.progress, task.status != DownloadStatus.DOWNLOADING)
            .setGroup("downloads")
            .build()
        
        notificationManager.notify(task.id, notification)
    }
    
    private fun showCompletedNotification(task: DownloadTask) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("@${task.shortcode}")
            .setContentText(task.message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup("downloads")
            .build()
        
        notificationManager.notify(task.id, notification)
    }
    
    private fun showFailedNotification(task: DownloadTask) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("@${task.shortcode} - Failed")
            .setContentText(task.message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup("downloads")
            .build()
        
        notificationManager.notify(task.id, notification)
    }
    
    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("InstaFetcher")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_OFFSET - 1, notification)
    }
    
    private fun showFinalSummaryNotification() {
        val message = when {
            failedDownloads == 0 && completedDownloads > 0 -> 
                "All $completedDownloads downloads completed!"
            completedDownloads == 0 && failedDownloads > 0 -> 
                "All $failedDownloads downloads failed"
            else -> 
                "$completedDownloads completed, $failedDownloads failed"
        }
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("InstaFetcher")
            .setContentText(message)
            .setSmallIcon(
                if (failedDownloads == 0) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }
}
