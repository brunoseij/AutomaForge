package com.automaforge.android

class RulebookPreprocessor {
    fun sections(number: Int, blocks: List<RuleTextBlock>, visuals: List<RuleVisual>): List<RuleSection> {
        val ordered=RulebookText.readingOrder(blocks)
        val median=blocks.map { it.fontSize }.filter { it>0 }.sorted().let { if(it.isEmpty())0f else it[it.size/2] }
        val result=mutableListOf<RuleSection>(); var current=mutableListOf<RuleTextBlock>(); var title: String?=null
        fun flush() {
            if(current.isEmpty())return
            val text=RulebookText.literalContent(current) // Keep numbers, hyphens, table rows and conditions literally.
            result+=RuleSection("p${number}_s${result.size}",title,text,current.map { it.id },current.map { it.source }.distinct(),
                visuals.filter { v -> v.type=="page_reference" || current.any { it.bounds.overlaps(v.bounds) } }.map { it.id },
                Regex("(?i)(?:p[áa]g(?:ina)?\\.?|page|se[çc][ãa]o|section|figura|figure)\\s+\\d+(?:[.:-]\\d+)*").findAll(text).map { it.value }.toList())
            current=mutableListOf()
        }
        for(block in ordered) {
            val heading=block.text.length<140 && block.fontSize>median*1.2f && median>0
            if(heading) { flush();title=block.text }
            current+=block
        }
        flush();return result
    }
    fun markRepeatedMargins(pages: List<RulePage>): List<RulePage> {
        val counts=pages.flatMap { page -> page.blocks.filter { it.bounds.top<.06f || it.bounds.bottom>.94f }.map { it.text.trim() }.distinct() }.groupingBy { it }.eachCount()
        return pages.map { page -> page.copy(repeatedMarginBlockIds=page.blocks.filter {
            (it.bounds.top<.06f || it.bounds.bottom>.94f) && (counts[it.text.trim()] ?: 0)>=maxOf(3, kotlin.math.ceil(pages.size*.8).toInt())
        }.map { it.id }) }
        // Retain even proven repeats in the lossless IR: they may contain rule-specific qualifications.
    }
}
