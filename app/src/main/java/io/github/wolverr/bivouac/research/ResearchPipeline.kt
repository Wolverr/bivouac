package io.github.wolverr.bivouac.research

import io.github.wolverr.bivouac.llm.ChatTurn
import io.github.wolverr.bivouac.llm.GenEvent
import io.github.wolverr.bivouac.llm.LlmRuntime
import io.github.wolverr.bivouac.llm.Sampling
import io.github.wolverr.bivouac.places.GeoPoint
import io.github.wolverr.bivouac.places.Place
import io.github.wolverr.bivouac.places.PlaceIntent
import io.github.wolverr.bivouac.places.Places
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** A numbered source shown to the model and to the user. */
data class Source(val n: Int, val passage: Passage, val evidence: String)

sealed interface ResearchEvent {
    data class Routed(val decision: Router.Decision) : ResearchEvent
    data class Searching(val query: String) : ResearchEvent
    data class Sources(val sources: List<Source>, val searchMs: Long) : ResearchEvent
    /** center == null: the location couldn't be found (or GPS isn't available). */
    data class PlacesFound(val intent: PlaceIntent, val center: GeoPoint?, val places: List<Place>) : ResearchEvent
    data class Llm(val event: GenEvent) : ResearchEvent
    /** "Think harder": tokens of (hidden) reasoning so far, out of the budget. */
    data class Thinking(val tokens: Int, val budget: Int) : ResearchEvent
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

    /** Forgets the last exchange if it was about [question] (before asking it again). */
    fun dropLast(question: String) {
        if (turns.lastOrNull()?.question == question) turns.removeAt(turns.lastIndex)
    }
}

