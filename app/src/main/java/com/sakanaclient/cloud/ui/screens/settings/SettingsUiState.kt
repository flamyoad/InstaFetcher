package com.sakanaclient.cloud.ui.screens.settings

data class SettingsUiState(
    val delayInput: String = "0",
    val delayMs: Long = 0,
    val delayError: String? = null,
    val isSaving: Boolean = false,
    val saveMessage: String? = null
)
