package io.github.wolverr.bivouac.research

import io.github.wolverr.bivouac.llm.ChatTurn
import io.github.wolverr.bivouac.llm.GenEvent
import io.github.wolverr.bivouac.llm.LlmRuntime
import io.github.wolverr.bivouac.llm.Sampling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** A numbered source shown to the model and to the user. */
data class Source(val n: Int, val passage: Passage, val evidence: String)

sealed interface ResearchEvent {
    data class Routed(val decision: Router.Decision) : ResearchEvent
    data class Searching(val query: String) : ResearchEvent
    data class Sources(val sources: List<Source>, val searchMs: Long) : ResearchEvent
    data class Llm(val event: GenEvent) : ResearchEvent
}

/**
 * Conversation memory. Each user turn keeps two renderings: exactly what the
 * model saw (with excerpts), which lets the engine reuse its cache on the next
 * turn, and the bare question, used when history has to be compacted.
 */
class Conversation {
    internal data class Turn(val question: String, val prompt: String, var answer: String)

    internal val turns = ArrayList<Turn>()
    val isEmpty: Boolean get() = turns.isEmpty()
    val lastQuestion: String? get() = turns.lastOrNull()?.question

    fun clear() = turns.clear()
}

class ResearchPipeline(private val llm: LlmRuntime, private val corpus: Corpus?) {

    data class Settings(
        val maxSources: Int = 6,
        val perArticle: Int = 2,
        val evidenceChars: Int = 450,
        val priorWeight: Double = 0.5,
        /** Score bonus for passages from articles named in the question. */
        val namedBoost: Double = 4.0,
        /** Rough prompt budget in characters (~4 chars/token) for history + new turn. */
        val historyChars: Int = 9_000,
        val sampling: Sampling = Sampling(temperature = 0.4f, maxTokens = 700),
    )

    fun answer(question: String, conversation: Conversation, settings: Settings = Settings()): Flow<ResearchEvent> = flow {
        val decision = Router.decide(question, !conversation.isEmpty)
        emit(ResearchEvent.Routed(decision))

        val sources = if (decision.route == Router.Route.LOOKUP && corpus?.path != null) {
            // A short follow-up ("and its population?") needs the earlier topic to search well.
            val query = if (decision.followUp) "${conversation.lastQuestion.orEmpty()} $question" else question
            retrieve(query, settings)
        } else {
            emptyList()
        }

        val prompt = buildString {
            if (sources.isNotEmpty()) {
                appendLine("Wikipedia excerpts:")
                for (s in sources) {
                    val where = if (s.passage.section.isEmpty()) s.passage.title else "${s.passage.title} — ${s.passage.section}"
                    appendLine("[${s.n}] $where")
                    appendLine(s.evidence)
                    appendLine()
                }
                append("Question: ")
            }
            append(question)
        }

        val turns = history(conversation, settings.historyChars - prompt.length) + ChatTurn("user", prompt)
        val (prefix, rest) = llm.chatPrompt(SYSTEM, turns)
        val answer = StringBuilder()
        llm.complete(prefix, rest, settings.sampling).collect { ev ->
            if (ev is GenEvent.Token) answer.append(ev.text)
            if (ev is GenEvent.Done && ev.stats.error.isEmpty() && !ev.stats.cancelled) {
                conversation.turns += Conversation.Turn(question, prompt, answer.toString().trim())
            }
            emit(ResearchEvent.Llm(ev))
        }
    }

    /**
     * Earlier turns that fit the budget. Recent turns keep their excerpts so the
     * engine can reuse its cache; once that doesn't fit, turns shrink to the bare
     * question, and the oldest ones are dropped.
     */
    private fun history(conversation: Conversation, budget: Int): List<ChatTurn> {
        val full = conversation.turns.flatMap { listOf(ChatTurn("user", it.prompt), ChatTurn("assistant", it.answer)) }
        if (full.sumOf { it.content.length } <= budget) return full
        val compact = ArrayList<ChatTurn>()
        var used = 0
        for (t in conversation.turns.asReversed()) {
            val size = t.question.length + t.answer.length
            if (used + size > budget) break
            compact.add(0, ChatTurn("assistant", t.answer))
            compact.add(0, ChatTurn("user", t.question))
            used += size
        }
        return compact
    }

