package com.automaforge.android

/** Coordinates are normalized to the visible, rotated page, origin at top left. */
data class RuleRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = (right-left).coerceAtLeast(0f)*(bottom-top).coerceAtLeast(0f)
    fun overlaps(b: RuleRect): Boolean = left < b.right && right > b.left && top < b.bottom && bottom > b.top
    fun contains(b: RuleRect): Boolean = left<=b.left && top<=b.top && right>=b.right && bottom>=b.bottom
    companion object { val PAGE = RuleRect(0f,0f,1f,1f) }
}
data class RuleTextBlock(val id: String, val text: String, val bounds: RuleRect,
    val source: String, val fontSize: Float = 0f, val confidence: Float? = null)
data class RuleSection(val id: String, val title: String?, val content: String,
    val blockIds: List<String>, val sources: List<String>, val visualReferences: List<String>,
    val crossReferences: List<String>)
data class RuleVisual(val id: String, val page: Int, val bounds: RuleRect, val type: String,
    val state: String = "available", val originalPath: String? = null, val localPath: String? = null,
    val originalBytes: Long = 0, val optimizedBytes: Long = 0)
data class RulePage(val number: Int, val width: Float, val height: Float,
    val blocks: List<RuleTextBlock>, val visuals: List<RuleVisual>, val sections: List<RuleSection>,
    val issues: List<String>, val ocrUsed: Boolean, val nativeUsed: Boolean,
    val repeatedMarginBlockIds: List<String> = emptyList())
data class RulebookDocument(val documentId: String, val pipelineVersion: Int = PIPELINE_VERSION,
    val schemaVersion: Int = RULE_SCHEMA_VERSION, val originalBytes: Long,
    val title: String, val language: String = "und", val pages: List<RulePage>)
const val PIPELINE_VERSION = 1
const val RULE_SCHEMA_VERSION = 1

data class RulebookMetrics(var pages: Int = 0, var nativePages: Int = 0, var ocrPages: Int = 0,
    var visualsIdentified: Int = 0, var imagesTransmitted: Int = 0,
    var originalPdfBytes: Long = 0, var sentRequestBytes: Long = 0, var attemptedRequestBytes: Long = 0, var imagesAttempted: Int = 0,
    var localMs: Long = 0, var remoteMs: Long = 0, var inputTokens: Long = 0,
    var outputTokens: Long = 0, var calls: Int = 0, var cacheHits: Int = 0, var cacheMisses: Int = 0,
    var usageAvailable: Boolean = false, var uncertainCalls: Int = 0,
    val tokenSavings: Long? = null, val monetarySavings: Double? = null) {
    // Negative is meaningful: optimized requests can exceed the original binary PDF.
    val byteDifference: Long get() = originalPdfBytes - sentRequestBytes
}
data class RulebookProgress(val state: String = "idle", val stage: String = "Aguardando",
    val page: Int = 0, val total: Int = 0, val ocr: String = "Aguardando",
    val visual: String = "Aguardando", val message: String = "",
    val metrics: RulebookMetrics = RulebookMetrics(), val draft: String? = null)

object RulebookText {
    fun needsOcr(blocks: List<RuleTextBlock>): Boolean {
        val text = blocks.joinToString("\n") { it.text }
        return text.count { it.isLetterOrDigit() } < 40 || text.count { it == '\uFFFD' } > text.length/50
    }
    fun merge(native: List<RuleTextBlock>, ocr: List<RuleTextBlock>): List<RuleTextBlock> {
        fun normalized(s: String) = s.trim().replace(Regex("\\s+"), " ")
        return native + ocr.filter { b -> native.none { a ->
            normalized(a.text)==normalized(b.text) && a.bounds.overlaps(b.bounds)
        } }
    }
    private fun rows(blocks: List<RuleTextBlock>): List<List<RuleTextBlock>> {
        val rows=mutableListOf<MutableList<RuleTextBlock>>()
        for(block in blocks.sortedBy { it.bounds.top }) {
            val row=rows.lastOrNull()
            if(row!=null && kotlin.math.abs(row.first().bounds.top-block.bounds.top)<.009f)row+=block
            else rows+=mutableListOf(block)
        }
        return rows.map { it.sortedBy { b -> b.bounds.left } }
    }
    fun isTable(blocks: List<RuleTextBlock>): Boolean {
        val aligned=rows(blocks).filter { row -> row.size>=2 && row.zipWithNext().all { (a,b) -> a.bounds.right<=b.bounds.left } }
        return aligned.size>=2 && (aligned.count { it.size>=3 }>=2 || aligned.count { row -> row.any { it.text.trim().matches(Regex("[0-9]+(?:[.,][0-9]+)?")) } }>=2)
    }
    fun uncertainLayout(blocks: List<RuleTextBlock>): Boolean = isTable(blocks) || rows(blocks).count { row ->
        row.size>=2 && row.first().bounds.right+.03f<row.last().bounds.left
    }>=2
    fun literalContent(blocks: List<RuleTextBlock>): String = if(isTable(blocks))
        rows(blocks).joinToString("\n") { row -> row.joinToString("\t") { it.text } }
        else blocks.joinToString("\n") { it.text }
    /** XY-cut: separate columns only across a genuine empty vertical gutter. Never join rows across it. */
    fun readingOrder(blocks: List<RuleTextBlock>): List<RuleTextBlock> {
        if (blocks.size < 2) return blocks
        if(isTable(blocks))return rows(blocks).flatten()
        val spanning = blocks.filter { it.bounds.left < .42f && it.bounds.right > .58f }
        if (spanning.isNotEmpty()) {
            val remaining = blocks.toMutableList(); val result=mutableListOf<RuleTextBlock>()
            for (s in spanning.sortedBy { it.bounds.top }) {
                val before=remaining.filter { it !== s && it.bounds.bottom<=s.bounds.top }
                result+=readingOrder(before); remaining.removeAll(before.toSet())
                result+=s; remaining.remove(s)
            }
            result+=readingOrder(remaining); return result
        }
        val left=blocks.filter { it.bounds.right<=.52f }; val right=blocks.filter { it.bounds.left>=.48f }
        if(left.isNotEmpty() && right.isNotEmpty() && left.size+right.size==blocks.size &&
            left.maxOf { it.bounds.right }+.015f<right.minOf { it.bounds.left })
            return readingOrder(left)+readingOrder(right)
        return blocks.sortedWith(compareBy<RuleTextBlock> { (it.bounds.top*150).toInt() }.thenBy { it.bounds.left })
    }
}
