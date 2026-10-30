package com.automaforge.android.data.rulebook

import com.automaforge.android.core.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

class PdfTextExtractor {
    fun extract(document: PDDocument, number: Int): List<RuleTextBlock> {
        val page = document.getPage(number - 1)
        val crop = page.cropBox
        val rotated = page.rotation % 180 != 0
        val width = if (rotated) crop.height else crop.width
        val height = if (rotated) crop.width else crop.height
        val result = mutableListOf<RuleTextBlock>()
        val stripper =
            object : PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    if (text.isBlank() || positions.isEmpty()) return
                    val groups = mutableListOf<MutableList<TextPosition>>()
                    for (p in positions) {
                        val last = groups.lastOrNull()?.lastOrNull()
                        if (
                            last == null ||
                                p.xDirAdj - last.xDirAdj - last.widthDirAdj > width * .06f ||
                                kotlin.math.abs(p.yDirAdj - last.yDirAdj) >
                                    kotlin.math.max(p.heightDir, last.heightDir) * .8f
                        )
                            groups.add(mutableListOf())
                        groups.last().add(p)
                    }
                    for (group in groups) {
                        val line =
                            if (groups.size == 1) text
                            else
                                buildString {
                                    var previous: TextPosition? = null
                                    for (p in group) {
                                        previous?.let {
                                            if (
                                                p.xDirAdj - it.xDirAdj - it.widthDirAdj >
                                                    p.widthOfSpace * .4f
                                            )
                                                append(' ')
                                        }
                                        append(p.unicode)
                                        previous = p
                                    }
                                }
                        val bounds =
                            RuleRect(
                                (group.minOf { it.xDirAdj } / width).coerceIn(0f, 1f),
                                ((group.minOf { it.yDirAdj - it.heightDir }) / height).coerceIn(
                                    0f,
                                    1f,
                                ),
                                (group.maxOf { it.xDirAdj + it.widthDirAdj } / width).coerceIn(
                                    0f,
                                    1f,
                                ),
                                (group.maxOf { it.yDirAdj } / height).coerceIn(0f, 1f),
                            )
                        result +=
                            RuleTextBlock(
                                "p${number}_n${result.size}",
                                line,
                                bounds,
                                "native_pdf",
                                group.map { it.fontSizeInPt }.average().toFloat(),
                            )
                    }
                }
            }
        stripper.startPage = number
        stripper.endPage = number
        stripper.sortByPosition = true
        stripper.getText(document)
        return result
    }
}
