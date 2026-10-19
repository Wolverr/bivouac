package io.github.wolverr.bivouac.places

import io.github.wolverr.bivouac.llm.NativeEngine
import io.github.wolverr.bivouac.research.Article
import io.github.wolverr.bivouac.research.Corpus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double, val label: String)

data class Place(
    val name: String,
    val nameEn: String?,
    val category: String,
    val lat: Double,
    val lon: Double,
    val street: String?,
    val locality: String?,
    val phone: String?,
    val confidence: Double,
    val distanceM: Double,
) {
    val displayName: String get() = if (nameEn != null) "$name ($nameEn)" else name
    val categoryLabel: String get() = category.replace('_', ' ')
}

/**
 * The offline places pack (places.db built by tools/build_places.py). [knownPlaces]: the
 * names the pack alone gets wrong, with the Wikipedia article's coordinates
 * (assets/place_names.tsv, tools/build_place_names.py), see [parseKnownPlaces].
 */
class Places(private val knownPlaces: Map<String, GeoPoint> = emptyMap()) {
    private val json = Json { ignoreUnknownKeys = true }
    private var categories: List<Pair<Int, String>> = emptyList()

    /**
     * Last word of every common category ("restaurant", "pharmacy"): what makes a question
     * about places. For generic endings the word before counts too: grocery_store -> "grocery".
     */
    var placeNouns: Set<String> = emptySet()
        private set

    var path: String? = null
        private set

    suspend fun open(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val err = NativeEngine.placesOpen(path.toByteArray()).decodeToString()
        if (err.isNotEmpty()) return@withContext Result.failure(IllegalStateException(err))
        val rows = query("SELECT id, name, n FROM category")
        categories = rows.map { (it[0] as Number).toInt() to it[1] as String }
        placeNouns = rows.filter { (it[2] as Number).toInt() >= 200 }
            .flatMap { placeNounsOf(it[1] as String) }
            .toSet()
        this@Places.path = path
        Result.success(Unit)
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        NativeEngine.placesClose()
        path = null
    }

    suspend fun meta(key: String): String =
        query("SELECT value FROM meta WHERE key = ?1", key).firstOrNull()?.get(0) as? String ?: ""

    /**
     * Categories for a request: the modifiers when they name categories themselves
     * ("vegetarian restaurants" -> vegetarian_restaurant, vegan_restaurant), else the noun.
     */
    fun categoriesFor(intent: PlaceIntent): Set<Int> =
        categoryIds(intent.modifierWords).ifEmpty { categoryIds(intent.nounWords) }

    /** Category ids whose name matches the keywords, e.g. "vegan" -> vegan_restaurant, vegan_bakery. */
    fun categoryIds(keywords: List<String>): Set<Int> {
        val out = HashSet<Int>()
        for (k in keywords) {
            for ((id, name) in categories) {
                val parts = name.split('_')
                if (k in parts || (k.length >= 5 && name.contains(k))) out += id
            }
        }
        return out
    }

