package io.github.wolverr.bivouac.research

/**
 * Decides, without calling the model, whether a message needs the library.
 * Searching costs prompt tokens (seconds on a phone), and irrelevant excerpts
 * make casual replies stiff, so chat and personal messages skip retrieval.
 */
object Router {
    enum class Route { LOOKUP, CHAT }

    /** [reasoning]: a calculation question, answered with thinking on (see [needsReasoning]). */
    data class Decision(val route: Route, val followUp: Boolean, val crisis: Boolean, val reasoning: Boolean = false)

    private val GREETING = Regex(
        "^(hi|hello|hey|yo|hiya|thanks|thank you|thx|ok|okay|cool|nice|great|good (morning|evening|night)|bye|lol)\\b[\\s!.?]*$",
    )
    private val PERSONAL = Regex(
        "\\b(i feel|i'm feeling|im feeling|i am feeling|i feel like|i'm (so )?(sad|stressed|anxious|lonely|bored|tired|upset|angry|scared|worried)|" +
            "im (so )?(sad|stressed|anxious|lonely|bored|tired|upset|angry|scared|worried)|feeling (bad|down|low|sad|lonely)|" +
            "my (girlfriend|boyfriend|wife|husband|partner|friend|mom|mum|dad|mother|father|boss|family)|" +
            "should i|do you think i|can you help me|help me (write|decide|with)|write (me|a)|rewrite|translate|" +
            "how are you|who are you|what are you)\\b",
    )
    private val CRISIS = Regex(
        "\\b(kill myself|killing myself|suicid\\w*|end my life|want to die|don't want to live|dont want to live|" +
            "self[- ]harm|hurt myself|cutting myself|overdose)\\b",
    )
    private val FOLLOW_UP_START = Regex(
        "^(and|but|also|so|what about|how about|why|why not|tell me more|more|explain|really|is that|are they|" +
            "does it|did they|it|its|it's|that|those|these|they|he|she|which one)\\b",
    )

    // Reasoning questions: numbers to work with plus a question that asks for a result.
    private val NUMBER = Regex("\\d+(?:[.,:]\\d+)*")
    private val CALC = Regex("\\b(how (long|many|much|far)|what (local )?time|is (that|it|this) enough|will (it|i|we) (last|need))\\b")
    private val RATE = Regex("\\b(per|every|each|a (day|week|month|year|hour)|in an? (day|week|month|year|hour))\\b|/")
    private val IF_START = Regex("^\\s*if\\b")
    private val BY_HOW = Regex("\\bby (roughly |about |approximately )?how (many|much)\\b")

    /**
     * Calculations ("23 km with a 900 m climb, how long…", "a 4 GB file at 12 MB/s…") and "by how
     * much" comparisons. Thinking makes other answers shorter and worse (9B, all 60 questions:
     * -0.25 when always on), but on these it gained or tied in all 9 cases (main + held-out, 4B and
     * 9B). Not timelines ("could Napoleon have met Beethoven", "was Newton alive when…"): thinking
     * talked both models into wrong dates (-4, -9). One number counts only with a rate or an "if":
     * "how long did the 100 Years' War last" is a lookup. Mirrors tools/answer_proto.py.
     */
    fun needsReasoning(message: String): Boolean {
        val m = message.trim().lowercase()
        val numbers = NUMBER.findAll(m).count()
        val calc = CALC.containsMatchIn(m) &&
            (numbers >= 2 || (numbers == 1 && (RATE.containsMatchIn(m) || IF_START.containsMatchIn(m))))
        return calc || BY_HOW.containsMatchIn(m)
    }

    fun decide(message: String, hasHistory: Boolean): Decision {
        val m = message.trim().lowercase()
        val crisis = CRISIS.containsMatchIn(m)
        val words = Evidence.keywords(m)
        val chat = crisis || GREETING.matches(m) || PERSONAL.containsMatchIn(m) || words.isEmpty()
        val followUp = hasHistory && (FOLLOW_UP_START.containsMatchIn(m) || m.split(Regex("\\s+")).size <= 5)
        return Decision(if (chat) Route.CHAT else Route.LOOKUP, followUp, crisis, reasoning = !chat && needsReasoning(m))
    }
}

/** Shown with the reply when a message suggests someone may be in danger. The app can't look these up offline. */
object CrisisResources {
    val lines = listOf(
        "Emergency: 112 (EU and many countries), 911 (US, Canada), 999 (UK)",
        "US: call or text 988 (Suicide & Crisis Lifeline)",
        "UK & Ireland: Samaritans 116 123",
        "Elsewhere: your local emergency number, or someone you trust nearby",
    )
}
