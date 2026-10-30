package com.automaforge.android.domain.game

import com.automaforge.android.core.*
import java.util.Arrays
import org.json.*

object AutoState {
    @JvmStatic
    fun enabled(room: JSONObject): Boolean {
        return !(room.optString("assistance_mode", "automatic") == "manual")
    }

    @JvmStatic
    @Throws(Exception::class)
    fun defaults(room: JSONObject): Unit {
        GameKnowledge.ensure(room)
        var s = GameEngine.state(room)
        if (s.optString("automa_player").isEmpty()) {
            s.put("automa_player", "automa")
            s.put("automa_identity_source", "application_role")
        }
        if (!s.has("physical_version")) {
            s.put("physical_version", 0)
        }
    }

    @JvmStatic
    fun modelReady(room: JSONObject): Boolean {
        var m = GameKnowledge.model(room)
        return ((m.optJSONArray("areas") != null) && (m.optJSONArray("fields") != null))
    }

    @JvmStatic
    @OptIn(kotlin.contracts.ExperimentalContracts::class)
    fun usable(datum: JSONObject?): Boolean {
        kotlin.contracts.contract { returns(true) implies (datum != null) }
        if ((datum == null)) {
            return false
        }
        if (Arrays.asList("confirmed", "observed").contains(datum.optString("status"))) {
            return !datum.optBoolean("stale")
        }
        return (((("inferred" == datum.optString("status")) &&
            datum.optBoolean("decision_usable")) && !datum.optBoolean("stale")) && evidence(datum))
    }

    @JvmStatic
    fun evidence(d: JSONObject): Boolean {
        var c = d.optDouble("confidence", -1.0)
        return (((java.lang.Double.isFinite(c) && (c >= .85)) && (c <= 1)) &&
            !d.optString("evidence").trim().isEmpty())
    }

