package com.automaforge.android.domain.game

import com.automaforge.android.core.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import org.json.*

object GameKnowledge {
    @JvmStatic
    fun array(o: JSONObject, key: String): JSONArray {
        var a = o.optJSONArray(key)
        return (if ((a == null)) JSONArray() else a)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun ensure(room: JSONObject): Unit {
        var state = GameEngine.state(room)
        if (!state.has("facts")) {
            state.put("facts", JSONArray())
        }
        if (!state.has("automa_player")) {
            state.put("automa_player", "")
        }
        if (!room.has("questions")) {
            room.put("questions", JSONArray())
        }
        if (!room.has("clarifications")) {
            room.put("clarifications", JSONArray())
        }
    }

    @JvmStatic
    fun model(room: JSONObject): JSONObject {
        var m = room.optJSONObject("state_model")
        return (if ((m == null)) JSONObject() else m)
    }

    @JvmStatic
    fun definition(room: JSONObject, key: String): JSONObject? {
        return named(array(model(room), "fields"), "key", key)
    }

    @JvmStatic
    fun named(all: JSONArray, field: String, id: String): JSONObject? {
        run {
            var i = 0
            while ((i < all.length())) {

                var o = all.optJSONObject(i)
                if (((o != null) && (id == o.optString(field)))) {
                    return o
                }

                i++
            }
        }
        return null
    }

    @JvmStatic
    fun fact(s: JSONObject, key: String, subject: String): JSONObject? {
        var facts = array(s, "facts")
        run {
            var i = 0
            while ((i < facts.length())) {

                var f = facts.optJSONObject(i)
                if (
                    (((f != null) && (key == f.optString("key"))) &&
                        (subject == f.optString("subject")))
                ) {
                    return f
                }

                i++
            }
        }
        return null
    }

    @JvmStatic
    @Throws(Exception::class)
    fun validateModel(m: JSONObject): Unit {
        var keys = HashSet<String>()
        for (category in Arrays.asList("fields", "areas")) {
            keys.clear()
            var ds = m.optJSONArray(category)
            if (((ds == null) || (ds.length() > 100))) {
                throw ApiException(422, "Modelo exige listas de campos e áreas (até 100 cada).")
            }
            run {
                var i = 0
                while ((i < ds.length())) {

                    var d = ds.getJSONObject(i)
                    var key = d.optString("key")
                    if ((!key.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")) || !keys.add(key))) {
                        throw ApiException(422, "Chave de campo/área inválida ou repetida.")
                    }
                    if (d.optString("label").trim().isEmpty()) {
                        throw ApiException(422, "Campo/área precisa de nome legível.")
                    }
                    if (("fields" == category)) {
                        if (
                            !Arrays.asList(
                                    "string",
                                    "integer",
                                    "number",
                                    "boolean",
                                    "enum",
                                    "entity_ref",
                                    "entity_list",
                                )
                                .contains(d.optString("type"))
                        ) {
                            throw ApiException(422, "Tipo de variável não suportado.")
                        }
                        if (
                            !Arrays.asList("game", "player", "area").contains(d.optString("scope"))
                        ) {
                            throw ApiException(422, "Escopo de variável inválido.")
                        }
                        if (
                            !Arrays.asList("public", "private").contains(d.optString("visibility"))
                        ) {
                            throw ApiException(422, "Defina a visibilidade de cada variável.")
                        }
                        if (
                            (("enum" == d.optString("type")) && (array(d, "options").length() == 0))
                        ) {
                            throw ApiException(422, "Variável de escolha precisa de alternativas.")
                        }
                    } else {
                        if (!Arrays.asList("shared", "player").contains(d.optString("scope"))) {
                            throw ApiException(422, "Escopo da área inválido.")
                        }
                    }

                    i++
                }
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun checkFact(room: JSONObject, s: JSONObject, f: JSONObject): Unit {
        var d = definition(room, f.optString("key"))
        if ((d == null)) {
            throw ApiException(422, "Variável ausente no modelo revisado.")
        }
        if (!("public" == d.optString("visibility"))) {
            throw ApiException(
                422,
                "Variável privada não pode ser respondida no estado compartilhado.",
            )
        }
        var subject = f.optString("subject")
        if ((subject.isEmpty() || ((("game" == d.optString("scope")) && !("game" == subject))))) {
            throw ApiException(422, "Informe o escopo correto da variável.")
        }
        if ((("area" == d.optString("scope")) && (GameEngine.entity(s, subject) == null))) {
            throw ApiException(422, "Área da variável não cadastrada.")
        }
        var v = f.opt("value")
        if ((((v == null) || (v == JSONObject.NULL)) || ("unknown" == f.optString("status")))) {
            return
        }
        var type = d.optString("type")
        var ok: Boolean = false
        when (type) {
            "integer" -> {
                ok =
                    ((v is Number) &&
                        (((v as Number)).toDouble() == ((v as Number)).toLong().toDouble()))
            }
            "number" -> {
                ok = ((v is Number) && java.lang.Double.isFinite(((v as Number)).toDouble()))
            }
            "boolean" -> {
                ok = (v is Boolean)
            }
            "enum" -> {
                ok = ((v is String) && GameEngine.contains(array(d, "options"), (v as String)))
            }
            "entity_ref" -> {
                ok = ((v is String) && (GameEngine.entity(s, (v as String)) != null))
            }
            "entity_list" -> {
                ok = (v is JSONArray)
                if (ok) {
                    run {
                        var i = 0
                        while ((i < ((v as JSONArray)).length())) {
                            if ((GameEngine.entity(s, ((v as JSONArray)).optString(i)) == null)) {
                                ok = false
                            }
                            i++
                        }
                    }
                }
            }
            else -> {
                ok = ((v is String) && !((v as String)).trim().isEmpty())
            }
        }
        if (!ok) {
            throw ApiException(
                422,
                (((("Valor incompatível com " + d.optString("label")) + " (") + type) + ")."),
            )
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun putFact(room: JSONObject, s: JSONObject, f: JSONObject, human: Boolean): Unit {
        checkFact(room, s, f)
        var current = fact(s, f.getString("key"), f.getString("subject"))
        if (((!human && (current != null)) && current.optBoolean("human_locked"))) {
            return
        }
        var next = GameEngine.copy(f)
        next.put("source", (if (human) "human" else "image"))
        next.put("human_locked", human)
        if (human) {
            next.put("status", (if (next.isNull("value")) "unknown" else "confirmed"))
        }
        if ((current != null)) {
            var keys = next.keys()
            while (keys.hasNext()) {
                var key = keys.next()
                current.put(key, next.get(key))
            }
        } else {
            s.getJSONArray("facts").put(next)
        }
    }

    @JvmStatic
    fun subjects(room: JSONObject, s: JSONObject, d: JSONObject): MutableList<String> {
        var result = ArrayList<String>()
        var scope = d.optString("scope")
        if (("game" == scope)) {
            result.add("game")
        } else {
            if (("player" == scope)) {
                if (!s.optString("active_player").isEmpty()) {
                    result.add(s.optString("active_player"))
                }
            } else {
                var es = array(s, "entities")
                run {
                    var i = 0
                    while ((i < es.length())) {

                        var e = es.optJSONObject(i)
                        if (
                            (((e != null) &&
                                Arrays.asList("board", "region", "group")
                                    .contains(e.optString("kind"))) &&
                                (((e.optString("owner") == s.optString("active_player")) ||
                                    (e.optString("owner") == "shared"))))
                        ) {
                            result.add(e.optString("id"))
                        }

                        i++
                    }
                }
            }
        }
        return result
    }

    @JvmStatic
    fun applies(d: JSONObject, action: String, phase: String): Boolean {
        var phases = array(d, "required_phases")
        return ((((phases.length() == 0) || GameEngine.contains(phases, phase))) &&
            ((GameEngine.contains(array(d, "required_for_actions"), action) ||
                GameEngine.contains(array(d, "required_for_actions"), "*"))))
    }

    @JvmStatic
    fun allows(room: JSONObject, s: JSONObject, selectors: JSONArray, id: String): Boolean {
        if (GameEngine.contains(selectors, id)) {
            return true
        }
        run {
            var i = 0
            while ((i < selectors.length())) {

                var selector = selectors.optString(i)
                if (!selector.startsWith("area:")) {
                    i++
                    continue
                }
                var key = selector.substring(5)
                var def = named(array(model(room), "areas"), "key", key)
                if ((def == null)) {
                    i++
                    continue
                }
                var e = GameEngine.entity(s, id)
                var seen = HashSet<String>()
                while (((e != null) && seen.add(e.optString("id")))) {
                    if ((key == e.optString("area_type"))) {
                        var owner = e.optString("owner")
                        if (
                            (if (("shared" == def.optString("scope"))) ("shared" == owner)
                            else
                                (!s.optString("active_player").isEmpty() &&
                                    (s.optString("active_player") == owner)))
                        ) {
                            return true
                        }
                    }
                    e = GameEngine.entity(s, e.optString("location"))
                }

                i++
            }
        }
        return false
    }

    @JvmStatic
    @Throws(Exception::class)
    fun blockers(room: JSONObject, s: JSONObject, action: String): JSONArray {
        var result = JSONArray()
        var m = model(room)
        if (!AutoState.modelReady(room)) {
            return result
        }
        var areas = array(m, "areas")
        run {
            var i = 0
            while ((i < areas.length())) {

                var d = areas.getJSONObject(i)
                if (!applies(d, action, s.optString("phase"))) {
                    i++
                    continue
                }
                var found = false
                var es = array(s, "entities")
                run {
                    var j = 0
                    while ((j < es.length())) {

                        var e = es.getJSONObject(j)
                        if (
                            (((d.optString("key") == e.optString("area_type")) &&
                                GameEngine.known(e)) &&
                                ((if (("shared" == d.optString("scope")))
                                    ("shared" == e.optString("owner"))
                                else
                                    (!s.optString("active_player").isEmpty() &&
                                        (s.optString("active_player") == e.optString("owner"))))))
                        ) {
                            found = true
                        }

                        j++
                    }
                }
                if (!found) {
                    result.put(
                        (("Confirme a entidade de área: " + d.optString("label")) +
                            " e a quem ela pertence.")
                    )
                }

                i++
            }
        }
        var ds = array(m, "fields")
        run {
            var i = 0
            while ((i < ds.length())) {

                var d = ds.getJSONObject(i)
                if (
                    (!("public" == d.optString("visibility")) ||
                        !applies(d, action, s.optString("phase")))
                ) {
                    i++
                    continue
                }
                var ss = subjects(room, s, d)
                if (ss.isEmpty()) {
                    result.put(
                        ("Confirme a entidade/escopo para a variável: " + d.optString("label"))
                    )
                }
                for (subject in ss) {
                    var f = fact(s, d.getString("key"), subject)
                    if ((!GameEngine.known(f) || f.isNull("value"))) {
                        result.put(
                            (((("Confirme a entidade de estado: " + d.optString("label")) + " (") +
                                subject) + ").")
                        )
                    }
                }

                i++
            }
        }
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun context(room: JSONObject): JSONObject {
        ensure(room)
        return GameJson.obj(
            "state_model",
            model(room),
            "confirmed_facts",
            GameEngine.state(room).getJSONArray("facts"),
            "human_answers",
            array(room, "clarifications"),
            "questions",
            array(room, "questions"),
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun hash(text: String): String {
        var bytes =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        var b = StringBuilder()
        run {
            var i = 0
            while ((i < 12)) {
                b.append(String.format(Locale.ROOT, "%02x", bytes[i]))
                i++
            }
        }
        return b.toString()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun addQuestion(room: JSONObject, input: JSONObject, origin: String): Unit {
        ensure(room)
        var questions = room.getJSONArray("questions")
        var q = GameEngine.copy(input)
        var prompt = q.optString("prompt").trim()
        if (prompt.isEmpty()) {
            return
        }
        var scope = q.optString("scope", "state")
        var key = q.optString("key")
        if (!Arrays.asList("state", "rules", "ownership").contains(scope)) {
            return
        }
        var field = definition(room, q.optString("field"))
        if (((field != null) && ("private" == field.optString("visibility")))) {
            return
        }
        var target =
            (if (((!q.optString("entity").isEmpty() && !q.optString("field").isEmpty())))
                ((q.optString("entity") + ":") + q.optString("field"))
            else ((key + ":") + prompt))
        var id =
            hash(
                ((((((scope + ":") + origin) + ":") + target) + ":") +
                    room.optInt("rules_revision"))
            )
        var previous = named(questions, "id", id)
        if (((previous != null) && ("answered" == previous.optString("status")))) {
            var d = definition(room, q.optString("field"))
            var current =
                (if ((d == null)) null
                else fact(GameEngine.state(room), q.optString("field"), q.optString("entity")))
            if (((d == null) || ((GameEngine.known(current) && !current.isNull("value"))))) {
                return
            }
            q.put("answer", JSONObject.NULL)
            // A formerly answered variable may be unknown after execution.
        }
        q.put("id", id)
        q.put("prompt", GameJson.clip(prompt, 1200))
        q.put("origin", origin)
        q.put(
            "status",
            (if (((previous != null) && ("unknown" == previous.optString("status")))) "unknown"
            else "open"),
        )
        q.put("visibility", "public")
        q.put("state_version", GameEngine.state(room).getInt("version"))
        q.put("rules_revision", room.optInt("rules_revision"))
        q.put("photo_version", room.optInt("board_version"))
        if ((previous != null)) {
            var keys = q.keys()
            while (keys.hasNext()) {
                var k = keys.next()
                previous.put(k, q.get(k))
            }
        } else {
            questions.put(q)
            if ((questions.length() > 200)) {
                var recent = JSONArray()
                run {
                    var i = (questions.length() - 200)
                    while ((i < questions.length())) {
                        recent.put(questions.get(i))
                        i++
                    }
                }
                room.put("questions", recent)
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun questions(room: JSONObject, response: JSONObject, origin: String): Unit {
        var qs = array(response, "questions")
        run {
            var i = 0
            while ((i < qs.length())) {
                addQuestion(room, qs.getJSONObject(i), origin)
                i++
            }
        }
        if (("board" == origin)) {
            var es = array(response, "entities")
            run {
                var i = 0
                while ((i < es.length())) {

                    var e = es.getJSONObject(i)
                    var a = named(array(model(room), "areas"), "key", e.optString("area_type"))
                    var confirmed = GameEngine.entity(GameEngine.state(room), e.optString("id"))
                    if (
                        (((a != null) && e.optString("owner").isEmpty()) &&
                            (((confirmed == null) || confirmed.optString("owner").isEmpty())))
                    ) {
                        addQuestion(
                            room,
                            GameJson.obj(
                                "key",
                                ("owner:" + e.optString("id")),
                                "prompt",
                                (((("A quem pertence " + a.optString("label")) + " (") +
                                    e.optString("id")) +
                                    ")? Use shared se for compartilhada, ou o ID da automa/jogador."),
                                "scope",
                                "ownership",
                                "entity",
                                e.optString("id"),
                                "field",
                                "owner",
                                "input_type",
                                "text",
                                "options",
                                JSONArray(),
                                "required",
                                true,
                            ),
                            origin,
                        )
                    }

                    i++
                }
            }
        }
        // Older schemas and free visual doubts also have an answer field; they do not implicitly
        // gate actions.
        var raw = array(response, "uncertainties")
        run {
            var i = 0
            while ((i < raw.length())) {
                addQuestion(
                    room,
                    GameJson.obj(
                        "key",
                        ("legacy:" + hash(raw.optString(i))),
                        "prompt",
                        raw.optString(i),
                        "scope",
                        (if (("rules" == origin)) "rules" else "state"),
                        "entity",
                        "",
                        "field",
                        "",
                        "input_type",
                        "text",
                        "options",
                        JSONArray(),
                        "required",
                        false,
                    ),
                    origin,
                )
                i++
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun rebaseQuestions(room: JSONObject, before: Int): Unit {
        var qs = array(room, "questions")
        run {
            var i = 0
            while ((i < qs.length())) {

                var q = qs.getJSONObject(i)
                if (
                    (((q.optInt("state_version") == before) &&
                        (q.optInt("rules_revision") == room.optInt("rules_revision"))) &&
                        !("answered" == q.optString("status")))
                ) {
                    q.put("state_version", GameEngine.state(room).getInt("version"))
                }

                i++
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun refreshQuestions(room: JSONObject): Unit {
        ensure(room)
        var s = GameEngine.state(room)
        var m = model(room)
        for (meta in Arrays.asList("active_player", "phase", "substep")) if (
            s.optString(meta).isEmpty()
        ) {
            addQuestion(
                room,
                GameJson.obj(
                    "key",
                    ("metadata:" + meta),
                    "prompt",
                    (if (("automa_player" == meta))
                        "Qual é o identificador do jogador controlado pela automa?"
                    else
                        (if (("active_player" == meta))
                            "De quem é a vez agora? Use automa se é a vez da IA."
                        else
                            (if (("phase" == meta)) "Em que fase a partida está agora?"
                            else "O que falta fazer nesta etapa do turno?"))),
                    "scope",
                    "state",
                    "entity",
                    "metadata",
                    "field",
                    meta,
                    "input_type",
                    "text",
                    "options",
                    JSONArray(),
                    "required",
                    true,
                ),
                "state_model",
            )
        }
        if (!AutoState.modelReady(room)) {
            return
        }
        var actor = s.optString("active_player")
        var ds = array(m, "fields")
        run {
            var i = 0
            while ((i < ds.length())) {

                var d = ds.getJSONObject(i)
                if (!("public" == d.optString("visibility"))) {
                    i++
                    continue
                }
                var required = false
                var actions = array(room, "actions")
                run {
                    var j = 0
                    while ((j < actions.length())) {
                        if (
                            applies(
                                d,
                                actions.getJSONObject(j).optString("name"),
                                s.optString("phase"),
                            )
                        ) {
                            required = true
                        }
                        j++
                    }
                }
                if (!required) {
                    i++
                    continue
                }
                for (subject in subjects(room, s, d)) {
                    var f = fact(s, d.getString("key"), subject)
                    if ((GameEngine.known(f) && !f.isNull("value"))) {
                        continue
                    }
                    var type = d.optString("type")
                    var inputType =
                        (if (("enum" == type)) "choice"
                        else
                            (if (("entity_ref" == type)) "entity"
                            else
                                (if (Arrays.asList("integer", "boolean").contains(type)) type
                                else "text")))
                    addQuestion(
                        room,
                        GameJson.obj(
                            "key",
                            ((("field:" + d.getString("key")) + ":") + subject),
                            "prompt",
                            ((((d.optString("label") + " — ") + subject) + ". ") +
                                d.optString("description")),
                            "scope",
                            "state",
                            "entity",
                            subject,
                            "field",
                            d.getString("key"),
                            "input_type",
                            inputType,
                            "options",
                            array(d, "options"),
                            "required",
                            true,
                        ),
                        "state_model",
                    )
                }

                i++
            }
        }
    }

    @JvmStatic
    fun observationEntity(room: JSONObject, id: String): JSONObject? {
        var o = room.optJSONObject("board_observation")
        return (if ((o == null)) null else named(array(o, "entities"), "id", id))
    }

    @JvmStatic
    @Throws(Exception::class)
    fun adoptCandidate(
        room: JSONObject,
        s: JSONObject,
        id: String,
        visiting: MutableSet<String>,
    ): JSONObject {
        var existing = GameEngine.entity(s, id)
        if ((existing != null)) {
            return existing
        }
        if (!visiting.add(id)) {
            throw ApiException(
                422,
                "A observação contém referência circular; corrija os locais antes de confirmar.",
            )
        }
        var candidate = observationEntity(room, id)
        if ((candidate == null)) {
            throw ApiException(422, "Cadastre primeiro a área/origem referenciada pela observação.")
        }
        if (!("public" == candidate.optString("visibility", "public"))) {
            throw ApiException(422, "Entidade privada não pode entrar no estado público.")
        }
        for (field in Arrays.asList("location", "group")) if (
            !candidate.optString(field).isEmpty()
        ) {
            adoptCandidate(room, s, candidate.optString(field), visiting)
        }
        var next = GameEngine.copy(candidate)
        s.getJSONArray("entities").put(next)
        visiting.remove(id)
        return next
    }

    @JvmStatic
    @Throws(Exception::class)
    fun versionGuard(room: JSONObject, body: JSONObject): Unit {
        if (
            ((body.optInt("state_version", -1) != GameEngine.state(room).getInt("version")) ||
                (body.optInt("rules_revision", -1) != room.optInt("rules_revision")))
        ) {
            throw ApiException(
                409,
                "Estado/regras mudaram. Recarregue as perguntas antes de responder.",
            )
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun answer(room: JSONObject, body: JSONObject): Unit {
        ensure(room)
        versionGuard(room, body)
        var q = named(array(room, "questions"), "id", body.optString("question_id"))
        if ((q == null)) {
            throw ApiException(404, "Pergunta não encontrada.")
        }
        if (
            ((q.optInt("rules_revision") != room.optInt("rules_revision")) ||
                ((!("rules" == q.optString("scope")) &&
                    (q.optInt("state_version") != GameEngine.state(room).getInt("version")))))
        ) {
            throw ApiException(409, "Pergunta de um estado anterior. Atualize a análise.")
        }
        var value = body.opt("value")
        var unknown = body.optBoolean("unknown")
        if (
            (!unknown &&
                ((((value == null) || (value == JSONObject.NULL)) ||
                    (((value is String) && ((value as String)).trim().isEmpty())))))
        ) {
            throw ApiException(422, "Preencha a resposta ou marque Não sei.")
        }
        if (((value != null) && (java.lang.String.valueOf(value).length > 4000))) {
            throw ApiException(422, "Resposta longa demais.")
        }
        if ((!unknown && (definition(room, q.optString("field")) == null))) {
            var type = q.optString("input_type", "text")
            var ok =
                (if (("boolean" == type)) (value is Boolean)
                else
                    (if (("integer" == type))
                        ((value is Number) &&
                            (((value as Number)).toDouble() ==
                                ((value as Number)).toLong().toDouble()))
                    else (value is String)))
            if (!ok) {
                throw ApiException(422, "Resposta incompatível com o tipo da pergunta.")
            }
        }
        var options = array(q, "options")
        if (
            ((!unknown && (options.length() > 0)) &&
                !GameEngine.contains(options, java.lang.String.valueOf(value)))
        ) {
            throw ApiException(422, "Escolha uma alternativa disponível.")
        }
        var s = GameEngine.state(room)
        var before = s.getInt("version")
        var field = q.optString("field")
        var entity = q.optString("entity")
        if (!unknown) {
            var d = definition(room, field)
            if (
                (("metadata" == entity) &&
                    Arrays.asList("active_player", "automa_player", "phase", "substep")
                        .contains(field))
            ) {
                s.put(field, java.lang.String.valueOf(value))
            } else {
                if ((d != null)) {
                    putFact(
                        room,
                        s,
                        GameJson.obj("key", field, "subject", entity, "value", value),
                        true,
                    )
                } else {
                    if (
                        (("game" == entity) &&
                            Arrays.asList("active_player", "automa_player", "phase", "substep")
                                .contains(field))
                    ) {
                        s.put(field, java.lang.String.valueOf(value))
                    } else {
                        if (
                            (!entity.isEmpty() &&
                                Arrays.asList(
                                        "owner",
                                        "area_type",
                                        "type",
                                        "quantity",
                                        "level",
                                        "occupancy_known",
                                        "location",
                                        "group",
                                        "status",
                                    )
                                    .contains(field))
                        ) {
                            var target = GameEngine.entity(s, entity)
                            if ((target == null)) {
                                target = adoptCandidate(room, s, entity, HashSet())
                            }
                            if (
                                (Arrays.asList("quantity", "level").contains(field) &&
                                    (((!((value is Number)) ||
                                        (((value as Number)).toDouble() !=
                                            ((value as Number)).toInt().toDouble())) ||
                                        (((value as Number)).toInt() < 0))))
                            ) {
                                throw ApiException(
                                    422,
                                    "Quantidade/nível precisa ser inteiro não negativo.",
                                )
                            }
                            if ((("occupancy_known" == field) && !((value is Boolean)))) {
                                throw ApiException(422, "Confirme a ocupação com verdadeiro/falso.")
                            }
                            if (
                                (Arrays.asList(
                                        "owner",
                                        "area_type",
                                        "type",
                                        "location",
                                        "group",
                                        "status",
                                    )
                                    .contains(field) && !((value is String)))
                            ) {
                                throw ApiException(
                                    422,
                                    "A resposta deste campo deve ser texto ou referência.",
                                )
                            }
                            target.put(field, value)
                            target.put("human_locked", true)
                            target.put("source", "human_answer")
                            var fields = target.optJSONObject("human_fields")
                            if ((fields == null)) {
                                fields = JSONObject()
                                target.put("human_fields", fields)
                            }
                            fields.put(field, true)
                        }
                    }
                }
            }
            if (
                (Arrays.asList("metadata", "game").contains(entity) &&
                    Arrays.asList("active_player", "phase", "substep").contains(field))
            ) {
                var evidence = s.optJSONObject("metadata_evidence")
                if ((evidence == null)) {
                    evidence = JSONObject()
                    s.put("metadata_evidence", evidence)
                }
                evidence.put(
                    field,
                    GameJson.obj(
                        "status",
                        "confirmed",
                        "source",
                        "human_answer",
                        "human_locked",
                        true,
                        "value",
                        s.opt(field),
                    ),
                )
            }
            if (!("confirmed" == s.optString("metadata_status"))) {
                var evidence = s.optJSONObject("metadata_evidence")
                var allHuman = (evidence != null)
                for (meta in Arrays.asList("active_player", "phase", "substep")) {
                    var item = (if ((evidence == null)) null else evidence.optJSONObject(meta))
                    if (((item == null) || !("confirmed" == item.optString("status")))) {
                        allHuman = false
                    }
                }
                s.put("metadata_status", (if (allHuman) "confirmed" else "inferred"))
            }
            if (("conflict" == q.optString("origin"))) {
                var acknowledgements = array(room, "vision_acknowledgements")
                acknowledgements.put(
                    GameJson.obj(
                        "photo_version",
                        room.optInt("board_version"),
                        "entity",
                        entity,
                        "field",
                        field,
                        "value",
                        value,
                    )
                )
                if ((acknowledgements.length() > 100)) {
                    var recent = JSONArray()
                    run {
                        var i = (acknowledgements.length() - 100)
                        while ((i < acknowledgements.length())) {
                            recent.put(acknowledgements.get(i))
                            i++
                        }
                    }
                    acknowledgements = recent
                }
                room.put("vision_acknowledgements", acknowledgements)
            }
            GameEngine.checkState(s)
        }
        val recordedValue: Any? = if (unknown) JSONObject.NULL else value
        var record =
            GameJson.obj(
                "question_id",
                q.getString("id"),
                "question",
                q.optString("prompt"),
                "scope",
                q.optString("scope"),
                "entity",
                entity,
                "field",
                field,
                "value",
                recordedValue,
                "status",
                (if (unknown) "unknown" else "confirmed"),
                "source",
                "human",
                "at",
                GameJson.now(),
                "rules_revision",
                room.optInt("rules_revision"),
            )
        room.getJSONArray("clarifications").put(record)
        var cs = room.getJSONArray("clarifications")
        if ((cs.length() > 200)) {
            var last = JSONArray()
            run {
                var i = (cs.length() - 200)
                while ((i < cs.length())) {
                    last.put(cs.get(i))
                    i++
                }
            }
            room.put("clarifications", last)
        }
        q.put("status", (if (unknown) "unknown" else "answered"))
        q.put("answer", recordedValue)
        GameEngine.changed(room)
        rebaseQuestions(room, before)
        var o = room.optJSONObject("board_observation")
        if (
            (((o != null) && (o.optInt("state_version", -1) == before)) &&
                (o.optInt("photo_version", -1) == room.optInt("board_version")))
        ) {
            o.put("state_version", s.getInt("version"))
        }
        if (
            (room.optBoolean("board_frozen") && (room.optInt("photo_state_version", -1) == before))
        ) {
            room.put("photo_state_version", s.getInt("version"))
        }
        room.put("phase", s.optString("phase"))
        room.put("turn", s.optInt("turn", 1))
    }
}