    private suspend fun FlowCollector<ResearchEvent>.retrieve(question: String, settings: Settings): List<Source> {
        val corpus = corpus ?: return emptyList()
        val words = Evidence.keywords(question)
        if (words.isEmpty()) return emptyList()
        val query = Evidence.ftsQuery(words)
        emit(ResearchEvent.Searching(query))
        val t0 = System.currentTimeMillis()

        // Articles named in the question lead with their intro: that's where definitions live.
        val named = titleMatches(corpus, question).take(3)
        val namedIds = named.map { it.id }.toSet()
        val leads = named.mapNotNull { corpus.chunk(it, 0) }
        val candidates = corpus.search(query, limit = settings.maxSources * 4, priorWeight = settings.priorWeight)
            .map { if (it.article in namedIds) it.copy(score = it.score + settings.namedBoost) else it }
            .sortedByDescending { it.score }

        val perArticle = HashMap<Long, Int>()
        val seen = HashSet<Long>()
        val picked = (leads + candidates).filter { p ->
            val n = perArticle.getOrDefault(p.article, 0)
            if (n >= settings.perArticle || !seen.add(p.chunk)) false else { perArticle[p.article] = n + 1; true }
        }.take(settings.maxSources)

        val sources = picked.mapIndexed { i, p -> Source(i + 1, p, Evidence.compress(p.text, words, settings.evidenceChars)) }
        emit(ResearchEvent.Sources(sources, System.currentTimeMillis() - t0))
        return sources
    }

    /**
     * Articles whose title is a phrase of the question, longest phrases first;
     * words inside a longer match aren't tried again ("Roman Empire" beats "Roman").
     */
    private suspend fun titleMatches(corpus: Corpus, question: String, maxN: Int = 5): List<Article> {
        val toks = Evidence.titleTokens(question)
        val used = BooleanArray(toks.size)
        val found = LinkedHashMap<Long, Article>()
        for (n in minOf(maxN, toks.size) downTo 1) {
            for (i in 0..toks.size - n) {
                if ((i until i + n).any { used[it] }) continue
                val words = toks.subList(i, i + n)
                if (words.all(Evidence::isStop) || (n == 1 && words[0].length < 3)) continue
                val phrase = words.joinToString(" ")
                val candidates = if (phrase.length > 3 && phrase.endsWith("s", ignoreCase = true)) {
                    listOf(phrase, phrase.dropLast(1))  // "tardigrades" -> "tardigrade"
                } else {
                    listOf(phrase)
                }
                val hit = candidates.firstNotNullOfOrNull { corpus.findArticle(it) } ?: continue
                if (found.putIfAbsent(hit.id, hit) == null) (i until i + n).forEach { used[it] = true }
            }
        }
        return found.values.toList()
    }

    companion object {
        // One prompt for every kind of message, so the engine's cached prefix always applies.
        const val SYSTEM =
            "You are Bivouac, an assistant that runs fully offline on a phone.\n" +
                "- Research questions: start with a direct answer, then the key supporting details. When numbered " +
                "Wikipedia excerpts are provided, cite the ones you use like [1] or [2][3] right after the claim. " +
                "Don't cite facts that come only from your own memory, and be careful with exact numbers and dates. " +
                "Ignore excerpts that are irrelevant.\n" +
                "- Personal or casual messages: reply warmly and naturally, like a thoughtful friend. If something is " +
                "ambiguous (for example feeling unwell physically or emotionally), ask a short clarifying question. " +
                "If someone may be in danger, gently encourage them to contact local emergency services or someone they trust.\n" +
                "- If you're not sure about something, say so instead of guessing.\n" +
                "- Be concise. The screen is small: use short paragraphs or lists, never tables or LaTeX."
    }
}
