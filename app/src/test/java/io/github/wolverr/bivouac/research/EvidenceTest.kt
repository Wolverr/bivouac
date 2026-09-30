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
    fun namesakeTownsAreDroppedWhenBothNamesAreAskedSeparately() {
        val q = "Which is further north: Rome or New York? By roughly how many degrees of latitude?"
        assertTrue(Evidence.isNamesake("Rome, New York", q))
        assertTrue(Evidence.isNamesake("Capitol Theatre (Rome, New York)", q))
        assertFalse(Evidence.isNamesake("Rome", q))
        assertFalse(Evidence.isNamesake("Washington, D.C.", q))
        // Asked about the town itself, or about both Portlands.
        assertFalse(Evidence.isNamesake("Rome, New York", "What is Rome, New York known for?"))
        assertFalse(Evidence.isNamesake("Portland, Oregon", "Portland, Oregon or Portland, Maine: which is bigger?"))
        assertFalse(Evidence.isNamesake("Paris, Texas (film)", "Who directed Paris, Texas?"))
    }

    @Test
    fun sentenceStartsFollowSentencePunctuation() {
        val q = "Could Napoleon have met Beethoven? Check their lifetimes. Cats. Dogs in the U.S. Army"
        val toks = Evidence.titleTokens(q)
        val starts = Evidence.sentenceStarts(q)
        assertEquals(toks.size, starts.size)
        val startWords = toks.filterIndexed { i, _ -> starts[i] }
        assertEquals(listOf("Could", "Check", "Cats", "Dogs"), startWords)
    }

    @Test
    fun infoboxFactsKeepMatchingLinesAtWordStarts() {
        val infobox = "Capital: Astana\nLargest city: Almaty\n• Named after: York (through James)\nOfficial languages: Kazakh"
        assertEquals("Capital: Astana; Largest city: Almaty",
            Evidence.infoboxFacts(infobox, listOf("capital", "almaty", "roughly"), 200))
        assertEquals("Capital: Astana", Evidence.infoboxFacts(infobox, listOf("capital", "almaty"), 20))
        assertEquals("", Evidence.infoboxFacts(infobox, listOf("latitude"), 200))
    }

    @Test
    fun compressTreatsLinesAsSentences() {
        val text = "Born: Bonn\nDied: 26 March 1827 · Vienna\nOccupations: Composer pianist\n" +
            "Works: List of compositions and many other things that make this text long enough to need cutting."
        val out = Evidence.compress(text, listOf("died", "vienna"), 60)
        assertTrue(out, out.contains("Died: 26 March 1827"))
        assertFalse(out, out.contains("Works"))
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