    @JvmStatic
    @Throws(Exception::class)
    fun stamp(d: JSONObject, photo: Int): Unit {
        var status = d.optString("status")
        var usable = (evidence(d) && Arrays.asList("observed", "inferred").contains(status))
        d.put("source", "image")
        d.put("image_version", photo)
        d.put("human_locked", false)
        d.put("decision_usable", usable)
        if (!usable) {
            d.put("status", "unknown")
            d.put("occupancy_known", false)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun activateDefinition(room: JSONObject, result: JSONObject, includeRules: Boolean): Unit {
        defaults(room)
        var m = result.optJSONObject("state_model")
        if ((m == null)) {
            throw ApiException(422, "A análise não identificou os dados necessários do jogo.")
        }
        GameKnowledge.validateModel(m)
        var current = GameKnowledge.model(room)
        if (!current.optBoolean("reviewed")) {
            var next = GameEngine.copy(m)
            next.put("reviewed", false)
            next.put("source", "manual_inference")
            room.put("state_model", next)
        }
        var actions = result.optJSONArray("actions")
        if ((actions != null)) {
            var previous = GameKnowledge.array(room, "actions")
            var next = JSONArray()
            run {
                var i = 0
                while ((i < actions.length())) {

                    var a = GameEngine.copy(actions.getJSONObject(i))
                    if (a.optString("name").trim().isEmpty()) {
                        throw ApiException(422, "Definição automática com ação sem nome.")
                    }
                    var old = GameKnowledge.named(previous, "name", a.optString("name"))
                    if (((old != null) && old.optBoolean("reviewed"))) {
                        next.put(old)
                    } else {
                        a.put("reviewed", false)
                        a.put("validation_complete", false)
                        a.put("source", "manual_inference")
                        next.put(a)
                    }

                    i++
                }
            }
            run {
                var i = 0
                while ((i < previous.length())) {

                    var old = previous.getJSONObject(i)
                    if ((GameKnowledge.named(next, "name", old.optString("name")) == null)) {
                        next.put(old)
                    }

                    i++
                }
            }
            room.put("actions", next)
        }
        if ((includeRules && !room.optBoolean("rules_human_validated"))) {
            for (key in Arrays.asList("rules_text", "setup", "turn_flow", "uncertainties")) if (
                result.has(key)
            ) {
                room.put(key, result.get(key))
            }
        }
        room.put("rules_revision", (room.optInt("rules_revision") + 1))
        GameEngine.changed(room)
        room.put(
            "inference_notice",
            "Regras e dados preenchidos pela IA; inferências permanecem identificadas. Confira a proposta antes de aprovar.",
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun photoCurrent(room: JSONObject): Boolean {
        var s = GameEngine.state(room)
        var binding = room.optInt("photo_physical_version", -1)
        return (if ((binding >= 0)) (binding == s.optInt("physical_version"))
        else (room.optInt("photo_state_version", -1) == s.optInt("version")))
    }

    @JvmStatic
    fun anchor(e: JSONObject): String {
        var kind = e.optString("kind")
        if (("pooled" == e.optString("identity"))) {
            return ((((((((kind + ":") + e.optString("type")) + ":") + e.optString("location")) +
                ":") + e.optString("group")) + ":") + e.optInt("level"))
        }
        if (((("position" == kind) && e.has("q")) && e.has("r"))) {
            return ((((("position:" + e.optString("location")) + ":") + e.optInt("q")) + ":") +
                e.optInt("r"))
        }
        return ""
    }

    @JvmStatic
    fun overlap(a: JSONArray?, b: JSONArray?): Double {
        if ((!RegionGeometry.validRegion(a) || !RegionGeometry.validRegion(b))) {
            return 0.0
        }
        var left = Math.max(a.optDouble(0), b.optDouble(0))
        var top = Math.max(a.optDouble(1), b.optDouble(1))
        var right = Math.min((a.optDouble(0) + a.optDouble(2)), (b.optDouble(0) + b.optDouble(2)))
        var bottom = Math.min((a.optDouble(1) + a.optDouble(3)), (b.optDouble(1) + b.optDouble(3)))
        var intersection = (Math.max(0.0, (right - left)) * Math.max(0.0, (bottom - top)))
        return (intersection /
            ((((a.optDouble(2) * a.optDouble(3)) + (b.optDouble(2) * b.optDouble(3))) -
                intersection)))
    }

    @JvmStatic
    fun resolvedForPhoto(room: JSONObject, entity: String, field: String, value: Any?): Boolean {
        var all = GameKnowledge.array(room, "vision_acknowledgements")
        run {
            var i = (all.length() - 1)
            while ((i >= 0)) {

                var a = all.optJSONObject(i)
                if (
                    (((((a != null) &&
                        (a.optInt("photo_version") == room.optInt("board_version"))) &&
                        (entity == a.optString("entity"))) && (field == a.optString("field"))) &&
                        (java.lang.String.valueOf(value) ==
                            java.lang.String.valueOf(a.opt("value"))))
                ) {
                    return true
                }

                i--
            }
        }
        return false
    }

    @JvmStatic
    fun issue(issues: JSONArray, id: String, field: String, text: String): Unit {
        issues.put(GameJson.obj("entity", id, "field", field, "message", text))
    }

    @JvmStatic
    @Throws(Exception::class)
    fun applyObservation(room: JSONObject, original: JSONObject, photo: Int): JSONObject {
        defaults(room)
        var work = GameEngine.copy(room)
        var s = GameEngine.state(work)
        var o = GameEngine.copy(original)
        var issues = JSONArray()
        var candidates = GameKnowledge.array(o, "entities")
        s.put("automatically_inferred", true)
        var ordered = ArrayList<JSONObject>()
        run {
            var i = 0
            while ((i < candidates.length())) {
                ordered.add(candidates.getJSONObject(i))
                i++
            }
        }
        ordered.sortWith(
            compareBy<JSONObject> { e ->
                val rank =
                    listOf("region", "board", "group", "position", "component", "resource", "card")
                        .indexOf(e.optString("kind"))
                if (rank < 0) 8 else rank
            }
        )
        candidates = JSONArray()
        for (e in ordered) candidates.put(e)
        o.put("entities", candidates)
        var mapping = HashMap<String, String>()
        var claimed = HashSet<String>()
        // Resolve stable anchors first. A new visual ID cannot create another copy of an
        // indistinguishable lot.
        run {
            var i = 0
            while ((i < candidates.length())) {

                var e = candidates.getJSONObject(i)
                for (ref in Arrays.asList("location", "group")) if (
                    mapping.containsKey(e.optString(ref))
                ) {
                    e.put(ref, mapping.get(e.optString(ref)))
                }
                var id = e.optString("id")
                var found = GameEngine.entity(s, id)
                if ((found == null)) {
                    var matches = ArrayList<JSONObject>()
                    var a = anchor(e)
                    run {
                        var j = 0
                        while ((j < s.getJSONArray("entities").length())) {

                            var old = s.getJSONArray("entities").getJSONObject(j)
                            var exact = (!a.isEmpty() && (a == anchor(old)))
                            var region =
                                (((Arrays.asList("board", "region", "group")
                                    .contains(e.optString("kind")) &&
                                    (e.optString("kind") == old.optString("kind"))) &&
                                    (e.optString("area_type") == old.optString("area_type"))) &&
                                    (overlap(e.optJSONArray("region"), old.optJSONArray("region")) >
                                        .65))
                            if ((exact || region)) {
                                matches.add(old)
                            }

                            j++
                        }
                    }
                    if ((matches.size == 1)) {
                        found = matches.get(0)
                    }
                    if ((matches.size > 1)) {
                        e.put("status", "unknown")
                        issue(
                            issues,
                            id,
                            "identity",
                            "Há mais de uma correspondência possível para esta entidade.",
                        )
                    }
                }
                var canonical = (if ((found == null)) id else found.optString("id"))
                if (!claimed.add(canonical)) {
                    e.put("status", "unknown")
                    issue(
                        issues,
                        canonical,
                        "identity",
                        "A leitura duplicou uma entidade; confira a correspondência.",
                    )
                }
                mapping.put(id, canonical)

                i++
            }
        }
        run {
            var i = 0
            while ((i < candidates.length())) {

                var e = candidates.getJSONObject(i)
                e.put("id", mapping.getOrDefault(e.optString("id"), e.optString("id")))
                for (ref in Arrays.asList("location", "group")) if (
                    mapping.containsKey(e.optString(ref))
                ) {
                    e.put(ref, mapping.get(e.optString(ref)))
                }
                var neighbors = e.optJSONArray("neighbors")
                if ((neighbors != null)) {
                    run {
                        var n = 0
                        while ((n < neighbors.length())) {
                            if (mapping.containsKey(neighbors.optString(n))) {
                                neighbors.put(n, mapping.get(neighbors.optString(n)))
                            }
                            n++
                        }
                    }
                }

                i++
            }
        }
        var handled = HashSet<String>()
        run {
            var i = 0
            while ((i < candidates.length())) {

                var e = candidates.getJSONObject(i)
                var id = e.optString("id")
                if (!handled.add(id)) {
                    i++
                    continue
                }
                var old = GameEngine.entity(s, id)
                stamp(e, photo)
                if ((old != null)) {
                    if (old.optBoolean("human_locked")) {
                        if ((evidence(e) && !("unknown" == e.optString("status")))) {
                            for (f in
                                Arrays.asList(
                                    "type",
                                    "location",
                                    "group",
                                    "quantity",
                                    "level",
                                    "owner",
                                    "occupancy_known",
                                )) {
                                if (
                                    ((((e.has(f) && old.has(f)) &&
                                        !resolvedForPhoto(work, id, f, old.opt(f))) &&
                                        !(java.lang.String.valueOf(e.opt(f)) ==
                                            java.lang.String.valueOf(old.opt(f)))) &&
                                        !e.optString(f).isEmpty())
                                ) {
                                    issue(
                                        issues,
                                        id,
                                        f,
                                        (((("A nova leitura diverge de uma correção humana em " +
                                            id) + " (") + f) + ")."),
                                    )
                                }
                            }
                        }
                        i++
                        continue
                    }
                    if (
                        (!(old.optString("kind") == e.optString("kind")) ||
                            !(old.optString("type") == e.optString("type")))
                    ) {
                        old.put("stale", true)
                        issue(
                            issues,
                            id,
                            "identity",
                            "Não foi possível confirmar a identidade da entidade nesta foto.",
                        )
                        i++
                        continue
                    }
                    var keys = e.keys()
                    while (keys.hasNext()) {
                        var key = keys.next()
                        old.put(key, e.get(key))
                    }
                    old.put("stale", false)
                } else {
                    s.getJSONArray("entities").put(e)
                }

                i++
            }
        }
        run {
            var i = 0
            while ((i < s.getJSONArray("entities").length())) {

                var old = s.getJSONArray("entities").getJSONObject(i)
                if (
                    ((!handled.contains(old.optString("id")) && !old.optBoolean("human_locked")) &&
                        ("image" == old.optString("source")))
                ) {
                    old.put("stale", true)
                    old.put("status", "unknown")
                }

                i++
            }
        }
        // Reject unresolved references as a real blocker rather than silently inventing their
        // targets.
        try {
            GameEngine.checkState(s)
        } catch (invalid: Exception) {
            room.put(
                "auto_issues",
                JSONArray()
                    .put(
                        GameJson.obj(
                            "entity",
                            "",
                            "field",
                            "structure",
                            "message",
                            "A leitura tem referências ou quantidades inconsistentes. Confira as áreas indicadas.",
                        )
                    ),
            )
            room.put("board_observation", o)
            return GameJson.obj("applied", false, "issues", room.getJSONArray("auto_issues"))
        }
        var facts = GameKnowledge.array(o, "facts")
        run {
            var i = 0
            while ((i < facts.length())) {

                var f = facts.getJSONObject(i)
                if (mapping.containsKey(f.optString("subject"))) {
                    f.put("subject", mapping.get(f.optString("subject")))
                }
                stamp(f, photo)
                var d = GameKnowledge.definition(work, f.optString("key"))
                if (((d == null) || !("public" == d.optString("visibility")))) {
                    i++
                    continue
                }
                var prior = GameKnowledge.fact(s, f.optString("key"), f.optString("subject"))
                if (
                    (((((prior != null) && prior.optBoolean("human_locked")) &&
                        !resolvedForPhoto(
                            work,
                            f.optString("subject"),
                            f.optString("key"),
                            prior.opt("value"),
                        )) && evidence(f)) &&
                        !(java.lang.String.valueOf(prior.opt("value")) ==
                            java.lang.String.valueOf(f.opt("value"))))
                ) {
                    issue(
                        issues,
                        f.optString("subject"),
                        f.optString("key"),
                        (("A nova leitura diverge da variável corrigida por você: " +
                            d.optString("label")) + "."),
                    )
                }
                try {
                    GameKnowledge.putFact(work, s, f, false)
                } catch (invalid: ApiException) {
                    issue(
                        issues,
                        f.optString("subject"),
                        f.optString("key"),
                        (("A leitura não resolveu o tipo/valor de " + d.optString("label")) + "."),
                    )
                }

                i++
            }
        }
        var metadata = s.optJSONObject("metadata_evidence")
        if ((metadata == null)) {
            metadata = JSONObject()
            s.put("metadata_evidence", metadata)
        }
        var values = GameKnowledge.array(o, "metadata")
        run {
            var i = 0
            while ((i < values.length())) {

                var f = values.getJSONObject(i)
                var field = f.optString("field")
                if (
                    (((!Arrays.asList("phase", "substep", "turn", "active_player")
                        .contains(field) || !evidence(f)) ||
                        !Arrays.asList("observed", "inferred").contains(f.optString("status"))) ||
                        f.isNull("value"))
                ) {
                    i++
                    continue
                }
                var prior = metadata.optJSONObject(field)
                if (((prior != null) && prior.optBoolean("human_locked"))) {
                    if (
                        (!resolvedForPhoto(work, "metadata", field, s.opt(field)) &&
                            !(java.lang.String.valueOf(f.opt("value")) ==
                                java.lang.String.valueOf(s.opt(field))))
                    ) {
                        issue(
                            issues,
                            "metadata",
                            field,
                            "A leitura diverge do valor de etapa corrigido por você.",
                        )
                    }
                    i++
                    continue
                }
                if ((("confirmed" == s.optString("metadata_status")) && (prior == null))) {
                    if (
                        (!resolvedForPhoto(work, "metadata", field, s.opt(field)) &&
                            !(java.lang.String.valueOf(f.opt("value")) ==
                                java.lang.String.valueOf(s.opt(field))))
                    ) {
                        issue(
                            issues,
                            "metadata",
                            field,
                            "A fase/etapa identificada diverge do valor informado por você.",
                        )
                    }
                    i++
                    continue
                }
                // legacy human confirmation
                var value = f.get("value")
                if (
                    (if (("turn" == field))
                        (!((value is Number)) || (((value as Number)).toInt() < 1))
                    else (!((value is String)) || ((value as String)).trim().isEmpty()))
                ) {
                    i++
                    continue
                }
                stamp(f, photo)
                s.put(field, value)
                metadata.put(field, f)

                i++
            }
        }
        if (!("confirmed" == s.optString("metadata_status"))) {
            s.put("metadata_status", "inferred")
        }
        GameEngine.changed(work)
        work.put("turn", s.optInt("turn", 1))
        work.put("phase", s.optString("phase"))
        work.put("auto_issues", issues)
        o.put("state_version", s.getInt("version"))
        o.put("photo_version", photo)
        o.put("automatically_applied", true)
        o.put("clarifications_count", GameKnowledge.array(work, "clarifications").length())
        work.put("board_observation", o)
        run {
            var i = 0
            while ((i < issues.length())) {

                var issue = issues.getJSONObject(i)
                var field = issue.optString("field")
                GameKnowledge.addQuestion(
                    work,
                    GameJson.obj(
                        "key",
                        ((("conflict:" + issue.optString("entity")) + ":") + field),
                        "scope",
                        "state",
                        "entity",
                        issue.optString("entity"),
                        "field",
                        field,
                        "prompt",
                        (issue.optString("message") + " Qual é o valor correto?"),
                        "input_type",
                        (if (Arrays.asList("quantity", "level").contains(field)) "integer"
                        else (if (("occupancy_known" == field)) "boolean" else "text")),
                        "options",
                        JSONArray(),
                        "required",
                        true,
                    ),
                    "conflict",
                )

                i++
            }
        }
        work.put("photo_state_version", s.getInt("version"))
        work.put("photo_physical_version", s.optInt("physical_version"))
        // Keep question references aligned with stable IDs too.
        var qs = GameKnowledge.array(o, "questions")
        run {
            var i = 0
            while ((i < qs.length())) {

                var q = qs.getJSONObject(i)
                if (mapping.containsKey(q.optString("entity"))) {
                    q.put("entity", mapping.get(q.optString("entity")))
                }

                i++
            }
        }
        run {
            var keys = work.keys()
            while (keys.hasNext()) {

                var key = keys.next()
                room.put(key, work.get(key))
            }
        }
        return GameJson.obj("applied", true, "issues", issues)
    }

    @JvmStatic
    fun metadataUsable(s: JSONObject): Boolean {
        if (("confirmed" == s.optString("metadata_status"))) {
            return ((!s.optString("active_player").isEmpty() && !s.optString("phase").isEmpty()) &&
                !s.optString("substep").isEmpty())
        }
        var evidence = s.optJSONObject("metadata_evidence")
        if ((evidence == null)) {
            return false
        }
        for (field in Arrays.asList("active_player", "phase", "substep")) {
            var e = evidence.optJSONObject(field)
            if ((!usable(e) || !(java.lang.String.valueOf(e.opt("value")) == s.optString(field)))) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun relevantIssues(room: JSONObject, p: JSONObject): JSONArray {
        var relevant = HashSet<String>()
        var moves = GameKnowledge.array(p, "moves")
        val s = GameEngine.state(room)
        run {
            var i = 0
            while ((i < moves.length())) {

                var m = moves.optJSONObject(i)
                if ((m == null)) {
                    i++
                    continue
                }
                for (key in Arrays.asList("component", "source", "destination")) {
                    var id = m.optString(key)
                    var seen = HashSet<String>()
                    while ((!id.isEmpty() && seen.add(id))) {
                        relevant.add(id)
                        var e = GameEngine.entity(s, id)
                        id = (if ((e == null)) "" else e.optString("location"))
                    }
                }

                i++
            }
        }
        var result = JSONArray()
        var all = GameKnowledge.array(room, "auto_issues")
        run {
            var i = 0
            while ((i < all.length())) {

                var e = all.optJSONObject(i)
                if (
                    ((e != null) &&
                        ((((e.optString("entity").isEmpty() ||
                            ("metadata" == e.optString("entity"))) ||
                            relevant.contains(e.optString("entity"))) ||
                            ((((GameKnowledge.definition(room, e.optString("field")) != null) &&
                                GameKnowledge.applies(
                                    GameKnowledge.definition(room, e.optString("field"))!!,
                                    p.optString("action"),
                                    s.optString("phase"),
                                )) &&
                                GameKnowledge.subjects(
                                        room,
                                        s,
                                        GameKnowledge.definition(room, e.optString("field"))!!,
                                    )
                                    .contains(e.optString("entity")))))))
                ) {
                    result.put(("Confirme a entidade: " + e.optString("message")))
                }

                i++
            }
        }
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun assertAutomaTurn(room: JSONObject): Unit {
        defaults(room)
        var s = GameEngine.state(room)
        if (!s.optString("active_player").isEmpty()) {
            return
        }
        s.put("active_player", s.optString("automa_player"))
        var es = s.optJSONObject("metadata_evidence")
        if ((es == null)) {
            es = JSONObject()
            s.put("metadata_evidence", es)
        }
        es.put(
            "active_player",
            GameJson.obj(
                "status",
                "confirmed",
                "source",
                "user_request",
                "human_locked",
                true,
                "value",
                s.getString("active_player"),
            ),
        )
        GameEngine.changed(room)
    }
}
