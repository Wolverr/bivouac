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
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+|\\s*\\n\\s*")  // infobox lines are sentences too

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

    /**
     * Per [titleTokens] token: does it begin a sentence? There a capital doesn't make a name
     * ("Could Napoleon have met Beethoven? Check their lifetimes" is not about the film "Check").
     */
    fun sentenceStarts(question: String): List<Boolean> {
        val out = ArrayList<Boolean>()
        var prevEnd = 0
        var prev = ""
        for (m in TOKEN.findAll(question)) {
            val gap = question.substring(prevEnd, m.range.first)
            out += out.isEmpty() || gap.any { it in ".!?" } ||
                (prev.endsWith('.') && prev.count { it == '.' } == 1)  // "cats. Dogs", not "U.S. Army"
            prevEnd = m.range.last + 1
            prev = m.value
        }
        return out
    }

    /**
     * The infobox lines that mention a question word, joined with "; ", at most [maxChars].
     * Stricter than [overlap]: words match at a word start ("roughly" must not match "through").
     */
    fun infoboxFacts(infobox: String, words: List<String>, maxChars: Int): String {
        val patterns = words.map { Regex("\\b" + Regex.escape(if (it.length > 5) it.take(5) else it)) }
        val out = ArrayList<String>()
        var used = 0
        for (raw in infobox.lines()) {
            val line = raw.trim().trimStart('•', ' ')
            if (line.isEmpty() || patterns.none { it.containsMatchIn(line.lowercase()) }) continue
            if (used + line.length > maxChars) break
            out += line
            used += line.length + 2
        }
        return out.joinToString("; ")
    }

    fun isStop(word: String) = word.lowercase() in STOP

    /** A token's word parts ("don't" -> don, t): punctuated tokens would be FTS phrase queries. */
    fun parts(word: String): List<String> = word.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    /**
     * How many of the question words a text mentions. Words are matched by their first
     * five letters, a cheap stand-in for stemming ("volcanoes" matches "volcanic").
     */
    fun overlap(text: String, words: List<String>): Int {
        val low = text.lowercase()
        return words.count { w -> low.contains(if (w.length > 5) w.take(5) else w) }
    }

    private val QUALIFIER = Regex("^(.*?)\\s*\\(([^()]*)\\)\\s*$")

    /**
     * "Rome, New York" (or "Capitol Theatre (Rome, New York)") when the question names Rome
     * and New York separately, as in "Rome or New York?": a namesake town, not what was asked.
     * Such articles mention both names, so BM25 ranks them high. Mirrors tools/retrieve_proto.py.
     */
    fun isNamesake(title: String, question: String): Boolean {
        val q = " ${question.lowercase().replace(NON_WORD, " ")} "
        val m = QUALIFIER.find(title)
        val parts = if (m != null) listOf(m.groupValues[1], m.groupValues[2]) else listOf(title)
        return parts.any { part ->
            val names = part.split(",").map { it.lowercase().replace(NON_WORD, " ").trim() }
            names.size == 2 && names.all { it.isNotEmpty() && " $it " in q } &&
                " ${names[0]} ${names[1]} " !in q  // asked about "Rome, New York" itself
        }
    }

    /** An entry of a list article: its name, the other cells, and whether a rank column led it. */
    data class ListRow(val name: String, val cells: List<String>, val ranked: Boolean)

    private val LIST_SKIP_CELL = Regex("^(:?-+:?|name|names|image|picture|photo|rank|no\\.?|#)$", RegexOption.IGNORE_CASE)
    private val CITATION = Regex("\\[\\s*\\d+\\s*]")
    private val HAS_LETTER = Regex("\\p{L}")
    private val ITEM_NAME_END = Regex("\\s[–—-]\\s|,|\\(|:")
    private val PADDING = Regex("[ \\t]{2,}")

    /**
     * The entries of a list article chunk: markdown table rows ("| Pergamon Museum | Museum Island | ... |";
     * a leading rank column like "1" or "=4" is dropped and sets [ListRow.ranked]) and bullet items
     * ("- Café Procope – the oldest ..."). Mirrors list_rows() in tools/retrieve_proto.py.
     */
    fun listRows(text: String): List<ListRow> {
        val out = ArrayList<ListRow>()
        for (raw in text.lines()) {
            val line = raw.replace(CITATION, "").trim()
            var ranked = false
            val name: String
            val rest: List<String>
            if (line.startsWith("|")) {
                val cells = line.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                while (cells.isNotEmpty() && !HAS_LETTER.containsMatchIn(cells[0])) {
                    cells.removeAt(0)
                    ranked = true
                }
                if (cells.isEmpty() || LIST_SKIP_CELL.matches(cells[0])) continue
                name = cells[0]
                rest = cells.drop(1)
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                val item = line.drop(2).trim()
                name = item.split(ITEM_NAME_END, limit = 2)[0].trim()
                rest = listOf(item.drop(name.length).trim(' ', ',', '–', '—', '-', ':'))
            } else {
                continue
            }
            if (name.length in 3..80 && HAS_LETTER.containsMatchIn(name)) out += ListRow(name, rest.filter { it.isNotEmpty() }, ranked)
        }
        return out
    }

    /** Keeps the sentences that mention the most question words, in their original order. */
    fun compress(passage: String, words: List<String>, maxChars: Int): String {
        val text = passage.replace(PADDING, " ")  // markdown tables are padded with long runs of spaces
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
