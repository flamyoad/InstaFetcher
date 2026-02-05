package com.flamyoad.instafetcher.data.local

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages Instagram session cookies securely using EncryptedSharedPreferences.
 * Stores sessionid and csrftoken which are required for authenticated API access.
 */
class InstagramCookieManager(context: Context) {
    
    companion object {
        private const val TAG = "InstagramCookieManager"
        private const val PREFS_NAME = "instagram_cookies_encrypted"
        private const val KEY_SESSION_ID = "sessionid"
        private const val KEY_CSRF_TOKEN = "csrftoken"
        private const val KEY_DS_USER_ID = "ds_user_id"
        private const val KEY_MID = "mid"
        private const val KEY_IG_DID = "ig_did"
        private const val KEY_ALL_COOKIES = "all_cookies"
    }
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    /**
     * Data class representing Instagram session cookies.
     */
    data class InstagramCookies(
        val sessionId: String,
        val csrfToken: String,
        val dsUserId: String? = null,
        val mid: String? = null,
        val igDid: String? = null,
        val allCookies: String = ""
    ) {
        fun isValid(): Boolean = sessionId.isNotBlank() && csrfToken.isNotBlank()
        
        /**
         * Generates the Cookie header value for HTTP requests.
         */
        fun toCookieHeader(): String {
            // If we have all cookies stored, use them
            if (allCookies.isNotBlank()) {
                return allCookies
            }
            
            // Otherwise construct from individual values
            val cookies = mutableListOf<String>()
            cookies.add("sessionid=$sessionId")
            cookies.add("csrftoken=$csrfToken")
            dsUserId?.let { cookies.add("ds_user_id=$it") }
            mid?.let { cookies.add("mid=$it") }
            igDid?.let { cookies.add("ig_did=$it") }
            return cookies.joinToString("; ")
        }
    }
    
    /**
     * Saves Instagram cookies from a raw cookie string (from WebView).
     * Parses individual cookie values and stores them securely.
     */
    fun saveCookies(cookieString: String) {
        Log.d(TAG, "Saving cookies: ${cookieString.take(100)}...")
        
        val cookieMap = parseCookieString(cookieString)
        
        prefs.edit().apply {
            cookieMap[KEY_SESSION_ID]?.let { putString(KEY_SESSION_ID, it) }
            cookieMap[KEY_CSRF_TOKEN]?.let { putString(KEY_CSRF_TOKEN, it) }
            cookieMap[KEY_DS_USER_ID]?.let { putString(KEY_DS_USER_ID, it) }
            cookieMap[KEY_MID]?.let { putString(KEY_MID, it) }
            cookieMap[KEY_IG_DID]?.let { putString(KEY_IG_DID, it) }
            putString(KEY_ALL_COOKIES, cookieString)
            apply()
        }
        
        Log.d(TAG, "Cookies saved successfully. Has sessionid: ${cookieMap.containsKey(KEY_SESSION_ID)}")
    }
    
    /**
     * Retrieves stored Instagram cookies.
     * Returns null if no valid session exists.
     */
    fun getCookies(): InstagramCookies? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val csrfToken = prefs.getString(KEY_CSRF_TOKEN, null) ?: return null
        
        return InstagramCookies(
            sessionId = sessionId,
            csrfToken = csrfToken,
            dsUserId = prefs.getString(KEY_DS_USER_ID, null),
            mid = prefs.getString(KEY_MID, null),
            igDid = prefs.getString(KEY_IG_DID, null),
            allCookies = prefs.getString(KEY_ALL_COOKIES, "") ?: ""
        )
    }
    
    /**
     * Checks if valid session cookies exist.
     */
    fun isLoggedIn(): Boolean {
        return getCookies()?.isValid() == true
    }
    
    /**
     * Clears all stored cookies (logout).
     */
    fun clearCookies() {
        Log.d(TAG, "Clearing all cookies")
        prefs.edit().clear().apply()
    }
    
    /**
     * Parses a cookie string into a map of key-value pairs.
     * Handles formats like "key1=value1; key2=value2" 
     */
    private fun parseCookieString(cookieString: String): Map<String, String> {
        return cookieString
            .split(";")
            .mapNotNull { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0].trim() to parts[1].trim()
                } else null
            }
            .toMap()
    }
}
