package com.sakanaclient.cloud.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages download-related preferences including delay between downloads.
 */
class DownloadPreferences(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "download_preferences"
        private const val KEY_DOWNLOAD_DELAY_MS = "download_delay_ms"
        private const val DEFAULT_DOWNLOAD_DELAY_MS = 0L // No delay by default
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Gets the download delay in milliseconds.
     * 
     * @return The delay in milliseconds between each download
     */
    fun getDownloadDelayMs(): Long {
        return prefs.getLong(KEY_DOWNLOAD_DELAY_MS, DEFAULT_DOWNLOAD_DELAY_MS)
    }
    
    /**
     * Sets the download delay in milliseconds.
     * 
     * @param delayMs The delay in milliseconds between each download
     */
    fun setDownloadDelayMs(delayMs: Long) {
        prefs.edit()
            .putLong(KEY_DOWNLOAD_DELAY_MS, delayMs.coerceAtLeast(0))
            .apply()
    }
    
    /**
     * Checks if download delay is enabled (delay > 0).
     */
    fun isDelayEnabled(): Boolean {
        return getDownloadDelayMs() > 0
    }
}
