package com.alejoacelas.morningreader

/** A selection in a block: offsets in the shown text, the paragraph it starts in, and the paragraphs it spans with the selection marked. */
data class Selection(val text: String, val start: Int, val end: Int, val paragraph: Int, val marked: String)

/**
 * The text of a block as the reader shows it: paragraphs separated by a blank line, with the
 * "## " of headings removed. Maps offsets in that text back to paragraphs, so a selection can be
 * sent to the model with exactly the paragraphs it touches.
 */
class BlockLayout(paragraphs: List<String>) {
    val text: String
    /** Each paragraph's span in [text] (`last` is its end, exclusive) and its shown text. */
    val ranges: List<Pair<IntRange, String>>
    val headings: List<Boolean> = paragraphs.map { it.startsWith("## ") }

    init {
        val out = StringBuilder()
        ranges = paragraphs.mapIndexed { i, raw ->
            if (i > 0) out.append("\n\n")
            val shown = if (headings[i]) raw.removePrefix("## ") else raw
            val start = out.length
            out.append(shown)
            (start..out.length) to shown
        }
        text = out.toString()
    }

    /** The selected text, and every paragraph it touches with the selection wrapped in <mark>…</mark>. */
    fun selection(rawStart: Int, rawEnd: Int): Selection? {
        var start = minOf(rawStart, rawEnd).coerceIn(0, text.length)
        var end = maxOf(rawStart, rawEnd).coerceIn(start, text.length)
        // Drop surrounding whitespace, so a selection starting on a blank line belongs to the paragraph after it.
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        val selected = text.substring(start, end)
        if (selected.isEmpty()) return null
        val first = paragraphAt(start)
        val last = paragraphAt(maxOf(start, end - 1))
        val marked = (first..last).joinToString("\n\n") { i ->
            val (range, para) = ranges[i]
            // Offsets inside this paragraph's text, clipped to the part of the selection it holds.
            val from = (start - range.first).coerceIn(0, para.length)
            val to = (end - range.first).coerceIn(from, para.length)
            para.substring(0, from) + "<mark>" + para.substring(from, to) + "</mark>" + para.substring(to)
        }
        return Selection(selected, start, end, first, marked)
    }

    private fun paragraphAt(offset: Int): Int = ranges.indexOfFirst { offset in it.first }.takeIf { it >= 0 }
        ?: ranges.indexOfLast { it.first.first <= offset }.coerceAtLeast(0)
}
