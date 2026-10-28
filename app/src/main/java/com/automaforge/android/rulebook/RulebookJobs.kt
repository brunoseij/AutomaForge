package com.automaforge.android

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer

/** Independent supervised jobs; no GameStore lock is held during PDF/OCR/network work. */
class RulebookJobs(context: Context, private val onDraft: BiConsumer<String,JSONObject>) {
    private val app=context.applicationContext
    private val cache=RulebookCacheRepository(File(app.filesDir,"rulebooks"))
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val slots=Semaphore(1) // one large bitmap/PDF at a time across all rooms
    private val jobs=ConcurrentHashMap<String,Job>()
    private val flows=ConcurrentHashMap<String,MutableStateFlow<RulebookProgress>>()
    private fun stateFile(code: String)=File(app.filesDir,"rulebook-jobs/$code.json")
    fun state(code: String): StateFlow<RulebookProgress> = flows.computeIfAbsent(code) {
        val old=cache.read(stateFile(code),RulebookProgress::class.java) ?: RulebookProgress()
        MutableStateFlow(if(old.state=="running")old.copy(state="paused",message="Processo interrompido. Retome para reutilizar checkpoints.") else old)
    }
    private fun publish(code: String, value: RulebookProgress) {
        val flow=state(code) as MutableStateFlow<RulebookProgress>
        // Serialize and copy so readers never see a mutable metrics object while it is being updated.
        val copy=cache.gson.fromJson(cache.gson.toJson(value),RulebookProgress::class.java)
        cache.write(stateFile(code),copy);flow.value=copy
        android.util.Log.i("AutomaForgePdf", "state=${copy.state} page=${copy.page}/${copy.total} native=${copy.metrics.nativePages} ocr=${copy.metrics.ocrPages} calls=${copy.metrics.calls} bytes=${copy.metrics.sentRequestBytes}")
    }
    fun status(code: String): JSONObject = JSONObject(cache.gson.toJson(state(code).value)).also { json ->
        json.getJSONObject("metrics").put("byteDifference",state(code).value.metrics.byteDifference)
    }
    @Synchronized fun start(code: String, name: String, files: List<File>, retryUnknown: Boolean, fallback: Boolean, rulesRevision: Int): JSONObject {
        require(files.isNotEmpty()) { "Envie o PDF ou fotografias do manual." }
        if(jobs[code]?.isCompleted==false)return status(code)
        val metrics=RulebookMetrics()
        publish(code,RulebookProgress(state="running",stage="Na fila",metrics=metrics))
        val job=scope.launch(start=CoroutineStart.LAZY) {
            fun update(stage: String, page: Int=0,total: Int=0,ocr: String="Aguardando",visual: String="Aguardando") {
                publish(code,RulebookProgress("running",stage,if(total==0)metrics.pages else page,if(total==0)metrics.pages else total,if(total==0)"Concluído" else ocr,if(total==0)"Catalogado" else visual,metrics=metrics))
            }
            try { slots.withPermit {
                val pipeline=RulebookPipeline(app,cache)
                val interpreter=RulebookInterpreter(cache,pipeline,OpenAiRulebookGateway(app))
                val draft=if(fallback)interpreter.originalFallback(name,files,metrics,retryUnknown) { update(it) }
                else {
                    val documents=files.map { file ->
                        val doc=pipeline.process(file,metrics) { s,p,t,o,v -> update(s,p,t,o,v) }
                        doc to file.name.endsWith(".pdf",true)
                    }
                    interpreter.interpret(name,documents,metrics,retryUnknown) { update(it) }
                }
                ensureActive()
                draft.put("base_rules_revision",rulesRevision)
                onDraft.accept(code,draft)
                publish(code,RulebookProgress("needs_review","Revisão humana necessária",metrics=metrics,draft=draft.toString(),
                    message=if(draft.optBoolean("processing_complete"))"Rascunho disponível. Revise condições, exceções e fontes antes de salvar." else "Interpretação parcial ou com lacunas. Consulte as fontes e corrija antes de confirmar."))
            } } catch(e: CancellationException) {
                publish(code,RulebookProgress("paused","Cancelado",metrics=metrics,message="Checkpoints preservados. Uma chamada interrompida pode precisar de confirmação antes de ser repetida."))
                throw e
            } catch(e: Exception) {
                // Never log document text, response bodies, keys or exception objects containing requests.
                val message=when(e) {
                    is RemoteOutcomeUnknown -> e.message!!
                    is RemoteHttpFailure -> e.message!!
                    is IllegalArgumentException, is IllegalStateException -> e.message ?: "Falha de validação. Revise o original."
                    else -> "Falha no processamento local ou remoto (${e.javaClass.simpleName}). Checkpoints preservados; retome ou utilize o fallback."
                }
                publish(code,RulebookProgress("error","Análise interrompida",metrics=metrics,message=message))
            }
        }
        jobs[code]=job;job.start();return status(code)
    }
    @Synchronized fun cancel(code: String): JSONObject { jobs[code]?.cancel();return status(code) }
    fun saveValidated(files: List<File>, rules: JSONObject) {
        for(file in files)cache.validated(cache.hash(file),rules.toString())
    }
    @Synchronized fun delete(code: String, files: List<File>) {
        check(jobs[code]?.isCompleted!=false) { "Cancele e aguarde a análise terminar antes de apagar." }
        for(file in files)cache.delete(cache.hash(file))
        // Remote results may combine several manuals; invalidate them together. Human corrections of other manuals stay intact.
        File(app.filesDir,"rulebooks/responses").deleteRecursively()
        stateFile(code).delete();flows.remove(code)
    }
    fun close() { scope.cancel() }
}
