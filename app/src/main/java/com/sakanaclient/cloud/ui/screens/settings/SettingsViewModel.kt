package com.sakanaclient.cloud.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakanaclient.cloud.data.local.DownloadPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val downloadPreferences: DownloadPreferences
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        loadCurrentSettings()
    }
    
    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.UpdateDelayInput -> updateDelayInput(event.input)
            is SettingsEvent.SaveSettings -> saveSettings()
            is SettingsEvent.ClearSaveMessage -> clearSaveMessage()
        }
    }
    
    private fun loadCurrentSettings() {
        val currentDelay = downloadPreferences.getDownloadDelayMs()
        _uiState.update { 
            it.copy(
                delayInput = currentDelay.toString(),
                delayMs = currentDelay
            )
        }
    }
    
    private fun updateDelayInput(input: String) {
        // Allow empty input or digits only
        if (input.isEmpty() || input.all { it.isDigit() }) {
            _uiState.update { it.copy(delayInput = input, delayError = null) }
        }
    }
    
    private fun saveSettings() {
        val input = _uiState.value.delayInput
        
        // Validate input
        if (input.isEmpty()) {
            _uiState.update { it.copy(delayError = "Please enter a value") }
            return
        }
        
        val delayMs = input.toLongOrNull()
        if (delayMs == null) {
            _uiState.update { it.copy(delayError = "Invalid number") }
            return
        }
        
        if (delayMs < 0) {
            _uiState.update { it.copy(delayError = "Delay cannot be negative") }
            return
        }
        
        if (delayMs > 60000) {
            _uiState.update { it.copy(delayError = "Delay too large (max 60 seconds)") }
            return
        }
        
        // Save settings
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, delayError = null) }
            
            try {
                downloadPreferences.setDownloadDelayMs(delayMs)
                
                _uiState.update { 
                    it.copy(
                        isSaving = false,
                        delayMs = delayMs,
                        saveMessage = "Settings saved successfully"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isSaving = false,
                        delayError = "Failed to save settings: ${e.message}"
                    )
                }
            }
        }
    }
    
    private fun clearSaveMessage() {
        _uiState.update { it.copy(saveMessage = null) }
    }
}
