package com.automaforge.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.*

class PdfImageOptimizer {
    /** Render the crop directly into a bounded bitmap; a page-sized bitmap is never needed for a crop. */
    fun render(pdf: File, pageNumber: Int, bounds: RuleRect, maxEdge: Int): Bitmap {
        ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer -> renderer.openPage(pageNumber-1).use { page ->
                val w=page.width*(bounds.right-bounds.left); val h=page.height*(bounds.bottom-bounds.top)
                require(w>0 && h>0)
                val scale=min(maxEdge/w,maxEdge/h)
                val bitmap=Bitmap.createBitmap(max(1,ceil(w*scale).toInt()),max(1,ceil(h*scale).toInt()),Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val matrix=Matrix().apply { setValues(floatArrayOf(scale,0f,-bounds.left*page.width*scale,0f,scale,-bounds.top*page.height*scale,0f,0f,1f)) }
                try { page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); return bitmap }
                catch(t: Throwable) { bitmap.recycle();throw t }
            } }
        }
    }
    fun photo(file: File, maxEdge: Int): Bitmap {
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,options)
        require(options.outWidth>0 && options.outHeight>0) { "Imagem inválida" }
        options.inJustDecodeBounds=false
        while(max(options.outWidth,options.outHeight)/options.inSampleSize.coerceAtLeast(1)>maxEdge*2)
            options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
        val source=BitmapFactory.decodeFile(file.path,options) ?: error("Imagem inválida")
        val scale=min(1f,maxEdge.toFloat()/max(source.width,source.height))
        if(scale==1f)return source
        val resized=Bitmap.createScaledBitmap(source,max(1,(source.width*scale).toInt()),max(1,(source.height*scale).toInt()),true)
        if(resized!==source)source.recycle()
        return resized
    }
    fun optimize(original: File, visual: RuleVisual, directory: File, pdf: Boolean): RuleVisual {
        directory.mkdirs()
        val raw=File(directory,"${visual.id}-original.png")
        // Keep a lossless master plus the untouched PDF. Bound pathological page dimensions.
        if(!raw.exists()) {
            val bitmap=if(pdf)render(original,visual.page,visual.bounds,3200) else photo(original,3200)
            try { raw.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
        }
        // Unknown graphics and small text remain PNG at up to 2400 px; never apply lossy compression blindly.
        val losslessWebp=android.os.Build.VERSION.SDK_INT>=30
        val target=File(directory,"${visual.id}-send."+if(losslessWebp)"webp" else "png")
        if(!target.exists()) {
            val bitmap=photo(raw,2400)
            try { target.outputStream().use { check(bitmap.compress(if(losslessWebp)Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
        }
        return visual.copy(originalPath=raw.name,localPath=target.name,originalBytes=raw.length(),optimizedBytes=target.length(),state="optimized")
    }
}
