package io.github.wolverr.bivouac.research

import io.github.wolverr.bivouac.llm.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Passage(
    val chunk: Long,
    val article: Long,
    val title: String,
    val section: String,
    val score: Double = 0.0,
    val text: String = "",
)

@Serializable
data class Article(
    val id: Long = 0,
    val title: String = "",
    val first_chunk: Long = 0,
    val n_chunks: Int = 0,
    val prior: Double = 0.0,
)

/** The offline Wikipedia pack (wiki.db built by tools/build_corpus.py). */
class Corpus {
    private val json = Json { ignoreUnknownKeys = true }

    var path: String? = null
        private set

    suspend fun open(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val err = NativeEngine.corpusOpen(path.toByteArray()).decodeToString()
        if (err.isEmpty()) {
            this@Corpus.path = path
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(err))
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        NativeEngine.corpusClose()
        path = null
    }

    suspend fun meta(key: String): String = withContext(Dispatchers.IO) {
        NativeEngine.corpusMeta(key.toByteArray()).decodeToString()
    }

    suspend fun search(ftsQuery: String, limit: Int, priorWeight: Double = 0.5): List<Passage> =
        withContext(Dispatchers.IO) {
            json.decodeFromString(NativeEngine.corpusSearch(ftsQuery.toByteArray(), limit, priorWeight, true).decodeToString())
        }

    suspend fun findArticle(title: String): Article? = withContext(Dispatchers.IO) {
        json.decodeFromString<Article>(NativeEngine.corpusFindArticle(title.toByteArray()).decodeToString())
            .takeIf { it.id != 0L }
    }

    suspend fun chunk(article: Article, index: Int): Passage? = withContext(Dispatchers.IO) {
        if (index !in 0 until article.n_chunks) return@withContext null
        val id = article.first_chunk + index
        val raw = NativeEngine.corpusChunk(id).decodeToString()
        if (raw == "{}") return@withContext null
        val p = json.decodeFromString<ChunkJson>(raw)
        Passage(chunk = id, article = p.article, title = article.title, section = p.section, text = p.text)
    }

    @Serializable
    private data class ChunkJson(val article: Long = 0, val section: String = "", val text: String = "")
}
