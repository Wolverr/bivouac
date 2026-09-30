package io.github.wolverr.bivouac.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTest {
    @Test
    fun ftsQueryNeverContainsPhrases() {
        // detail=column indexes reject multi-token quoted phrases.
        val q = Evidence.ftsQuery(Evidence.keywords("Kazakhstan's capital and heat-pump efficiency"))
        assertEquals("\"kazakhstan\" OR \"capital\" OR \"heat\" OR \"pump\" OR \"efficiency\"", q)
        q.split(" OR ").forEach { assertFalse(it, it.trim('"').contains(Regex("[^\\p{L}\\p{N}]"))) }
    }

    @Test
    fun ftsQueryStripsQuotesAndOperators() {
        val q = Evidence.ftsQuery(Evidence.keywords("\"NEAR\" AND (tungsten) OR *melting*"))
        assertTrue(q, q.contains("\"tungsten\""))
        assertFalse(q, q.contains("(") || q.contains("*"))
    }

    @Test
    fun compressKeepsRelevantSentences() {
        val text = "Astana is the capital of Kazakhstan. The city has many parks. It became the capital in 1997. " +
            "Football is popular. Winters are cold."
        val out = Evidence.compress(text, listOf("capital", "kazakhstan"), 80)
        assertTrue(out, out.contains("capital of Kazakhstan"))
        assertFalse(out, out.contains("Football"))
    }
}
