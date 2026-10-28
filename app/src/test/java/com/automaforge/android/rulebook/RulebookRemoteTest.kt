package com.automaforge.android

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RulebookRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var cache: RulebookCacheRepository
    private lateinit var gateway: OpenAiRulebookGateway
    private val id="a".repeat(64)
    private val source="$id:p1_s0"
    @Before fun setup() {
        server=MockWebServer();server.start()
        directory=java.nio.file.Files.createTempDirectory("rulebook-test").toFile();cache=RulebookCacheRepository(directory)
        gateway=OpenAiRulebookGateway(RuntimeEnvironment.getApplication(),OkHttpClient.Builder().retryOnConnectionFailure(false)
            .readTimeout(2,TimeUnit.SECONDS).build(),server.url("/v1/responses").toString()) { "test-key-only" }
    }
    @After fun close() { server.shutdown();directory.deleteRecursively() }
    private fun response(value: JSONObject): MockResponse = MockResponse().setBody(JSONObject()
        .put("status","completed").put("output",JSONArray().put(JSONObject().put("content",JSONArray().put(JSONObject().put("type","output_text").put("text",value.toString())))))
        .put("usage",JSONObject().put("input_tokens",40).put("output_tokens",15)).toString())
    private fun analysis(visuals: List<String> = emptyList(), pages: List<Int> = emptyList()) = JSONObject()
        .put("facts",JSONArray().put(JSONObject().put("text","Move 3 spaces unless blocked; ties favor defender.").put("sources",JSONArray().put(source))))
        .put("requested_visual_ids",JSONArray(visuals)).put("requested_page_numbers",JSONArray(pages)).put("uncertainties",JSONArray())
    private fun finalRules()=JSONObject().put("game_name","Synthetic").put("rules_text","Move 3 spaces unless blocked; ties favor defender.")
        .put("setup","Spend 2 energy.").put("turn_flow","Alternate.").put("actions",JSONArray()).put("uncertainties",JSONArray())
        .put("evidence",analysis().getJSONArray("facts")).put("unresolved",JSONArray()).put("covered_sources",JSONArray().put(source).put("$id:p1"))
    private fun doc(): RulebookDocument {
        val block=RuleTextBlock("n1","Move 3 spaces unless blocked; ties favor defender. Spend 2 energy.",RuleRect.PAGE,"native_pdf")
        val v=RuleVisual("p1_page",1,RuleRect.PAGE,"page_reference")
        val section=RuleSection("p1_s0",null,block.text,listOf(block.id),listOf(block.source),listOf(v.id),emptyList())
        return RulebookDocument(id,originalBytes=500000,title="Synthetic",pages=listOf(RulePage(1,600f,800f,listOf(block),listOf(v),listOf(section),emptyList(),false,true)))
    }
    private fun interpreter(): RulebookInterpreter {
        val fresh=OpenAiRulebookGateway(RuntimeEnvironment.getApplication(),OkHttpClient.Builder().retryOnConnectionFailure(false)
            .readTimeout(10,TimeUnit.SECONDS).build(),server.url("/v1/responses").toString()) { "test-key-only" }
        return RulebookInterpreter(cache,RulebookPipeline(RuntimeEnvironment.getApplication(),cache),fresh)
    }
    @Test fun textualPdfIsNotUploadedAndResponsesReused() = runBlocking {
        server.enqueue(response(analysis()));server.enqueue(response(finalRules()))
        val metrics=RulebookMetrics()
        val result=interpreter().interpret("Synthetic",listOf(doc() to true),metrics,false) {}
        assertTrue(result.getBoolean("processing_complete"));assertTrue(result.getBoolean("requires_human_validation"))
        assertEquals(2,metrics.calls);assertEquals(80L,metrics.inputTokens)
        val textRequest=server.takeRequest().body.readUtf8()
        val consolidation=server.takeRequest().body.readUtf8()
        assertFalse(textRequest.contains("input_file"))
        assertEquals((textRequest.toByteArray(Charsets.UTF_8).size+consolidation.toByteArray(Charsets.UTF_8).size).toLong(),metrics.sentRequestBytes)
        assertEquals(metrics.attemptedRequestBytes,metrics.sentRequestBytes)
        val reused=RulebookMetrics();interpreter().interpret("Synthetic",listOf(doc() to true),reused,false) {}
        assertEquals(0,reused.calls);assertEquals(2,reused.cacheHits);assertEquals(2,server.requestCount)
    }
    @Test fun requestedImageOnlyIsRecovered() = runBlocking {
        // Existing lossless masters simulate completed render checkpoints; test transport and retrieval without native rendering.
        val dir=File(cache.versionDir(id),"visuals").apply { mkdirs() }
        File(dir,"p1_page-original.png").writeBytes(byteArrayOf(1,2,3))
        File(dir,"p1_page-send.png").writeBytes(byteArrayOf(1,2,3))
        server.enqueue(response(analysis(listOf("$id:p1_page"))))
        server.enqueue(response(analysis()))
        server.enqueue(response(finalRules()))
        val metrics=RulebookMetrics();interpreter().interpret("Synthetic",listOf(doc() to true),metrics,false) {}
        val first=server.takeRequest().body.readUtf8();val visual=server.takeRequest().body.readUtf8()
        assertFalse(first.contains("input_image"))
        val blocks=JSONObject(visual).getJSONArray("input").getJSONObject(0).getJSONArray("content")
        val images=(0 until blocks.length()).map { blocks.getJSONObject(it) }.filter { it.optString("type")=="input_image" }
        assertEquals(1,images.size);assertTrue(images.single().getString("image_url").startsWith("data:image/png;base64,"))
        assertEquals(1,metrics.imagesTransmitted);assertEquals(3,metrics.calls)
    }
    @Test fun networkFailureDoesNotSilentlyRepeatUnknownRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { interpreter().interpret("Synthetic",listOf(doc() to true),RulebookMetrics(),false) {};fail() } catch(_: RemoteOutcomeUnknown) {}
        val count=server.requestCount
        try { interpreter().interpret("Synthetic",listOf(doc() to true),RulebookMetrics(),false) {};fail() } catch(_: RemoteOutcomeUnknown) {}
        assertEquals(count,server.requestCount)
    }
    @Test fun complementaryPageRequestedByNumber() = runBlocking {
        val dir=File(cache.versionDir(id),"visuals").apply { mkdirs() }
        File(dir,"p1_page-original.png").writeBytes(byteArrayOf(1,2,3));File(dir,"p1_page-send.png").writeBytes(byteArrayOf(1,2,3))
        server.enqueue(response(analysis(pages=listOf(1))));server.enqueue(response(analysis()));server.enqueue(response(finalRules()))
        val result=interpreter().interpret("Synthetic",listOf(doc() to true),RulebookMetrics(),false) {}
        assertEquals("$id:p1_page",result.getJSONArray("visuals_examined").getString(0))
    }
    @Test fun explicitRetryCanResumeUnknownCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { interpreter().interpret("Synthetic",listOf(doc() to true),RulebookMetrics(),false) {};fail() } catch(_: RemoteOutcomeUnknown) {}
        server.enqueue(response(analysis()));server.enqueue(response(finalRules()))
        val result=interpreter().interpret("Synthetic",listOf(doc() to true),RulebookMetrics(),true) {}
        assertTrue(result.getBoolean("requires_human_validation"));assertEquals(3,server.requestCount)
    }
    @Test fun cancellationClosesRemoteCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job=launch(Dispatchers.IO) { gateway.send(gateway.requestBody("test","rulebook_analysis",emptyList()),"rulebook_analysis",0) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3,TimeUnit.SECONDS)) }
        job.cancelAndJoin();assertTrue(job.isCancelled)
    }
    @Test fun invalidStructuredOutputRejected() = runBlocking {
        server.enqueue(response(JSONObject().put("facts","invalid")))
        try { gateway.send(gateway.requestBody("test","rulebook_analysis",emptyList()),"rulebook_analysis",0);fail() }
        catch(_: IllegalArgumentException) {}
    }
    @Test fun atomicBackupIsRecoveredAndDamagedRemoteCacheNeedsConfirmation() {
        val page=cache.page(id,1);cache.write(page,doc().pages.single())
        assertTrue(page.renameTo(File(page.path+".bak")))
        assertNotNull(cache.read(page,RulePage::class.java))
        val key="b".repeat(64);val response=cache.response(key)
        response.parentFile!!.mkdirs();response.writeText("{broken")
        assertNull(cache.read(response,RulebookRemoteResult::class.java))
        assertTrue(cache.pending(key).exists())
    }
    @Test fun cacheHashAndValidatedCorrectionsSurviveVersionedCacheDeletion() {
        val file=File(directory,"source.pdf").apply { writeText("%PDF-deterministic") }
        val hash=cache.hash(file);assertEquals(64,hash.length);cache.preserve(file,hash)
        cache.validated(hash,"User correction: spend 3, not 2.")
        cache.write(cache.page(hash,1),doc().pages.single())
        assertNotNull(cache.read(cache.page(hash,1),RulePage::class.java))
        cache.versionDir(hash).deleteRecursively()
        assertTrue(File(cache.directory(hash),"human-validated.json").readText().contains("spend 3"))
        file.appendText("changed");assertNotEquals(hash,cache.hash(file))
        cache.delete(hash);assertFalse(File(directory,hash).exists())
    }
}
