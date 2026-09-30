package io.github.wolverr.bivouac.research

import io.github.wolverr.bivouac.llm.GenEvent
import io.github.wolverr.bivouac.llm.LlmRuntime
import io.github.wolverr.bivouac.llm.Sampling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** A numbered source shown to the model and to the user. */
data class Source(val n: Int, val passage: Passage, val evidence: String)

sealed interface ResearchEvent {
    data class Searching(val query: String) : ResearchEvent
    data class Sources(val sources: List<Source>, val searchMs: Long) : ResearchEvent
    data class Llm(val event: GenEvent) : ResearchEvent
}

class ResearchPipeline(private val llm: LlmRuntime, private val corpus: Corpus?) {

    data class Settings(
        val maxSources: Int = 6,
        val perArticle: Int = 2,
        val evidenceChars: Int = 450,
        val priorWeight: Double = 0.5,
        val sampling: Sampling = Sampling(temperature = 0.4f, maxTokens = 700),
    )

    fun answer(question: String, settings: Settings = Settings()): Flow<ResearchEvent> = flow {
        val sources = if (corpus?.path != null) retrieve(question, settings) else emptyList()
        val system = if (sources.isEmpty()) SYSTEM_NO_SOURCES else SYSTEM_WITH_SOURCES
        val user = buildString {
            if (sources.isNotEmpty()) {
                appendLine("Sources:")
                for (s in sources) {
                    val where = if (s.passage.section.isEmpty()) s.passage.title else "${s.passage.title} — ${s.passage.section}"
                    appendLine("[${s.n}] $where")
                    appendLine(s.evidence)
                    appendLine()
                }
            }
            append("Question: ").append(question)
        }
        val (prefix, rest) = llm.chatPrompt(system, user)
        llm.complete(prefix, rest, settings.sampling).collect { emit(ResearchEvent.Llm(it)) }
    }

    private suspend fun FlowCollector<ResearchEvent>.retrieve(
        question: String,
        settings: Settings,
    ): List<Source> {
        val corpus = corpus ?: return emptyList()
        val words = Evidence.keywords(question)
        if (words.isEmpty()) return emptyList()
        val query = Evidence.ftsQuery(words)
        emit(ResearchEvent.Searching(query))
        val t0 = System.currentTimeMillis()
        val candidates = corpus.search(query, limit = settings.maxSources * 4, priorWeight = settings.priorWeight)

        val perArticle = HashMap<Long, Int>()
        val picked = candidates.filter { p ->
            val n = perArticle.getOrDefault(p.article, 0)
            if (n >= settings.perArticle) false else { perArticle[p.article] = n + 1; true }
        }.take(settings.maxSources)

        val sources = picked.mapIndexed { i, p -> Source(i + 1, p, Evidence.compress(p.text, words, settings.evidenceChars)) }
        emit(ResearchEvent.Sources(sources, System.currentTimeMillis() - t0))
        return sources
    }

    companion object {
        // Kept identical across questions so the engine's prefix cache is reused.
        const val SYSTEM_WITH_SOURCES =
            "You are Bivouac, an offline research assistant running on a phone. You are given numbered " +
                "excerpts from Wikipedia and a question.\n" +
                "- Start with a direct answer, then the key supporting details.\n" +
                "- Cite the excerpts you use with their numbers in brackets, like [1] or [2][3], right after the claim.\n" +
                "- Use your own knowledge to explain and connect facts, but when a fact comes only from your memory " +
                "and not the excerpts, don't cite it, and be careful with exact numbers and dates.\n" +
                "- If the excerpts are irrelevant, ignore them.\n" +
                "- Be concise: a few short paragraphs or a short list. The screen is small: never use tables or LaTeX."

        const val SYSTEM_NO_SOURCES =
            "You are Bivouac, an offline research assistant running on a phone. Answer accurately and concisely. " +
                "Lead with the direct answer, then give the key supporting details. If you are not sure about a " +
                "fact, say so instead of guessing. The screen is small: use short paragraphs or lists, never tables or LaTeX."
    }
}
