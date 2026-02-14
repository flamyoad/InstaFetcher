package com.sakanaclient.cloud.ui.screens.settings

sealed interface SettingsEvent {
    data class UpdateDelayInput(val input: String) : SettingsEvent
    data object SaveSettings : SettingsEvent
    data object ClearSaveMessage : SettingsEvent
}
