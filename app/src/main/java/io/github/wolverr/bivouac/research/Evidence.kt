package io.github.wolverr.bivouac.research

/**
 * Turning questions into search queries and passages into short evidence.
 * On a phone CPU every prompt token costs ~30-100 ms, so evidence is cut down
 * to the sentences that overlap the question before the model sees it.
 */
object Evidence {
    private val STOP = """
        a an and are as at be but by can could did do does for from had has have how i if in into is it its
        me my of on or should so than that the their them then there these they this to was we were what when
        where which who whom why will with would you your about tell explain describe give some any much many
        vs versus between difference compare please know
    """.trim().split(Regex("\\s+")).toHashSet()

    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'\\-]*")
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    fun keywords(question: String): List<String> =
        WORD.findAll(question.lowercase())
            .map { it.value.trim('\'', '-') }
            .filter { it.length > 1 && it !in STOP }
            .distinct()
            .toList()

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Quoted OR-query: FTS5 syntax in user text can't break it, and BM25 rewards
     * matching more terms. Words are split on punctuation first: "heat-pump" or
     * "kazakhstan's" would otherwise be phrase queries, which FTS5 rejects when
     * the index has detail=column.
     */
    fun ftsQuery(words: List<String>): String =
        words.flatMap { it.split(NON_WORD) }
            .filter { it.length > 1 && it !in STOP }
            .distinct()
            .joinToString(" OR ") { "\"$it\"" }

    private val TOKEN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'\\-.]*")

    /**
     * Phrases of the question to try as exact article titles, longest first,
     * as index ranges into [titleTokens].
     */
    fun titleTokens(question: String): List<String> = TOKEN.findAll(question).map { it.value.trim('.', '\'', '-') }.toList()

    fun isStop(word: String) = word.lowercase() in STOP

    /**
     * How many of the question words a text mentions. Words are matched by their first
     * five letters, a cheap stand-in for stemming ("volcanoes" matches "volcanic").
     */
    fun overlap(text: String, words: List<String>): Int {
        val low = text.lowercase()
        return words.count { w -> low.contains(if (w.length > 5) w.take(5) else w) }
    }

    /** Keeps the sentences that mention the most question words, in their original order. */
    fun compress(text: String, words: List<String>, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val sentences = text.split(SENTENCE_END)
        val ranked = sentences.withIndex().sortedByDescending { (i, s) ->
            val low = s.lowercase()
            words.count { it in low } + if (i == 0) 0.5 else 0.0
        }
        val keep = HashSet<Int>()
        var total = 0
        for ((i, s) in ranked) {
            if (keep.isNotEmpty() && total + s.length > maxChars) continue
            keep += i
            total += s.length + 1
        }
        return sentences.filterIndexed { i, _ -> i in keep }.joinToString(" ").take(maxChars + 200)
    }
}
