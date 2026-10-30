package com.automaforge.android.data.ai

import android.content.Context
import android.util.Base64
import com.automaforge.android.core.*
import com.automaforge.android.data.rulebook.RuleJsonValidator
import com.automaforge.android.data.security.ApiKeyStore
import com.automaforge.android.domain.game.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Supplier
import org.json.*

class AiClient
@JvmOverloads
constructor(
    private val context: Context,
    private val endpoint: String = "https://api.openai.com/v1/responses",
    private val keyOverride: Supplier<String>? = null,
) {
    class RuleFile(
        @JvmField val bytes: ByteArray,
        @JvmField val mimeType: String,
        @JvmField val fileName: String,
    ) {
        companion object {
            @JvmStatic fun image(bytes: ByteArray) = RuleFile(bytes, "image/jpeg", "manual.jpg")
        }
    }

    private var pinnedSettings: AiSettings.Selection? = null

    private fun settings() = pinnedSettings ?: AiSettings(context).snapshot()

    fun key() = keyOverride?.get() ?: ApiKeyStore(context).read()

    fun enabled() = key().isNotEmpty()

    fun model() = settings().model

    fun reasoningEffort() = settings().reasoningEffort

    fun snapshot() =
        AiClient(context, endpoint, keyOverride).also { it.pinnedSettings = settings() }

    private fun readAll(stream: InputStream) = stream.readBytes()

    @Throws(Exception::class)
    private fun schema(name: String): JSONObject {
        context.getAssets().open((("schemas/" + name) + ".json")).use { `in` ->
            return JSONObject(String(readAll(`in`), StandardCharsets.UTF_8))
        }
    }

    @Throws(Exception::class)
    fun ask(instructions: String, schema: String, files: List<RuleFile>?): JSONObject {
        if (!enabled()) {
            throw ApiException(
                409,
                "Configure a chave OpenAI no aparelho controlador para ativar IA.",
            )
        }
        if ((files != null)) {
            var size = 0
            for (f in files) size += f.bytes.size
            if ((size > ((24L * 1024) * 1024))) {
                throw ApiException(
                    422,
                    "Anexos visuais excederam o orçamento de 24 MiB. Fotografe menos áreas por vez.",
                )
            }
        }
        var content = JSONArray()
        content.put(GameJson.obj("type", "input_text", "text", instructions))
        if ((files != null)) {
            for (file in files) {
                content.put(
                    GameJson.obj(
                        "type",
                        "input_text",
                        "text",
                        ("Anexo identificado: " + file.fileName),
                    )
                )
                var encoded = Base64.encodeToString(file.bytes, Base64.NO_WRAP)
                if (("application/pdf" == file.mimeType)) {
                    content.put(
                        GameJson.obj(
                            "type",
                            "input_file",
                            "filename",
                            file.fileName,
                            "file_data",
                            ("data:application/pdf;base64," + encoded),
                            "detail",
                            "high",
                        )
                    )
                } else {
                    content.put(
                        GameJson.obj(
                            "type",
                            "input_image",
                            "image_url",
                            ((("data:" + file.mimeType) + ";base64,") + encoded),
                            "detail",
                            "high",
                        )
                    )
                }
            }
        }
        var schemaFormat =
            GameJson.obj(
                "type",
                "json_schema",
                "name",
                ("automaforge_" + schema),
                "schema",
                schema(schema),
                "strict",
                true,
            )
        var selected = settings()
        var body =
            selected.applyTo(
                GameJson.obj(
                    "store",
                    false,
                    "input",
                    JSONArray().put(GameJson.obj("role", "user", "content", content)),
                    "text",
                    GameJson.obj("format", schemaFormat),
                )
            )
        var conn = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            conn.setRequestMethod("POST")
            conn.setConnectTimeout(18000)
            conn.setReadTimeout(100000)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", ("Bearer " + key()))
            conn.setDoOutput(true)
            conn.getOutputStream().use { out ->
                out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            var status = conn.getResponseCode()
            if (((status < 200) || (status >= 300))) {
                throw ApiException(502, AiSettings.errorMessage(status))
            }
            var response =
                JSONObject(String(readAll(conn.getInputStream()), StandardCharsets.UTF_8))
            if (!("completed" == response.optString("status", "completed"))) {
                throw ApiException(502, "Resposta incompleta da IA; a análise não foi concluída.")
            }
            var output = response.optJSONArray("output")
            if ((output != null)) {
                run {
                    var i = 0
                    while ((i < output.length())) {

                        var item = output.optJSONObject(i)
                        if ((item == null)) {
                            i++
                            continue
                        }
                        var blocks = item.optJSONArray("content")
                        if ((blocks == null)) {
                            i++
                            continue
                        }
                        run {
                            var j = 0
                            while ((j < blocks.length())) {

                                var block = blocks.optJSONObject(j)
                                if (
                                    ((block != null) && ("output_text" == block.optString("type")))
                                ) {
                                    try {
                                        var value = JSONObject(block.optString("text"))
                                        RuleJsonValidator.validate(value, schema(schema))
                                        return value
                                    } catch (e: Exception) {
                                        throw ApiException(
                                            502,
                                            "Resposta da IA fora do esquema esperado; análise não concluída.",
                                        )
                                    }
                                }

                                j++
                            }
                        }

                        i++
                    }
                }
            }
            throw ApiException(502, "IA não retornou JSON interpretável.")
        } finally {
            conn.disconnect()
        }
    }

    @Throws(Exception::class)
    fun rules(name: String, files: List<RuleFile>): JSONObject {
        return ask(AiPrompts.rules(name), "rules", files)
    }

    @Throws(Exception::class)
    fun discoverModel(room: JSONObject): JSONObject {
        var context =
            GameJson.obj(
                "rules",
                room.optString("rules_text"),
                "setup",
                room.optString("setup"),
                "turn_flow",
                room.optString("turn_flow"),
                "actions",
                room.optJSONArray("actions"),
                "human_answers",
                GameKnowledge.context(room),
            )
        if ((context.toString().length > 180000)) {
            throw ApiException(422, "Regras extensas: use interpretação do manual em pacotes.")
        }
        return ask(
            (("Derive o modelo de estado, perguntas e definições parametrizadas SOMENTE das regras fornecidas. Preserve exatamente os nomes de todas as ações já cadastradas; proponha sua parametrização sem inventar regras nem remover ações. Se as regras forem insuficientes, registre perguntas. " +
                AiPrompts.ACTION_DEFINITIONS) + context),
            "state_model",
            null,
        )
    }

    @Throws(Exception::class)
    fun board(
        room: JSONObject,
        photo: ByteArray,
        manualReferences: List<java.io.File>?,
    ): JSONObject {
        var context =
            GameJson.obj(
                "game_state",
                GameEngine.state(room),
                "knowledge",
                GameKnowledge.context(room),
                "board_version",
                room.optInt("board_version"),
                "photo_at",
                room.optString("photo_at"),
                "rules",
                room.optString("rules_text"),
            )
        if ((context.toString().length > 180000)) {
            throw ApiException(
                422,
                "Contexto de reconhecimento extenso; reduza as referências sem omitir o estado necessário.",
            )
        }
        var overview = ArrayList<RuleFile>()
        if ((manualReferences != null)) {
            for (file in manualReferences) overview.add(
                RuleFile(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    "image/png",
                    ((("MANUAL_REFERENCE:" + (file.parentFile?.parentFile?.name ?: "manual")) +
                        ":") + file.getName()),
                )
            )
        }
        overview.add(RuleFile(photo, "image/jpeg", "CURRENT_PHOTO"))
        var layout =
            ask(
                ((((("Localize as áreas de jogo na CURRENT_PHOTO usando o modelo do jogo, as respostas humanas e referências do manual, que são exemplos e não o estado. " +
                    "Primeiro identifique os tabuleiros/áreas, antes de contar peças. Cada área: id estável, label compreensível, area_type correspondente ao modelo, owner somente quando evidenciado pela configuração/respostas/contexto (senão vazio), region=[x,y,w,h] normalizada à CURRENT_PHOTO. ") +
                    "Reutilize regiões/IDs confirmados mesmo com dúvida em peças. É permitido reconhecer uma área sem saber ainda a quem pertence. ") +
                    "Se owner for desconhecido, pergunte a quem pertence a área, com entity=id e field=owner, input_type=text. ") +
                    "Não chame uma oferta de tabuleiro pessoal só por seu formato; compare as ilustrações e a função descrita nas regras. Contexto: ") +
                    context),
                "board_layout",
                overview,
            )
        var areas = layout.getJSONArray("areas")
        var ids = HashSet<String>()
        run {
            var i = 0
            while ((i < areas.length())) {

                var area = areas.getJSONObject(i)
                if (
                    ((!area.optString("id").matches(Regex("[A-Za-z0-9_.:-]{1,100}")) ||
                        !ids.add(area.optString("id"))) ||
                        !BoardImages.validRegion(area.optJSONArray("region")))
                ) {
                    throw ApiException(
                        422,
                        "A IA retornou uma região inválida; não foi criada referência inventada.",
                    )
                }
                var old = GameEngine.entity(GameEngine.state(room), area.optString("id"))
                if (((old != null) && old.optBoolean("human_locked"))) {
                    area.put("owner", old.optString("owner"))
                    area.put("area_type", old.optString("area_type"))
                }

                i++
            }
        }
        var detailed = ArrayList<RuleFile>(overview)
        detailed.addAll(BoardImages.crops(photo, areas))
        var result =
            ask(
                AiPrompts.board(
                    room.optString("rules_text"),
                    context.put("layout", layout).toString(),
                ),
                "board",
                detailed,
            )
        var entities = result.getJSONArray("entities")
        run {
            var i = 0
            while ((i < areas.length())) {

                var area = areas.getJSONObject(i)
                var e = GameKnowledge.named(entities, "id", area.getString("id"))
                if ((e == null)) {
                    var def =
                        GameKnowledge.named(
                            GameKnowledge.array(GameKnowledge.model(room), "areas"),
                            "key",
                            area.optString("area_type"),
                        )
                    e =
                        GameJson.obj(
                            "id",
                            area.getString("id"),
                            "kind",
                            (if ((def == null)) "region" else def.optString("kind", "region")),
                            "type",
                            area.optString("label"),
                            "area_type",
                            area.optString("area_type"),
                            "owner",
                            area.optString("owner"),
                            "visibility",
                            "public",
                            "location",
                            "",
                            "group",
                            "",
                            "quantity",
                            0,
                            "level",
                            0,
                            "identity",
                            "not_applicable",
                            "status",
                            "observed",
                            "occupancy_known",
                            false,
                            "region",
                            area.getJSONArray("region"),
                            "confidence",
                            (if (("high" == area.optString("confidence"))) .9
                            else (if (("medium" == area.optString("confidence"))) .65 else .4)),
                            "evidence",
                            "Área localizada na foto atual; papel candidato associado ao manual.",
                            "x",
                            -1,
                            "y",
                            -1,
                            "topology",
                            (if ((def == null)) "none" else def.optString("topology", "none")),
                            "neighbors",
                            JSONArray(),
                            "attributes",
                            JSONArray(),
                        )
                    entities.put(e)
                }
                if ((e.optString("owner").isEmpty() && !area.optString("owner").isEmpty())) {
                    e.put("owner", area.optString("owner"))
                }

                i++
            }
        }
        var questions = result.getJSONArray("questions")
        run {
            var i = 0
            while ((i < layout.getJSONArray("questions").length())) {
                questions.put(layout.getJSONArray("questions").get(i))
                i++
            }
        }
        result.put("layout", layout)
        result.put("analysis_passes", 2)
        result.put(
            "manual_reference_count",
            (if ((manualReferences == null)) 0 else manualReferences.size),
        )
        result.put("crop_count", (detailed.size - overview.size))
        return result
    }

    @Throws(Exception::class)
    fun privateCard(room: JSONObject, photo: ByteArray): JSONObject {
        return ask(
            AiPrompts.privateCard(room.optString("rules_text")),
            "card",
            java.util.Collections.singletonList(RuleFile.image(photo)),
        )
    }

    @Throws(Exception::class)
    fun propose(room: JSONObject, privateCards: JSONArray, errors: JSONArray): JSONObject {
        var context =
            GameJson.obj(
                "game_name",
                room.optString("game_name"),
                "rules_text",
                room.optString("rules_text"),
                "setup",
                room.optString("setup"),
                "turn_flow",
                room.optString("turn_flow"),
                "actions",
                room.optJSONArray("actions"),
                "uncertainties",
                room.optJSONArray("uncertainties"),
                "turn",
                room.optInt("turn"),
                "phase",
                room.optString("phase"),
                "variables",
                room.optJSONObject("variables"),
                "board_notes",
                room.optString("board_notes"),
                "recent_history",
                tail(room.optJSONArray("history"), 8),
            )
        context.put("game_state", GameEngine.state(room))
        context.put("game_knowledge", GameKnowledge.context(room))
        context.put("rules_revision", room.optInt("rules_revision"))
        context.put("observation", room.optJSONObject("board_observation"))
        context.put("repair_errors", errors)
        context.put("private_cards_ONLY_FOR_AI", privateCards)
        if ((context.toString().length > 180000)) {
            throw ApiException(
                422,
                "Contexto grande demais; reduza a etapa sem descartar dados necessários.",
            )
        }
        var answer = ask(AiPrompts.proposal(context.toString()), "proposal", null)
        var entries = answer.optJSONArray("parameters")
        var params = JSONObject()
        if ((entries != null)) {
            run {
                var i = 0
                while ((i < entries.length())) {

                    var entry = entries.getJSONObject(i)
                    var name = entry.getString("name")
                    if (params.has(name)) {
                        throw ApiException(422, "IA repetiu um parâmetro.")
                    }
                    params.put(name, entry.get("value"))

                    i++
                }
            }
        }
        answer.put("parameters", params)
        return answer
    }

    private fun tail(all: JSONArray?, count: Int): JSONArray {
        var out = JSONArray()
        if ((all == null)) {
            return out
        }
        run {
            var i = Math.max(0, (all.length() - count))
            while ((i < all.length())) {
                out.put(all.opt(i))
                i++
            }
        }
        return out
    }
}