    /**
     * Where a place phrase is: an article with coordinates in the Wikipedia pack
     * ("Florence Cathedral", "Shinjuku Station"), else the centroid of a locality
     * named that way in the places data ("Firenze").
     */
    suspend fun geocode(phrase: String, corpus: Corpus?): GeoPoint? {
        // "Shinjuku Station that is open late" -> also try "Shinjuku Station".
        val core = phrase.split(CLAUSE, limit = 2)[0].trim()
        val parts = core.split(Regex("\\s+in\\s+|,\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = buildList {
            add(phrase)
            add(core)
            parts.reversed().forEach(::add)
        }.distinct()
        if (corpus != null && parts.size >= 2) landmark(corpus, parts[0], parts.drop(1))?.let { return it }
        for (c in candidates) {
            articleCoords(corpus ?: break, corpus.findArticle(c) ?: continue)?.let { return it }
        }
        for (c in candidates) {
            // Overture uses local names: "Florence" alone finds Florence, Alabama; the
            // article's other names add "firenze", and the biggest locality wins.
            val names = (listOf(c.lowercase()) + (corpus?.findArticle(c)?.let { corpus.otherNames(it) } ?: emptyList()))
                .flatMap { listOf(it, it.substringBefore(",").trim()) }  // "roma, italy" -> also "roma"
                .distinct()
            // Without the wiki pack: "rome" is Rome, Italy (not the bigger Rome, Georgia), "munich" is München.
            names.firstNotNullOfOrNull { knownPlaces[it] }?.let { return it }
            val row = query(
                "SELECT lat, lon, name FROM locality WHERE name IN (${names.indices.joinToString(",") { "?${it + 1}" }}) " +
                    "ORDER BY n DESC LIMIT 1",
                *names.toTypedArray(),
            ).firstOrNull() ?: continue
            return GeoPoint((row[0] as Number).toDouble(), (row[1] as Number).toDouble(), c)
        }
        return null
    }

    /**
     * The article's coordinates: the pack's coord table (many landmarks keep theirs outside
     * the infobox, so FineWiki dropped them), else its infobox coordinates line.
     */
    private suspend fun articleCoords(corpus: Corpus, article: Article, label: String = article.title): GeoPoint? {
        if (article.lat != null && article.lon != null) return GeoPoint(article.lat, article.lon, label)
        val box = corpus.chunk(article, 0)?.takeIf { it.section == "Infobox" }?.text ?: return null
        return parseCoordinates(box)?.let { GeoPoint(it.first, it.second, label) }
    }

    /**
     * "Shibuya Crossing" in "Tokyo": the landmark's own coordinates, else the first part of
     * its infobox Location line ("Shibuya, Tokyo, Japan" -> Shibuya), never the city centre
     * a few km away. Its infobox or intro must mention the city, so a generic "old town" in
     * Prague can't match another town's article. A bare "Duomo" (an article about the word)
     * also tries "Florence Duomo", "Duomo of Florence", "Grand Bazaar, Istanbul".
     * Mirrors tools/places_proto.py.
     */
    private suspend fun landmark(corpus: Corpus, place: String, where: List<String>): GeoPoint? {
        val city = where.first()
        for (title in listOf(place, "$city $place", "$place of $city", "$place, $city", "$place ($city)")) {
            val article = corpus.findArticle(title) ?: continue
            val head = (0 until minOf(2, article.n_chunks)).mapNotNull { corpus.chunk(article, it) }
            if (where.none { w -> head.any { it.text.contains(w, ignoreCase = true) } }) continue
            articleCoords(corpus, article)?.let { return it }
            val box = head.firstOrNull()?.takeIf { it.section == "Infobox" }?.text ?: continue
            val area = locationArea(box)?.takeIf { a -> where.none { it.equals(a, ignoreCase = true) } } ?: continue
            corpus.findArticle(area)?.let { sub ->
                articleCoords(corpus, sub, "${sub.title} (location of ${article.title})")?.let { return it }
            }
            val row = query(
                "SELECT lat, lon, name FROM locality WHERE name IN (?1, ?2) ORDER BY n DESC LIMIT 1",
                area.lowercase(), area.lowercase() + " city",
            ).firstOrNull() ?: continue
            return GeoPoint((row[0] as Number).toDouble(), (row[1] as Number).toDouble(), "$area (location of ${article.title})")
        }
        return null
    }

    /**
     * Places of the given categories around a point, nearest first (confident places
     * win ties). Widens the radius when the neighborhood has too few matches.
     */
    suspend fun nearby(center: GeoPoint, categoryIds: Set<Int>, limit: Int = 12): List<Place> {
        if (categoryIds.isEmpty()) return emptyList()
        var radius = 800.0
        var found: List<Place> = emptyList()
        while (radius <= 12_000) {
            found = within(center, radius, categoryIds)
            if (found.size >= limit / 2) break
            radius *= 2.5
        }
        return found.sortedBy {
            it.distanceM * (1.6 - it.confidence) * (if (namedAsCategory(it)) NAME_HINT_FACTOR else 1.0) *
                (if (restricted(it)) RESTRICTED_FACTOR else 1.0)
        }.take(limit)
    }

    /**
     * The name says what the place is ("Farmacia del Duomo", "龍生堂薬局"). Overture tags law firms,
     * izakayas and phone shops in Tokyo as pharmacies (all at confidence 0.85, which elsewhere
     * holds good data), so for health places a telling name ranks first. Mirrors tools/places_proto.py.
     */
    private fun namedAsCategory(p: Place): Boolean =
        NAME_HINTS.any { (cat, re) -> cat in p.category && re.containsMatchIn(p.name) }

    /**
     * A hospital the public can't walk into: Rome's two military hospitals ranked first for "a hospital
     * near the Colosseum", ahead of the emergency departments a visitor needs. Mirrors tools/places_proto.py.
     */
    private fun restricted(p: Place): Boolean =
        listOf("hospital", "clinic", "medical").any { it in p.category } && RESTRICTED.containsMatchIn(p.name)

    private suspend fun within(c: GeoPoint, radiusM: Double, cats: Set<Int>): List<Place> {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * cos(c.lat * PI / 180).coerceAtLeast(0.01))
        val catList = cats.joinToString(",")
        // Category ids come from the pack itself, so inlining them is safe. Filter them here, not
        // after the LIMIT: a city centre has more than 4000 places within 800 m (the Duomo: 4488),
        // and the R-tree returns them in no particular order, so the nearest matches got cut.
        val rows = query(
            """
            SELECT p.name, p.name_en, k.name, p.lat, p.lon, p.street, p.locality, p.phone, p.conf, p.alt, p.cat
            FROM place_geo g JOIN place p ON p.id = g.id JOIN category k ON k.id = p.cat
            WHERE g.lat0 >= ?1 AND g.lat1 <= ?2 AND g.lon0 >= ?3 AND g.lon1 <= ?4
              AND (p.cat IN ($catList) OR p.alt != '')
            LIMIT 4000
            """.trimIndent(),
            (c.lat - dLat).toString(), (c.lat + dLat).toString(), (c.lon - dLon).toString(), (c.lon + dLon).toString(),
        )
        return rows.mapNotNull { r ->
            val cat = (r[10] as Number).toInt()
            val alts = (r[9] as? String)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
            if (cat !in cats && alts.none { it in cats }) return@mapNotNull null
            val lat = (r[3] as Number).toDouble()
            val lon = (r[4] as Number).toDouble()
            val d = haversine(c.lat, c.lon, lat, lon)
            if (d > radiusM) return@mapNotNull null
            Place(
                name = r[0] as String, nameEn = r[1] as? String, category = r[2] as String, lat = lat, lon = lon,
                street = r[5] as? String, locality = r[6] as? String, phone = r[7] as? String,
                confidence = (r[8] as Number).toDouble(), distanceM = d,
            )
        }
    }

