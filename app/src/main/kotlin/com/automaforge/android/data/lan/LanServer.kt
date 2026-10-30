package com.automaforge.android.data.lan

import android.content.Context
import android.graphics.*
import com.automaforge.android.BuildConfig
import com.automaforge.android.core.*
import com.automaforge.android.data.ai.*
import com.automaforge.android.data.game.GameStore
import com.automaforge.android.data.rulebook.*
import com.automaforge.android.domain.game.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.*
import java.util.function.BiConsumer
import org.json.*

/** HTTP/WebSocket adapter. Domain rules and durable data live outside the network/UI layers. */
class LanServer
@JvmOverloads
constructor(
    private val context: Context,
    private val ai: AiClient = AiClient(context),
    private val store: GameStore = GameStore(context),
) {
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap<String, MutableSet<WsClient>>()
    private val assisting = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false
    @Volatile private var closed = false
    private val activeConnections = ConcurrentHashMap.newKeySet<Socket>()

    private fun ensureOpen() {
        if (closed) throw ApiException(409, "A sessão do servidor foi encerrada.")
    }

    private var listener: ServerSocket? = null
    private val rulebookJobs =
        RulebookJobs(
            context,
            BiConsumer { code, draft ->
                synchronized(store) {
                    ensureOpen()
                    val room = store.get(code)
                    check(room.optInt("rules_revision") == draft.optInt("base_rules_revision")) {
                        "O manual ou as regras mudaram durante a análise. O resultado ficou no cache; retome com o conteúdo atual."
                    }
                    check(
                        !draft.has("ai_config_revision") ||
                            AiSettings(context).revision() == draft.optInt("ai_config_revision")
                    ) {
                        "A configuração de IA mudou durante a leitura. O resultado anterior não foi ativado; execute novamente com a configuração atual."
                    }
                    draft.optJSONArray("actions")?.let { definitions ->
                        for (i in 0 until definitions.length()) definitions
                            .getJSONObject(i)
                            .put("reviewed", false)
                            .put("validation_complete", false)
                    }
                    draft.optJSONObject("state_model")?.let {
                        it.put("reviewed", false)
                        room.put("state_model_draft", it)
                    }
                    room.put("rules_draft", draft)
                    if (AutoState.enabled(room)) AutoState.activateDefinition(room, draft, true)
                    GameKnowledge.questions(room, draft, "rules")
                    store.save(room, true)
                    broadcast(code)
                }
            },
        )

    fun isRunning() = running

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        ensureOpen()
        if (running) return
        val server =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", PORT))
            }
        listener = server
        running = true
        pool.execute {
            while (running) try {
                val client = server.accept().apply { soTimeout = 600000 }
                activeConnections.add(client)
                try {
                    pool.execute { serve(client) }
                } catch (rejected: RejectedExecutionException) {
                    client.close()
                    activeConnections.remove(client)
                }
            } catch (failure: IOException) {
                if (running) android.util.Log.e("AutomaForge", "accept", failure)
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        closed = true
        activeConnections.toList().forEach { runCatching { it.close() } }
        activeConnections.clear()
        runCatching { listener?.close() }
        listener = null
        sockets.values.forEach { it.toList().forEach(WsClient::close) }
        sockets.clear()
        rulebookJobs.close()
        pool.shutdownNow()
        store.clearAllPrivate()
    }

    private class Request {
        var method = ""
        var path = ""
        val headers = mutableMapOf<String, String>()
        var body = ByteArray(0)

        fun header(key: String) = headers[key.lowercase(Locale.ROOT)].orEmpty()

        fun json(): JSONObject {
            if (body.size > 1024 * 1024) throw ApiException(413, "JSON grande demais.")
            return try {
                JSONObject(String(body, StandardCharsets.UTF_8))
            } catch (failure: Exception) {
                throw ApiException(400, "JSON inválido.")
            }
        }
    }

    private class Response(val status: Int, val body: ByteArray, val contentType: String)

    private class UploadFile(val bytes: ByteArray, val mimeType: String)

    private class WsClient(val out: OutputStream, val connection: Socket, val code: String) {
        @Synchronized
        fun send(value: JSONObject) {
            val bytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            out.write(0x81)
            when {
                bytes.size < 126 -> out.write(bytes.size)
                bytes.size <= 65535 -> {
                    out.write(126)
                    out.write(bytes.size shr 8)
                    out.write(bytes.size)
                }
                else -> {
                    out.write(127)
                    for (i in 7 downTo 0) out.write(
                        ((bytes.size.toLong() ushr (8 * i)) and 255).toInt()
                    )
                }
            }
            out.write(bytes)
            out.flush()
        }

        fun close() {
            runCatching { connection.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (byte == '\n'.code) return out.toString("ISO-8859-1")
            if (out.size() > 16384) throw ApiException(431, "Cabeçalho muito longo.")
            if (byte != '\r'.code) out.write(byte)
        }
    }

    private fun streamBytes(input: InputStream) = input.readBytes()

    private fun locate(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun websocket(req: Request, input: InputStream, out: OutputStream, socket: Socket) {
        val code = req.path.substring(4).uppercase(Locale.ROOT)
        val room =
            synchronized(store) {
                ensureOpen()
                store.publicRoom(store.get(code))
            }
        val key = req.header("sec-websocket-key")
        if (key.isEmpty()) throw ApiException(400, "Chave WebSocket ausente.")
        val sha =
            MessageDigest.getInstance("SHA-1")
                .digest(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(
                        StandardCharsets.US_ASCII
                    )
                )
        val accept = android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP)
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII)
        )
        out.flush()
        val client = WsClient(out, socket, code)
        sockets.computeIfAbsent(code) { ConcurrentHashMap.newKeySet() }.add(client)
        try {
            client.send(GameJson.obj("type", "state", "room", room))
            while (running && !socket.isClosed) {
                val opcode = input.read()
                if (opcode < 0 || (opcode and 15) == 8) break
                val b = input.read()
                if (b < 0) break
                var count = (b and 127).toLong()
                if (count == 126L) count = ((input.read() shl 8) or input.read()).toLong()
                else if (count == 127L) {
                    count = 0
                    repeat(8) { count = (count shl 8) or (input.read() and 255).toLong() }
                }
                if (count < 0 || count > 65536) break
                if ((b and 128) != 0) readExactly(input, 4)
                readExactly(input, count.toInt())
            }
        } finally {
            sockets[code]?.remove(client)
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use { client ->
                val input = BufferedInputStream(client.getInputStream())
                val out = client.getOutputStream()
                try {
                    val request = parse(input) ?: return
                    ensureOpen()
                    if (
                        request.path.startsWith("/ws/") &&
                            request.header("upgrade").equals("websocket", ignoreCase = true)
                    ) {
                        websocket(request, input, out, client)
                        return
                    }
                    reply(out, dispatch(request, client))
                } catch (failure: ApiException) {
                    reply(out, json(failure.status, GameJson.obj("detail", failure.message)))
                } catch (failure: Exception) {
                    android.util.Log.e("AutomaForge", "Request failed", failure)
                    reply(
                        out,
                        json(
                            500,
                            GameJson.obj(
                                "detail",
                                "Erro interno. Consulte os registros do Android.",
                            ),
                        ),
                    )
                }
            }
        } catch (ignored: Exception) {} finally {
            activeConnections.remove(socket)
        }
    }

    private fun staticFile(path: String): Response {
        var file = (if ((path == "/")) "index.html" else path.substring("/static/".length))
        if (!file.matches(Regex("(index\\.html|app\\.js|style\\.css)"))) {
            throw ApiException(404, "Arquivo não encontrado.")
        }
        var type =
            (if (file.endsWith(".html")) "text/html; charset=utf-8"
            else
                (if (file.endsWith(".js")) "application/javascript; charset=utf-8"
                else "text/css; charset=utf-8"))
        context.getAssets().open(("web/" + file)).use { `in` ->
            return Response(200, streamBytes(`in`), type)
        }
    }

    @Throws(Exception::class)
    private fun dispatch(req: Request, client: Socket?): Response {
        ensureOpen()
        var path = req.path
        var method = req.method
        if (((method == "GET") && (((path == "/") || path.startsWith("/static/"))))) {
            return staticFile(path)
        }
        if (((path == "/api/health") && (method == "GET"))) {
            return success(
                GameJson.obj(
                    "status",
                    "ok",
                    "ai_enabled",
                    ai.enabled(),
                    "model",
                    ai.model(),
                    "reasoning_effort",
                    ai.reasoningEffort(),
                    "reasoning_label",
                    AiModels.effortLabel(ai.reasoningEffort()),
                    "https",
                    false,
                    "android_host",
                    true,
                    "app_version",
                    BuildConfig.VERSION_NAME,
                    "state_contract",
                    3,
                    "manual_default",
                    "pdf_lossless",
                )
            )
        }
        if (((path == "/api/ai/settings") && (method == "GET"))) {
            return success(
                AiSettings(context)
                    .publicSettings()
                    .put("key_configured", ai.enabled())
                    .put(
                        "can_configure",
                        (((client != null) && (client.getInetAddress() != null)) &&
                            client.getInetAddress().isLoopbackAddress()),
                    )
            )
        }
        if (((path == "/api/ai/settings") && (method == "PUT"))) {
            if (
                (((client == null) || (client.getInetAddress() == null)) ||
                    !client.getInetAddress().isLoopbackAddress())
            ) {
                throw ApiException(
                    403,
                    "Altere a configuração de IA no controlador, pelo botão IA do APK ou navegador local.",
                )
            }
            var body = req.json()
            if (body.has("api_key")) {
                throw ApiException(422, "Cadastre a chave somente na tela nativa do controlador.")
            }
            try {
                AiSettings(context)
                    .save(body.optString("model"), body.optString("reasoning_effort", "default"))
            } catch (e: IllegalArgumentException) {
                throw ApiException(422, e.message ?: "Falha de validação.")
            }
            return success(AiSettings(context).publicSettings())
        }
        if (((path == "/api/rooms") && (method == "POST"))) {
            synchronized(store) {
                ensureOpen()

                return success(store.create(req.json().optString("game_name", "Jogo sem nome")))
            }
        }
        if (!path.startsWith("/api/rooms/")) {
            throw ApiException(404, "Rota não encontrada.")
        }
        var p = path.substring("/api/rooms/".length).split(Regex("/"))
        if ((p.size < 1)) {
            throw ApiException(404, "Sala não encontrada.")
        }
        var code = p[0].uppercase(Locale.ROOT)
        // Reject path traversal with the same validation used by GameStore.
        synchronized(store) {
            ensureOpen()
            store.get(code)
        }
        var rest = path.substring(("/api/rooms/".length + p[0].length))
        if (((method == "GET") && rest.isEmpty())) {
            synchronized(store) {
                ensureOpen()

                return success(store.publicRoom(store.get(code)))
            }
        }
        if (((method == "PUT") && (rest == "/rules"))) {
            var body = req.json()
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                if (!body.optBoolean("human_validated")) {
                    throw ApiException(
                        422,
                        "Confirme a revisão das regras e ambiguidades antes de salvar.",
                    )
                }
                r.put("game_name", GameJson.clip(body.optString("game_name", "Jogo sem nome"), 100))
                r.put("rules_text", body.optString("rules_text"))
                r.put("rules_human_validated", true)
                r.put("setup", body.optString("setup"))
                r.put("turn_flow", body.optString("turn_flow"))
                r.put("actions", normalizeActions(body.optJSONArray("actions")))
                GameEngine.changed(r)
                if ((body.optJSONObject("state_model") != null)) {
                    var m = GameEngine.copy(body.getJSONObject("state_model"))
                    GameKnowledge.validateModel(m)
                    m.put("reviewed", true)
                    if ((body.optJSONArray("actions") != null)) {
                        var proposed = normalizeActions(body.getJSONArray("actions"))
                        var current = r.getJSONArray("actions")
                        if ((current.length() > 0)) {
                            if ((proposed.length() != current.length())) {
                                throw ApiException(
                                    422,
                                    "A parametrização deve preservar todas as ações cadastradas.",
                                )
                            }
                            run {
                                var i = 0
                                while ((i < current.length())) {
                                    if (
                                        (GameKnowledge.named(
                                            proposed,
                                            "name",
                                            current.getJSONObject(i).getString("name"),
                                        ) == null)
                                    ) {
                                        throw ApiException(
                                            422,
                                            "A parametrização alterou nomes de ações; revise nas regras.",
                                        )
                                    }
                                    i++
                                }
                            }
                        }
                        run {
                            var i = 0
                            while ((i < proposed.length())) {
                                proposed
                                    .getJSONObject(i)
                                    .put("reviewed", true)
                                    .put("validation_complete", false)
                                i++
                            }
                        }
                        r.put("actions", proposed)
                        r.put("actions_draft", JSONObject.NULL)
                    }
                    r.put("state_model", m)
                    r.put("state_model_draft", JSONObject.NULL)
                }
                r.put(
                    "uncertainties",
                    (if ((body.optJSONArray("uncertainties") != null))
                        body.optJSONArray("uncertainties")
                    else JSONArray()),
                )
                rulebookJobs.saveValidated(manualFiles(code, r), body)
                r.put("rules_revision", (r.optInt("rules_revision") + 1))
                // Keep the exact interpreted evidence alongside the user's editable corrections.
                if ((r.optJSONObject("rules_draft") != null)) {
                    r.put("rules_evidence", r.optJSONObject("rules_draft"))
                }
                r.put("rules_draft", JSONObject.NULL)
                r.put("proposal", JSONObject.NULL)
                if (
                    ((body.optJSONObject("state_model") == null) &&
                        (r.optJSONObject("state_model") != null))
                ) {
                    r.getJSONObject("state_model").put("reviewed", false)
                }
                GameKnowledge.refreshQuestions(r)
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "POST") && (rest == "/rules/pages"))) {
            var files = files(req)
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var names = r.getJSONArray("rule_pages")
                if (
                    ((files.isEmpty() || (files.size > 12)) || ((files.size + names.length()) > 30))
                ) {
                    throw ApiException(400, "Limite de 12 arquivos por envio e 30 por sala.")
                }
                var folder = store.photoDir(code, "rules")
                for (content in files) {
                    var pdf = ("application/pdf" == content.mimeType)
                    if ((pdf && !isPdf(content.bytes))) {
                        throw ApiException(400, "Arquivo PDF inválido.")
                    }
                    var name = (UUID.randomUUID().toString() + ((if (pdf) ".pdf" else ".jpg")))
                    var stored = (if (pdf) content.bytes else jpeg(content.bytes, 2600))
                    Files.write(File(folder, name).toPath(), stored)
                    names.put(name)
                }
                r.put("rules_revision", (r.optInt("rules_revision") + 1))
                r.put("rules_draft", JSONObject.NULL)
                store.save(r, true)
                broadcast(code)
                return success(GameJson.obj("pages_count", names.length()))
            }
        }
        if (((method == "GET") && (rest == "/rules/processing"))) {
            return success(rulebookJobs.status(code))
        }
        if (((method == "POST") && (rest == "/rules/cancel"))) {
            return success(rulebookJobs.cancel(code))
        }
        if (((method == "DELETE") && (rest == "/rules/manuals"))) {
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var originals = manualFiles(code, r)
                try {
                    rulebookJobs.delete(code, originals)
                } catch (e: IllegalStateException) {
                    throw ApiException(409, e.message ?: "Falha de validação.")
                }
                for (file in originals) Files.deleteIfExists(file.toPath())
                r.put("rule_pages", JSONArray())
                r.put("rules_draft", JSONObject.NULL)
                r.put("rules_revision", (r.optInt("rules_revision") + 1))
                store.save(r, true)
                broadcast(code)
                return success(GameJson.obj("deleted", true))
            }
        }
        if (((method == "POST") && (rest == "/rules/interpret"))) {
            var body = (if ((req.body.size == 0)) JSONObject() else req.json())
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                try {
                    return success(
                        rulebookJobs.start(
                            code,
                            r.optString("game_name"),
                            manualFiles(code, r),
                            body.optBoolean("retry_uncertain"),
                            body.optBoolean("original_fallback"),
                            r.optInt("rules_revision"),
                            body.optString("mode", "pdf_lossless"),
                            GameKnowledge.context(r).toString(),
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    throw ApiException(409, e.message ?: "Falha de validação.")
                }
            }
        }
        if (((method == "POST") && (rest == "/state/model/discover"))) {
            var snapshot: JSONObject
            var revision: Int = 0
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                revision = r.optInt("revision")
                snapshot = GameEngine.copy(r)
                if (r.optString("rules_text").isEmpty()) {
                    throw ApiException(
                        409,
                        "Importe o manual ou informe as regras antes de identificar as variáveis.",
                    )
                }
            }
            var result = ai.snapshot().discoverModel(snapshot)
            var m = result.getJSONObject("state_model")
            GameKnowledge.validateModel(m)
            m.put("reviewed", false)
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                if ((r.optInt("revision") != revision)) {
                    throw ApiException(
                        409,
                        "As regras ou o estado mudaram durante a identificação.",
                    )
                }
                r.put("state_model_draft", m)
                var actionDraft = normalizeActions(result.optJSONArray("actions"))
                run {
                    var i = 0
                    while ((i < actionDraft.length())) {
                        actionDraft
                            .getJSONObject(i)
                            .put("reviewed", false)
                            .put("validation_complete", false)
                        i++
                    }
                }
                r.put("actions_draft", actionDraft)
                if (AutoState.enabled(r)) {
                    AutoState.activateDefinition(r, result, false)
                }
                GameKnowledge.questions(r, result, "rules")
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "PUT") && (rest == "/state/model"))) {
            var body = req.json()
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                GameKnowledge.versionGuard(r, body)
                if (!body.optBoolean("human_validated")) {
                    throw ApiException(422, "Revise o modelo de estado antes de confirmar.")
                }
                var m = GameEngine.copy(body.getJSONObject("state_model"))
                GameKnowledge.validateModel(m)
                m.put("reviewed", true)
                if ((body.optJSONArray("actions") != null)) {
                    var proposed = normalizeActions(body.getJSONArray("actions"))
                    var current = r.getJSONArray("actions")
                    if ((current.length() > 0)) {
                        if ((proposed.length() != current.length())) {
                            throw ApiException(
                                422,
                                "A parametrização deve preservar todas as ações cadastradas.",
                            )
                        }
                        run {
                            var i = 0
                            while ((i < current.length())) {
                                if (
                                    (GameKnowledge.named(
                                        proposed,
                                        "name",
                                        current.getJSONObject(i).getString("name"),
                                    ) == null)
                                ) {
                                    throw ApiException(
                                        422,
                                        "A parametrização alterou nomes de ações; revise nas regras.",
                                    )
                                }
                                i++
                            }
                        }
                    }
                    run {
                        var i = 0
                        while ((i < proposed.length())) {
                            proposed
                                .getJSONObject(i)
                                .put("reviewed", true)
                                .put("validation_complete", false)
                            i++
                        }
                    }
                    r.put("actions", proposed)
                    r.put("actions_draft", JSONObject.NULL)
                }
                r.put("state_model", m)
                r.put("state_model_draft", JSONObject.NULL)
                r.put("rules_revision", (r.optInt("rules_revision") + 1))
                GameKnowledge.ensure(r)
                var previousFacts = GameEngine.state(r).getJSONArray("facts")
                run {
                    var i = 0
                    while ((i < previousFacts.length())) {

                        var f = previousFacts.getJSONObject(i)
                        f.put("status", "unknown")
                        f.put("model_review_pending", true)

                        i++
                    }
                }
                GameEngine.changed(r)
                GameKnowledge.refreshQuestions(r)
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "POST") && (rest == "/questions/answer"))) {
            var body = req.json()
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var work = GameEngine.copy(r)
                GameKnowledge.answer(work, body)
                GameKnowledge.refreshQuestions(work)
                run {
                    var keys = work.keys()
                    while (keys.hasNext()) {

                        var key = keys.next()
                        r.put(key, work.get(key))
                    }
                }
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "GET") && (rest == "/questions"))) {
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                GameKnowledge.refreshQuestions(r)
                store.save(r, false)
                return success(
                    GameJson.obj(
                        "questions",
                        r.getJSONArray("questions"),
                        "clarifications",
                        r.getJSONArray("clarifications"),
                    )
                )
            }
        }
        if (((method == "PUT") && (rest == "/state/facts"))) {
            var body = req.json()
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var work = GameEngine.copy(r)
                GameKnowledge.ensure(work)
                GameKnowledge.versionGuard(work, body)
                var s = GameEngine.state(work)
                var before = s.getInt("version")
                var facts = body.optJSONArray("facts")
                if ((facts != null)) {
                    run {
                        var i = 0
                        while ((i < facts.length())) {
                            GameKnowledge.putFact(work, s, facts.getJSONObject(i), true)
                            i++
                        }
                    }
                }
                for (field in
                    Arrays.asList("active_player", "automa_player", "phase", "substep")) if (
                    body.has(field)
                ) {
                    s.put(field, GameJson.clip(body.getString(field), 140))
                }
                if (body.has("turn")) {
                    var turn = body.getInt("turn")
                    if ((turn < 1)) {
                        throw ApiException(422, "Turno deve ser positivo.")
                    }
                    s.put("turn", turn)
                }
                if (
                    ((!s.optString("active_player").isEmpty() && !s.optString("phase").isEmpty()) &&
                        !s.optString("substep").isEmpty())
                ) {
                    s.put("metadata_status", "confirmed")
                }
                GameEngine.changed(work)
                GameKnowledge.rebaseQuestions(work, before)
                GameKnowledge.refreshQuestions(work)
                if (
                    (work.optBoolean("board_frozen") &&
                        (work.optInt("photo_state_version", -1) == before))
                ) {
                    work.put("photo_state_version", s.getInt("version"))
                }
                work.put("phase", s.optString("phase"))
                work.put("turn", s.optInt("turn", 1))
                run {
                    var keys = work.keys()
                    while (keys.hasNext()) {

                        var key = keys.next()
                        r.put(key, work.get(key))
                    }
                }
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "POST") && (rest == "/board/confirm-state"))) {
            var body = req.json()
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var o = r.optJSONObject("board_observation")
                if (
                    ((((o == null) ||
                        (body.optInt("photo_version", -1) != r.optInt("board_version"))) ||
                        (o.optInt("photo_version") != r.optInt("board_version"))) ||
                        (o.optInt("state_version") != GameEngine.state(r).getInt("version")))
                ) {
                    throw ApiException(
                        409,
                        "Foto/análise desatualizada; reanalise antes de confirmar.",
                    )
                }
                var work = GameEngine.copy(r)
                GameEngine.correct(work, body.getJSONObject("state"))
                work.put("photo_state_version", GameEngine.state(work).getInt("version"))
                work
                    .getJSONObject("board_observation")
                    .put("state_version", GameEngine.state(work).getInt("version"))
                GameKnowledge.refreshQuestions(work)
                run {
                    var keys = work.keys()
                    while (keys.hasNext()) {

                        var key = keys.next()
                        r.put(key, work.get(key))
                    }
                }
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "PUT") && (rest == "/state/structured"))) {
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                GameEngine.correct(r, req.json())
                GameEngine.state(r)
                    .put("physical_version", (GameEngine.state(r).optInt("physical_version") + 1))
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "PUT") && (rest == "/state"))) {
            var body = req.json()
            var turn = body.optInt("turn", 0)
            if (((turn < 1) || (turn > 1000000))) {
                throw ApiException(400, "Turno deve ser positivo.")
            }
            var vars = body.optJSONObject("variables")
            if ((vars == null)) {
                vars = JSONObject()
            }
            if ((vars.length() > 100)) {
                throw ApiException(400, "Limite de 100 variáveis.")
            }
            var it = vars.keys()
            while (it.hasNext()) {
                var key = it.next()
                if (((key.length > 100) || (vars.optString(key).length > 1000))) {
                    throw ApiException(400, "Variável grande demais.")
                }
            }
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                r.put("turn", turn)
                r.put("phase", GameJson.clip(body.optString("phase"), 140))
                r.put("board_notes", GameJson.clip(body.optString("board_notes"), 12000))
                r.put("variables", vars)
                var state = GameEngine.state(r)
                state.put("turn", turn)
                state.put("phase", r.optString("phase"))
                GameEngine.changed(r)
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "POST") && (rest == "/board/freeze"))) {
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                r.put("board_frozen", req.json().optBoolean("frozen"))
                store.save(r, false)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "POST") && (rest == "/board/photo"))) {
            var content = files(req).get(0).bytes
            var compressed = jpeg(content, 2560)
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                if (r.optBoolean("board_frozen")) {
                    return success(
                        GameJson.obj("board_version", r.optInt("board_version"), "frozen", true)
                    )
                }
                Files.write(store.boardPhoto(code).toPath(), compressed)
                var version = (r.optInt("board_version") + 1)
                r.put("board_version", version)
                r.put("photo_at", GameJson.now())
                r.put("photo_state_version", GameEngine.state(r).getInt("version"))
                r.put("photo_physical_version", GameEngine.state(r).optInt("physical_version"))
                store.save(r, false)
                broadcastEvent(code, GameJson.obj("type", "board", "board_version", version))
                return success(GameJson.obj("board_version", version))
            }
        }
        if (((method == "GET") && (rest == "/board/photo"))) {
            synchronized(store) {
                ensureOpen()

                var file = store.boardPhoto(code)
                if (!file.exists()) {
                    throw ApiException(404, "Foto indisponível.")
                }
                return image(Files.readAllBytes(file.toPath()))
            }
        }
        if (((method == "POST") && (rest == "/board/analyze"))) {
            return analyzeForRoom(code)
        }
        if (((method == "POST") && (rest == "/cards/private"))) {
            var photo = jpeg(files(req).get(0).bytes, 1600)
            var snapshot: JSONObject
            synchronized(store) {
                ensureOpen()

                if ((store.privateCards(code).length() >= 40)) {
                    throw ApiException(409, "Limite de 40 cartas privadas.")
                }
                snapshot = JSONObject(store.get(code).toString())
            }
            var reading = ai.privateCard(snapshot, photo)
            synchronized(store) {
                ensureOpen()

                var privateCards = store.privateCards(code)
                if ((privateCards.length() >= 40)) {
                    throw ApiException(409, "Limite de cartas atingido.")
                }
                privateCards.put(reading)
                // No image, card text or any sensitive interpretation written to disk or public
                // JSON.
                broadcast(code)
                return success(
                    GameJson.obj(
                        "stored",
                        true,
                        "private_card_count",
                        privateCards.length(),
                        "has_uncertainties",
                        ((reading.optJSONArray("uncertainties") != null) &&
                            ((reading.optJSONArray("uncertainties")?.length() ?: 0) > 0)),
                    )
                )
            }
        }
        if (((method == "DELETE") && (rest == "/cards/private"))) {
            synchronized(store) {
                ensureOpen()

                store.clearPrivate(code)
                broadcast(code)
                return success(GameJson.obj("private_card_count", 0))
            }
        }
        if (((method == "POST") && (rest == "/automa/manual"))) {
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                if (!r.isNull("proposal")) {
                    throw ApiException(409, "Resolva a proposta pendente.")
                }
                var pData = req.json()
                var proposal = GameEngine.proposal(r, pData)
                if (
                    (("invalid" == proposal.getJSONObject("validation").getString("status")) ||
                        (proposal.getJSONObject("validation").getJSONArray("blockers").length() >
                            0))
                ) {
                    throw ApiException(422, proposal.getJSONObject("validation").toString())
                }
                r.put("proposal", proposal)
                r.put("decision", JSONObject.NULL)
                store.save(r, true)
                broadcast(code)
                return success(proposal)
            }
        }
        if (((method == "POST") && (rest == "/automa/propose"))) {
            return proposeForRoom(code)
        }
        if (((method == "POST") && (rest == "/automa/assist"))) {
            return assist(code)
        }
        if (((method == "POST") && (rest == "/automa/reject"))) {
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var pData = r.optJSONObject("proposal")
                if (
                    ((pData != null) &&
                        !(pData.optString("id") == req.json().optString("proposal_id")))
                ) {
                    throw ApiException(409, "Proposta mudou.")
                }
                r.put("proposal", JSONObject.NULL)
                r.put("decision", JSONObject.NULL)
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "POST") && (rest == "/automa/confirm"))) {
            var body = req.json()
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var proposal = r.optJSONObject("proposal")
                if (
                    ((proposal == null) ||
                        !(proposal.optString("id") == body.optString("proposal_id")))
                ) {
                    throw ApiException(409, "Proposta ausente ou alterada.")
                }
                var validation = GameEngine.validate(r, proposal)
                validation.remove("simulated")
                proposal.put("validation", validation)
                if (("invalid" == validation.optString("status"))) {
                    throw ApiException(422, validation.toString())
                }
                if (
                    (("unverifiable" == validation.optString("status")) &&
                        !body.optBoolean("acknowledge_unverified"))
                ) {
                    throw ApiException(
                        422,
                        "Revise as restrições não verificáveis e confirme explicitamente.",
                    )
                }
                proposal.put("execution_status", "approved")
                proposal.put("approved_at", GameJson.now())
                r.put(
                    "last_announcement",
                    GameJson.obj(
                        "id",
                        proposal.optString("id"),
                        "text",
                        proposal.optString("spoken"),
                    ),
                )
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        if (((method == "POST") && (rest == "/automa/executed"))) {
            var body = req.json()
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var proposal = r.optJSONObject("proposal")
                if (
                    (((proposal == null) ||
                        !(proposal.optString("id") == body.optString("proposal_id"))) ||
                        !("approved" == proposal.optString("execution_status")))
                ) {
                    throw ApiException(409, "Aprove a proposta atual primeiro.")
                }
                var full = GameEngine.validate(r, proposal)
                if (("invalid" == full.optString("status"))) {
                    throw ApiException(409, full.toString())
                }
                var count = body.optInt("completed_moves", -1)
                var total = proposal.getJSONArray("moves").length()
                if (((count < 0) || (count > total))) {
                    throw ApiException(
                        422,
                        (("Informe quantos movimentos iniciais foram executados, de 0 a " + total) +
                            "."),
                    )
                }
                var next = full.getJSONObject("simulated")
                if ((count < total)) {
                    // A prefix changes physical state but does not apply end-of-action effects or
                    // total quotas.
                    var partialRoom = GameEngine.copy(r)
                    var partial = GameEngine.copy(proposal)
                    val a =
                        GameEngine.action(partialRoom, proposal.optString("action"))
                            ?: throw ApiException(422, "Ação não cadastrada.")
                    a.put("min_moves", 0)
                    a.put("effects", JSONArray())
                    var cs = JSONArray()
                    var original = a.optJSONArray("constraints")
                    if ((original != null)) {
                        run {
                            var i = 0
                            while ((i < original.length())) {

                                var c = original.getJSONObject(i)
                                if (
                                    !Arrays.asList("min_quantity", "max_quantity")
                                        .contains(c.optString("kind"))
                                ) {
                                    cs.put(c)
                                }

                                i++
                            }
                        }
                    }
                    a.put("constraints", cs)
                    var prefix = JSONArray()
                    run {
                        var i = 0
                        while ((i < count)) {
                            prefix.put(proposal.getJSONArray("moves").get(i))
                            i++
                        }
                    }
                    partial.put("moves", prefix)
                    next =
                        (if ((count == 0)) GameEngine.copy(GameEngine.state(r))
                        else GameEngine.validate(partialRoom, partial).getJSONObject("simulated"))
                }
                // Physical execution is reported by a person, not inferred from approval or camera
                // silence.
                var entities = next.getJSONArray("entities")
                var moved = HashSet<String>()
                run {
                    var i = 0
                    while ((i < count)) {

                        var m = proposal.getJSONArray("moves").getJSONObject(i)
                        if (("transfer" == m.optString("operation"))) {
                            moved.add(m.optString("component"))
                        }

                        i++
                    }
                }
                run {
                    var i = 0
                    while ((i < entities.length())) {

                        var e = entities.getJSONObject(i)
                        var before = GameEngine.entity(GameEngine.state(r), e.optString("id"))
                        // Confirm only the moved units/locations reported by the person, not all
                        // unrelated vision data.
                        if (
                            ((before == null) ||
                                ((moved.contains(e.optString("id")) &&
                                    !(e.optString("location") == before.optString("location")))))
                        ) {
                            e.put("human_locked", true)
                            e.put("status", "confirmed")
                            e.put("source", "human_execution")
                        }

                        i++
                    }
                }
                var facts = GameKnowledge.array(next, "facts")
                run {
                    var i = 0
                    while ((i < facts.length())) {

                        var f = facts.getJSONObject(i)
                        var d = GameKnowledge.definition(r, f.optString("key"))
                        if (
                            (((count > 0) && (d != null)) &&
                                ((GameEngine.contains(
                                    GameKnowledge.array(d, "changes_with_actions"),
                                    proposal.optString("action"),
                                ) ||
                                    GameEngine.contains(
                                        GameKnowledge.array(d, "changes_with_actions"),
                                        "*",
                                    ))))
                        ) {
                            f.put("status", "unknown")
                            f.put("source", "execution_requires_update")
                        }

                        i++
                    }
                }
                next.put(
                    "physical_version",
                    (GameEngine.state(r).optInt("physical_version") + ((if ((count > 0)) 1 else 0))),
                )
                next.put("version", (GameEngine.state(r).getInt("version") + 1))
                r.put("game_state", next)
                r.put("phase", next.optString("phase"))
                r.put("turn", next.optInt("turn", 1))
                var history = GameEngine.copy(proposal)
                history.put("completed_moves", count)
                history.put("execution_status", (if ((count == total)) "executed" else "partial"))
                history.put("at", GameJson.now())
                history.put("turn", r.optInt("turn"))
                var all = r.getJSONArray("history")
                all.put(history)
                if ((all.length() > 100)) {
                    var trim = JSONArray()
                    run {
                        var i = (all.length() - 100)
                        while ((i < all.length())) {
                            trim.put(all.get(i))
                            i++
                        }
                    }
                    r.put("history", trim)
                }
                r.put("proposal", JSONObject.NULL)
                r.put("decision", JSONObject.NULL)
                r.put("board_frozen", false)
                GameKnowledge.refreshQuestions(r)
                store.save(r, true)
                broadcast(code)
                return success(store.publicRoom(r))
            }
        }
        throw ApiException(404, "Rota não encontrada.")
    }

    @Throws(Exception::class)
    private fun stage(code: String, status: String, text: String): Unit {
        synchronized(store) {
            ensureOpen()

            var r = store.get(code)
            r.put("assist_task", GameJson.obj("status", status, "stage", text))
            store.save(r, false)
            broadcast(code)
        }
    }

    @Throws(Exception::class)
    private fun ensureDefinition(code: String): Unit {
        var snapshot: JSONObject
        var revision: Int = 0
        var config = AiSettings(context).revision()
        synchronized(store) {
            ensureOpen()

            var r = store.get(code)
            AutoState.defaults(r)
            if (AutoState.modelReady(r)) {
                return
            }
            var draft = r.optJSONObject("rules_draft")
            if (((draft != null) && (draft.optJSONObject("state_model") != null))) {
                AutoState.activateDefinition(r, draft, true)
                store.save(r, true)
                broadcast(code)
                return
            }
            if (r.optString("rules_text").isEmpty()) {
                throw ApiException(
                    409,
                    "Envie o manual para a IA identificar as regras e os dados do jogo.",
                )
            }
            revision = r.optInt("revision")
            snapshot = GameEngine.copy(r)
        }
        var result = ai.snapshot().discoverModel(snapshot)
        synchronized(store) {
            ensureOpen()

            var r = store.get(code)
            if (
                ((r.optInt("revision") != revision) || (AiSettings(context).revision() != config))
            ) {
                throw ApiException(409, "Estado ou modelo de IA mudou durante a identificação.")
            }
            AutoState.activateDefinition(r, result, false)
            store.save(r, true)
            broadcast(code)
        }
    }

    @Throws(Exception::class)
    private fun assist(code: String): Response {
        if (!assisting.add(code)) {
            throw ApiException(409, "A IA já está trabalhando nesta sala.")
        }
        var config = AiSettings(context).revision()
        try {
            if (!ai.enabled()) {
                throw ApiException(
                    409,
                    "Configure a chave e escolha o modelo no botão IA do controlador.",
                )
            }
            stage(code, "running", "Identificando os dados necessários deste jogo")
            ensureDefinition(code)
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                if (!r.isNull("proposal")) {
                    throw ApiException(409, "Resolva a proposta pendente.")
                }
                // This button explicitly asks for the automa's turn. It does not override a known
                // different active player.
                AutoState.assertAutomaTurn(r)
                store.save(r, true)
            }
            var analyze: Boolean = false
            synchronized(store) {
                ensureOpen()

                var r = store.get(code)
                var o = r.optJSONObject("board_observation")
                analyze =
                    (store.boardPhoto(code).exists() &&
                        ((((((o == null) || !o.optBoolean("automatically_applied")) ||
                            (o.optInt("clarifications_count", -1) !=
                                GameKnowledge.array(r, "clarifications").length())) ||
                            (o.optInt("photo_version") != r.optInt("board_version"))) ||
                            (o.optInt("state_version") != GameEngine.state(r).optInt("version")))))
            }
            if (analyze) {
                stage(code, "running", "Identificando tabuleiros, peças e variáveis na foto")
                analyzeForRoom(code)
            }
            if ((AiSettings(context).revision() != config)) {
                throw ApiException(
                    409,
                    "A configuração de IA mudou. Inicie novamente usando a configuração atual.",
                )
            }
            stage(code, "running", "Simulando uma jogada concreta para a automa")
            var response = proposeForRoom(code)
            stage(
                code,
                "done",
                "Análise concluída: confira a proposta ou responda ao dado que falta",
            )
            return response
        } catch (failure: Exception) {
            stage(
                code,
                "error",
                (if ((failure is ApiException)) (failure.message ?: "Falha de análise.")
                else
                    "Não foi possível concluir a identificação. Tente uma foto mais legível ou corrija o dado indicado."),
            )
            throw failure
        } finally {
            assisting.remove(code)
        }
    }

    @Throws(Exception::class)
    private fun analyzeForRoom(code: String): Response {
        ensureDefinition(code)
        var aiRevision = AiSettings(context).revision()
        var inference = ai.snapshot()
        var snapshot: JSONObject
        var photo: ByteArray
        var revision: Int = 0
        synchronized(store) {
            ensureOpen()

            var r = store.get(code)
            revision = r.optInt("revision")
            snapshot = JSONObject(r.toString())
            if (!AutoState.modelReady(r)) {
                throw ApiException(
                    409,
                    "A análise ainda não conseguiu identificar os dados necessários pelas regras deste jogo.",
                )
            }
            var file = store.boardPhoto(code)
            if (!file.exists()) {
                throw ApiException(409, "Envie foto do tabuleiro.")
            }
            if (!AutoState.photoCurrent(r)) {
                throw ApiException(
                    409,
                    "Foto anterior à atualização do estado. Libere a captura e envie uma foto atual.",
                )
            }
            photo = Files.readAllBytes(file.toPath())
            r.put("board_frozen", true)
            store.save(r, false)
            broadcast(code)
        }
        var observation =
            inference.board(
                snapshot,
                photo,
                rulebookJobs.boardReferences(GameKnowledge.model(snapshot)),
            )
        synchronized(store) {
            ensureOpen()

            var r = store.get(code)
            if (
                (((AiSettings(context).revision() != aiRevision) ||
                    (r.optInt("revision") != revision)) ||
                    (r.optInt("board_version") != snapshot.optInt("board_version")))
            ) {
                throw ApiException(409, "Estado ou foto mudou durante análise; analise novamente.")
            }
            if (!AutoState.photoCurrent(r)) {
                throw ApiException(
                    409,
                    "Foto anterior à atualização do estado. Capture uma foto atual.",
                )
            }
            GameEngine.observation(r, observation, r.optInt("board_version"))
            r.put("board_observation", observation)
            if (AutoState.enabled(r)) {
                AutoState.applyObservation(r, observation, r.optInt("board_version"))
            }
            GameKnowledge.questions(r, r.getJSONObject("board_observation"), "board")
            GameKnowledge.refreshQuestions(r)
            store.save(r, true)
            broadcast(code)
            return success(observation)
        }
    }

    @Throws(Exception::class)
    private fun proposeForRoom(code: String): Response {
        var aiRevision = AiSettings(context).revision()
        var inference = ai.snapshot()
        var snapshot: JSONObject
        var cards: JSONArray
        var revision: Int = 0
        synchronized(store) {
            ensureOpen()

            var r = store.get(code)
            revision = r.optInt("revision")
            if (!r.isNull("proposal")) {
                throw ApiException(409, "Resolva a jogada pendente.")
            }
            if ((r.getJSONArray("actions").length() == 0)) {
                throw ApiException(409, "Cadastre pelo menos uma ação.")
            }
            snapshot = JSONObject(r.toString())
            cards = JSONArray(store.privateCards(code).toString())
        }
        if (!ai.enabled()) {
            throw ApiException(409, "Configure a IA ou use a entrada manual parametrizada.")
        }
        var proposal: JSONObject? = null
        var decision: JSONObject? = null
        var errors = JSONArray()
        run {
            var attempt = 0
            while ((attempt < 3)) {

                var answer = inference.propose(snapshot, cards, errors)
                if (!("proposed" == answer.optString("status"))) {
                    // No generated prose or arbitrary diagnostics from private context reaches LAN
                    // clients.
                    var needed = JSONArray()
                    var input = answer.optJSONArray("needed_data")
                    if ((input != null)) {
                        run {
                            var i = 0
                            while ((i < input.length())) {

                                var n = input.getJSONObject(i)
                                var id = n.optString("entity")
                                var field = n.optString("field")
                                if (
                                    (((id.isEmpty() ||
                                        (GameEngine.entity(GameEngine.state(snapshot), id) !=
                                            null))) &&
                                        ((Arrays.asList(
                                                "active_player",
                                                "phase",
                                                "substep",
                                                "turn",
                                                "entities",
                                                "occupancy_known",
                                                "quantity",
                                                "level",
                                                "type",
                                                "location",
                                                "group",
                                                "calibration",
                                                "photo_coverage",
                                                "required_state",
                                                "actions",
                                                "parameters",
                                                "source",
                                                "destination",
                                                "owner",
                                                "area_type",
                                            )
                                            .contains(field) ||
                                            (((GameKnowledge.definition(snapshot, field) != null) &&
                                                ("public" ==
                                                    GameKnowledge.definition(snapshot, field)
                                                        ?.optString("visibility")))))))
                                ) {
                                    needed.put(GameJson.obj("entity", id, "field", field))
                                }

                                i++
                            }
                        }
                    }
                    var status = answer.optString("status")
                    decision =
                        GameJson.obj(
                            "status",
                            (if (("needs_data" == status)) "needs_data" else "analysis_error"),
                            "needed_data",
                            needed,
                            "message",
                            (if (("no_legal_move" == status))
                                "IA alegou ausência de jogada; o aplicativo não possui busca exaustiva para comprová-la."
                            else
                                (if (("needs_data" == status))
                                    "Confirme os dados indicados no editor do estado; campos sem entidade referem-se ao estado ou cobertura da imagem."
                                else "A análise não produziu uma jogada.")),
                        )
                    break
                }
                if ((cards.length() > 0)) {
                    var definition = GameEngine.action(snapshot, answer.optString("action"))
                    var params = answer.optJSONObject("parameters")
                    var defs =
                        (if ((definition == null)) JSONArray()
                        else definition.optJSONArray("parameters"))
                    if ((params != null)) {
                        run {
                            var keys = params.keys()
                            while (keys.hasNext()) {

                                var key = keys.next()
                                var value = params.opt(key)
                                var publicValue =
                                    (!((value is String)) ||
                                        (GameEngine.entity(
                                            GameEngine.state(snapshot),
                                            java.lang.String.valueOf(value),
                                        ) != null))
                                if ((defs != null)) {
                                    run {
                                        var i = 0
                                        while ((i < defs.length())) {

                                            var d = defs.getJSONObject(i)
                                            if (
                                                ((key == d.optString("name")) &&
                                                    GameEngine.contains(
                                                        d.optJSONArray("values"),
                                                        java.lang.String.valueOf(value),
                                                    ))
                                            ) {
                                                publicValue = true
                                            }

                                            i++
                                        }
                                    }
                                }
                                if (!publicValue) {
                                    params.put(key, JSONObject.NULL)
                                }
                            }
                        }
                    }
                }
                val generatedProposal = GameEngine.proposal(snapshot, answer)
                proposal = generatedProposal
                var validation = generatedProposal.getJSONObject("validation")
                if ((validation.getJSONArray("blockers").length() > 0)) {
                    decision =
                        GameJson.obj(
                            "status",
                            "needs_data",
                            "message",
                            "Dados essenciais ainda não confirmados. Corrija o estado público antes de decidir.",
                            "errors",
                            validation.getJSONArray("blockers"),
                        )
                    proposal = null
                    break
                }
                if (!("invalid" == validation.getString("status"))) {
                    break
                }
                errors = validation.getJSONArray("errors")
                proposal = null

                attempt++
            }
        }
        if (((proposal == null) && (decision == null))) {
            decision =
                GameJson.obj(
                    "status",
                    "validation_error",
                    "errors",
                    errors,
                    "message",
                    "Proposta rejeitada após três tentativas. Corrija os dados ou a definição da ação.",
                )
        }
        if (((proposal != null) && (cards.length() > 0))) {
            proposal?.put(
                "reason",
                "Decisão baseada no estado público e na memória privada, sem divulgar seu conteúdo.",
            )
        }
        synchronized(store) {
            ensureOpen()

            var r = store.get(code)
            if (
                (((AiSettings(context).revision() != aiRevision) ||
                    (r.optInt("revision") != revision)) || !r.isNull("proposal"))
            ) {
                throw ApiException(409, "Estado mudou durante a sugestão.")
            }
            r.put("proposal", (if ((proposal == null)) JSONObject.NULL else proposal))
            r.put("decision", (if ((decision == null)) JSONObject.NULL else decision))
            if (((decision != null) && ("needs_data" == decision?.optString("status")))) {
                var needed = decision?.optJSONArray("needed_data")
                if ((needed != null)) {
                    run {
                        var i = 0
                        while ((i < needed.length())) {

                            var n = needed.getJSONObject(i)
                            var field = n.optString("field")
                            var entity = n.optString("entity")
                            GameKnowledge.addQuestion(
                                r,
                                GameJson.obj(
                                    "key",
                                    ((("decision:" + entity) + ":") + field),
                                    "prompt",
                                    (((("Confirme " + field) + " em ") +
                                        ((if (entity.isEmpty()) "estado atual" else entity))) +
                                        " para continuar."),
                                    "scope",
                                    "state",
                                    "entity",
                                    entity,
                                    "field",
                                    field,
                                    "input_type",
                                    "text",
                                    "options",
                                    JSONArray(),
                                    "required",
                                    true,
                                ),
                                "decision",
                            )

                            i++
                        }
                    }
                }
            }
            GameKnowledge.refreshQuestions(r)
            store.save(r, true)
            broadcast(code)
            return success(requireNotNull(proposal ?: decision))
        }
    }

    @Throws(Exception::class)
    private fun manualFiles(code: String, room: JSONObject): MutableList<File> {
        var files = ArrayList<File>()
        var names = room.getJSONArray("rule_pages")
        var directory = store.photoDir(code, "rules")
        run {
            var i = 0
            while ((i < names.length())) {

                var name = names.getString(i)
                if (!name.matches(Regex("[a-f0-9-]+\\.(pdf|jpg)"))) {
                    throw ApiException(400, "Nome de manual inválido.")
                }
                files.add(File(directory, name))

                i++
            }
        }
        return files
    }

    @Throws(Exception::class)
    private fun broadcast(code: String): Unit {
        broadcastEvent(
            code,
            GameJson.obj("type", "state", "room", store.publicRoom(store.get(code))),
        )
    }

    private fun broadcastEvent(code: String, event: JSONObject): Unit {
        for (ws in sockets.getOrDefault(code, mutableSetOf<WsClient>())) {
            try {
                ws.send(event)
            } catch (e: Exception) {
                sockets.getOrDefault(code, mutableSetOf<WsClient>()).remove(ws)
                ws.close()
            }
        }
    }

    @Throws(Exception::class)
    private fun readExactly(input: InputStream, length: Int): ByteArray {
        var bytes = ByteArray(length)
        var pos = 0
        while ((pos < length)) {
            var got = input.read(bytes, pos, (length - pos))
            if ((got < 0)) {
                throw ApiException(400, "Upload incompleto.")
            }
            pos += got
        }
        return bytes
    }

    @Throws(Exception::class)
    private fun parse(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ", limit = 3)
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1."))
            throw ApiException(400, "Requisição inválida.")
        val req =
            Request().apply {
                method = parts[0].uppercase(Locale.ROOT)
                path = parts[1].substringBefore('?')
            }
        if (!req.path.startsWith("/")) throw ApiException(400, "Rota inválida.")
        for (i in 0 until 60) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0)
                req.headers[header.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                    header.substring(colon + 1).trim()
        }
        if (req.header("transfer-encoding").lowercase(Locale.ROOT).contains("chunked"))
            throw ApiException(411, "Envie Content-Length.")
        val length = req.header("content-length")
        val size =
            if (length.isEmpty()) 0
            else length.toIntOrNull() ?: throw ApiException(400, "Content-Length inválido.")
        if (size < 0 || size > MAX_BODY) throw ApiException(413, "Upload excedeu 30 MB.")
        if (size > 0) req.body = readExactly(input, size)
        return req
    }

    private fun json(status: Int, value: Any): Response {
        return Response(
            status,
            java.lang.String.valueOf(value).toByteArray(StandardCharsets.UTF_8),
            "application/json; charset=utf-8",
        )
    }

    private fun success(value: Any): Response {
        return json(200, value)
    }

    private fun image(data: ByteArray): Response {
        return Response(200, data, "image/jpeg")
    }

    private fun reason(status: Int): String {
        when (status) {
            200 -> {
                return "OK"
            }
            101 -> {
                return "Switching Protocols"
            }
            400 -> {
                return "Bad Request"
            }
            403 -> {
                return "Forbidden"
            }
            404 -> {
                return "Not Found"
            }
            409 -> {
                return "Conflict"
            }
            411 -> {
                return "Length Required"
            }
            413 -> {
                return "Payload Too Large"
            }
            422 -> {
                return "Unprocessable Entity"
            }
            431 -> {
                return "Request Header Fields Too Large"
            }
            502 -> {
                return "Bad Gateway"
            }
            else -> {
                return "Internal Server Error"
            }
        }
    }

    @Throws(Exception::class)
    private fun reply(out: OutputStream, response: Response): Unit {
        var headers =
            ((((((((((("HTTP/1.1 " + response.status) + " ") + reason(response.status)) + "\r\n") +
                "Content-Type: ") + response.contentType) + "\r\nContent-Length: ") +
                response.body.size) +
                "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n") +
                "Content-Security-Policy: default-src 'self' data: blob: 'unsafe-inline'; img-src 'self' data: blob:; ") +
                "connect-src 'self' ws: wss:;\r\n\r\n")
        out.write(headers.toByteArray(StandardCharsets.US_ASCII))
        out.write(response.body)
        out.flush()
    }

    @Throws(Exception::class)
    private fun normalizeActions(source: JSONArray?): JSONArray {
        var out = JSONArray()
        if ((source == null)) {
            return out
        }
        var names = HashSet<String>()
        run {
            var i = 0
            while ((i < source.length())) {

                var action = source.getJSONObject(i)
                var name = action.optString("name").trim()
                if ((name.isEmpty() || !names.add(name))) {
                    throw ApiException(422, "Ação sem nome ou duplicada.")
                }
                // Preserve all reviewed definitions; legacy three-field actions load as unverified.
                var normalized = GameEngine.copy(action)
                normalized.put("name", name)
                out.put(normalized)

                i++
            }
        }
        return out
    }

    @Throws(Exception::class)
    private fun files(req: Request): MutableList<UploadFile> {
        var type = req.header("content-type")
        if (
            (type.startsWith("image/", ignoreCase = true) ||
                type.startsWith("application/pdf", ignoreCase = true))
        ) {
            if ((req.body.size == 0)) {
                throw ApiException(400, "Arquivo vazio.")
            }
            if ((req.body.size > ((10 * 1024) * 1024))) {
                throw ApiException(413, "Arquivo maior que 10 MB.")
            }
            var mime = type.split(Regex(";"), limit = 2)[0].trim().lowercase(Locale.ROOT)
            if ((!("application/pdf" == mime) && !mime.startsWith("image/"))) {
                throw ApiException(400, "Envie uma imagem ou um arquivo PDF.")
            }
            if ((("application/pdf" == mime) && !isPdf(req.body))) {
                throw ApiException(400, "Arquivo PDF inválido.")
            }
            return Collections.singletonList(UploadFile(req.body, mime))
        }
        if (!type.startsWith("multipart/form-data", ignoreCase = true)) {
            throw ApiException(400, "Envie uma imagem, um PDF ou um formulário multipart.")
        }
        var boundary: String? = null
        for (option in type.split(Regex(";"))) {
            var s = option.trim()
            if (s.startsWith("boundary=")) {
                boundary = s.substring(9).replace("\"", "")
            }
        }
        if ((((boundary == null) || boundary.isEmpty()) || (boundary.length > 150))) {
            throw ApiException(400, "Limite multipart inválido.")
        }
        var prefix = (("--" + boundary)).toByteArray(StandardCharsets.ISO_8859_1)
        var next = (("\r\n--" + boundary)).toByteArray(StandardCharsets.ISO_8859_1)
        var separator = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        var result = ArrayList<UploadFile>()
        var pos = locate(req.body, prefix, 0)
        while ((pos >= 0)) {
            var head = (pos + prefix.size)
            if (
                ((((head + 1) < req.body.size) && (req.body[head].toInt() == '-'.code)) &&
                    (req.body[(head + 1)].toInt() == '-'.code))
            ) {
                break
            }
            var endHeaders = locate(req.body, separator, head)
            if ((endHeaders < 0)) {
                throw ApiException(400, "Upload multipart inválido.")
            }
            var headers = String(req.body, head, (endHeaders - head), StandardCharsets.ISO_8859_1)
            var dataStart = (endHeaders + separator.size)
            var dataEnd = locate(req.body, next, dataStart)
            if ((dataEnd < 0)) {
                throw ApiException(400, "Upload multipart incompleto.")
            }
            if (headers.lowercase(Locale.ROOT).contains("filename=")) {
                if (((dataEnd - dataStart) > ((10 * 1024) * 1024))) {
                    throw ApiException(413, "Arquivo maior que 10 MB.")
                }
                var content = Arrays.copyOfRange(req.body, dataStart, dataEnd)
                var partType = partHeader(headers, "content-type").lowercase(Locale.ROOT)
                var disposition = partHeader(headers, "content-disposition")
                var fileName = partFileName(disposition).lowercase(Locale.ROOT)
                var mime = partType.split(Regex(";"), limit = 2)[0].trim()
                if ((mime.isEmpty() || ("application/octet-stream" == mime))) {
                    mime = mimeFromName(fileName)
                }
                if (!isSupportedUploadType(mime)) {
                    throw ApiException(
                        400,
                        "Envie uma imagem JPEG, PNG ou WebP, ou um arquivo PDF.",
                    )
                }
                if ((("application/pdf" == mime) && !isPdf(content))) {
                    throw ApiException(400, "Arquivo PDF inválido.")
                }
                result.add(UploadFile(content, mime))
            }
            pos = (dataEnd + 2)
        }
        if (result.isEmpty()) {
            throw ApiException(400, "Selecione ao menos um arquivo.")
        }
        return result
    }

    private fun partHeader(headers: String, name: String): String {
        for (line in headers.split(Regex("\r\n"))) {
            var colon = line.indexOf(':')
            if (((colon > 0) && line.substring(0, colon).trim().equals(name, ignoreCase = true))) {
                return line.substring((colon + 1)).trim()
            }
        }
        return ""
    }

    private fun partFileName(disposition: String): String {
        for (parameter in disposition.split(Regex(";"))) {
            var value = parameter.trim()
            if (value.startsWith("filename=", ignoreCase = true)) {
                var name = value.substring(9).trim()
                if ((((name.length >= 2) && name.startsWith("\"")) && name.endsWith("\""))) {
                    name = name.substring(1, (name.length - 1))
                }
                return name
            }
        }
        return ""
    }

    private fun mimeFromName(name: String): String {
        if (name.endsWith(".pdf")) {
            return "application/pdf"
        }
        if (name.endsWith(".png")) {
            return "image/png"
        }
        if (name.endsWith(".webp")) {
            return "image/webp"
        }
        if ((name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
            return "image/jpeg"
        }
        return ""
    }

    private fun isSupportedUploadType(mime: String): Boolean {
        return (((("application/pdf" == mime) || ("image/jpeg" == mime)) ||
            ("image/png" == mime)) || ("image/webp" == mime))
    }

    private fun isPdf(bytes: ByteArray): Boolean {
        return ((((((bytes.size >= 5) && (bytes[0].toInt() == '%'.code)) &&
            (bytes[1].toInt() == 'P'.code)) && (bytes[2].toInt() == 'D'.code)) &&
            (bytes[3].toInt() == 'F'.code)) && (bytes[4].toInt() == '-'.code))
    }

    @Throws(Exception::class)
    private fun jpeg(bytes: ByteArray, maxSide: Int): ByteArray {
        if ((bytes.size > ((10 * 1024) * 1024))) {
            throw ApiException(413, "Foto maior que 10 MB.")
        }
        var first = BitmapFactory.Options()
        first.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, first)
        if (
            ((((first.outWidth < 1) || (first.outHeight < 1)) || (first.outWidth > 16000)) ||
                (first.outHeight > 16000))
        ) {
            throw ApiException(400, "Imagem inválida ou grande demais.")
        }
        var opts = BitmapFactory.Options()
        var max = Math.max(first.outWidth, first.outHeight)
        opts.inSampleSize = 1
        while (((max / opts.inSampleSize) > (maxSide * 2))) opts.inSampleSize *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if ((bitmap == null)) {
            throw ApiException(400, "Não foi possível abrir esta imagem.")
        }
        var resized = bitmap
        var width = bitmap.getWidth()
        var height = bitmap.getHeight()
        if ((Math.max(width, height) > maxSide)) {
            var scale = ((maxSide).toFloat() / Math.max(width, height))
            resized =
                Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, Math.round((width * scale))),
                    Math.max(1, Math.round((height * scale))),
                    true,
                )
        }
        var out = ByteArrayOutputStream()
        var ok = resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if ((resized != bitmap)) {
            resized.recycle()
        }
        bitmap.recycle()
        if (!ok) {
            throw ApiException(400, "Não foi possível converter imagem.")
        }
        return out.toByteArray()
    }

    companion object {
        const val PORT = 8765
        private const val MAX_BODY = 30 * 1024 * 1024
    }
}
