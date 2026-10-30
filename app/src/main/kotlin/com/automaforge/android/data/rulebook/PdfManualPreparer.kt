package com.automaforge.android.data.rulebook

import android.content.Context
import android.graphics.Bitmap
import com.automaforge.android.core.*
import com.automaforge.android.data.ai.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ManualPacket(
    val documentId: String,
    val firstPage: Int,
    val lastPage: Int,
    val file: File,
    val pdf: Boolean,
) {
    fun sources() = (firstPage..lastPage).map { "$documentId:p$it" }

    fun label() =
        if (pdf) "$documentId-pages-$firstPage-$lastPage.pdf" else "$documentId-page-$firstPage.jpg"
}

/** Keeps complete PDF pages (native text, vectors and images); OCR is not the transport. */
class PdfManualPreparer(private val context: Context, private val cache: RulebookCacheRepository) {
    companion object {
        const val MAX_PACKET_BYTES = 12L * 1024 * 1024
        const val MAX_PAGES = 12
    }

    suspend fun prepare(
        files: List<File>,
        mode: String,
        metrics: RulebookMetrics,
        progress: (String) -> Unit,
    ): List<ManualPacket> {
        require(mode in setOf("pdf_lossless", "pdf_balanced", "original"))
        PDFBoxResourceLoader.init(context)
        val packets = mutableListOf<ManualPacket>()
        val start = System.nanoTime()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val id = cache.hash(file)
            cache.preserve(file, id)
            metrics.originalPdfBytes += file.length()
            if (!file.name.endsWith(".pdf", true)) {
                metrics.pages++
                metrics.preparedPdfBytes += file.length()
                packets += ManualPacket(id, 1, 1, file, false)
                continue
            }
            progress("Preparando PDF completo — texto, imagens e diagramas")
            val dir = File(cache.versionDir(id), "pdf-$mode-v1").also { it.mkdirs() }
            val optimized =
                if (mode == "original") file
                else optimize(file, File(dir, "optimized.pdf"), mode == "pdf_balanced", metrics)
            PDDocument.load(optimized, MemoryUsageSetting.setupTempFileOnly().setTempDir(dir))
                .use { doc ->
                    require(!doc.isEncrypted && doc.numberOfPages > 0) {
                        "PDF protegido ou sem páginas. Importe cópia legível."
                    }
                    metrics.pages += doc.numberOfPages
                    metrics.preparedPdfBytes += optimized.length()
                    suspend fun packet(first: Int, last: Int) {
                        currentCoroutineContext().ensureActive()
                        if (
                            first == 1 &&
                                last == doc.numberOfPages &&
                                optimized.length() <= MAX_PACKET_BYTES
                        ) {
                            packets += ManualPacket(id, first, last, optimized, true)
                            return
                        }
                        val target = File(dir, "pages-$first-$last.pdf")
                        if (!target.exists()) {
                            val tmp = File(dir, "pages-$first-$last.tmp")
                            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(dir))
                                .use { part ->
                                    for (page in first..last) {
                                        val source = doc.getPage(page - 1)
                                        val imported = part.importPage(source)
                                        imported.resources = source.resources
                                        imported.cropBox = source.cropBox
                                        imported.mediaBox = source.mediaBox
                                        imported.rotation = source.rotation
                                    }
                                    part.save(tmp)
                                }
                            check(tmp.renameTo(target))
                        }
                        if (target.length() > MAX_PACKET_BYTES) {
                            require(first < last) {
                                "Uma página excedeu 12 MiB. Reduza a imagem ou use outra cópia; nada foi omitido."
                            }
                            val mid = (first + last) / 2
                            packet(first, mid)
                            packet(mid + 1, last)
                        } else packets += ManualPacket(id, first, last, target, true)
                    }
                    for (first in 1..doc.numberOfPages step MAX_PAGES) packet(
                        first,
                        min(doc.numberOfPages, first + MAX_PAGES - 1),
                    )
                }
        }
        metrics.pdfPackets = packets.size
        metrics.pdfPacketBytes = packets.sumOf { it.file.length() }
        metrics.localMs += (System.nanoTime() - start) / 1_000_000
        return packets
    }

    suspend fun optimize(
        original: File,
        target: File,
        balanced: Boolean,
        metrics: RulebookMetrics,
    ): File {
        if (target.exists()) return if (target.length() < original.length()) target else original
        val dir = target.parentFile!!.also { it.mkdirs() }
        val tmp = File(dir, target.name + ".tmp")
        try {
            PDDocument.load(original, MemoryUsageSetting.setupTempFileOnly().setTempDir(dir)).use {
                doc ->
                require(!doc.isEncrypted && doc.numberOfPages > 0) {
                    "PDF protegido ou sem páginas."
                }
                val text = PDFTextStripper().getText(doc)
                val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                val seenImages = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                val replacements = IdentityHashMap<COSBase, PDImageXObject>()
                suspend fun visit(resources: PDResources?, depth: Int) {
                    if (resources == null || depth > 20 || !seen.add(resources.cosObject)) return
                    for (name in resources.xObjectNames.toList()) {
                        currentCoroutineContext().ensureActive()
                        when (val x = resources.getXObject(name)) {
                            is PDFormXObject -> visit(x.resources, depth + 1)
                            is PDImageXObject -> {
                                val reused = replacements[x.cosObject]
                                if (reused != null) {
                                    resources.put(name, reused)
                                    continue
                                }
                                if (!seenImages.add(x.cosObject)) continue
                                // Preserve stencils, masks, color mappings and special color spaces
                                // untouched.
                                if (
                                    x.isStencil ||
                                        x.mask != null ||
                                        x.softMask != null ||
                                        x.colorKeyMask != null ||
                                        x.decode != null ||
                                        x.optionalContent != null ||
                                        x.bitsPerComponent != 8 ||
                                        x.width.toLong() * x.height > 12_000_000L ||
                                        x.width < 1 ||
                                        x.height < 1 ||
                                        (x.colorSpace !is PDDeviceRGB &&
                                            x.colorSpace !is PDDeviceGray)
                                )
                                    continue
                                if (!balanced && x.suffix in setOf("jpg", "jpx", "tiff")) continue
                                val bitmap = x.image ?: continue
                                var resized = bitmap
                                try {
                                    if (balanced && max(bitmap.width, bitmap.height) > 2800) {
                                        val scale = 2800.0 / max(bitmap.width, bitmap.height)
                                        resized =
                                            Bitmap.createScaledBitmap(
                                                bitmap,
                                                max(1, (bitmap.width * scale).roundToInt()),
                                                max(1, (bitmap.height * scale).roundToInt()),
                                                true,
                                            )
                                    }
                                    val replacement =
                                        if (balanced)
                                            JPEGFactory.createFromImage(doc, resized, .90f)
                                        else LosslessFactory.createFromImage(doc, resized)
                                    replacement.interpolate = x.interpolate
                                    if (replacement.cosObject.length < x.cosObject.length) {
                                        resources.put(name, replacement)
                                        replacements[x.cosObject] = replacement
                                        metrics.imagesRecompressed++
                                    }
                                } finally {
                                    if (resized !== bitmap) resized.recycle()
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                }
                for (page in doc.pages) visit(page.resources, 0)
                doc.save(tmp)
                PDDocument.load(tmp, MemoryUsageSetting.setupTempFileOnly().setTempDir(dir)).use {
                    checked ->
                    require(
                        checked.numberOfPages == doc.numberOfPages &&
                            PDFTextStripper().getText(checked) == text
                    ) {
                        "A verificação de integridade do texto/páginas falhou; original preservado."
                    }
                }
            }
            check(tmp.renameTo(target))
            return if (target.length() < original.length()) target else original
        } finally {
            tmp.delete()
        }
    }
}
