package com.automaforge.android.domain.model

import java.net.URI

data class ReasoningOption(val value: String, val label: String)

data class ModelOption(val id: String, val label: String, val reasoning: List<ReasoningOption>)

data class AiConfiguration(
    val model: String,
    val effort: String,
    val models: List<ModelOption>,
    val keyConfigured: Boolean,
)

data class GameSummary(
    val code: String,
    val name: String,
    val turn: Int,
    val phase: String,
    val pendingProposal: Boolean,
)

data class SessionTarget(
    val host: String = "127.0.0.1",
    val controller: Boolean = true,
    val room: String = "",
)

data class SessionConnection(val target: SessionTarget, val publicAddress: String) {
    val baseUrl
        get() = "http://${target.host}:${NetworkPolicy.PORT}"

    val initialUrl
        get() =
            baseUrl +
                "/" +
                if (target.room.isEmpty()) "" else "?room=${target.room}&role=controller"
}

data class CameraRequest(val host: String, val room: String, val role: String) {
    fun valid() =
        NetworkPolicy.isLocalHost(host) &&
            room.matches(Regex("[A-HJ-NP-Z2-9]{8}")) &&
            role in setOf("board", "cards")
}

object NetworkPolicy {
    const val PORT = 8765

    fun isLocalHost(host: String): Boolean {
        if (!host.matches(Regex("(\\d{1,3}\\.){3}\\d{1,3}"))) return false
        val n = host.split('.').map { it.toInt() }
        return n.all { it in 0..255 } &&
            (n[0] == 10 ||
                n[0] == 127 ||
                (n[0] == 172 && n[1] in 16..31) ||
                (n[0] == 192 && n[1] == 168))
    }

    fun sameOrigin(url: String, host: String): Boolean =
        runCatching {
                val uri = URI(url)
                uri.scheme == "http" && uri.host == host && uri.port == PORT && uri.userInfo == null
            }
            .getOrDefault(false)
}
