package com.automaforge.android.domain.game

import com.automaforge.android.core.*
import java.util.Arrays
import java.util.UUID
import org.json.*

object GameEngine {
    @JvmStatic
    @Throws(Exception::class)
    fun copy(o: JSONObject): JSONObject {
        return JSONObject(o.toString())
    }

    @JvmStatic
    @Throws(Exception::class)
    fun state(room: JSONObject): JSONObject {
        var s = room.optJSONObject("game_state")
        if ((s == null)) {
            s =
                GameJson.obj(
                    "id",
                    UUID.randomUUID().toString(),
                    "version",
                    0,
                    "active_player",
                    "",
                    "phase",
                    room.optString("phase"),
                    "turn",
                    room.optInt("turn", 1),
                    "substep",
                    "",
                    "metadata_status",
                    "unknown",
                    "entities",
                    JSONArray(),
                    "unknowns",
                    JSONArray()
                        .put(
                            "Confirme jogador, fase, subetapa, oferta e tabuleiros; notas antigas não são ocupação confirmada."
                        ),
                )
            room.put("game_state", s)
            // Legacy proposals have no executable contract and must never be confirmed.
            var old = room.optJSONObject("proposal")
            if (((old != null) && !old.has("state_id"))) {
                room.put("legacy_proposal", old)
                room.put("proposal", JSONObject.NULL)
            }
        }
        return s
    }

    @JvmStatic
    @Throws(Exception::class)
    fun changed(room: JSONObject): Unit {
        var s = state(room)
        s.put("version", (s.getInt("version") + 1))
        room.put("decision", JSONObject.NULL)
        // Keep a pending proposal visible but stale.
    }

    @JvmStatic
    fun entity(s: JSONObject, id: String): JSONObject? {
        var a = s.optJSONArray("entities")
        if ((a == null)) {
            return null
        }
        run {
            var i = 0
            while ((i < a.length())) {

                var e = a.optJSONObject(i)
                if (((e != null) && (id == e.optString("id")))) {
                    return e
                }

                i++
            }
        }
        return null
    }

