package com.flamyoad.instafetcher.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.flamyoad.instafetcher.R
import com.flamyoad.instafetcher.data.local.InstagramCookieManager
import org.koin.android.ext.android.inject

/**
 * Activity that displays a WebView for Instagram login.
 * After successful login, extracts cookies and stores them for API access.
 */
class InstagramLoginActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "InstagramLoginActivity"
        private const val INSTAGRAM_LOGIN_URL = "https://www.instagram.com/accounts/login/"
        private const val INSTAGRAM_HOME_URL = "https://www.instagram.com/"
        
        const val RESULT_LOGIN_SUCCESS = 100
        const val RESULT_LOGIN_CANCELLED = 101
    }
    
    private val cookieManager: InstagramCookieManager by inject()
    
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instagram_login)
        
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        
        setupWebView()
        setupBackNavigation()
        
        // Clear any existing cookies first for clean login
        clearWebViewCookies()
        
        // Load Instagram login page
        webView.loadUrl(INSTAGRAM_LOGIN_URL)
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            
            // User agent to appear as mobile browser
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                Log.d(TAG, "Page started: $url")
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                Log.d(TAG, "Page finished: $url")
                
                // Check if we've successfully logged in
                checkLoginStatus(url)
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Keep navigation within Instagram domain
                return if (url.contains("instagram.com")) {
                    false // Let WebView handle it
                } else {
                    Log.d(TAG, "Blocking external URL: $url")
                    true // Block external URLs
                }
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
            }
        }
    }
    
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    setResult(RESULT_LOGIN_CANCELLED)
                    finish()
                }
            }
        })
    }
    
    private fun checkLoginStatus(url: String?) {
        Log.d(TAG, "Checking login status for URL: $url")
        
        // Get cookies from the CookieManager
        val webCookieManager = CookieManager.getInstance()
        
        // Get cookies from multiple Instagram domains
        val mainCookies = webCookieManager.getCookie("https://www.instagram.com") ?: ""
        val apiCookies = webCookieManager.getCookie("https://i.instagram.com") ?: ""
        val rootCookies = webCookieManager.getCookie("https://instagram.com") ?: ""
        
        // Merge all cookies, preferring main domain
        val allCookies = mergeCookies(mainCookies, apiCookies, rootCookies)
        
        Log.d(TAG, "Main cookies: ${mainCookies.take(200)}...")
        Log.d(TAG, "API cookies: ${apiCookies.take(200)}...")
        Log.d(TAG, "Merged cookies: ${allCookies.take(200)}...")
        
        if (allCookies.contains("sessionid=")) {
            // Successfully logged in - sessionid cookie is present
            Log.d(TAG, "Login successful! Sessionid found in cookies")
            
            // Log individual important cookies
            val cookieMap = parseCookieString(allCookies)
            Log.d(TAG, "sessionid present: ${cookieMap.containsKey("sessionid")}")
            Log.d(TAG, "csrftoken present: ${cookieMap.containsKey("csrftoken")}")
            Log.d(TAG, "ds_user_id present: ${cookieMap.containsKey("ds_user_id")}")
            Log.d(TAG, "mid present: ${cookieMap.containsKey("mid")}")
            
            // Save cookies to our secure storage
            cookieManager.saveCookies(allCookies)
            
            // Show success message
            Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show()
            
            // Return success result
            setResult(RESULT_LOGIN_SUCCESS)
            finish()
        }
    }
    
    /**
     * Merges cookies from multiple sources, removing duplicates.
     */
    private fun mergeCookies(vararg cookieStrings: String): String {
        val cookieMap = mutableMapOf<String, String>()
        
        for (cookieString in cookieStrings) {
            if (cookieString.isBlank()) continue
            
            cookieString.split(";").forEach { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    // Only add if not already present or if it's a more valuable cookie
                    if (!cookieMap.containsKey(key) || value.isNotBlank()) {
                        cookieMap[key] = value
                    }
                }
            }
        }
        
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    
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
    
    private fun clearWebViewCookies() {
        val webCookieManager = CookieManager.getInstance()
        webCookieManager.removeAllCookies { success ->
            Log.d(TAG, "Cookies cleared: $success")
        }
        webCookieManager.flush()
    }
    
    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
