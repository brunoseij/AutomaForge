package com.automaforge.android

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

class RulebookPipeline(private val context: Context, private val cache: RulebookCacheRepository) {
    private val extractor=PdfTextExtractor()
    private val analyzer=PdfVisualAnalyzer()
    private val optimizer=PdfImageOptimizer()
    private val preprocessor=RulebookPreprocessor()
    suspend fun process(file: File, metrics: RulebookMetrics, progress: (String,Int,Int,String,String)->Unit): RulebookDocument {
        val start=System.nanoTime(); val id=cache.hash(file)
        cache.preserve(file,id)
        val saved=cache.read(cache.document(id),RulebookDocument::class.java)
        if(saved!=null && saved.pipelineVersion==PIPELINE_VERSION && saved.schemaVersion==RULE_SCHEMA_VERSION && saved.pages.all { cache.page(id,it.number).exists() }) {
            metrics.cacheHits++;account(saved,metrics);return saved
        }
        metrics.cacheMisses++
        PDFBoxResourceLoader.init(context)
        val pages=mutableListOf<RulePage>()
        val pdf=file.inputStream().use { val magic=ByteArray(5); it.read(magic)==5 && String(magic,Charsets.US_ASCII)=="%PDF-" }
        PdfOcrProcessor().use { ocr ->
            if(pdf) {
                val memory=MemoryUsageSetting.setupTempFileOnly().setTempDir(cache.versionDir(id))
                PDDocument.load(cache.original(id),memory).use { doc ->
                    require(!doc.isEncrypted) { "PDF protegido. Importe uma cópia sem senha ou utilize o fluxo original." }
                    require(doc.numberOfPages>0) { "PDF sem páginas" }
                    for(number in 1..doc.numberOfPages) {
                        currentCoroutineContext().ensureActive()
                        cache.read(cache.page(id,number),RulePage::class.java)?.let { pages+=it;metrics.cacheHits++;progress("Página recuperada do cache",number,doc.numberOfPages,"Cache","Cache");return@let }
                            ?: run {
                                val page=doc.getPage(number-1); val issues=mutableListOf<String>()
                                progress("Extração de texto",number,doc.numberOfPages,"Aguardando","Catalogando")
                                val native=try { extractor.extract(doc,number) } catch(e: Exception) { issues+="native_extraction_failed";emptyList() }
                                val visuals=try { analyzer.analyze(page,number) } catch(e: Exception) {
                                    issues+="visual_catalog_incomplete";listOf(RuleVisual("p${number}_page",number,RuleRect.PAGE,"page_reference"))
                                }
                                var blocks=native; var used=false
                                val fullOcr=RulebookText.needsOcr(native) || page.rotation!=0
                                val regions=if(fullOcr)listOf(RuleRect.PAGE) else visuals.filter { it.type=="embedded_image" && it.bounds.area>.00005f }.map { it.bounds }.distinct()
                                // One bitmap/task at a time. Even small icons remain in the catalog when not OCR'd.
                                for(region in regions) {
                                    currentCoroutineContext().ensureActive()
                                    progress("OCR seletivo",number,doc.numberOfPages,"Processando região","Disponível")
                                    used=true
                                    try {
                                        val bitmap=optimizer.render(cache.original(id),number,region,2200)
                                        blocks=RulebookText.merge(blocks,ocr.recognize(bitmap,number,region))
                                    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch(e: Exception) { issues+="ocr_failed" }
                                }
                                if(used)issues+="ocr_requires_visual_verification"
                                if(RulebookText.uncertainLayout(blocks))issues+="layout_requires_visual_verification"
                                if(RulebookText.needsOcr(blocks))issues+="text_absent_or_insufficient"
                                if(page.rotation!=0)issues+="rotated_page_reading_order_requires_review"
                                if(blocks.any { it.text.contains('\uFFFD') })issues+="unmapped_symbols"
                                val w=if(page.rotation%180==0)page.cropBox.width else page.cropBox.height
                                val h=if(page.rotation%180==0)page.cropBox.height else page.cropBox.width
                                val result=RulePage(number,w,h,blocks,visuals,preprocessor.sections(number,blocks,visuals),issues.distinct(),used,native.isNotEmpty())
                                cache.write(cache.page(id,number),result);pages+=result
                                progress("Página processada",number,doc.numberOfPages,if(used)"Concluído" else "Não necessário","Catalogado")
                            }
                    }
                }
            } else {
                currentCoroutineContext().ensureActive()
                progress("OCR da fotografia",1,1,"Processando","Disponível")
                val bitmap=optimizer.photo(cache.original(id),2400); val w=bitmap.width.toFloat(); val h=bitmap.height.toFloat()
                val blocks=ocr.recognize(bitmap,1,RuleRect.PAGE)
                val visuals=listOf(RuleVisual("p1_page",1,RuleRect.PAGE,"page_reference"))
                val page=RulePage(1,w,h,blocks,visuals,preprocessor.sections(1,blocks,visuals),listOf("ocr_requires_visual_verification"),true,false)
                pages+=page;cache.write(cache.page(id,1),page)
            }
        }
        val result=RulebookDocument(id,originalBytes=file.length(),title=file.name,pages=preprocessor.markRepeatedMargins(pages))
        cache.write(cache.document(id),result); metrics.localMs+=(System.nanoTime()-start)/1_000_000
        account(result,metrics); return result
    }
    private fun account(document: RulebookDocument, metrics: RulebookMetrics) {
        metrics.pages+=document.pages.size;metrics.nativePages+=document.pages.count { it.nativeUsed }
        metrics.ocrPages+=document.pages.count { it.ocrUsed };metrics.visualsIdentified+=document.pages.sumOf { it.visuals.size }
        metrics.originalPdfBytes+=document.originalBytes
    }
    fun visual(document: RulebookDocument, visual: RuleVisual, pdf: Boolean): Pair<RuleVisual,File> {
        val dir=File(cache.versionDir(document.documentId),"visuals")
        val optimized=optimizer.optimize(cache.original(document.documentId),visual,dir,pdf)
        cache.write(File(dir,"${visual.id}.json"),optimized)
        return optimized to File(dir,optimized.localPath!!)
    }
}
