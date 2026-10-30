package com.automaforge.android.presentation.main

import androidx.lifecycle.*
import com.automaforge.android.domain.model.*
import com.automaforge.android.domain.usecase.SessionActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Holds route and public screen state. No Activity, WebView, image, key or CameraDevice is
 * retained.
 */
data class MainUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val connection: SessionConnection? = null,
    val games: List<GameSummary> = emptyList(),
    val importWarnings: Int = 0,
    val error: String? = null,
    val settingsOpen: Boolean = false,
    val currentUrl: String = "",
    val reloadEpoch: Int = 0,
)

class MainViewModel(private val actions: SessionActions, private val saved: SavedStateHandle) :
    ViewModel() {
    private val mutable = MutableStateFlow(MainUiState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch() {
            try {
                val warnings = actions.initialize()
                mutable.update { it.copy(loading = false, importWarnings = warnings) }
                actions.observeGames().collect { games ->
                    mutable.update { it.copy(games = games) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(
                        loading = false,
                        error =
                            "Não foi possível abrir as partidas salvas. Os arquivos originais foram preservados.",
                    )
                }
            }
        }
        val host = saved.get<String>("host")
        if (host != null)
            open(
                SessionTarget(host, saved["controller"] ?: true, saved["room"] ?: ""),
                restore = true,
            )
    }

    fun startController(code: String = "") = open(SessionTarget(room = code))

    fun connect(host: String) = open(SessionTarget(host.trim(), false))

    private fun open(target: SessionTarget, restore: Boolean = false) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val connection = actions.open(target)
                val last =
                    saved.get<String>("url").takeIf {
                        restore && it != null && NetworkPolicy.sameOrigin(it, target.host)
                    }
                saved["host"] = target.host
                saved["controller"] = target.controller
                saved["room"] = target.room
                mutable.update {
                    it.copy(
                        connection = connection,
                        busy = false,
                        currentUrl = last ?: connection.initialUrl,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(
                        busy = false,
                        error = failure.message ?: "Não foi possível abrir a conexão.",
                    )
                }
            }
        }
    }

    fun rememberLocation(url: String) {
        val host = mutable.value.connection?.target?.host ?: return
        if (NetworkPolicy.sameOrigin(url, host)) {
            saved["url"] = url
            mutable.update { it.copy(currentUrl = url) }
        }
    }

    fun leave() {
        actions.close()
        saved.remove<String>("host")
        saved.remove<String>("url")
        saved.remove<String>("room")
        mutable.update { it.copy(connection = null, settingsOpen = false, currentUrl = "") }
    }

    fun showSettings() {
        if (mutable.value.connection?.target?.controller == true)
            mutable.update { it.copy(settingsOpen = true) }
    }

    fun closeSettings() {
        mutable.update { it.copy(settingsOpen = false) }
    }

    fun settingsSaved() {
        mutable.update { it.copy(settingsOpen = false, reloadEpoch = it.reloadEpoch + 1) }
    }

    fun report(message: String) {
        mutable.update { it.copy(error = message) }
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    override fun onCleared() {
        actions.close()
    }
}