    private suspend fun query(sql: String, vararg args: String): List<List<Any?>> = withContext(Dispatchers.IO) {
        val res = json.parseToJsonElement(
            NativeEngine.placesQuery(sql.toByteArray(), args.map { it.toByteArray() }.toTypedArray()).decodeToString(),
        ) as JsonObject
        val err = (res["error"] as? JsonPrimitive)?.content.orEmpty()
        check(err.isEmpty()) { "places query failed: $err" }
        res["rows"]!!.jsonArray.map { row ->
            (row as JsonArray).map { v ->
                when {
                    v is JsonNull -> null
                    v.jsonPrimitive.isString -> v.jsonPrimitive.content
                    else -> v.jsonPrimitive.doubleOrNull
                }
            }
        }
    }

    companion object {
        /** Category name part -> words in place names that confirm it, in common languages. */
        private val NAME_HINTS = listOf(
            "pharmacy" to Regex(
                "pharm|farmac|apothe|apotek|aptek|chemist|drug|eczane|botica|薬|藥|药|약국|ドラッグ", RegexOption.IGNORE_CASE,
            ),
            "drugstore" to Regex("pharm|farmac|apothe|apotek|aptek|chemist|drug|薬|藥|药|약국|ドラッグ", RegexOption.IGNORE_CASE),
            "hospital" to Regex(
                "hospit|ospedal|hôpital|hopital|krankenhaus|klinik|clinic|clínic|ziekenhuis|szpital|policlin|" +
                    "病院|医院|醫院|병원|クリニック|medical|medic",
                RegexOption.IGNORE_CASE,
            ),
        )
        private const val NAME_HINT_FACTOR = 0.4
        private val RESTRICTED = Regex(
            "milit|army|armed forces|veteran|prison|penitentiar|caserma|kaserne|自衛隊|軍", RegexOption.IGNORE_CASE,
        )
        private const val RESTRICTED_FACTOR = 4.0
        private val GENERIC_ENDINGS = setOf("store", "shop", "station", "center", "centre", "office")
        private val CLAUSE = Regex("\\s+(?:and|that|which|where|with|for|so|because|to)\\s+|[;:]")

        fun placeNounsOf(category: String): List<String> {
            val parts = category.split('_')
            val last = parts.last()
            val before = if (last in GENERIC_ENDINGS && parts.size >= 2) listOf(parts[parts.size - 2]) else emptyList()
            return (listOf(last) + before).filter { it.length > 2 }
        }

        private val DECIMAL_COORDS = Regex("(\\d+(?:\\.\\d+)?)°\\s*([NS])\\s+(\\d+(?:\\.\\d+)?)°\\s*([EW])")

        private val COORDS_LINE = Regex("^(geographic )?coordinates", RegexOption.IGNORE_CASE)

        /**
         * "Coordinates: 43°46′23″N 11°15′22″E / 43.77306°N 11.25611°E" -> (43.77306, 11.25611);
         * also "Geographic coordinates: ..." (Sagrada Família, Shinjuku Station).
         */
        fun parseCoordinates(infobox: String): Pair<Double, Double>? {
            val line = infobox.lineSequence().firstOrNull { COORDS_LINE.containsMatchIn(it) } ?: return null
            val m = DECIMAL_COORDS.findAll(line).lastOrNull() ?: return null
            val lat = m.groupValues[1].toDouble() * if (m.groupValues[2] == "S") -1 else 1
            val lon = m.groupValues[3].toDouble() * if (m.groupValues[4] == "W") -1 else 1
            return lat to lon
        }

        /** place_names.tsv lines "rome\t41.89333\t12.48278\tRome" -> "rome" to Rome's coordinates. */
        fun parseKnownPlaces(tsv: String): Map<String, GeoPoint> =
            tsv.lineSequence().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 4) return@mapNotNull null
                val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
                f[0] to GeoPoint(lat, lon, f[3])
            }.toMap()

        /** "Location: Shibuya, Tokyo, Japan" -> "Shibuya": the most specific part of an infobox's Location line. */
        fun locationArea(infobox: String): String? =
            infobox.lineSequence().firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                ?.substringAfter(':')?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * PI / 180
            val dLon = (lon2 - lon1) * PI / 180
            val a = sin(dLat / 2).pow(2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
