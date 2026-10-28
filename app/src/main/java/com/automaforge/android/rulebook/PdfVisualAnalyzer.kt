package com.automaforge.android

import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage


/** Catalog only: no visual is classified as disposable. Vectors are retained through a page candidate. */
class PdfVisualAnalyzer {
    fun analyze(page: PDPage, number: Int): List<RuleVisual> {
        val result=mutableListOf<RuleVisual>()
        val box=page.cropBox
        val paths=mutableListOf<RuleRect>()
        var coalesced=false
        val engine=object: PDFGraphicsStreamEngine(page) {
            var point=PointF()
            var pathPoints=mutableListOf<PointF>()
            fun retainPath() {
                if(page.rotation==0 && pathPoints.isNotEmpty()) {
                    val b=RuleRect(((pathPoints.minOf { it.x }-box.lowerLeftX)/box.width-.007f).coerceIn(0f,1f),
                        (1f-(pathPoints.maxOf { it.y }-box.lowerLeftY)/box.height-.007f).coerceIn(0f,1f),
                        ((pathPoints.maxOf { it.x }-box.lowerLeftX)/box.width+.007f).coerceIn(0f,1f),
                        (1f-(pathPoints.minOf { it.y }-box.lowerLeftY)/box.height+.007f).coerceIn(0f,1f))
                    if(b.area>0 && !coalesced) { paths+=b; if(paths.size>512) { paths.clear();paths+=RuleRect.PAGE;coalesced=true } }
                }
                pathPoints.clear()
            }
            override fun drawImage(image: PDImage) {
                if(page.rotation!=0)return // rotated page retained below, coordinates must not be guessed
                val m=graphicsState.currentTransformationMatrix
                val corners=listOf(m.transformPoint(0f,0f),m.transformPoint(1f,0f),m.transformPoint(0f,1f),m.transformPoint(1f,1f))
                val b=RuleRect(((corners.minOf { it.x }-box.lowerLeftX)/box.width-.005f).coerceIn(0f,1f),
                    (1f-(corners.maxOf { it.y }-box.lowerLeftY)/box.height-.005f).coerceIn(0f,1f),
                    ((corners.maxOf { it.x }-box.lowerLeftX)/box.width+.005f).coerceIn(0f,1f),
                    (1f-(corners.minOf { it.y }-box.lowerLeftY)/box.height+.005f).coerceIn(0f,1f))
                if(b.area>0f)result+=RuleVisual("p${number}_image${result.size}",number,b,"embedded_image")
            }
            override fun appendRectangle(a: PointF,b: PointF,c: PointF,d: PointF) { point=a;pathPoints.addAll(listOf(a,b,c,d)) }
            override fun clip(windingRule: android.graphics.Path.FillType) {}
            override fun moveTo(x: Float,y: Float) { point=PointF(x,y);pathPoints+=point }
            override fun lineTo(x: Float,y: Float) { point=PointF(x,y);pathPoints+=point }
            override fun curveTo(x1: Float,y1: Float,x2: Float,y2: Float,x3: Float,y3: Float) { point=PointF(x3,y3);pathPoints.addAll(listOf(PointF(x1,y1),PointF(x2,y2),point)) }
            override fun getCurrentPoint(): PointF = point
            override fun closePath() {}
            override fun endPath() { pathPoints.clear() }
            override fun strokePath() { retainPath() }
            override fun fillPath(windingRule: android.graphics.Path.FillType) { retainPath() }
            override fun fillAndStrokePath(windingRule: android.graphics.Path.FillType) { retainPath() }
            override fun shadingFill(shadingName: com.tom_roush.pdfbox.cos.COSName) {}
        }
        engine.processPage(page)
        // Merge touching strokes into conservative graphics/table crops. No classification discards artwork.
        val merged=mutableListOf<RuleRect>()
        for(path in paths.filter { it.area<.85f }) {
            var candidate=path; var changed=true
            while(changed) {
                changed=false
                val iterator=merged.iterator()
                while(iterator.hasNext()) {
                    val b=iterator.next()
                    val expanded=RuleRect(candidate.left-.02f,candidate.top-.02f,candidate.right+.02f,candidate.bottom+.02f)
                    if(expanded.overlaps(b)) {
                        candidate=RuleRect(minOf(candidate.left,b.left),minOf(candidate.top,b.top),maxOf(candidate.right,b.right),maxOf(candidate.bottom,b.bottom))
                        iterator.remove();changed=true
                    }
                }
            }
            merged+=candidate
        }
        merged.addAll(paths.filter { it.area>=.85f })
        merged.forEachIndexed { index,bounds -> result+=RuleVisual("p${number}_vector$index",number,bounds,"vector_graphic_or_table") }
        // Includes vector diagrams, tables, icons, inline artwork and anything the heuristic missed.
        result+=RuleVisual("p${number}_page",number,RuleRect.PAGE,"page_reference")
        return result
    }
}
