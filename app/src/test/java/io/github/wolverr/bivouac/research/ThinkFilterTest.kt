package io.github.wolverr.bivouac.research

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkFilterTest {
    /** Streams [pieces] through a fresh filter and returns everything shown. */
    private fun run(vararg pieces: String): String {
        val f = ThinkFilter()
        return pieces.joinToString("") { f.feed(it) } + f.flush()
    }

    @Test
    fun reasoningOpenedByThePromptIsHidden() {
        // Qwen3.5 with thinking on: the prompt ends with "<think>", the stream has only "</think>".
        val f = ThinkFilter(startInThink = true)
        val shown = listOf("Thinking Process:\n1. Add 3h 55m", "\n</th", "ink>\n\nThe train arrives at **19:35**.")
            .joinToString("") { f.feed(it) } + f.flush()
        assertEquals("\n\nThe train arrives at **19:35**.", shown)
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("Astana is the capital.", run("Astana ", "is the ", "capital."))
    }

    @Test
    fun emptyThinkBlockIsRemoved() {
        assertEquals("\nA heat pump moves heat.", run("<think></think>", "\nA heat pump moves heat."))
    }

    @Test
    fun reasoningIsHiddenEvenWhenTagsAreSplit() {
        assertEquals("Answer: 42", run("<th", "ink>let me", " work it out</th", "ink>Answer: ", "42"))
    }

    @Test
    fun specialTokensAreDropped() {
        assertEquals("[1] Sagrada Família", run("<|tool_call", "_start|>", "[1] Sagrada Família"))
    }

    @Test
    fun ordinaryAngleBracketsSurvive() {
        assertEquals("x < 3 and a <b> tag", run("x < 3 and a <b", "> tag"))
        assertEquals("ends with <", run("ends with <"))
    }

    @Test
    fun unfinishedReasoningShowsNothing() {
        assertEquals("", run("<think>never closed"))
    }
}
