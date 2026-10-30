package com.automaforge.android

import com.automaforge.android.core.*
import com.automaforge.android.data.ai.*
import com.automaforge.android.data.rulebook.*

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PdfManualPreparerTest {
    private lateinit var dir: File
    private lateinit var cache: RulebookCacheRepository
    @Before fun setup(){PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication());dir=kotlin.io.path.createTempDirectory("manual-pdf").toFile();cache=RulebookCacheRepository(File(dir,"cache"))}
    @After fun cleanup(){dir.deleteRecursively()}
    private fun fixture(name: String): File=File(dir,name).also { file -> javaClass.getResourceAsStream("/manuals/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } } }
    private fun record(name: String, original: Long, prepared: Long, packetBytes: Long, metrics: RulebookMetrics) {
        val report=File("build/reports/manual-pdf/optimization.json");report.parentFile.mkdirs()
        val data=if(report.exists())org.json.JSONObject(report.readText()) else org.json.JSONObject()
        data.put(name,org.json.JSONObject().put("original_bytes",original).put("selected_pdf_bytes",prepared).put("packet_bytes",packetBytes).put("ocr_pages",metrics.ocrPages).put("images_recompressed",metrics.imagesRecompressed).put("synthetic",true))
        report.writeText(data.toString(2))
    }
    private fun text(f: File)=PDDocument.load(f).use { PDFTextStripper().getText(it) }
    @Test fun losslessDefaultKeepsAllNativeTextAndPagesWithoutOcr()=runBlocking {
        val input=fixture("native.pdf");val metrics=RulebookMetrics(processingMode="pdf_lossless")
        val packets=PdfManualPreparer(RuntimeEnvironment.getApplication(),cache).prepare(listOf(input),"pdf_lossless",metrics){}
        assertEquals(1,packets.size);assertEquals(text(input),text(packets.single().file));assertEquals(0,metrics.ocrPages)
        assertEquals(2,metrics.pages);assertEquals(input.length(),metrics.originalPdfBytes);assertTrue(metrics.preparedPdfBytes<=input.length());assertTrue(cache.original(cache.hash(input)).isFile)
        record("native.pdf",input.length(),metrics.preparedPdfBytes,metrics.pdfPacketBytes,metrics)
    }
    @Test fun longPdfIsSplitWithEveryPageMappedInOrder()=runBlocking {
        val input=fixture("large.pdf");val metrics=RulebookMetrics()
        val packets=PdfManualPreparer(RuntimeEnvironment.getApplication(),cache).prepare(listOf(input),"original",metrics){}
        assertEquals((1..120).toList(),packets.flatMap { (it.firstPage..it.lastPage).toList() });assertTrue(packets.all { it.lastPage-it.firstPage+1<=12 })
        assertEquals(text(input),packets.joinToString("") { text(it.file) });assertEquals(0,metrics.ocrPages)
        record("large.pdf",input.length(),metrics.preparedPdfBytes,metrics.pdfPacketBytes,metrics)
    }
    @Test fun redundantRgbImageCanShrinkWithoutRemovingNativeTextOrImage()=runBlocking {
        val original=File(dir,"redundant.pdf")
        PDDocument().use { document ->
            val page=com.tom_roush.pdfbox.pdmodel.PDPage();document.addPage(page)
            val raw=ByteArray(240*240*3) { if(it%3==0)30 else if(it%3==1)90 else 150.toByte() }
            val image=com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject(document,java.io.ByteArrayInputStream(raw),null,240,240,8,com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document,page).use { stream ->
                stream.drawImage(image,20f,20f,300f,300f);stream.beginText();stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,12f);stream.newLineAtOffset(20f,400f);stream.showText("Keep all diagrams and 3 exact tokens.");stream.endText()
            }
            document.save(original)
        }
        val metrics=RulebookMetrics();val output=PdfManualPreparer(RuntimeEnvironment.getApplication(),cache).optimize(original,File(dir,"small.pdf"),false,metrics)
        assertTrue(output.length()<original.length());assertEquals(text(original),text(output));assertTrue(metrics.imagesRecompressed>0)
        record("redundant-rgb.pdf",original.length(),output.length(),output.length(),metrics)
        PDDocument.load(output).use { doc -> assertTrue(doc.getPage(0).resources.xObjectNames.any { doc.getPage(0).resources.isImageXObject(it) }) }
    }
    @Test fun scannedPdfAndDiagramsRemainPdfNotOcrText()=runBlocking {
        for(name in listOf("scanned.pdf","diagrams.pdf","symbols.pdf","hybrid.pdf")) {
            val input=fixture(name);val metrics=RulebookMetrics();val packets=PdfManualPreparer(RuntimeEnvironment.getApplication(),cache).prepare(listOf(input),"pdf_lossless",metrics){}
            PDDocument.load(packets.single().file).use { output -> PDDocument.load(input).use { original ->
                assertEquals(original.numberOfPages,output.numberOfPages);assertEquals(PDFTextStripper().getText(original),PDFTextStripper().getText(output))
                for(i in 0 until output.numberOfPages)assertNotNull(output.getPage(i).contents)
            } }
            assertEquals(0,metrics.ocrPages)
            record(name,input.length(),metrics.preparedPdfBytes,metrics.pdfPacketBytes,metrics)
        }
    }
}
