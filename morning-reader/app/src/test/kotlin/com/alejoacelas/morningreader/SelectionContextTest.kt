package com.alejoacelas.morningreader

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Every selection the reader can make must reach the model whole: the marked paragraphs must hold
 * exactly the selected text, in the paragraph the selection starts in. Runs over every built pack.
 */
class SelectionContextTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val packs = File("../packs").listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString<BookPack>(it.readText()) }.getOrNull() }

    private fun check(layout: BlockLayout, start: Int, end: Int, what: String) {
        val sel = layout.selection(start, end) ?: return
        val inner = Regex("<mark>(.*?)</mark>", RegexOption.DOT_MATCHES_ALL).findAll(sel.marked)
            .joinToString("\n\n") { it.groupValues[1] }
        assertEquals("$what: marked text differs from the selection", sel.text, inner.trim())
        assertTrue("$what: selection not in its paragraph",
            layout.ranges[sel.paragraph].second.contains(sel.text.lineSequence().first().trim()))
        assertEquals("$what: marked paragraphs don't match the shown text",
            sel.marked.replace("<mark>", "").replace("</mark>", ""),
            layout.text.substring(layout.ranges[sel.paragraph].first.first, layout.ranges[layout.ranges.indexOfLast {
                it.first.first <= maxOf(sel.start, sel.end - 1) }].first.last))
    }

    @Test fun everySelectionReachesTheModel() {
        assertTrue("no packs found in ../packs", packs.isNotEmpty())
        val random = Random(7)
        var checked = 0
        for (book in packs) for (block in book.blocks) {
            val layout = BlockLayout(block.paragraphs)
            val n = layout.text.length
            if (n == 0) continue
            // Words near the end of each long paragraph: the case the old 1,500-character cut lost.
            layout.ranges.forEach { (range, para) ->
                if (para.length > 20) check(layout, range.last - 12, range.last - 2, "${book.id}/${block.id} end of paragraph")
                check(layout, range.first, range.last, "${book.id}/${block.id} whole paragraph")
            }
            repeat(40) {
                val start = random.nextInt(n)
                val len = listOf(5, 30, 200, 1500, 4000).random(random)
                check(layout, start, minOf(n, start + len), "${book.id}/${block.id} @$start+$len")
                checked++
            }
            // Reversed handles, as Android reports when selecting backwards.
            check(layout, minOf(n, 50), 0, "${book.id}/${block.id} reversed")
        }
        println("checked $checked random selections in ${packs.size} packs")
    }

    @Test fun selectionsFromHistoryThatLostContext() {
        // Selections whose answers said the text wasn't in the excerpt (Bleak House, 2026-11-03).
        val book = packs.firstOrNull { it.title == "Bleak House" } ?: return
        val block = book.blocks.first { it.title.contains("In Fashion") }
        val layout = BlockLayout(block.paragraphs)
        val at = layout.text.indexOf("as of the")
        check(layout, at, at + "as of the".length, "Bleak House 'as of the'")
        assertTrue(layout.selection(at, at + 9)!!.marked.contains("<mark>as of the</mark>"))
    }
}
