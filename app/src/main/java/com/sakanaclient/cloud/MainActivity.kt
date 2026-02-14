package com.sakanaclient.cloud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.sakanaclient.cloud.service.DownloadService
import com.sakanaclient.cloud.ui.screens.home.HomeScreen
import com.sakanaclient.cloud.ui.screens.settings.SettingsScreen
import com.sakanaclient.cloud.ui.theme.SakanaClientTheme

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Notification permission granted: $isGranted")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request notification permission for Android 13+
        requestNotificationPermission()
        
        // Handle shared URL from intent - start download silently and finish
        if (handleShareIntent(intent)) {
            // Share intent handled - finish activity to stay silent
            finish()
            return
        }
        
        // Normal app launch - show home screen
        setContent {
            var currentScreen by remember { mutableStateOf("home") }
            
            SakanaClientTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        "home" -> HomeScreen(
                            sharedUrl = null,
                            onNavigateToSettings = { currentScreen = "settings" }
                        )
                        "settings" -> SettingsScreen(
                            onBackClick = { currentScreen = "home" }
                        )
                    }
                }
            }
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Handle new share intents when app is already running
        if (handleShareIntent(intent)) {
            // Share intent handled - finish activity to stay silent
            finish()
        }
    }
    
    /**
     * Handles share intent and auto-starts download service.
     * @return true if this was a share intent and was handled, false otherwise
     */
    private fun handleShareIntent(intent: Intent?): Boolean {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            Log.d(TAG, "handleShareIntent: sharedText=$sharedText")
            
            if (!sharedText.isNullOrBlank()) {
                // Start download service with shared text
                DownloadService.startDownloadFromSharedText(this, sharedText)
                return true
            }
        }
        return false
    }
}