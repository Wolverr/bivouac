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
    fun listRowsReadTablesAndBullets() {
        val table = """
            This is a list of museums in Berlin.
            | Name            | Image | Neighborhood  | Type        |
            | --------------- | ----- | ------------- | ----------- |
            | Pergamon Museum |       | Museum Island | Archaeology [ 3 ] |
            | M               |       |               |             |
        """.trimIndent()
        assertEquals(listOf(Evidence.ListRow("Pergamon Museum", listOf("Museum Island", "Archaeology"), false)),
            Evidence.listRows(table))
        val ranked = "| 1 | Burj Khalifa | 828 metres |\n| =2 | Marina 101 | 425 metres |"
        assertEquals(listOf("Burj Khalifa", "Marina 101"), Evidence.listRows(ranked).map { it.name })
        assertTrue(Evidence.listRows(ranked).all { it.ranked })
        val bullets = "- Café Procope – the oldest restaurant in Paris\n* Guy Savoy\nplain text"
        assertEquals(listOf(Evidence.ListRow("Café Procope", listOf("the oldest restaurant in Paris"), false),
            Evidence.ListRow("Guy Savoy", emptyList(), false)), Evidence.listRows(bullets))
    }

    @Test
    fun compressCollapsesTablePadding() {
        val out = Evidence.compress("| Pergamon Museum          | Museum Island        |", listOf("pergamon"), 450)
        assertEquals("| Pergamon Museum | Museum Island |", out)
    }

    @Test
    fun compressKeepsRelevantSentences() {
        val text = "Astana is the capital of Kazakhstan. The city has many parks. It became the capital in 1997. " +
            "Football is popular. Winters are cold."
        val out = Evidence.compress(text, listOf("capital", "kazakhstan"), 80)
        assertTrue(out, out.contains("capital of Kazakhstan"))
        assertFalse(out, out.contains("Football"))
    }

    @Test
    fun plainWordRedirectsMustKeepTheWord() {
        assertTrue(Evidence.sameStem("Litre", "liters"))
        assertTrue(Evidence.sameStem("Cat", "cat"))
        assertTrue(Evidence.sameStem("Snakebite", "snake bite"))
        assertFalse(Evidence.sameStem("ELIZA", "doctor"))
        assertFalse(Evidence.sameStem("Shaquille O'Neal", "diesel"))
    }

    @Test
    fun creativeWorksAreRecognised() {
        assertTrue(Evidence.describesWork("Floating Ice is an album by American jazz bassist Michael Bisio. It was recorded in 2012."))
        assertTrue(Evidence.describesWork("Monsoon Season is the debut mixtape from Hanumankind."))
        assertTrue(Evidence.isQualifiedTwin("Snake Bite (truck)", listOf("Snakebite")))
        assertFalse(Evidence.isQualifiedTwin("Snakebite", listOf("Snakebite")))
        assertFalse(Evidence.isQualifiedTwin("Mercury (planet)", listOf("Venus")))
        // The intro opens with a review-scores table ("What should I do after a snake bite?" got the Motörhead album).
        assertTrue(Evidence.describesWork("| Review scores | Review scores |\n| Source | Rating |\n| AllMusic | [ 2 ] |\n\nSnake Bite Love is the fourteenth studio album by British rock band Motörhead."))
        assertFalse(Evidence.describesWork("Ice is water that is frozen into a solid state. It is the subject of many songs."))
        assertTrue(Evidence.isWorkTitle("Big (film)"))
        assertTrue(Evidence.isWorkTitle("Northern Light (song)"))
        assertFalse(Evidence.isWorkTitle("Rome, New York"))
        assertTrue(Evidence.asksAboutMedia("Who sang Bohemian Rhapsody?"))
        assertFalse(Evidence.asksAboutMedia("Why does ice float on water?"))
    }

    @Test
    fun bestExcerptAnswersTheQuestion() {
        val evidences = listOf(
            "Thailand is a country in Southeast Asia. It is officially known as the Kingdom of Thailand.",
            "Official languages: Thai\nThailand's climate is influenced by monsoon winds. The southwest monsoon brings rain " +
                "from mid-May to October, while the east coast gets most of its rain from October to December.",
        )
        val (i, s) = Evidence.bestExcerpt(evidences, "When is the monsoon season in Thailand, and which coast is affected?")!!
        assertEquals(1, i)
        assertTrue(s, s.contains("mid-May to October"))
        assertEquals(null, Evidence.bestExcerpt(listOf("Released: 2012\nGenre: Jazz"), "Why does ice float?"))

        // What to do: the treatment section, not "the most common symptom is fear".
        val snake = Evidence.bestExcerpt(
            listOf(
                "A snakebite is an injury caused by the bite of a snake. The most common symptom of any snakebite is overwhelming fear.",
                "Keep the bitten limb still and below the heart, and get to hospital as fast as possible.",
            ),
            "What should I do after a snake bite?", listOf("", "Treatment"),
        )
        assertEquals(1, snake?.first)
    }
}
