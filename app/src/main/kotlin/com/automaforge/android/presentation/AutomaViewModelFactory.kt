package com.automaforge.android.presentation

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import com.automaforge.android.domain.usecase.*
import com.automaforge.android.presentation.camera.CameraViewModel
import com.automaforge.android.presentation.main.MainViewModel
import com.automaforge.android.presentation.settings.AiSettingsViewModel
import javax.inject.Inject

class AutomaViewModelFactory
@Inject
constructor(
    private val sessions: SessionActions,
    private val settings: SettingsActions,
    private val upload: UploadCapture,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        when (modelClass) {
            MainViewModel::class.java -> MainViewModel(sessions, extras.createSavedStateHandle())
            AiSettingsViewModel::class.java -> AiSettingsViewModel(settings)
            CameraViewModel::class.java -> CameraViewModel(upload)
            else -> error("ViewModel desconhecido: ${modelClass.name}")
        }
            as T
}