class ResearchPipeline(
    private val llm: LlmRuntime,
    /**
     * One prompt for every kind of message, so the engine's cached prefix always applies.
     * Lives in assets/prompts/ (system.txt, or the model's catalog "prompt"), shared with the
     * desktop harness (tools/answer_proto.py). Set when a model is loaded.
     */
    var system: String,
    private val corpus: Corpus?,
    private val places: Places? = null,
    /** The phone's position for "near me" questions; null when unavailable or not permitted. */
    private val locate: suspend () -> GeoPoint? = { null },
) {

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
        /**
         * "Think harder": tokens of reasoning before the engine makes the model answer. 1,024 kept
         * nearly all of unbounded thinking's gain on the reasoning questions (4B 4.2 -> 8.7, 9B 3.5 -> 8.8
         * of 10) at ~1,100 tokens per answer; 512 lost much of it for the 4B (docs/ROADMAP.md).
         */
        val thinkBudget: Int = 1024,
    ) {
        companion object {
            /**
             * Fits the prompt to the loaded context: the answer (700) and system prompt
             * (~400 tokens) come first, the rest at ~3 chars/token goes to history + excerpts.
             * 4096 gives the defaults; small contexts also read fewer sources.
             */
            fun forContext(nCtx: Int) = Settings(
                maxSources = if (nCtx < 3072) 3 else 6,
                historyChars = maxOf(0, (nCtx - 1100) * 3),
                thinkBudget = (nCtx - 1700).coerceIn(256, 1024),
            )
        }
    }

    /**
     * [thinkHarder]: let the model reason first (hidden; up to [Settings.thinkBudget] tokens, so a
     * few minutes on a phone). It fixes most multi-step reasoning mistakes.
     */
    fun answer(
        question: String,
        conversation: Conversation,
        settings: Settings = Settings.forContext(llm.contextSize),
        thinkHarder: Boolean = false,
    ): Flow<ResearchEvent> = flow {
        val decision = Router.decide(question, !conversation.isEmpty)
        emit(ResearchEvent.Routed(decision))

        val placeIntent = places?.takeIf { it.path != null }?.let { PlaceIntent.parse(question, it.placeNouns) }
        val nearby = if (placeIntent != null) findPlaces(placeIntent) else emptyList()

        val sources = if (placeIntent == null && decision.route == Router.Route.LOOKUP && corpus?.path != null) {
            // A short follow-up ("and its population?") needs the earlier topic to search well.
            val query = if (decision.followUp) "${conversation.lastQuestion.orEmpty()} $question" else question
            retrieve(query, settings)
        } else {
            emptyList()
        }

        val prompt = buildString {
            if (nearby.isNotEmpty()) {
                // Place instructions travel with the list: in the system prompt, small models
                // leaked them ("cuisine notes") into unrelated answers.
                appendLine(placesHeader(placeIntent!!))
                nearby.forEachIndexed { i, p ->
                    append("[P${i + 1}] ${p.displayName} — ${p.categoryLabel} — ${formatDistance(p.distanceM)}")
                    listOfNotNull(p.street, p.locality).joinToString(", ").takeIf { it.isNotEmpty() }?.let { append(" — $it") }
                    p.phone?.let { append(" — $it") }
                    appendLine()
                }
                appendLine()
                append("Question: ")
            } else if (sources.isNotEmpty()) {
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

        // Thinking needs room in the context: the reasoning (~3 chars/token) comes out of the history.
        val budget = settings.thinkBudget
        val promptChars = settings.historyChars - if (thinkHarder) budget * 3 else 0
        val turns = history(conversation, promptChars - prompt.length) + ChatTurn("user", prompt)
        val (prefix, rest) = llm.chatPrompt(system, turns, thinking = thinkHarder)
        val sampling = if (thinkHarder) {
            settings.sampling.copy(maxTokens = settings.sampling.maxTokens + budget, thinkBudget = budget)
        } else {
            settings.sampling
        }
        val answer = StringBuilder()
        val filter = ThinkFilter(startInThink = thinkHarder && rest.trimEnd().endsWith("<think>"))
        var hidden = 0
        llm.complete(prefix, rest, sampling).collect { ev ->
            if (ev is GenEvent.Token) {
                val visible = filter.feed(ev.text)
                if (visible.isNotEmpty()) {
                    answer.append(visible)
                    emit(ResearchEvent.Llm(GenEvent.Token(visible)))
                } else if (thinkHarder && answer.isEmpty() && ++hidden % 8 == 0) {
                    emit(ResearchEvent.Thinking(minOf(hidden, budget), budget))
                }
                return@collect
            }
            if (ev is GenEvent.Done) {
                filter.flush().takeIf { it.isNotEmpty() }?.let {
                    answer.append(it)
                    emit(ResearchEvent.Llm(GenEvent.Token(it)))
                }
                if (ev.stats.error.isEmpty() && !ev.stats.cancelled) {
                    conversation.turns += Conversation.Turn(question, prompt, answer.toString().trim())
                }
            }
            emit(ResearchEvent.Llm(ev))
        }
    }

    private suspend fun FlowCollector<ResearchEvent>.findPlaces(intent: PlaceIntent): List<Place> {
        val places = places ?: return emptyList()
        emit(ResearchEvent.Searching("places"))
        val center = if (intent.nearMe) locate() else places.geocode(intent.location!!, corpus?.takeIf { it.path != null })
        val found = if (center != null) places.nearby(center, places.categoriesFor(intent)) else emptyList()
        emit(ResearchEvent.PlacesFound(intent, center, found))
        return found
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
        val named = (listOfNotNull(listArticle(corpus, question)) + titleMatches(corpus, question))
            .distinctBy { it.id }.take(3)
        val namedIds = named.map { it.id }.toSet()
        val seen = HashSet<Long>()
        val facts = HashMap<Long, String>()
        val whole = HashMap<Long, String>()  // chunk -> finished evidence of an expanded list article
        val expanded = HashSet<Long>()  // those articles: further chunks would repeat rows
        val sightsQuestion = isSightsQuestion(question)
        val leads = named.flatMap { article ->
            if (article.title.startsWith("List of")) {
                val entries = listEntries(corpus, article)
                val first = if (entries.isNotEmpty()) corpus.chunk(article, 0) else null
                if (first != null) {  // its most viewed entries, not its first rows
                    whole[first.chunk] = entries.joinToString("\n")
                    expanded += article.id
                    return@flatMap listOf(first.copy(section = LIST_SECTION))
                }
            }
            val sights = if (sightsQuestion) sightsEvidence(corpus, article) else null
            sights?.let { (p, text) -> whole[p.chunk] = text }  // a city's sights list, whole
            listOfNotNull(lead(corpus, article, words, seen, facts), sights?.first?.copy(section = SIGHTS_SECTION))
        }
        val candidates = corpus.search(query, limit = settings.maxSources * 4, priorWeight = settings.priorWeight)
            .map { if (it.article in namedIds) it.copy(score = it.score + settings.namedBoost) else it }
            .sortedByDescending { it.score }

        val perArticle = HashMap<Long, Int>()
        // Relevance gate: a passage that shares few of the question's words is noise, and
        // small models tend to answer "the excerpts don't cover this" instead of answering.
        val needed = (words.size + 1) / 2
        val relevant = candidates.filter {
            it.article !in expanded && Evidence.overlap("${it.title} ${it.section} ${it.text}", words) >= needed &&
                !Evidence.isNamesake(it.title, question)
        }
        val picked = (leads + relevant).filter { p ->
            val n = perArticle.getOrDefault(p.article, 0)
            if (n >= settings.perArticle || !seen.add(p.chunk)) false else { perArticle[p.article] = n + 1; true }
        }.take(settings.maxSources)

        val sources = picked.mapIndexed { i, p ->
            whole[p.chunk]?.let { return@mapIndexed Source(i + 1, p, it) }
            val f = facts[p.chunk].orEmpty()
            val evidence = Evidence.compress(p.text, words, settings.evidenceChars - f.length)
            Source(i + 1, p, if (f.isEmpty()) evidence else "$f\n$evidence")
        }
        emit(ResearchEvent.Sources(sources, System.currentTimeMillis() - t0))
        return sources
    }

    /**
     * The [k] most viewed entries of a list article that are articles themselves, as
     * "Name: cell — cell" lines. "Must-see museums in Berlin" wants the Pergamon Museum, not the
     * first rows alphabetically, and one excerpt of a padded table is one row. Ranked lists
     * ("tallest buildings", a rank column) keep their order. Mirrors tools/retrieve_proto.py.
     */
    private suspend fun listEntries(corpus: Corpus, list: Article, k: Int = 8, maxChunks: Int = 80, maxNames: Int = 200): List<String> {
        class Entry(val prior: Double, val order: Int, val line: String)
        val found = LinkedHashMap<Long, Entry>()
        val names = HashSet<String>()
        var ranked = 0
        for (i in 0 until minOf(list.n_chunks, maxChunks)) {
            val chunk = corpus.chunk(list, i) ?: continue
            for (row in Evidence.listRows(chunk.text)) {
                if (row.name in names || names.size >= maxNames) continue
                names += row.name
                val a = corpus.findArticle(row.name) ?: continue
                if (a.id in found) continue
                val line = if (row.cells.isEmpty()) row.name else "${row.name}: ${row.cells.joinToString(" — ")}"
                found[a.id] = Entry(a.prior, found.size, line.take(LIST_LINE_CHARS))
                if (row.ranked) ranked++
            }
        }
        val entries = found.values.toList()
        val ordered = if (ranked * 2 >= entries.size) entries else entries.sortedByDescending { it.prior }
        return ordered.take(k).map { it.line }
    }

    /**
     * A named article's intro, led by its relevant infobox lines (into [facts], by chunk). The
     * infobox chunk alone is cut before what matters: Napoleon's never reaches his birth and
     * death dates. Infobox lines count when they mention a question word other than the
     * article's own name; the infobox chunk goes into [skip]. Mirrors tools/answer_proto.py.
     */
    private suspend fun lead(corpus: Corpus, article: Article, words: List<String>, skip: MutableSet<Long>, facts: MutableMap<Long, String>): Passage? {
        val first = corpus.chunk(article, 0) ?: return null
        if (first.section != "Infobox") return first
        val intro = corpus.chunk(article, 1)?.takeIf { it.section != "Infobox" } ?: return first
        skip += first.chunk
        val own = Evidence.keywords(article.title).toSet()
        Evidence.infoboxFacts(first.text, words.filterNot { it in own }, INFOBOX_FACT_CHARS)
            .takeIf { it.isNotEmpty() }?.let { facts[intro.chunk] = it }
        return intro
    }

    /**
     * Articles whose title is a phrase of the question, longest phrases first;
     * words inside a longer match aren't tried again ("Roman Empire" beats "Roman").
     */
    private suspend fun titleMatches(corpus: Corpus, question: String, maxN: Int = 5): List<Article> {
        val toks = Evidence.titleTokens(question)
        val starts = Evidence.sentenceStarts(question)
        val used = BooleanArray(toks.size)
        val found = LinkedHashMap<Long, Article>()
        val plain = HashSet<Long>()  // everyday-word matches ("North", "Time"), dropped when a name matched
        var namedHit = false
        for (n in minOf(maxN, toks.size) downTo 1) {
            for (i in 0..toks.size - n) {
                if ((i until i + n).any { used[it] }) continue
                val words = toks.subList(i, i + n)
                if (words.all(Evidence::isStop) || (n == 1 && words[0].length < 3)) continue
                val phrase = words.joinToString(" ")
                val candidates = when {
                    phrase.endsWith("'s", ignoreCase = true) -> listOf(phrase.dropLast(2))  // "Ethereum's" -> "Ethereum"
                    phrase.length > 3 && phrase.endsWith("s", ignoreCase = true) ->
                        listOf(phrase, phrase.dropLast(1))  // "tardigrades" -> "tardigrade"
                    else -> listOf(phrase)
                }
                for (c in candidates) {
                    val hit = corpus.findArticle(c, question) ?: continue
                    if (hit.id in found) continue
                    val common = everyday(corpus, words, starts.subList(i, i + n))
                    val obscure = hit.prior < COMMON_MIN_PRIOR
                    if (common && obscure) continue
                    // A name and the lowercase noun after it: "Soviet Union collapse" -> "Collapse of the
                    // Soviet Union", "UK parliament" -> "Parliament of the UK"; ahead of the bare name.
                    val k = i + n
                    if (k < toks.size && !used[k] && toks[k].length > 3 && toks[k].any(Char::isLetter) && toks[k] == toks[k].lowercase() &&
                        !Evidence.isStop(toks[k]) && words.indices.any { isNamed(words[it], starts[i + it]) }
                    ) {
                        for (form in listOf("${toks[k]} of the $c", "${toks[k]} of $c")) {
                            val about = corpus.findArticle(form) ?: continue
                            if (about.id in found) continue
                            found[about.id] = about
                            used[k] = true
                            break
                        }
                    }
                    found[hit.id] = hit
                    // Also a lone lowercase word naming something obscure ("whereabouts" -> an album).
                    val named = words.indices.any { isNamed(words[it], starts[i + it]) }
                    namedHit = namedHit || named
                    if (common || (n == 1 && obscure && !named)) plain += hit.id
                    (i until i + n).forEach { used[it] = true }
                    break
                }
            }
        }
        // "Which is further north: Rome or New York?" is about Rome and New York, not "North". Only
        // names push these out: "why do we have seasons" keeps "Season", and "is a snake bite
        // venomous" keeps "Snakebite" next to "Venom".
        return if (namedHit) found.values.filterNot { it.id in plain } else found.values.toList()
    }

    /**
     * "museums in Berlin", "tallest buildings in Dubai": Wikipedia's list article
     * ("List of museums in Berlin") answers these far better than any single
     * museum's page. Tries the longest noun phrase and place first; aliases catch
     * near-misses ("List of museums and galleries in Berlin"). Mirrors tools/retrieve_proto.py.
     */
    private suspend fun listArticle(corpus: Corpus, question: String): Article? {
        if (EXPLAIN_QUERY.containsMatchIn(question)) return null  // "what causes earthquakes in Japan" isn't a list
        for (m in LIST_QUERY.findAll(question)) {
            val nouns = m.groupValues[1].split(WHITESPACE).filterNot(Evidence::isStop)
            val place = m.groupValues[3].split(WHITESPACE).takeWhile { it.lowercase() !in PLACE_STOP }.take(4)
            if (nouns.isEmpty() || place.isEmpty() || !place[0][0].isUpperCase()) continue
            for (n in nouns.size downTo 1) {
                val what = nouns.takeLast(n).joinToString(" ").lowercase()
                for (k in place.size downTo 1) {
                    val where = place.take(k).joinToString(" ")
                    for (prep in listOf("in", "of")) corpus.findArticle("List of $what $prep $where")?.let { return it }
                }
            }
            // "must-see sights in Barcelona": Wikipedia's words are "tourist attractions" / "Tourism in X".
            if (nouns.last().lowercase().trimEnd('s') in SIGHT_NOUNS) {
                for (k in place.size downTo 1) {
                    val where = place.take(k).joinToString(" ")
                    for (title in listOf("List of tourist attractions in $where", "Tourism in $where")) {
                        corpus.findArticle(title)?.takeIf { it.title.startsWith("List of") || it.title.startsWith("Tourism") }
                            ?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** "must-see sights in Barcelona", "landmarks of Rome". Mirrors tools/retrieve_proto.py. */
    private fun isSightsQuestion(question: String): Boolean =
        question.contains("must-see", ignoreCase = true) ||
            Regex("\\p{L}+").findAll(question).any { it.value.lowercase().trimEnd('s') in SIGHT_NOUNS }

    /**
     * A city's sights sections as one source: the start of their opening prose ("The best-known
     * site of Florence is ... the Duomo") and the bullet lines of the first few chunks ("Minor
     * basilica of Sagrada Família, the symbol of Barcelona."). Compressing to the question's words
     * kept "Barcelona is home to many World Heritage Sites:" and dropped the list after it.
     * Mirrors tools/retrieve_proto.py sights_evidence.
     */
    private suspend fun sightsEvidence(corpus: Corpus, article: Article): Pair<Passage, String>? {
        val chunks = (0 until article.n_chunks).mapNotNull { corpus.chunk(article, it) }
            .filter { SIGHTS_SECTION_RE.containsMatchIn(it.section) }
        if (chunks.isEmpty()) return null
        var intro = chunks[0].text.lines().filter { it.isNotBlank() && !it.startsWith("- ") && !it.startsWith("|") }
            .joinToString(" ").take(SIGHTS_PROSE_CHARS)
        if (intro.length == SIGHTS_PROSE_CHARS && ". " in intro) intro = intro.substring(0, intro.lastIndexOf(". ") + 1)
        val lines = chunks.take(SIGHTS_BULLET_CHUNKS)
            .flatMap { c -> c.text.lines().filter { it.startsWith("- ") }.map { it.drop(2).trim().take(LIST_LINE_CHARS) } }
            .take(SIGHTS_LINES)
        val text = (listOf(intro) + lines).filter { it.isNotEmpty() }.joinToString("\n")
        return if (text.isEmpty()) null else chunks[0] to text
    }

    /**
     * No name-like word ("GPS", "mRNA", "Germany" mid-sentence) and every content word
     * common. Such a title counts only for a real topic ("Season", "Age of the universe"),
     * not an obscure one ("Determine", "Broad Strokes"). Mirrors tools/retrieve_proto.py.
     */
    private suspend fun everyday(corpus: Corpus, words: List<String>, starts: List<Boolean>): Boolean {
        if (words.indices.any { isNamed(words[it], starts[it]) }) return false
        return words.filterNot(Evidence::isStop).all { corpus.docFreq(it, COMMON_DF + 1) > COMMON_DF }
    }

    /** Written as a name: capitalized mid-sentence, or any inner capital ("GPS", "mRNA"). */
    private fun isNamed(word: String, sentenceStart: Boolean) =
        word.drop(1).any(Char::isUpperCase) || (word.first().isUpperCase() && !sentenceStart)

    companion object {
        /** Mirrored in tools/answer_proto.py. */
        const val PLACES_HEADER = "Places from the offline map (straight-line distances). Recommend from this list " +
            "using the labels like [P1] and say how far each is; never invent places that aren't listed."
        private const val DIET_NOTE = "The list may be incomplete, so also point out places whose cuisine usually suits this diet."

        /** The diet sentence only when a diet was asked for: otherwise small models add diet notes to pharmacies. */
        fun placesHeader(intent: PlaceIntent): String =
            if (intent.categoryWords.any { it in PlaceIntent.DIET_WORDS }) "$PLACES_HEADER $DIET_NOTE" else PLACES_HEADER
        private val LIST_QUERY = Regex(
            "((?:[\\p{L}-]+\\s+){0,2}[\\p{L}-]+s)\\s+(in|of|near|around)\\s+(?:the\\s+)?([\\p{L}][\\p{L}'\\- ]*)",
        )
        private val WHITESPACE = Regex("\\s+")
        private val EXPLAIN_QUERY = Regex("^\\s*(why|how)\\b|\\bcaus", RegexOption.IGNORE_CASE)
        private val SIGHT_NOUNS = setOf("sight", "attraction", "landmark", "monument")
        private val PLACE_STOP = setOf("and", "or", "for", "with", "that", "which", "what", "to", "is", "are")
        private const val COMMON_DF = 20_000
        private const val INFOBOX_FACT_CHARS = 200
        private const val LIST_LINE_CHARS = 160
        /** Section label of an expanded list article's source; mirrored in tools/answer_proto.py. */
        const val LIST_SECTION = "Most viewed entries"
        /** Section label of a city's sights source; mirrored in tools/answer_proto.py. */
        const val SIGHTS_SECTION = "Main sights"
        /** Any level of the heading path: "Main sights", "Tourism > Main attractions > Old Town", "Heritage > Important monuments". */
        private val SIGHTS_SECTION_RE = Regex(
            "(^|> )[^>]*\\b(sights|attractions|landmarks|monuments|points of interest)\\b", RegexOption.IGNORE_CASE,
        )
        private const val SIGHTS_PROSE_CHARS = 600
        private const val SIGHTS_LINES = 8
        private const val SIGHTS_BULLET_CHUNKS = 3  // bullets further down are subsections (a square's history)
        private val COMMON_MIN_PRIOR = kotlin.math.ln(1.0 + 3_000)  // prior = ln(1 + monthly views)

        fun formatDistance(m: Double): String =
            if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(Locale.US, "%.1f km", m / 1000)
    }
}
