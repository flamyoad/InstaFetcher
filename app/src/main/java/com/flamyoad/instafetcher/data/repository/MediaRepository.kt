package com.flamyoad.instafetcher.data.repository

import android.util.Log
import com.flamyoad.instafetcher.data.model.MediaResult
import com.flamyoad.instafetcher.data.remote.InstagramService
import com.flamyoad.instafetcher.util.InstagramUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(
    private val instagramService: InstagramService,
    private val urlParser: InstagramUrlParser
) {
    companion object {
        private const val TAG = "MediaRepository"
    }

    /**
     * Fetches Instagram media from a URL.
     * 
     * @param url The full Instagram URL (e.g., https://www.instagram.com/p/ABC123/)
     * @return MediaResult containing either the media data or an error
     */
    suspend fun fetchMediaFromUrl(url: String): MediaResult = withContext(Dispatchers.IO) {
        try {
            val shortcode = urlParser.extractShortcode(url)
                ?: return@withContext MediaResult.Error("Invalid Instagram URL. Could not extract post ID.")
            
            Log.d(TAG, "Fetching media for shortcode: $shortcode")
            val media = instagramService.fetchMedia(shortcode)
            Log.d(TAG, "Media fetched - displayUrl length: ${media.displayUrl.length}, displayUrl empty: ${media.displayUrl.isEmpty()}")
            Log.d(TAG, "Display URL: ${media.displayUrl}")
            
            MediaResult.Success(media)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching media", e)
            MediaResult.Error(
                message = "Failed to fetch media: ${e.message ?: "Unknown error"}",
                exception = e
            )
        }
    }

    /**
     * Fetches Instagram media by shortcode.
     */
    suspend fun fetchMediaByShortcode(shortcode: String): MediaResult = withContext(Dispatchers.IO) {
        try {
            val media = instagramService.fetchMedia(shortcode)
            MediaResult.Success(media)
        } catch (e: Exception) {
            MediaResult.Error(
                message = "Failed to fetch media: ${e.message ?: "Unknown error"}",
                exception = e
            )
        }
    }
}