    @JvmStatic
    @OptIn(kotlin.contracts.ExperimentalContracts::class)
    fun known(e: JSONObject?): Boolean {
        kotlin.contracts.contract { returns(true) implies (e != null) }
        return AutoState.usable(e)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun checkState(s: JSONObject): Unit {
        var a = s.getJSONArray("entities")
        if ((a.length() > 3000)) {
            throw ApiException(422, "Limite de 3000 entidades públicas.")
        }
        var ids = HashSet<String>()
        var anchors = HashSet<String>()
        run {
            var i = 0
            while ((i < a.length())) {

                var e = a.getJSONObject(i)
                var id = e.optString("id")
                if ((!id.matches(Regex("[A-Za-z0-9_.:-]{1,100}")) || !ids.add(id))) {
                    throw ApiException(422, ("ID inválido ou duplicado: " + id))
                }
                if (
                    !Arrays.asList("confirmed", "observed", "inferred", "unknown")
                        .contains(e.optString("status"))
                ) {
                    throw ApiException(422, ("Situação ausente em " + id))
                }
                if (!("public" == e.optString("visibility", "public"))) {
                    throw ApiException(
                        422,
                        "Estado compartilhado aceita somente informações públicas.",
                    )
                }
                if (((e.optInt("quantity", 0) < 0) || (e.optInt("level", 0) < 0))) {
                    throw ApiException(422, ("Quantidade/nível negativo: " + id))
                }
                if ((("pooled" == e.optString("identity")) && (e.optInt("quantity") > 0))) {
                    var anchor =
                        ((((((((e.optString("kind") + "/") + e.optString("type")) + "/") +
                            e.optString("location")) + "/") + e.optString("group")) + "/") +
                            e.optInt("level"))
                    if (!anchors.add(anchor)) {
                        throw ApiException(
                            422,
                            (("Lotes indistinguíveis duplicados no mesmo local/nível: " + id) +
                                ". Consolide tipo e quantidade."),
                        )
                    }
                }
                if (e.optString("kind").isEmpty()) {
                    throw ApiException(422, ("Tipo de entidade ausente: " + id))
                }

                i++
            }
        }
        run {
            var i = 0
            while ((i < a.length())) {

                var e = a.getJSONObject(i)
                for (f in Arrays.asList("location", "group")) if (
                    (!e.optString(f).isEmpty() && (entity(s, e.optString(f)) == null))
                ) {
                    throw ApiException(
                        422,
                        ((("Referência " + f) + " inexistente em ") + e.optString("id")),
                    )
                }

                i++
            }
        }
    }

    /** Full replacement is an explicit human review, guarded by state version. */
    @JvmStatic
    @Throws(Exception::class)
    fun correct(room: JSONObject, input: JSONObject): Unit {
        var current = state(room)
        if (
            (!(current.getString("id") == input.optString("id")) ||
                (current.getInt("version") != input.optInt("version", -1)))
        ) {
            throw ApiException(409, "Estado foi atualizado; recarregue antes de corrigir.")
        }
        var next = copy(input)
        checkState(next)
        if (!next.has("facts")) {
            next.put("facts", JSONArray())
        }
        run {
            var i = 0
            while ((i < next.getJSONArray("facts").length())) {
                GameKnowledge.checkFact(room, next, next.getJSONArray("facts").getJSONObject(i))
                i++
            }
        }
        if ((next.optInt("turn", 0) < 1)) {
            throw ApiException(422, "Turno deve ser positivo.")
        }
        var entities = next.getJSONArray("entities")
        run {
            var i = 0
            while ((i < entities.length())) {

                var e = entities.getJSONObject(i)
                e.put("human_locked", true)
                e.put("source", "human")

                i++
            }
        }
        next.put("version", (current.getInt("version") + 1))
        next.put("metadata_status", "confirmed")
        next.put("metadata_source", "human")
        room.put("game_state", next)
        room.put("phase", next.optString("phase"))
        room.put("turn", next.optInt("turn", 1))
        room.put("decision", JSONObject.NULL)
    }

    /**
     * Observations are candidates, never automatic additions/deletions or replacements of human
     * facts.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun observation(room: JSONObject, observation: JSONObject, photoVersion: Int): JSONObject {
        var s = state(room)
        var conflicts = JSONArray()
        var candidates = observation.optJSONArray("entities")
        if ((candidates == null)) {
            throw ApiException(422, "Análise sem entidades estruturadas.")
        }
        var seen = HashSet<String>()
        run {
            var i = 0
            while ((i < candidates.length())) {

                var e = candidates.getJSONObject(i)
                var id = e.optString("id")
                if ((id.isEmpty() || !seen.add(id))) {
                    throw ApiException(422, "Análise com IDs ausentes ou duplicados.")
                }
                e.put("source", "image")
                e.put("image_version", photoVersion)
                e.put("human_locked", false)
                if (("confirmed" == e.optString("status"))) {
                    e.put("status", "observed")
                }
                var old = entity(s, id)
                if ((old != null)) {
                    // Identity anchor must remain consistent; uncertain matches go to review.
                    if (
                        ((old.optBoolean("human_locked") ||
                            !(old.optString("kind") == e.optString("kind"))) ||
                            !(old.optString("type") == e.optString("type")))
                    ) {
                        conflicts.put(("Revisar correspondência/correção humana: " + id))
                    }
                }

                i++
            }
        }
        observation.put("conflicts", conflicts)
        observation.put("photo_version", photoVersion)
        observation.put("state_version", s.getInt("version"))
        observation.put("at", GameJson.now())
        return observation
    }

    @JvmStatic
    fun action(room: JSONObject, name: String): JSONObject? {
        var actions = room.optJSONArray("actions")
        if ((actions == null)) {
            return null
        }
        run {
            var i = 0
            while ((i < actions.length())) {

                var a = actions.optJSONObject(i)
                if (((a != null) && (name == a.optString("name")))) {
                    return a
                }

                i++
            }
        }
        return null
    }

    @JvmStatic
    fun contains(a: JSONArray?, v: String): Boolean {
        if ((a == null)) {
            return false
        }
        run {
            var i = 0
            while ((i < a.length())) {
                if ((v == a.optString(i))) {
                    return true
                }
                i++
            }
        }
        return false
    }

    @JvmStatic
    fun report(errors: JSONArray, missing: JSONArray, simulated: JSONObject): JSONObject {
        var blockers = JSONArray()
        run {
            var i = 0
            while ((i < missing.length())) {

                var m = missing.optString(i)
                if (
                    ((((m.startsWith("Confirme jogador") || m.startsWith("Confirme a entidade")) ||
                        m.contains("confirme agrupamento")) ||
                        m.contains("ocupação do destino desconhecida")) ||
                        m.contains("componente/origem não confirmado"))
                ) {
                    blockers.put(m)
                }

                i++
            }
        }
        return GameJson.obj(
            "status",
            (if ((errors.length() > 0)) "invalid"
            else (if ((missing.length() > 0)) "unverifiable" else "valid")),
            "errors",
            errors,
            "missing",
            missing,
            "blockers",
            blockers,
            "simulated",
            simulated,
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun validate(room: JSONObject, p: JSONObject): JSONObject {
        try {
            return validateInternal(room, p)
        } catch (e: JSONException) {
            return report(
                JSONArray()
                    .put("Resposta fora do contrato: campos ou tipos estruturados inválidos."),
                JSONArray(),
                copy(state(room)),
            )
        } catch (e: ClassCastException) {
            return report(
                JSONArray()
                    .put("Resposta fora do contrato: campos ou tipos estruturados inválidos."),
                JSONArray(),
                copy(state(room)),
            )
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    private fun validateInternal(room: JSONObject, p: JSONObject): JSONObject {
        var s = copy(state(room))
        var errors = JSONArray()
        var missing = JSONArray()
        if (
            ((!(s.getString("id") == p.optString("state_id")) ||
                (s.getInt("version") != p.optInt("state_version", -1))) ||
                (room.optInt("rules_revision") != p.optInt("rules_revision", -1)))
        ) {
            errors.put("Proposta baseada em estado/regras antigos.")
        }
        if (!("proposed" == p.optString("status"))) {
            errors.put("Contrato sem status proposed.")
        }
        for (field in Arrays.asList("state_id", "player", "phase", "substep", "action")) if (
            !((p.opt(field) is String))
        ) {
            errors.put(("Campo obrigatório de texto: " + field))
        }
        for (field in Arrays.asList("state_version", "rules_revision")) if (
            !((p.opt(field) is Int))
        ) {
            errors.put(("Campo obrigatório inteiro: " + field))
        }
        var conflicts = AutoState.relevantIssues(room, p)
        run {
            var i = 0
            while ((i < conflicts.length())) {
                missing.put(conflicts.get(i))
                i++
            }
        }
        if (s.optBoolean("automatically_inferred")) {
            missing.put(
                "O estado contém reconhecimento/inferência automática; confira os movimentos antes de aprovar."
            )
        }
        var a = action(room, p.optString("action"))
        if ((a == null)) {
            errors.put("Ação não cadastrada.")
            return report(errors, missing, s)
        }
        if (!GameKnowledge.model(room).optBoolean("reviewed")) {
            missing.put("Modelo de estado deste jogo ainda não foi revisado.")
        }
        var knowledgeMissing = GameKnowledge.blockers(room, s, p.optString("action"))
        run {
            var i = 0
            while ((i < knowledgeMissing.length())) {
                missing.put(knowledgeMissing.get(i))
                i++
            }
        }
        if ((!a.optBoolean("reviewed") || !a.optBoolean("validation_complete"))) {
            missing.put("Cobertura das regras desta ação não foi confirmada por uma pessoa.")
        }
        if (!AutoState.metadataUsable(s)) {
            missing.put("Confirme jogador ativo, fase e subetapa do estado.")
        }
        if (
            (GameKnowledge.model(room).optBoolean("reviewed") &&
                s.optString("automa_player").isEmpty())
        ) {
            missing.put("Confirme jogador controlado pela automa e a propriedade de seu tabuleiro.")
        }
        if (
            (!s.optString("automa_player").isEmpty() &&
                !(s.optString("automa_player") == s.optString("active_player")))
        ) {
            errors.put("O jogador ativo não é a automa identificada.")
        }
        if (!(s.optString("active_player") == p.optString("player"))) {
            errors.put("Jogador incorreto.")
        }
        if (!(s.optString("phase") == p.optString("phase"))) {
            errors.put("Fase incorreta.")
        }
        if (!(s.optString("substep") == p.optString("substep"))) {
            errors.put("Subetapa incorreta.")
        }
        if ((!a.optString("phase").isEmpty() && !(s.optString("phase") == a.optString("phase")))) {
            errors.put("Ação indisponível nesta fase.")
        }
        if (
            (!a.optString("player").isEmpty() &&
                !(s.optString("active_player") == a.optString("player")))
        ) {
            errors.put("Ação indisponível para o jogador.")
        }
        var required = a.optJSONArray("required_state")
        if ((required == null)) {
            missing.put("Defina os dados de estado necessários à ação.")
        } else {
            run {
                var i = 0
                while ((i < required.length())) {

                    var id = required.getString(i)
                    if (id.startsWith("field:")) {
                        var key = id.substring(6)
                        var d = GameKnowledge.definition(room, key)
                        if ((d == null)) {
                            missing.put(("Confirme a entidade de variável não definida: " + key))
                        } else {
                            var subjects = GameKnowledge.subjects(room, s, d)
                            if (subjects.isEmpty()) {
                                missing.put(
                                    ("Confirme a entidade/escopo de " + d.optString("label"))
                                )
                            }
                            for (subject in subjects) {
                                var f = GameKnowledge.fact(s, key, subject)
                                if ((!known(f) || f.isNull("value"))) {
                                    missing.put(
                                        (((("Confirme a entidade de estado: " +
                                            d.optString("label")) + " (") + subject) + ").")
                                    )
                                }
                            }
                        }
                    } else {
                        if (id.startsWith("area:")) {
                            var key = id.substring(5)
                            var found = false
                            var es = s.getJSONArray("entities")
                            run {
                                var j = 0
                                while ((j < es.length())) {

                                    var e = es.getJSONObject(j)
                                    if (
                                        (((key == e.optString("area_type")) && known(e)) &&
                                            (((e.optString("owner") == "shared") ||
                                                (e.optString("owner") ==
                                                    s.optString("active_player")))))
                                    ) {
                                        found = true
                                    }

                                    j++
                                }
                            }
                            if (!found) {
                                missing.put(("Confirme a entidade de área necessária: " + key))
                            }
                        } else {
                            if (!known(entity(s, id))) {
                                missing.put(("Confirme a entidade necessária: " + id))
                            }
                        }
                    }

                    i++
                }
            }
        }
        var params = p.optJSONObject("parameters")
        var defs = a.optJSONArray("parameters")
        if ((params == null)) {
            errors.put("Parâmetros ausentes.")
            params = JSONObject()
        }
        if ((defs == null)) {
            missing.put("Defina os parâmetros da ação.")
        } else {
            var names = HashSet<String>()
            run {
                var i = 0
                while ((i < defs.length())) {

                    var d = defs.getJSONObject(i)
                    var name = d.optString("name")
                    var type = d.optString("type")
                    names.add(name)
                    var value = params.opt(name)
                    if (((value == null) || (value == JSONObject.NULL))) {
                        if (d.optBoolean("required")) {
                            errors.put(("Parâmetro obrigatório: " + name))
                        }
                        i++
                        continue
                    }
                    var ok =
                        (if (("string" == type)) (value is String)
                        else
                            (if (("integer" == type))
                                ((value is Number) &&
                                    (((value as Number)).toDouble() ==
                                        ((value as Number)).toInt().toDouble()))
                            else (if (("boolean" == type)) (value is Boolean) else false)))
                    if (!ok) {
                        errors.put(("Tipo de parâmetro inválido: " + name))
                    }
                    var choices = d.optJSONArray("values")
                    if (
                        (((choices != null) && (choices.length() > 0)) &&
                            !contains(choices, java.lang.String.valueOf(value)))
                    ) {
                        errors.put(("Escolha não permitida: " + name))
                    }

                    i++
                }
            }
            var keys = params.keys()
            while (keys.hasNext()) {
                var key = keys.next()
                if (!names.contains(key)) {
                    errors.put("Parâmetro não definido na ação pública.")
                }
            }
        }
        var moves = p.optJSONArray("moves")
        if (((moves == null) || (moves.length() == 0))) {
            errors.put("Uma intenção genérica não é jogada: informe movimentos parametrizados.")
            return report(errors, missing, s)
        }
        if ((moves.length() > 100)) {
            errors.put("Limite de 100 movimentos por etapa.")
            return report(errors, missing, s)
        }
        if (
            ((moves.length() < a.optInt("min_moves", 1)) ||
                (moves.length() > a.optInt("max_moves", 100)))
        ) {
            errors.put("Quantidade de movimentos fora da definição da ação.")
        }
        var constraints = a.optJSONArray("constraints")
        if ((constraints == null)) {
            missing.put("Restrições não definidas.")
        }
        var total = 0
        var group: String? = null
        run {
            var i = 0
            while ((i < moves.length())) {

                var m = moves.getJSONObject(i)
                var op = m.optString("operation")
                var prefix = (("Movimento " + ((i + 1))) + ": ")
                for (field in
                    Arrays.asList(
                        "operation",
                        "component",
                        "source",
                        "destination",
                        "choice_parameter",
                    )) if (!((m.opt(field) is String))) {
                    errors.put(((prefix + "campo de texto ausente: ") + field))
                }
                for (field in Arrays.asList("quantity", "level")) if (!((m.opt(field) is Int))) {
                    errors.put(((prefix + "campo inteiro ausente: ") + field))
                }
                if (!contains(a.optJSONArray("operations"), op)) {
                    errors.put((prefix + "operação não permitida."))
                }
                if (!Arrays.asList("transfer", "select", "choose").contains(op)) {
                    missing.put((prefix + "operação sem simulador."))
                    i++
                    continue
                }
                if (("choose" == op)) {
                    var choice = m.optString("choice_parameter")
                    var value = params.opt(choice)
                    if (
                        (((choice.isEmpty() || (value == null)) || (value == JSONObject.NULL)) ||
                            (((value is String) && ((value as String)).trim().isEmpty())))
                    ) {
                        errors.put((prefix + "escolha parametrizada ausente."))
                    }
                    if ((value is String)) {
                        var concrete = (entity(s, (value as String)) != null)
                        if ((defs != null)) {
                            run {
                                var j = 0
                                while ((j < defs.length())) {

                                    var d = defs.getJSONObject(j)
                                    if (
                                        ((choice == d.optString("name")) &&
                                            contains(d.optJSONArray("values"), (value as String)))
                                    ) {
                                        concrete = true
                                    }

                                    j++
                                }
                            }
                        }
                        if (!concrete) {
                            errors.put(
                                (prefix +
                                    "escolha textual precisa ser alternativa explícita ou ID público, não intenção genérica.")
                            )
                        }
                    }
                    i++
                    continue
                }
                var c = entity(s, m.optString("component"))
                var src = entity(s, m.optString("source"))
                var dst = entity(s, m.optString("destination"))
                if ((c == null)) {
                    errors.put((prefix + "componente inexistente."))
                    i++
                    continue
                }
                if (!Arrays.asList("component", "resource", "card").contains(c.optString("kind"))) {
                    errors.put((prefix + "entidade não é componente/recurso/carta."))
                }
                if ((src == null)) {
                    errors.put((prefix + "origem inexistente."))
                    i++
                    continue
                }
                if (
                    (("transfer" == op) &&
                        (((dst == null) || !("position" == dst.optString("kind")))))
                ) {
                    errors.put((prefix + "destino inexistente ou não é uma posição."))
                    i++
                    continue
                }
                if (
                    ((!known(c) || !known(src)) ||
                        ((("position" == src.optString("kind")) &&
                            !src.optBoolean("occupancy_known"))))
                ) {
                    missing.put((prefix + "componente/origem não confirmado ou observado."))
                    i++
                    continue
                }
                if (!(c.optString("location") == m.optString("source"))) {
                    errors.put((prefix + "componente não está na origem."))
                }
                if (
                    !GameKnowledge.allows(
                        room,
                        s,
                        GameKnowledge.array(a, "sources"),
                        m.optString("source"),
                    )
                ) {
                    errors.put((prefix + "origem não permitida."))
                }
                var quantity = m.optInt("quantity", 0)
                if ((("transfer" == op) || !contains(a.optJSONArray("operations"), "transfer"))) {
                    total += quantity
                }
                if (((quantity < 1) || (quantity > c.optInt("quantity", 0)))) {
                    errors.put((prefix + "quantidade indisponível."))
                }
                if ((constraints != null)) {
                    run {
                        var j = 0
                        while ((j < constraints.length())) {
                            if (
                                (("same_group" == constraints.getJSONObject(j).optString("kind")) &&
                                    c.optString("group").isEmpty())
                            ) {
                                missing.put((prefix + "confirme agrupamento do componente."))
                            }
                            j++
                        }
                    }
                }
                if (!c.optString("group").isEmpty()) {
                    if ((group == null)) {
                        group = c.optString("group")
                    }
                    var g = entity(s, c.optString("group"))
                    if (!known(g)) {
                        missing.put(((prefix + "confirme agrupamento ") + c.optString("group")))
                    }
                }
                if (("select" == op)) {
                    if ((constraints != null)) {
                        run {
                            var j = 0
                            while ((j < constraints.length())) {
                                if (
                                    ((("same_group" ==
                                        constraints.getJSONObject(j).optString("kind")) &&
                                        (group != null)) && !(group == c.optString("group")))
                                ) {
                                    errors.put((prefix + "componentes de grupos diferentes."))
                                }
                                j++
                            }
                        }
                    }
                    c.put("selected_quantity", quantity)
                    i++
                    continue
                }
                if (((dst == null) || !("position" == dst.optString("kind")))) {
                    errors.put((prefix + "destino inexistente ou não é uma posição."))
                    i++
                    continue
                }
                if ((!known(dst) || !dst.optBoolean("occupancy_known"))) {
                    missing.put(
                        ((prefix + "ocupação do destino desconhecida: ") + dst.optString("id"))
                    )
                    i++
                    continue
                }
                if (
                    !GameKnowledge.allows(
                        room,
                        s,
                        GameKnowledge.array(a, "destinations"),
                        dst.optString("id"),
                    )
                ) {
                    errors.put((prefix + "destino não permitido."))
                }
                if ((dst.optString("id") == src.optString("id"))) {
                    errors.put((prefix + "origem igual ao destino."))
                }
                var all = s.getJSONArray("entities")
                run {
                    var j = 0
                    while ((j < all.length())) {

                        var other = all.getJSONObject(j)
                        if (
                            ((dst.optString("id") == other.optString("location")) && !known(other))
                        ) {
                            missing.put(
                                (prefix +
                                    "ocupação do destino desconhecida: componente ocluso ou inferido.")
                            )
                        }

                        j++
                    }
                }
                var height = height(s, dst.optString("id"))
                var level = m.optInt("level", -1)
                if ((level != (height + 1))) {
                    errors.put(
                        (((prefix + "nível deve ser ") + ((height + 1))) + " no estado sequencial.")
                    )
                }
                if ((quantity != 1)) {
                    errors.put(
                        (prefix +
                            "separe a transferência em movimentos unitários com ordem e nível explícitos.")
                    )
                }
                if (
                    (("position" == src.optString("kind")) &&
                        (c.optInt("level") != height(s, src.optString("id"))))
                ) {
                    errors.put(
                        (prefix + "apenas topo da pilha pode ser retirado pelo simulador atual.")
                    )
                }
                if ((constraints != null)) {
                    run {
                        var j = 0
                        while ((j < constraints.length())) {

                            var rule = constraints.getJSONObject(j)
                            var kind = rule.optString("kind")
                            when (kind) {
                                "max_height" -> {
                                    if ((level > rule.optInt("value", 0))) {
                                        errors.put((prefix + "altura máxima excedida."))
                                    }
                                }
                                "empty_destination" -> {
                                    if ((height > 0)) {
                                        errors.put((prefix + "destino deve estar vazio."))
                                    }
                                }
                                "adjacent" -> {
                                    var board = entity(s, src.optString("location"))
                                    var topology =
                                        (if ((board == null)) "" else board.optString("topology"))
                                    if (
                                        (((!known(board) ||
                                            !Arrays.asList("hex_axial", "graph")
                                                .contains(topology)) ||
                                            ((("hex_axial" == topology) &&
                                                ((((!src.has("q") || !src.has("r")) ||
                                                    !dst.has("q")) || !dst.has("r")))))) ||
                                            ((("graph" == topology) &&
                                                (src.optJSONArray("neighbors") == null))))
                                    ) {
                                        missing.put(
                                            "Confirme a entidade de tabuleiro, coordenadas e conexões para verificar adjacência."
                                        )
                                    } else {
                                        if (!adjacent(s, src, dst)) {
                                            errors.put((prefix + "destino não adjacente."))
                                        }
                                    }
                                }
                                "selected_only" -> {
                                    if ((c.optInt("selected_quantity", 0) < quantity)) {
                                        errors.put((prefix + "componente não selecionado."))
                                    }
                                }
                                "same_group" -> {
                                    if (((group != null) && !(group == c.optString("group")))) {
                                        errors.put((prefix + "componentes de grupos diferentes."))
                                    }
                                }
                                "min_quantity",
                                "max_quantity" -> {}
                                else -> {
                                    missing.put(("Restrição sem implementação: " + kind))
                                }
                            }

                            j++
                        }
                    }
                }
                if ((errors.length() > 0)) {
                    i++
                    continue
                }
                var remaining = (c.optInt("quantity") - quantity)
                var moved = c
                if ((remaining > 0)) {
                    moved = copy(c)
                    moved.put("id", ((("move:" + p.optString("id", "draft")) + ":") + i))
                    if ((entity(s, moved.getString("id")) != null)) {
                        errors.put((prefix + "colisão de ID."))
                        i++
                        continue
                    }
                    s.getJSONArray("entities").put(moved)
                    c.put("quantity", remaining)
                    c.put(
                        "selected_quantity",
                        Math.max(0, (c.optInt("selected_quantity") - quantity)),
                    )
                }
                moved.put("quantity", quantity)
                moved.put("location", dst.getString("id"))
                moved.put("level", level)
                moved.put("group", "")
                moved.put("selected_quantity", 0)

                i++
            }
        }
        if ((constraints != null)) {
            run {
                var i = 0
                while ((i < constraints.length())) {

                    var rule = constraints.getJSONObject(i)
                    var k = rule.optString("kind")
                    if ((("min_quantity" == k) && (total < rule.optInt("value")))) {
                        errors.put("Quantidade total abaixo do mínimo.")
                    }
                    if ((("max_quantity" == k) && (total > rule.optInt("value")))) {
                        errors.put("Quantidade total acima do máximo.")
                    }
                    if (
                        !Arrays.asList(
                                "max_height",
                                "empty_destination",
                                "adjacent",
                                "selected_only",
                                "same_group",
                                "min_quantity",
                                "max_quantity",
                            )
                            .contains(k)
                    ) {
                        missing.put(("Restrição sem implementação: " + k))
                    }

                    i++
                }
            }
        }
        var effects = a.optJSONArray("effects")
        if ((effects == null)) {
            missing.put("Efeitos da ação não definidos.")
        } else {
            run {
                var i = 0
                while ((i < effects.length())) {

                    var effect = effects.getJSONObject(i)
                    var field = effect.optString("field")
                    if (
                        !Arrays.asList("active_player", "phase", "substep", "turn").contains(field)
                    ) {
                        missing.put(("Efeito sem implementação: " + field))
                        i++
                        continue
                    }
                    if (("turn" == field)) {
                        try {
                            var turn = Integer.parseInt(effect.getString("value"))
                            if ((turn < 1)) {
                                throw Exception()
                            }
                            s.put(field, turn)
                        } catch (e: Exception) {
                            errors.put("Turno inválido no efeito.")
                        }
                    } else {
                        s.put(field, effect.optString("value"))
                    }

                    i++
                }
            }
        }
        return report(errors, missing, s)
    }

    @JvmStatic
    fun height(s: JSONObject, position: String): Int {
        var height = 0
        val es = s.getJSONArray("entities")
        run {
            var i = 0
            while ((i < es.length())) {

                val e = es.getJSONObject(i)
                if (((position == e.optString("location")) && (e.optInt("quantity") > 0))) {
                    height = Math.max(height, e.optInt("level"))
                }

                i++
            }
        }
        return height
    }

    @JvmStatic
    fun adjacent(s: JSONObject, a: JSONObject?, b: JSONObject?): Boolean {
        if (
            (((a == null) || (b == null)) || !(a.optString("location") == b.optString("location")))
        ) {
            return false
        }
        if (contains(a.optJSONArray("neighbors"), b.optString("id"))) {
            return true
        }
        var board = entity(s, a.optString("location"))
        if (((board == null) || !("hex_axial" == board.optString("topology")))) {
            return false
        }
        if ((((!a.has("q") || !a.has("r")) || !b.has("q")) || !b.has("r"))) {
            return false
        }
        var dq = (a.optInt("q") - b.optInt("q"))
        var dr = (a.optInt("r") - b.optInt("r"))
        return (Math.max(Math.max(Math.abs(dq), Math.abs(dr)), Math.abs((dq + dr))) == 1)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun steps(p: JSONObject): JSONArray {
        var result = JSONArray()
        var moves = p.getJSONArray("moves")
        run {
            var i = 0
            while ((i < moves.length())) {

                var m = moves.getJSONObject(i)
                var op = m.optString("operation")
                result.put(
                    (if (("choose" == op))
                        ((("Escolha " + m.optString("choice_parameter")) + " = ") +
                            p.getJSONObject("parameters").opt(m.optString("choice_parameter")))
                    else
                        (if (("select" == op))
                            ((((("Selecione " + m.optInt("quantity")) + " × ") +
                                m.optString("component")) + " da origem ") + m.optString("source"))
                        else
                            ((((((((("Mova " + m.optInt("quantity")) + " × ") +
                                m.optString("component")) + " de ") + m.optString("source")) +
                                " para ") + m.optString("destination")) + ", nível ") +
                                m.optInt("level"))))
                )

                i++
            }
        }
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun proposal(room: JSONObject, data: JSONObject): JSONObject {
        var p = JSONObject()
        for (key in
            Arrays.asList(
                "status",
                "state_id",
                "state_version",
                "rules_revision",
                "player",
                "phase",
                "substep",
                "action",
                "parameters",
                "reason",
            )) if (data.has(key)) {
            p.put(key, data.get(key))
        }
        var input = data.optJSONArray("moves")
        var moves = JSONArray()
        if ((input != null)) {
            run {
                var i = 0
                while ((i < input.length())) {

                    var original = input.optJSONObject(i)
                    var m = JSONObject()
                    if ((original == null)) {
                        moves.put(JSONObject.NULL)
                        i++
                        continue
                    }
                    for (key in
                        Arrays.asList(
                            "operation",
                            "component",
                            "source",
                            "destination",
                            "quantity",
                            "level",
                            "choice_parameter",
                        )) if (original.has(key)) {
                        m.put(key, original.get(key))
                    }
                    moves.put(m)

                    i++
                }
            }
        }
        p.put("moves", moves)
        p.put("id", UUID.randomUUID().toString())
        p.put("execution_status", "proposed")
        var validation = validate(room, p)
        validation.remove("simulated")
        p.put("validation", validation)
        p.put(
            "player_steps",
            (if (("invalid" == validation.optString("status"))) JSONArray() else steps(p)),
        )
        p.put("spoken", p.getJSONArray("player_steps").join(". ").replace("\"", ""))
        p.put("reason", GameJson.clip(p.optString("reason"), 1000))
        return p
    }
}
