package com.automaforge.android.domain.usecase

import com.automaforge.android.domain.model.*
import com.automaforge.android.domain.repository.*
import javax.inject.Inject

class SessionActions
@Inject
constructor(private val host: HostRepository, private val games: GameLibraryRepository) {
    suspend fun initialize() = games.initialize()

    fun observeGames() = games.observeGames()

    suspend fun open(target: SessionTarget): SessionConnection {
        require(NetworkPolicy.isLocalHost(target.host)) {
            "Use um IPv4 válido de uma rede privada."
        }
        if (target.controller && target.room.isNotEmpty()) games.requireGame(target.room)
        return host.open(target)
    }

    fun close() = host.close()
}

class SettingsActions @Inject constructor(private val settings: AiConfigurationRepository) {
    fun read() = settings.read()

    suspend fun save(model: String, effort: String, newKey: String) =
        settings.save(model, effort, newKey)

    suspend fun clearKey() = settings.clearKey()
}

class UploadCapture @Inject constructor(private val repository: CaptureRepository) {
    suspend operator fun invoke(request: CameraRequest, bytes: ByteArray): String {
        require(request.valid()) { "Destino da câmera inválido." }
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) {
            "Foto inválida ou maior que 10 MB."
        }
        return repository.upload(request, bytes)
    }
}
