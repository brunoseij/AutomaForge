package com.automaforge.android.data.repository

import android.content.Context
import com.automaforge.android.data.ai.*
import com.automaforge.android.data.game.GameStore
import com.automaforge.android.data.lan.LanServer
import com.automaforge.android.data.security.ApiKeyStore
import com.automaforge.android.domain.model.*
import com.automaforge.android.domain.repository.*
import java.net.*
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class RoomGameLibraryRepository @Inject constructor(private val store: GameStore) :
    GameLibraryRepository {
    override suspend fun initialize(): Int =
        withContext(Dispatchers.IO) {
            store.importLegacy()
            store.importErrors().size
        }

    override fun observeGames() =
        store
            .observeGames()
            .map { records ->
                records.map {
                    val room = JSONObject(it.publicJson)
                    GameSummary(
                        it.code,
                        it.name,
                        room.optInt("turn", 1),
                        room.optString("phase"),
                        room.optJSONObject("proposal") != null,
                    )
                }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun requireGame(code: String) {
        withContext(Dispatchers.IO) { store.get(code) }
    }
}

@Singleton
class LocalAiConfigurationRepository
@Inject
constructor(private val settings: AiSettings, private val keys: ApiKeyStore) :
    AiConfigurationRepository {
    override fun read(): AiConfiguration {
        val cfg = settings.publicSettings()
        val options = cfg.getJSONArray("model_options")
        val models =
            (0 until options.length()).map { index ->
                val m = options.getJSONObject(index)
                val levels = m.getJSONArray("reasoning_options")
                ModelOption(
                    m.getString("id"),
                    m.getString("label"),
                    (0 until levels.length()).map {
                        val level = levels.getJSONObject(it)
                        ReasoningOption(level.getString("value"), level.getString("label"))
                    },
                )
            }
        return AiConfiguration(
            cfg.getString("model"),
            cfg.getString("reasoning_effort"),
            models,
            keys.read().isNotBlank(),
        )
    }

    override suspend fun save(model: String, effort: String, newKey: String) {
        withContext(Dispatchers.IO) {
            settings.save(model, effort)
            if (newKey.isNotBlank()) keys.save(newKey.trim())
        }
    }

    override suspend fun clearKey() {
        withContext(Dispatchers.IO) { keys.clear() }
    }
}

@Singleton
class LanHostRepository
@Inject
constructor(private val context: Context, private val store: GameStore, private val ai: AiClient) :
    HostRepository {
    private var server: LanServer? = null

    override suspend fun open(target: SessionTarget): SessionConnection =
        withContext(Dispatchers.IO) {
            synchronized(this@LanHostRepository) {
                if (target.controller) {
                    if (server?.isRunning() != true)
                        server = LanServer(context, ai, store).also { it.start() }
                } else close()
                SessionConnection(target, if (target.controller) localIp() else target.host)
            }
        }

    @Synchronized
    override fun close() {
        server?.stop()
        server = null
    }

    private fun localIp(): String =
        runCatching {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                val sorted =
                    interfaces.sortedBy {
                        if (
                            it.name.startsWith("wlan") ||
                                it.name.startsWith("ap") ||
                                it.name.startsWith("swlan")
                        )
                            0
                        else 1
                    }
                sorted
                    .flatMap { Collections.list(it.inetAddresses) }
                    .firstOrNull {
                        it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress
                    }
                    ?.hostAddress ?: "IP indisponível"
            }
            .getOrDefault("IP indisponível")
}

class LanCaptureRepository @Inject constructor() : CaptureRepository {
    override suspend fun upload(request: CameraRequest, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val path = if (request.role == "board") "/board/photo" else "/cards/private"
            val conn =
                URL("http://${request.host}:${NetworkPolicy.PORT}/api/rooms/${request.room}$path")
                    .openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 6000
                conn.readTimeout = 100000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "image/jpeg")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                val status = conn.responseCode
                val result =
                    (if (status < 400) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()
                        ?.use { JSONObject(it.readText()) }
                        ?: error("Resposta vazia do controlador.")
                if (status >= 400) error(result.optString("detail", "Erro HTTP $status"))
                if (request.role == "board")
                    "Foto ${result.optInt("board_version")} enviada ao controlador."
                else
                    "Carta lida privadamente. Total: ${result.optInt("private_card_count")}. A face não é exibida ao controlador."
            } finally {
                conn.disconnect()
            }
        }
}
