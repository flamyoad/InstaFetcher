package com.flamyoad.instafetcher.ui.screens.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flamyoad.instafetcher.ui.components.MediaPreviewCard
import com.flamyoad.instafetcher.ui.components.UrlInputField
import com.flamyoad.instafetcher.ui.login.InstagramLoginActivity
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sharedUrl: String?,
    viewModel: HomeViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
    // Login activity launcher
    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == InstagramLoginActivity.RESULT_LOGIN_SUCCESS) {
            viewModel.onEvent(HomeEvent.RefreshLoginStatus)
        }
    }
    
    // Handle permission request
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onEvent(HomeEvent.DismissPermissionDialog)
        if (isGranted) {
            viewModel.onEvent(HomeEvent.DownloadMedia)
        }
    }
    
    // Process shared URL on first composition
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            viewModel.processSharedUrl(sharedUrl)
        }
    }
    
    // Show snackbar for download progress
    LaunchedEffect(uiState.downloadProgress) {
        when (val progress = uiState.downloadProgress) {
            is DownloadProgress.Success -> {
                snackbarHostState.showSnackbar(
                    message = progress.message,
                    duration = SnackbarDuration.Short
                )
            }
            is DownloadProgress.Error -> {
                snackbarHostState.showSnackbar(
                    message = progress.message,
                    duration = SnackbarDuration.Long
                )
            }
            else -> {}
        }
    }
    
    // Handle permission dialog
    if (uiState.showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(HomeEvent.DismissPermissionDialog) },
            title = { Text("Permission Required") },
            text = { Text("Storage permission is required to save images to your gallery.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEvent(HomeEvent.DismissPermissionDialog)
                        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_IMAGES
                        } else {
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }
                        permissionLauncher.launch(permission)
                    }
                ) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onEvent(HomeEvent.DismissPermissionDialog) }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Logout confirmation dialog
    if (uiState.showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(HomeEvent.DismissLogoutDialog) },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout? You'll need to login again to access restricted posts.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onEvent(HomeEvent.ConfirmLogout) }
                ) {
                    Text("Logout")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onEvent(HomeEvent.DismissLogoutDialog) }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("InstaFetcher") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Login/Logout button
                    if (uiState.isLoggedIn) {
                        IconButton(
                            onClick = { viewModel.onEvent(HomeEvent.ShowLogoutDialog) }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Logout",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val intent = Intent(context, InstagramLoginActivity::class.java)
                                loginLauncher.launch(intent)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Login,
                                contentDescription = "Login to Instagram",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // URL Input Section
            UrlInputField(
                value = uiState.urlInput,
                onValueChange = { viewModel.onEvent(HomeEvent.UrlChanged(it)) },
                onFetchClick = { viewModel.onEvent(HomeEvent.FetchMedia) },
                onClearClick = { viewModel.onEvent(HomeEvent.UrlChanged("")) },
                isLoading = uiState.isLoading,
                error = uiState.error
            )
            
            // Loading indicator
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            
            // Media Preview
            uiState.media?.let { media ->
                MediaPreviewCard(
                    media = media,
                    downloadProgress = uiState.downloadProgress,
                    onDownloadClick = { viewModel.onEvent(HomeEvent.DownloadMedia) },
                    onDownloadAllClick = { viewModel.onEvent(HomeEvent.DownloadAllCarouselItems) },
                    onDownloadItemClick = { index -> viewModel.onEvent(HomeEvent.DownloadCarouselItem(index)) },
                    onClearClick = { viewModel.onEvent(HomeEvent.ClearMedia) }
                )
            }
            
            // Login status card
            LoginStatusCard(
                isLoggedIn = uiState.isLoggedIn,
                onLoginClick = {
                    val intent = Intent(context, InstagramLoginActivity::class.java)
                    loginLauncher.launch(intent)
                }
            )
            
            // Instructions when no media
            if (uiState.media == null && !uiState.isLoading) {
                InstructionsCard()
            }
        }
    }
}

@Composable
private fun LoginStatusCard(
    isLoggedIn: Boolean,
    onLoginClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoggedIn) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLoggedIn) "✓ Logged in to Instagram" else "Not logged in",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isLoggedIn) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                )
                Text(
                    text = if (isLoggedIn) {
                        "Full resolution access enabled"
                    } else {
                        "Login for full resolution on restricted posts"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLoggedIn) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    }
                )
            }
            
            if (!isLoggedIn) {
                TextButton(onClick = onLoginClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Login")
                }
            }
        }
    }
}

@Composable
private fun InstructionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "How to use",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "1. Copy an Instagram post link",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "2. Paste the link above and tap 'Fetch'",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "3. Preview the media and tap 'Download'",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "💡 Tip: You can also share links directly from Instagram to this app!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
