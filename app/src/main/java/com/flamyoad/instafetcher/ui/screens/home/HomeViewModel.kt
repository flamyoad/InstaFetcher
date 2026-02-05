package com.flamyoad.instafetcher.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flamyoad.instafetcher.data.local.InstagramCookieManager
import com.flamyoad.instafetcher.data.model.MediaResult
import com.flamyoad.instafetcher.data.model.MediaType
import com.flamyoad.instafetcher.domain.usecase.DownloadMediaUseCase
import com.flamyoad.instafetcher.domain.usecase.DownloadResult
import com.flamyoad.instafetcher.domain.usecase.ExtractMediaUrlUseCase
import com.flamyoad.instafetcher.util.InstagramUrlParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val extractMediaUrlUseCase: ExtractMediaUrlUseCase,
    private val downloadMediaUseCase: DownloadMediaUseCase,
    private val urlParser: InstagramUrlParser,
    private val context: Context,
    private val cookieManager: InstagramCookieManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    init {
        // Check login status on init
        refreshLoginStatus()
    }
    
    private fun refreshLoginStatus() {
        val isLoggedIn = cookieManager.isLoggedIn()
        _uiState.update { it.copy(isLoggedIn = isLoggedIn) }
    }

    /**
     * Handles UI events.
     */
    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.UrlChanged -> {
                _uiState.update { it.copy(urlInput = event.url, error = null) }
            }
            is HomeEvent.FetchMedia -> fetchMedia()
            is HomeEvent.DownloadMedia -> downloadCurrentMedia()
            is HomeEvent.DownloadCarouselItem -> downloadCarouselItem(event.index)
            is HomeEvent.DownloadAllCarouselItems -> downloadAllCarouselItems()
            is HomeEvent.ClearError -> _uiState.update { it.copy(error = null) }
            is HomeEvent.ClearMedia -> _uiState.update { it.copy(media = null, downloadProgress = DownloadProgress.Idle) }
            is HomeEvent.DismissPermissionDialog -> _uiState.update { it.copy(showPermissionDialog = false) }
            is HomeEvent.RequestPermission -> _uiState.update { it.copy(showPermissionDialog = true) }
            is HomeEvent.RefreshLoginStatus -> refreshLoginStatus()
            is HomeEvent.ShowLogoutDialog -> _uiState.update { it.copy(showLogoutDialog = true) }
            is HomeEvent.DismissLogoutDialog -> _uiState.update { it.copy(showLogoutDialog = false) }
            is HomeEvent.ConfirmLogout -> {
                cookieManager.clearCookies()
                _uiState.update { it.copy(isLoggedIn = false, showLogoutDialog = false) }
            }
        }
    }

    /**
     * Processes a shared URL from an intent.
     */
    fun processSharedUrl(sharedText: String?) {
        if (sharedText.isNullOrBlank()) return
        
        // Extract Instagram URL from shared text (may contain caption and other text)
        val instagramUrl = urlParser.extractInstagramUrlFromText(sharedText)
        
        if (instagramUrl != null) {
            _uiState.update { it.copy(urlInput = instagramUrl) }
            fetchMedia()
        } else {
            _uiState.update { 
                it.copy(
                    urlInput = sharedText,
                    error = "No valid Instagram URL found in shared text"
                ) 
            }
        }
    }

    private fun fetchMedia() {
        val url = _uiState.value.urlInput.trim()
        
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Please enter an Instagram URL") }
            return
        }
        
        if (!urlParser.isValidInstagramUrl(url)) {
            _uiState.update { it.copy(error = "Invalid Instagram URL format") }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, media = null) }
            
            when (val result = extractMediaUrlUseCase(url)) {
                is MediaResult.Success -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            media = result.media,
                            downloadProgress = DownloadProgress.Idle
                        ) 
                    }
                }
                is MediaResult.Error -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            error = result.message
                        ) 
                    }
                }
                is MediaResult.Loading -> {
                    // Already handled above
                }
            }
        }
    }

    private fun downloadCurrentMedia() {
        val media = _uiState.value.media ?: return
        
        if (!hasStoragePermission()) {
            _uiState.update { it.copy(showPermissionDialog = true) }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(downloadProgress = DownloadProgress.Downloading) }
            
            val filename = "instagram_${media.shortcode}_${System.currentTimeMillis()}"
            
            val result = when (media.mediaType) {
                MediaType.VIDEO -> {
                    media.videoUrl?.let { downloadMediaUseCase.downloadVideo(it, filename) }
                        ?: DownloadResult.Error("No video URL available")
                }
                MediaType.IMAGE -> {
                    downloadMediaUseCase.downloadImage(media.displayUrl, filename)
                }
                MediaType.CAROUSEL -> {
                    // Download first item for single download, or prompt for all
                    downloadMediaUseCase.downloadImage(media.displayUrl, filename)
                }
            }
            
            when (result) {
                is DownloadResult.Success -> {
                    _uiState.update { 
                        it.copy(downloadProgress = DownloadProgress.Success("Saved to gallery!")) 
                    }
                }
                is DownloadResult.Error -> {
                    _uiState.update { 
                        it.copy(downloadProgress = DownloadProgress.Error(result.message)) 
                    }
                }
            }
        }
    }

    private fun downloadCarouselItem(index: Int) {
        val media = _uiState.value.media ?: return
        val item = media.carouselMedia.getOrNull(index) ?: return
        
        if (!hasStoragePermission()) {
            _uiState.update { it.copy(showPermissionDialog = true) }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(downloadProgress = DownloadProgress.Downloading) }
            
            val filename = "instagram_${media.shortcode}_${index + 1}_${System.currentTimeMillis()}"
            
            val result = when (item.mediaType) {
                MediaType.VIDEO -> {
                    item.videoUrl?.let { downloadMediaUseCase.downloadVideo(it, filename) }
                        ?: DownloadResult.Error("No video URL available")
                }
                else -> {
                    downloadMediaUseCase.downloadImage(item.displayUrl, filename)
                }
            }
            
            when (result) {
                is DownloadResult.Success -> {
                    _uiState.update { 
                        it.copy(downloadProgress = DownloadProgress.Success("Item ${index + 1} saved!")) 
                    }
                }
                is DownloadResult.Error -> {
                    _uiState.update { 
                        it.copy(downloadProgress = DownloadProgress.Error(result.message)) 
                    }
                }
            }
        }
    }

    private fun downloadAllCarouselItems() {
        val media = _uiState.value.media ?: return
        
        if (!hasStoragePermission()) {
            _uiState.update { it.copy(showPermissionDialog = true) }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(downloadProgress = DownloadProgress.Downloading) }
            
            var successCount = 0
            var failCount = 0
            
            media.carouselMedia.forEachIndexed { index, item ->
                val filename = "instagram_${media.shortcode}_${index + 1}_${System.currentTimeMillis()}"
                
                val result = when (item.mediaType) {
                    MediaType.VIDEO -> {
                        item.videoUrl?.let { downloadMediaUseCase.downloadVideo(it, filename) }
                            ?: DownloadResult.Error("No video URL")
                    }
                    else -> {
                        downloadMediaUseCase.downloadImage(item.displayUrl, filename)
                    }
                }
                
                when (result) {
                    is DownloadResult.Success -> successCount++
                    is DownloadResult.Error -> failCount++
                }
            }
            
            val message = when {
                failCount == 0 -> "All $successCount items saved!"
                successCount == 0 -> "Failed to download items"
                else -> "$successCount saved, $failCount failed"
            }
            
            _uiState.update { 
                it.copy(
                    downloadProgress = if (failCount == media.carouselMedia.size) {
                        DownloadProgress.Error(message)
                    } else {
                        DownloadProgress.Success(message)
                    }
                ) 
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage - no permission needed for app-created files
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
