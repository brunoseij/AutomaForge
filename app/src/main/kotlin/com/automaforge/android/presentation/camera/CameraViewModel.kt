package com.automaforge.android.presentation.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.automaforge.android.domain.model.CameraRequest
import com.automaforge.android.domain.usecase.UploadCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class CameraUiState(
    val message: String = "Preparando câmera…",
    val uploading: Boolean = false,
)

class CameraViewModel(private val upload: UploadCapture) : ViewModel() {
    private val mutable = MutableStateFlow(CameraUiState())
    val state = mutable.asStateFlow()
    private val lock = Mutex()

    fun message(value: String) {
        mutable.update { it.copy(message = value) }
    }

    fun image(request: CameraRequest, bytes: ByteArray) {
        if (!lock.tryLock())
            return // Bounded queue: do not retain a stream of private images while the network is
        // slow.
        mutable.update { it.copy(uploading = true) }
        viewModelScope.launch {
            try {
                val result = upload(request, bytes)
                mutable.update { it.copy(message = result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(message = failure.message ?: "Erro de envio.") }
            } finally {
                mutable.update { it.copy(uploading = false) }
                lock.unlock()
            }
        }
    }
}
