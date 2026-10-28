package com.automaforge.android

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PdfOcrProcessor: AutoCloseable {
    companion object { private val recognitionSlots=Semaphore(1) }
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    /** ML Kit task owns the bitmap until completion, even when the awaiting coroutine is cancelled. */
    suspend fun recognize(bitmap: Bitmap, number: Int, region: RuleRect): List<RuleTextBlock> {
        try { recognitionSlots.acquire() } catch(t: Throwable) { bitmap.recycle();throw t }
        return suspendCancellableCoroutine { continuation ->
            val pending=try { recognizer.process(InputImage.fromBitmap(bitmap,0)) }
            catch(t: Exception) { bitmap.recycle();recognitionSlots.release();continuation.resumeWithException(t);return@suspendCancellableCoroutine }
            pending.addOnCompleteListener { task ->
                try {
                    if(!continuation.isActive)return@addOnCompleteListener
                    if(!task.isSuccessful) { continuation.resumeWithException(task.exception ?: IllegalStateException("OCR indisponível")); return@addOnCompleteListener }
                    val lines=task.result.textBlocks.flatMap { it.lines }
                    val output=lines.mapIndexedNotNull { index,line ->
                        val r=line.boundingBox ?: return@mapIndexedNotNull null
                        fun x(value: Int)=region.left+(region.right-region.left)*value/bitmap.width
                        fun y(value: Int)=region.top+(region.bottom-region.top)*value/bitmap.height
                        RuleTextBlock("p${number}_ocr${region.hashCode()}_$index",line.text,
                            RuleRect(x(r.left),y(r.top),x(r.right),y(r.bottom)),"local_ocr",confidence=line.confidence)
                    }
                    continuation.resume(output)
                } catch(t: Exception) { if(continuation.isActive)continuation.resumeWithException(t) }
                finally { bitmap.recycle();recognitionSlots.release() }
            }
        }
    }
    override fun close() { recognizer.close() }
}
