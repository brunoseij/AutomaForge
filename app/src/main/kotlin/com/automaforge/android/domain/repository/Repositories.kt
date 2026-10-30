package com.automaforge.android.domain.repository

import com.automaforge.android.domain.model.*
import kotlinx.coroutines.flow.Flow

interface GameLibraryRepository {
    suspend fun initialize(): Int

    fun observeGames(): Flow<List<GameSummary>>

    suspend fun requireGame(code: String)
}

interface HostRepository {
    suspend fun open(target: SessionTarget): SessionConnection

    fun close()
}

interface AiConfigurationRepository {
    fun read(): AiConfiguration

    suspend fun save(model: String, effort: String, newKey: String)

    suspend fun clearKey()
}

interface CaptureRepository {
    suspend fun upload(request: CameraRequest, bytes: ByteArray): String
}
