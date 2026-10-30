package com.automaforge.android.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.automaforge.android.domain.model.*
import com.automaforge.android.domain.usecase.SettingsActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AiSettingsUiState(
    val configuration: AiConfiguration? = null,
    val model: String = "",
    val effort: String = "default",
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * Public configuration is screen state; successful saves are one-shot events, never replayed on
 * reopen.
 */
class AiSettingsViewModel(
    private val actions: SettingsActions,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(AiSettingsUiState())
    val state = mutable.asStateFlow()
    private val saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvents = saved.asSharedFlow()

    fun reload() {
        if (mutable.value.saving) return
        mutable.update { it.copy(configuration = null, saving = true, error = null) }
        viewModelScope.launch(io) {
            try {
                val c = actions.read()
                mutable.value = AiSettingsUiState(c, c.model, c.effort)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(error = "Não foi possível carregar a configuração.") }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun chooseModel(id: String) {
        if (mutable.value.saving) return
        mutable.update { s ->
            val model = s.configuration?.models?.find { it.id == id } ?: return@update s
            s.copy(
                model = id,
                effort =
                    s.effort.takeIf { selected -> model.reasoning.any { it.value == selected } }
                        ?: "default",
                error = null,
            )
        }
    }

    fun chooseEffort(effort: String) {
        if (mutable.value.saving) return
        mutable.update { s ->
            if (
                s.configuration
                    ?.models
                    ?.find { it.id == s.model }
                    ?.reasoning
                    ?.any { it.value == effort } == true
            )
                s.copy(effort = effort)
            else s
        }
    }

    fun save(newKey: String) {
        val current = mutable.value
        if (current.saving || current.configuration == null) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch(io) {
            try {
                actions.save(current.model, current.effort, newKey)
                saved.emit(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(error = "Não foi possível salvar a configuração.") }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun clearKey() {
        if (mutable.value.saving || mutable.value.configuration == null) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch(io) {
            try {
                actions.clearKey()
                val c = actions.read()
                mutable.update { it.copy(configuration = c) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(error = "Não foi possível apagar a chave.") }
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }
}
