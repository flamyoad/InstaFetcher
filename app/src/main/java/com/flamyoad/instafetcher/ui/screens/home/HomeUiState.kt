package com.flamyoad.instafetcher.ui.screens.home

import com.flamyoad.instafetcher.data.model.InstagramMedia

/**
 * UI state for the Home screen.
 */
data class HomeUiState(
    val urlInput: String = "",
    val isLoading: Boolean = false,
    val media: InstagramMedia? = null,
    val error: String? = null,
    val downloadProgress: DownloadProgress = DownloadProgress.Idle,
    val showPermissionDialog: Boolean = false,
    val isLoggedIn: Boolean = false,
    val showLogoutDialog: Boolean = false
)

sealed class DownloadProgress {
    data object Idle : DownloadProgress()
    data object Downloading : DownloadProgress()
    data class Success(val message: String) : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
}

/**
 * Events that can be triggered from the UI.
 */
sealed class HomeEvent {
    data class UrlChanged(val url: String) : HomeEvent()
    data object FetchMedia : HomeEvent()
    data object DownloadMedia : HomeEvent()
    data class DownloadCarouselItem(val index: Int) : HomeEvent()
    data object DownloadAllCarouselItems : HomeEvent()
    data object ClearError : HomeEvent()
    data object ClearMedia : HomeEvent()
    data object DismissPermissionDialog : HomeEvent()
    data object RequestPermission : HomeEvent()
    data object RefreshLoginStatus : HomeEvent()
    data object ShowLogoutDialog : HomeEvent()
    data object DismissLogoutDialog : HomeEvent()
    data object ConfirmLogout : HomeEvent()
}
