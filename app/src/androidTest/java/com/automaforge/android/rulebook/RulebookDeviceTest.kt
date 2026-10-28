package com.automaforge.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Real PdfRenderer and bundled ML Kit. Runs offline; no API key or Google model download required. */
@RunWith(AndroidJUnit4::class)
class RulebookDeviceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var cache: RulebookCacheRepository
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        root=File(context.cacheDir,"device-tests-${System.nanoTime()}").apply { mkdirs() }
        cache=RulebookCacheRepository(File(root,"cache"))
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun fixture(name: String): File {
        val testContext=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        return File(root,"$name.pdf").also { f -> testContext.assets.open("manuals/$name.pdf").use { i -> f.outputStream().use { i.copyTo(it) } } }
    }
    private suspend fun process(name: String)=withContext(Dispatchers.IO) {
        RulebookPipeline(context,cache).process(fixture(name),RulebookMetrics()) { _,_,_,_,_ -> }
    }
    @Test fun lanServerRemainsResponsiveDuringManualProcessing() {
        val server=LanServer(context)
        val client=okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        fun call(path: String, body: okhttp3.RequestBody? = null): org.json.JSONObject {
            val builder=okhttp3.Request.Builder().url("http://127.0.0.1:8765$path")
            if(body!=null)builder.post(body)
            client.newCall(builder.build()).execute().use { response ->
                assertEquals(200,response.code)
                return org.json.JSONObject(response.body!!.string())
            }
        }
        try {
            server.start()
            val jsonType="application/json".toMediaType()
            val pdfType="application/pdf".toMediaType()
            val create="{\"game_name\":\"Offline fixture\"}".toRequestBody(jsonType)
            val code=call("/api/rooms",create).getString("code")
            call("/api/rooms/$code/rules/pages",fixture("scanned").readBytes().toRequestBody(pdfType))
            call("/api/rooms/$code/rules/interpret","{}".toRequestBody(jsonType))
            repeat(3) { assertEquals("ok",call("/api/health").getString("status"));assertEquals(code,call("/api/rooms/$code").getString("code")) }
            call("/api/rooms/$code/rules/cancel","{}".toRequestBody(jsonType))
        } finally { server.stop() }
    }
    @Test fun apiKeyIsEncryptedAndLegacyPreferenceMigrated() {
        val keys=ApiKeyStore(context);keys.clear()
        try {
            val prefs=context.getSharedPreferences("private_settings",0)
            prefs.edit().putString("api_key","test-sentinel-not-a-real-key").commit()
            assertEquals("test-sentinel-not-a-real-key",keys.read())
            assertFalse(prefs.contains("api_key"))
            assertFalse(prefs.getString("api_key_encrypted","")!!.contains("test-sentinel"))
        } finally { keys.clear() }
    }
    @Test fun nativeAndCache() = runBlocking {
        val file=fixture("native");val pipeline=RulebookPipeline(context,cache)
        val first=withContext(Dispatchers.IO) { pipeline.process(file,RulebookMetrics()) { _,_,_,_,_ -> } }
        assertEquals(2,first.pages.size);assertTrue(first.pages.all { it.nativeUsed && !it.ocrUsed })
        assertTrue(first.pages.flatMap { it.sections }.any { it.content.contains("3 spaces unless blocked") })
        val metrics=RulebookMetrics()
        withContext(Dispatchers.IO) { pipeline.process(file,metrics) { _,_,_,_,_ -> } }
        assertTrue(metrics.cacheHits>0);assertEquals(0,metrics.calls)
    }
    @Test fun scannedRunsBundledOcrLocally() = runBlocking {
        val doc=process("scanned");assertTrue(doc.pages.single().ocrUsed)
        val text=doc.pages.single().blocks.joinToString(" ") { it.text }
        assertTrue(text,text.contains("3"));assertTrue(text,text.contains("blocked",true))
        assertTrue(doc.pages.single().issues.contains("ocr_requires_visual_verification"))
    }
    @Test fun hybridKeepsNativeAndOcr() = runBlocking {
        val page=process("hybrid").pages.single()
        assertTrue(page.nativeUsed);assertTrue(page.ocrUsed)
        assertTrue(page.blocks.any { it.source=="native_pdf" });assertTrue(page.blocks.any { it.source=="local_ocr" })
    }
    @Test fun blanksAreFlagged() = runBlocking {
        assertTrue(process("blank").pages.single().issues.contains("text_absent_or_insufficient"))
    }
    @Test fun diagramsAndIconsProduceRecoverableCrops() = runBlocking {
        for(name in listOf("diagrams","symbols")) {
            val doc=process(name);val visual=doc.pages.single().visuals.last()
            val (optimized,file)=withContext(Dispatchers.IO) { RulebookPipeline(context,cache).visual(doc,visual,true) }
            assertTrue(file.length()>0);assertTrue(optimized.originalBytes>0)
            assertTrue(cache.original(doc.documentId).exists())
        }
    }
    @Test fun interruptedManualResumesCompletedPages() = runBlocking {
        val file=fixture("large");val pipeline=RulebookPipeline(context,cache)
        try { withContext(Dispatchers.IO) { pipeline.process(file,RulebookMetrics()) { _,page,_,_,_ -> if(page==4)throw CancellationException("test interruption") } };fail() }
        catch(_: CancellationException) {}
        val hash=cache.hash(file);assertTrue(cache.page(hash,1).exists());assertFalse(cache.document(hash).exists())
        val metrics=RulebookMetrics()
        val completed=withContext(Dispatchers.IO) { pipeline.process(file,metrics) { _,_,_,_,_ -> } }
        assertEquals(120,completed.pages.size);assertTrue(metrics.cacheHits>=3)
    }
}
