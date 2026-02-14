package com.sakanaclient.cloud.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Use case for downloading media and saving to device storage.
 */
class DownloadMediaUseCase(
    private val context: Context,
    private val httpClient: HttpClient
) {
    companion object {
        private const val TAG = "DownloadMediaUseCase"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        private const val FOLDER_NAME = "SakanaClient"
    }

    /**
     * Downloads an image from URL and saves it to the device gallery.
     * 
     * @param imageUrl The URL of the image to download
     * @param filename The desired filename (without extension)
     * @return Result indicating success or failure
     */
    suspend fun downloadImage(imageUrl: String, filename: String): DownloadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading image from: ${imageUrl.take(100)}...")
            
            // Download the image
            val response = httpClient.get(imageUrl) {
                headers {
                    append(HttpHeaders.UserAgent, USER_AGENT)
                }
            }
            
            // Read all bytes first to ensure we have the full content
            val channel = response.bodyAsChannel()
            val buffer = ByteArrayOutputStream()
            channel.toInputStream().use { input ->
                input.copyTo(buffer)
            }
            val bytes = buffer.toByteArray()
            Log.d(TAG, "Downloaded ${bytes.size} bytes")
            
            if (bytes.isEmpty()) {
                return@withContext DownloadResult.Error("Downloaded 0 bytes - URL may be invalid or expired", null)
            }
            
            // Save to gallery using appropriate method based on API level
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveImageToMediaStoreQ(ByteArrayInputStream(bytes), filename)
            } else {
                saveImageToExternalStorage(ByteArrayInputStream(bytes), filename)
            }
            
            DownloadResult.Success(filename)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download image", e)
            DownloadResult.Error("Failed to download: ${e.message}", e)
        }
    }

    /**
     * Downloads a video from URL and saves it to the device gallery.
     */
    suspend fun downloadVideo(videoUrl: String, filename: String): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.get(videoUrl) {
                headers {
                    append(HttpHeaders.UserAgent, USER_AGENT)
                }
            }
            
            val inputStream = response.bodyAsChannel().toInputStream()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveVideoToMediaStoreQ(inputStream, filename)
            } else {
                saveVideoToExternalStorage(inputStream, filename)
            }
            
            DownloadResult.Success(filename)
        } catch (e: Exception) {
            DownloadResult.Error("Failed to download video: ${e.message}", e)
        }
    }

    /**
     * Saves image using MediaStore API for Android 10+.
     */
    private fun saveImageToMediaStoreQ(inputStream: InputStream, filename: String) {
        // Read all bytes
        val bytes = inputStream.readBytes()
        Log.d(TAG, "Saving ${bytes.size} bytes to MediaStore")
        
        // Try to decode to check if it's a valid image
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                if (bitmap != null) {
                    // Successfully decoded - save as JPEG
                    Log.d(TAG, "Bitmap decoded: ${bitmap.width}x${bitmap.height}")
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    bitmap.recycle()
                } else {
                    // Could not decode as bitmap, save raw bytes
                    // This might happen with HEIC images
                    Log.d(TAG, "Could not decode bitmap, saving raw bytes")
                    outputStream.write(bytes)
                }
            }
            
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            Log.d(TAG, "Image saved to: $uri")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Saves video using MediaStore API for Android 10+.
     */
    private fun saveVideoToMediaStoreQ(inputStream: InputStream, filename: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$filename.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Saves image to external storage for Android 9 and below.
     */
    @Suppress("DEPRECATION")
    private fun saveImageToExternalStorage(inputStream: InputStream, filename: String) {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val appDir = File(picturesDir, FOLDER_NAME)
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        
        val file = File(appDir, "$filename.jpg")
        FileOutputStream(file).use { outputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                ?: inputStream.copyTo(outputStream)
        }
        
        // Notify media scanner
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }

    /**
     * Saves video to external storage for Android 9 and below.
     */
    @Suppress("DEPRECATION")
    private fun saveVideoToExternalStorage(inputStream: InputStream, filename: String) {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, FOLDER_NAME)
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        
        val file = File(appDir, "$filename.mp4")
        FileOutputStream(file).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        
        // Notify media scanner
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4"),
            null
        )
    }
}

sealed class DownloadResult {
    data class Success(val filename: String) : DownloadResult()
    data class Error(val message: String, val exception: Throwable? = null) : DownloadResult()
}
