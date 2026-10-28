package com.automaforge.android

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No implicit retries: a broken connection after POST has an unknown billing outcome. */
class RemoteOutcomeUnknown: IOException("Uma chamada pode ter sido recebida pela OpenAI. Confirme uma nova tentativa, que pode gerar nova cobrança.")
class RemoteHttpFailure(val code: Int): IOException("OpenAI retornou HTTP $code. Confira conexão, modelo, chave e limites; o conteúdo da resposta não foi registrado.")
data class RulebookRemoteResult(val json: String, val requestBytes: Long, val remoteMs: Long,
    val inputTokens: Long?, val outputTokens: Long?, val imageCount: Int)

class OpenAiRulebookGateway(private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .connectTimeout(20,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).callTimeout(150,TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://api.openai.com/v1/responses",
    private val apiKey: ()->String = { ApiKeyStore(context).read() }) {
    fun model(): String = context.getSharedPreferences("private_settings",0).getString("model","gpt-4.1-mini")!!
    fun checkConfiguration() {
        require(apiKey().isNotBlank()) { "Configure a chave OpenAI no controlador." }
        require(model() in setOf("gpt-4.1-mini","gpt-4.1-mini-2025-04-14")) {
            "O pipeline foi validado para gpt-4.1-mini (Responses, imagens e JSON Schema). Selecione esse modelo antes de continuar."
        }
    }
    fun schema(name: String): JSONObject = context.assets.open("schemas/$name.json").bufferedReader().use { JSONObject(it.readText()) }
    fun requestBody(instructions: String, schemaName: String, images: List<Pair<String,File>>, originals: Boolean = false): String {
        val content=JSONArray().put(JSONObject().put("type","input_text").put("text",instructions))
        for((id,file) in images) {
            content.put(JSONObject().put("type","input_text").put("text","Elemento visual: $id"))
            if(originals && id.endsWith(".pdf")) {
                content.put(JSONObject().put("type","input_file").put("filename",id)
                    .put("file_data","data:application/pdf;base64,"+Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)))
                continue
            }
            val mime=when(file.extension) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            content.put(JSONObject().put("type","input_image").put("detail","high")
                .put("image_url","data:$mime;base64,"+Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)))
        }
        return JSONObject().put("model",model()).put("store",false)
            .put("input",JSONArray().put(JSONObject().put("role","user").put("content",content)))
            .put("text",JSONObject().put("format",JSONObject().put("type","json_schema")
                .put("name","automaforge_$schemaName").put("strict",true).put("schema",schema(schemaName))))
            .put("max_output_tokens",16000).toString()
    }
    suspend fun send(body: String, schemaName: String, imageCount: Int, onBytesSent: (Long)->Unit = {}): RulebookRemoteResult {
        val start=System.nanoTime()
        val payload=body.toRequestBody("application/json".toMediaType())
        val counted=object: RequestBody() {
            override fun contentType()=payload.contentType()
            override fun contentLength()=payload.contentLength()
            override fun writeTo(sink: BufferedSink) {
                val counting=object: ForwardingSink(sink) {
                    override fun write(source: Buffer, byteCount: Long) { super.write(source,byteCount);onBytesSent(byteCount) }
                }.buffer()
                payload.writeTo(counting);counting.flush()
            }
        }
        val request=Request.Builder().url(endpoint).header("Authorization","Bearer "+apiKey())
            .post(counted).build()
        val bytes=body.toByteArray(Charsets.UTF_8).size.toLong()
        return suspendCancellableCoroutine { cont ->
            val call=client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call,e: IOException) { if(cont.isActive)cont.resumeWithException(RemoteOutcomeUnknown()) }
                override fun onResponse(call: Call,response: Response) {
                    try { response.use {
                        if(!cont.isActive)return
                        if(!response.isSuccessful)throw RemoteHttpFailure(response.code)
                        val stream=response.body?.byteStream() ?: error("Resposta vazia")
                        val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                        while(out.size()<=8*1024*1024) { val n=stream.read(buffer);if(n<0)break;out.write(buffer,0,n) }
                        val data=out.toByteArray()
                        require(data.size<=8*1024*1024) { "Resposta excedeu o limite seguro; revisão necessária." }
                        val envelope=JSONObject(String(data,Charsets.UTF_8))
                        require(envelope.optString("status")=="completed") { "Resposta incompleta ou recusada; o manual não foi concluído." }
                        val blocks=envelope.getJSONArray("output")
                        val texts=mutableListOf<String>()
                        for(i in 0 until blocks.length()) {
                            val content=blocks.getJSONObject(i).optJSONArray("content") ?: continue
                            for(j in 0 until content.length()) {
                                val block=content.getJSONObject(j)
                                require(block.optString("type")!="refusal") { "A OpenAI recusou esta análise." }
                                if(block.optString("type")=="output_text")texts+=block.getString("text")
                            }
                        }
                        val value=JSONObject(texts.joinToString(""))
                        RuleJsonValidator.validate(value,schema(schemaName))
                        val usage=envelope.optJSONObject("usage")
                        val input=usage?.takeIf { it.has("input_tokens") }?.getLong("input_tokens")
                        val output=usage?.takeIf { it.has("output_tokens") }?.getLong("output_tokens")
                        if(cont.isActive)cont.resume(RulebookRemoteResult(value.toString(),bytes,(System.nanoTime()-start)/1_000_000,input,output,imageCount))
                    } } catch(t: Exception) { if(cont.isActive)cont.resumeWithException(t) }
                }
            })
        }
    }
}

/** Runtime validation as well as strict output on the server. Supports the subset used in our schemas. */
object RuleJsonValidator {
    fun validate(value: Any, schema: JSONObject) {
        val rawType=schema.get("type")
        if(rawType is JSONArray) {
            require((0 until rawType.length()).any { index ->
                runCatching { validate(value,JSONObject(schema.toString()).put("type",rawType.getString(index))) }.isSuccess
            }) { "Tipo fora da união permitida" }
            return
        }
        schema.optJSONArray("enum")?.let { choices ->
            require((0 until choices.length()).any { choices.get(it)==value }) { "Valor fora das escolhas permitidas" }
        }
        when(schema.getString("type")) {
            "object" -> {
                require(value is JSONObject)
                val properties=schema.getJSONObject("properties"); val required=schema.getJSONArray("required")
                for(i in 0 until required.length())require(value.has(required.getString(i)))
                val keys=value.keys()
                while(keys.hasNext()) { val k=keys.next();require(properties.has(k));validate(value.get(k),properties.getJSONObject(k)) }
            }
            "array" -> { require(value is JSONArray);for(i in 0 until value.length())validate(value.get(i),schema.getJSONObject("items")) }
            "string" -> require(value is String)
            "integer" -> require(value is Number && value.toDouble()==value.toLong().toDouble())
            "boolean" -> require(value is Boolean)
            "number" -> require(value is Number && value.toDouble().isFinite())
            else -> error("Tipo de esquema não suportado")
        }
    }
}
