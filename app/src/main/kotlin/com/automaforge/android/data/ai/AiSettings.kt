package com.automaforge.android.data.ai

import android.content.Context
import com.automaforge.android.core.GameJson
import org.json.*

/**
 * Controller preferences shared by PDF, vision and decisions. Credentials have a separate store.
 */
class AiSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("private_settings", 0)

    fun model(): String = prefs.getString("model", "gpt-4.1-mini")!!

    fun reasoningEffort(): String = prefs.getString("reasoning_effort", "default")!!

    fun revision(): Int = prefs.getInt("ai_revision", 0)

    fun saveModel(value: String?) =
        synchronized(lock) {
            save(
                value,
                reasoningEffort().takeIf { AiModels.supports(context, value.orEmpty(), it) }
                    ?: "default",
            )
        }

    fun save(value: String?, effort: String?) =
        synchronized(lock) {
            val chosen = value.orEmpty().trim()
            require(validModel(chosen)) { "Escolha um modelo válido." }
            require(effort != null && AiModels.supports(context, chosen, effort)) {
                "O nível de raciocínio não é compatível com o modelo escolhido. Escolha uma das opções do menu."
            }
            if (chosen == model() && effort == reasoningEffort()) return@synchronized
            check(
                prefs
                    .edit()
                    .putString("model", chosen)
                    .putString("reasoning_effort", effort)
                    .putInt("ai_revision", revision() + 1)
                    .commit()
            ) {
                "Não foi possível salvar a configuração de IA."
            }
        }

    fun snapshot(): Selection =
        synchronized(lock) { Selection(model(), reasoningEffort(), revision()) }

    class Selection(
        @JvmField val model: String,
        @JvmField val reasoningEffort: String,
        @JvmField val revision: Int,
    ) {
        fun applyTo(body: JSONObject): JSONObject =
            body.apply {
                put("model", model)
                if (reasoningEffort != "default")
                    put("reasoning", GameJson.obj("effort", reasoningEffort))
            }
    }

    fun publicSettings(): JSONObject =
        synchronized(lock) {
            GameJson.obj(
                "model",
                model(),
                "reasoning_effort",
                reasoningEffort(),
                "reasoning_label",
                AiModels.effortLabel(reasoningEffort()),
                "revision",
                revision(),
                "model_options",
                AiModels.options(context, model()),
            )
        }

    companion object {
        private val lock = Any()

        @JvmStatic
        fun validModel(value: String?): Boolean =
            value != null &&
                value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,179}")) &&
                !value.contains("://")

        @JvmStatic
        fun errorMessage(status: Int): String =
            when (status) {
                400,
                404,
                422 ->
                    "A API recusou o modelo, o nível de raciocínio ou esta modalidade de entrada/saída. Confira a seleção e o suporte a Responses, imagens/PDF e JSON Schema. O aplicativo não trocou de modelo nem de nível de raciocínio."
                401,
                403 ->
                    "Acesso à API negado. Confira a chave e a permissão para usar o modelo escolhido."
                429 -> "Limite da API atingido. Confira cota e limites do modelo escolhido."
                else ->
                    "A API retornou HTTP $status. A análise não foi concluída e o modelo escolhido foi mantido."
            }
    }
}

object AiModels {
    private var catalogue: String? = null

    @Synchronized
    private fun catalogue(context: Context): JSONArray {
        if (catalogue == null)
            catalogue = context.assets.open("ai/models.json").bufferedReader().use { it.readText() }
        return JSONArray(catalogue!!)
    }

    private fun profile(context: Context, model: String): JSONObject? {
        val base = if (model.startsWith("ft:")) model.substring(3).substringBefore(':') else model
        val all = catalogue(context)
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            val id = item.getString("id")
            if (base == id || base.matches(Regex(Regex.escape(id) + "-\\d{4}-\\d{2}-\\d{2}")))
                return item
        }
        return null
    }

    @JvmStatic
    fun effortLabel(effort: String): String =
        when (effort) {
            "none" -> "Sem raciocínio adicional"
            "minimal" -> "Mínimo"
            "low" -> "Baixo"
            "medium" -> "Médio"
            "high" -> "Alto"
            "xhigh" -> "Muito alto"
            "max" -> "Máximo"
            else -> "Padrão do modelo"
        }

    @JvmStatic
    fun supports(context: Context, model: String, effort: String): Boolean {
        if (effort == "default") return true
        val levels = profile(context, model)?.optJSONArray("efforts") ?: return false
        return (0 until levels.length()).any { effort == levels.optString(it) }
    }

    private fun option(context: Context, model: String, label: String): JSONObject {
        val profile = profile(context, model)
        val levels = profile?.optJSONArray("efforts") ?: JSONArray()
        val default =
            if (profile == null) "Padrão do modelo (compatibilidade desconhecida)"
            else if (levels.length() == 0) "Não ajustável neste modelo" else "Padrão do modelo"
        val choices = JSONArray().put(GameJson.obj("value", "default", "label", default))
        for (i in 0 until levels.length()) choices.put(
            GameJson.obj("value", levels.getString(i), "label", effortLabel(levels.getString(i)))
        )
        return GameJson.obj("id", model, "label", label, "reasoning_options", choices)
    }

    @JvmStatic
    fun options(context: Context, savedModel: String): JSONArray {
        val all = catalogue(context)
        val result = JSONArray()
        var found = false
        for (i in 0 until all.length()) {
            val item = all.getJSONObject(i)
            val id = item.getString("id")
            found = found || id == savedModel
            result.put(option(context, id, item.getString("label") + " · " + id))
        }
        if (!found) result.put(option(context, savedModel, "$savedModel (salvo anteriormente)"))
        return result
    }
}
