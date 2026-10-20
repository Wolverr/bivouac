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
            // "should I" inside a question is not asking for personal advice (these used to skip Wikipedia).
            "How can I tell if a snake bite is venomous, and what first aid should I give?",
            "What are the symptoms of heat stroke and what should I do first?",
            "How do I treat a minor burn at home, and when should I see a doctor?",
            "What should I know about altitude sickness before trekking to Everest Base Camp?",
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
    fun calculationsNeedReasoning() {
        listOf(
            "If I walk at 5 km/h and need to cover 23 km with a 900 m climb, roughly how long will it take using Naismith's rule?",
            "A train leaves at 14:40 and the trip takes 3 h 55 min, crossing into a time zone one hour ahead. What local time does it arrive?",
            "I have 1.5 liters of water and am hiking 6 hours in 30°C heat. Is that enough? Explain.",
            "If Ethereum's block time is 12 seconds, how many blocks are produced in a week?",
            "Which is further north: Rome or New York? By roughly how many degrees of latitude?",
            "A 4 GB file downloads at 12 MB/s. How long will it take?",
            "If I save 200 euros a month, how long until I have 5000?",
        ).forEach { assertTrue(it, route(it).reasoning) }
    }

    @Test
    fun lookupsWithNumbersDontNeedReasoning() {
        listOf(
            "How long did the 100 Years War last?",
            "How many Oscars did Titanic (1997) win?",
            "How many countries are in the EU in 2024?",
            "How long does it take to boil an egg?",
            "What year did the Berlin Wall fall?",
            "Could Napoleon have met Beethoven? Check their lifetimes and whereabouts.",
            "Was Isaac Newton alive when Galileo Galilei died?",
            "Explain how GPS works",
            "hi",
        ).forEach { assertFalse(it, route(it).reasoning) }
    }

    @Test
    fun followUpsNeedHistory() {
        assertTrue(route("and its population?", history = true).followUp)
        assertTrue(route("why?", history = true).followUp)
        assertFalse(route("and its population?", history = false).followUp)
        assertFalse(route("What were the main causes of the Thirty Years' War in Europe?", history = true).followUp)
        // About the places list of the last answer, however long.
        assertTrue(route("Which of those are open late on a Sunday?", history = true).followUp)
        assertTrue(route("Are any of them good for a group of eight people?", history = true).followUp)
    }
}
