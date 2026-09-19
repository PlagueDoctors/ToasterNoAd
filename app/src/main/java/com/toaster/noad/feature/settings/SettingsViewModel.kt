package com.toaster.noad.feature.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val autostart: Boolean = false,
    val notification: Boolean = true,
    val darkTheme: Boolean = true,
)

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = SettingsUiState(autostart = true, notification = true, darkTheme = true)
    }

    fun toggleAutostart() {
        _uiState.value = _uiState.value.copy(autostart = !_uiState.value.autostart)
    }

    fun toggleNotification() {
        _uiState.value = _uiState.value.copy(notification = !_uiState.value.notification)
    }

    fun toggleDarkTheme() {
        _uiState.value = _uiState.value.copy(darkTheme = !_uiState.value.darkTheme)
    }
}
