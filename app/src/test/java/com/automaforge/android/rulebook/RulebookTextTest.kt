package com.automaforge.android

import com.automaforge.android.core.*
import com.automaforge.android.data.ai.*
import com.automaforge.android.data.rulebook.*

import org.junit.Assert.*
import org.junit.Test

class RulebookTextTest {
    private fun b(id: String,text: String,x: Float,y: Float,right: Float=x+.25f,source: String="native_pdf")=
        RuleTextBlock(id,text,RuleRect(x,y,right,y+.03f),source)
    @Test fun selectiveOcr() {
        assertTrue(RulebookText.needsOcr(emptyList()))
        assertFalse(RulebookText.needsOcr(listOf(b("a","Move exactly 3 spaces unless blocked. Ties favor the defender. Spend 2 energy.",0f,0f))))
        assertTrue(RulebookText.needsOcr(listOf(b("a","abc\uFFFD\uFFFD",0f,0f))))
    }
    @Test fun columnsAreNotInterleaved() {
        val items=listOf(b("l1","Left 1",.05f,.1f),b("r1","Right 1",.6f,.1f),b("l2","Left 2",.05f,.2f),b("r2","Right 2",.6f,.2f))
        assertEquals(listOf("l1","l2","r1","r2"),RulebookText.readingOrder(items).map { it.id })
    }
    @Test fun fullWidthHeadingPrecedesColumns() {
        val items=listOf(b("h","Preparation",.05f,.01f,.95f),b("l","Left",.05f,.1f),b("r","Right",.6f,.1f))
        assertEquals(listOf("h","l","r"),RulebookText.readingOrder(items).map { it.id })
    }
    @Test fun duplicatesRemovedOnlyAtSameLocation() {
        val native=b("a","Spend 2 energy.",.1f,.1f)
        val output=RulebookText.merge(listOf(native),listOf(native.copy(id="ocr",source="local_ocr"),b("other","Spend 2 energy.",.1f,.6f,source="local_ocr")))
        assertEquals(listOf("a","other"),output.map { it.id })
    }
    @Test fun preserveExceptionsNumbersReferencesAndTables() {
        val text="1. Spend 2 energy.\nMove exactly 3 spaces unless blocked.\nIf tied, defender wins. See page 2.\nCost | Range\n4 | 1"
        val section=RulebookPreprocessor().sections(1,listOf(b("a",text,.1f,.1f)),emptyList()).single()
        assertEquals(text,section.content)
        assertEquals(listOf("page 2"),section.crossReferences)
    }
    @Test fun longDocumentNoTruncation() {
        val text=(1..10000).joinToString("\n") { "$it. Unless blocked, spend 2 energy; see page 3." }
        assertEquals(text,RulebookPreprocessor().sections(1,listOf(b("a",text,0f,0f)),emptyList()).single().content)
        assertEquals(text,text.chunked(12000).joinToString(""))
    }
    @Test fun repeatedMarginsStayRecoverable() {
        val header=b("h","Unless blocked",.05f,.01f)
        val pages=(1..4).map { RulePage(it,600f,800f,listOf(header),emptyList(),emptyList(),emptyList(),false,true) }
        val marked=RulebookPreprocessor().markRepeatedMargins(pages)
        assertTrue(marked.all { it.repeatedMarginBlockIds==listOf("h") && it.blocks.single().text==header.text })
    }
}
