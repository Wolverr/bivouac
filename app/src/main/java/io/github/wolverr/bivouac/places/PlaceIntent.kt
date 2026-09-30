package io.github.wolverr.bivouac.places

/**
 * "Vegetarian restaurants near the Duomo in Florence", "pharmacy near me",
 * "museums in Berlin": a kind of place plus a location. Returns null for
 * anything else, so ordinary questions never touch the places pack.
 */
data class PlaceIntent(
    /** Words right before the place noun, synonym-expanded ("vegetarian" -> vegetarian, vegan). */
    val modifierWords: List<String>,
    /** The place noun, synonym-expanded ("coffee" -> cafe, coffee). */
    val nounWords: List<String>,
    /** Where to look, as written ("the Duomo in Florence"); null means the phone's own location. */
    val location: String?,
) {
    val nearMe: Boolean get() = location == null
    val categoryWords: List<String> get() = (modifierWords + nounWords).distinct()

    companion object {
        // Words people use that don't appear in Overture's category names.
        private val SYNONYMS = mapOf(
            "vegetarian" to listOf("vegetarian", "vegan"),
            "veggie" to listOf("vegetarian", "vegan"),
            "coffee" to listOf("cafe", "coffee"),
            "chemist" to listOf("pharmacy", "drugstore"),
            "drugstore" to listOf("pharmacy", "drugstore"),
            "pharmacies" to listOf("pharmacy", "drugstore"),
            "restroom" to listOf("toilet"),
            "bathroom" to listOf("toilet"),
            "toilets" to listOf("toilet"),
            "wc" to listOf("toilet"),
            "grocery" to listOf("grocery", "supermarket"),
            "groceries" to listOf("grocery", "supermarket"),
            "supermarket" to listOf("grocery", "supermarket"),
            "cash" to listOf("atm"),
            "doctor" to listOf("doctor", "clinic", "medical"),
            "er" to listOf("emergency", "hospital"),
            "accommodation" to listOf("hotel", "hostel", "guest"),
            "stay" to listOf("hotel", "hostel", "guest"),
            "eat" to listOf("restaurant"),
            "food" to listOf("restaurant"),
            "petrol" to listOf("gas"),
            "fuel" to listOf("gas"),
            "wifi" to listOf("internet", "cafe"),
        )

        /** Questions about what a place is or means belong to Wikipedia, not the map. */
        private val KNOWLEDGE = Regex("\\b(must[- ]see|famous|known for|history|worth (?:a )?visit|most important)\\b")
        private val NEAR_ME = Regex("\\b(near me|nearby|near here|around here|close by|closest|nearest|around me)\\b")
        private val LOCATION = Regex("\\b(?:near|around|close to|in|at|by|next to)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$", RegexOption.IGNORE_CASE)
        private val WORD = Regex("[\\p{L}][\\p{L}'\\-]*")

        /**
         * @param placeNouns the last word of every category in the pack ("restaurant",
         *   "pharmacy", "museum"...), which is what makes a question about places.
         */
        fun parse(question: String, placeNouns: Set<String>): PlaceIntent? {
            val q = question.trim()
            val lower = q.lowercase()
            val words = WORD.findAll(lower).map { it.value }.toList()
            val singular = words.map(::singularize)
            val nounAt = singular.indexOfFirst { it in placeNouns || SYNONYMS[it]?.any { s -> s in placeNouns } == true }
            if (nounAt < 0) return null

            if (KNOWLEDGE.containsMatchIn(lower)) return null
            val nearMe = NEAR_ME.containsMatchIn(lower)
            val loc = if (nearMe) null else LOCATION.find(q.substring(textIndexAfter(q, words, nounAt)))?.groupValues?.get(1)
            if (!nearMe && loc.isNullOrBlank()) return null

            // Up to two modifiers right before the noun ("cheap vegan restaurants").
            val modifiers = (maxOf(0, nounAt - 2) until nounAt).map { singular[it] }
                .flatMap { SYNONYMS[it] ?: listOf(it) }
                .distinct()
            val noun = SYNONYMS[singular[nounAt]] ?: listOf(singular[nounAt])
            return PlaceIntent(modifiers, noun, loc?.trim())
        }

        /** Character offset just past the place noun, so the location is looked for after it. */
        private fun textIndexAfter(q: String, words: List<String>, idx: Int): Int {
            var from = 0
            for (i in 0..idx) {
                val at = q.lowercase().indexOf(words[i], from)
                if (at < 0) return 0
                from = at + words[i].length
            }
            return from
        }

        fun singularize(w: String): String = when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") && w.length > 3 -> w.dropLast(1)
            else -> w
        }
    }
}
