package com.alejoacelas.morningreader

import org.junit.Assert.assertEquals
import org.junit.Test

class ItalicsTest {
    @Test fun underscoresBecomeItalicSpans() {
        val layout = BlockLayout(listOf("## A _Title_", "He read _Middlemarch_ twice, not snake_case_name."))
        assertEquals("A Title\n\nHe read Middlemarch twice, not snake_case_name.", layout.text)
        assertEquals(listOf("Title", "Middlemarch"), layout.italics.map { layout.text.substring(it.first, it.last + 1) })
        assertEquals("He read Middlemarch twice, not snake_case_name.", layout.ranges[1].second)
    }
}
