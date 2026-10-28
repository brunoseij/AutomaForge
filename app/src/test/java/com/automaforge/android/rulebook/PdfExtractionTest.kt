package com.automaforge.android

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PdfExtractionTest {
    @Before fun init() { PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication()) }
    private fun load(name: String)=PDDocument.load(javaClass.getResourceAsStream("/manuals/$name.pdf"))
    @Test fun nativeTextIntegrity() { load("native").use { doc ->
        val blocks=PdfTextExtractor().extract(doc,1)
        val text=RulebookText.readingOrder(blocks).joinToString("\n") { it.text }
        listOf("3 spaces unless blocked","defender wins","page 2","2 energy","12 points","0").forEach { assertTrue(text,text.contains(it)) }
        assertFalse(RulebookText.needsOcr(blocks))
    } }
    @Test fun scannedPageNeedsOcrAndKeepsImage() { load("scanned").use { doc ->
        assertTrue(RulebookText.needsOcr(PdfTextExtractor().extract(doc,1)))
        assertTrue(PdfVisualAnalyzer().analyze(doc.getPage(0),1).any { it.type=="embedded_image" })
    } }
    @Test fun hybridKeepsNativeAndRaster() { load("hybrid").use { doc ->
        assertFalse(RulebookText.needsOcr(PdfTextExtractor().extract(doc,1)))
        assertTrue(PdfVisualAnalyzer().analyze(doc.getPage(0),1).size>=2)
    } }
    @Test fun multiColumnOrder() { load("columns").use { doc ->
        val text=RulebookText.readingOrder(PdfTextExtractor().extract(doc,1)).joinToString("\n") { it.text }
        assertTrue(text,text.indexOf("LEFT 3")<text.indexOf("RIGHT 1"))
    } }
    @Test fun tableValuesPreserved() { load("tables").use { doc ->
        val text=PdfTextExtractor().extract(doc,1).joinToString("\n") { it.text }
        listOf("Action","Cost","Range","Move","2","3","Attack","4","1").forEach { assertTrue(text,text.contains(it)) }
        val blocks=PdfTextExtractor().extract(doc,1)
        val rows=RulebookText.literalContent(RulebookText.readingOrder(blocks))
        assertTrue(rows,rows.contains("Move\t2\t3"))
        assertEquals("page_reference",PdfVisualAnalyzer().analyze(doc.getPage(0),1).last().type)
    } }
    @Test fun vectorDiagramsAndSymbolsRemainAvailable() { for(name in listOf("diagrams","symbols"))load(name).use { doc ->
        val catalog=PdfVisualAnalyzer().analyze(doc.getPage(0),1)
        assertTrue(catalog.any { it.bounds==RuleRect.PAGE })
    } }
    @Test fun blankPageNotReportedAsCompleteText() { load("blank").use { doc -> assertTrue(RulebookText.needsOcr(PdfTextExtractor().extract(doc,1))) } }
    @Test fun largeManualPageByPage() { load("large").use { doc ->
        assertEquals(120,doc.numberOfPages)
        for(i in 1..doc.numberOfPages)assertTrue(PdfTextExtractor().extract(doc,i).any { it.text.contains("unless blocked") })
    } }
    @Test fun measureSyntheticNativeFixtures() {
        val output=org.json.JSONArray()
        for(name in listOf("native","columns","tables","diagrams","symbols","large")) {
            val original=javaClass.getResourceAsStream("/manuals/$name.pdf")!!.use { it.readBytes() }
            val started=System.nanoTime()
            load(name).use { doc ->
                var textBytes=0
                for(page in 1..doc.numberOfPages) {
                    val blocks=PdfTextExtractor().extract(doc,page)
                    textBytes+=RulebookText.literalContent(RulebookText.readingOrder(blocks)).toByteArray(Charsets.UTF_8).size
                }
                output.put(org.json.JSONObject().put("fixture",name).put("pages",doc.numberOfPages)
                    .put("original_pdf_bytes",original.size).put("literal_native_text_bytes",textBytes)
                    .put("local_extraction_ms",(System.nanoTime()-started)/1_000_000)
                    .put("scope","native text only; excludes JSON/requests, visual inputs and billed tokens"))
            }
        }
        val file=java.io.File("build/reports/rulebook/local-extraction.json")
        file.parentFile!!.mkdirs();file.writeText(output.toString(2))
    }
    @Test(expected=java.io.IOException::class) fun corruptedRejected() { load("corrupt").close() }
}
