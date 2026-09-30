package io.github.wolverr.bivouac.research

import io.github.wolverr.bivouac.research.Router.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    private fun route(q: String, history: Boolean = false) = Router.decide(q, history)

    @Test
    fun factualQuestionsGoToTheLibrary() {
        listOf(
            "What is the capital of Kazakhstan?",
            "Explain how a heat pump works",
            "Compare the Roman Republic and the Roman Empire",
            "Why did the Bronze Age collapse happen?",
            "vegetarian restaurants near the Duomo in Florence",
        ).forEach { assertEquals(it, Route.LOOKUP, route(it).route) }
    }

    @Test
    fun personalAndCasualMessagesSkipTheLibrary() {
        listOf(
            "hi",
            "thanks!",
            "im feeling bad rn what should i do?",
            "I'm so stressed about my exams",
            "should i text my ex",
            "how are you",
        ).forEach { assertEquals(it, Route.CHAT, route(it).route) }
    }

    @Test
    fun crisisIsFlagged() {
        assertTrue(route("i want to die").crisis)
        assertTrue(route("thinking about suicide").crisis)
        assertFalse(route("how did Napoleon die?").crisis)
    }

    @Test
    fun followUpsNeedHistory() {
        assertTrue(route("and its population?", history = true).followUp)
        assertTrue(route("why?", history = true).followUp)
        assertFalse(route("and its population?", history = false).followUp)
        assertFalse(route("What were the main causes of the Thirty Years' War in Europe?", history = true).followUp)
    }
}
