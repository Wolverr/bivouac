package io.github.wolverr.bivouac.eval

import android.util.Log
import io.github.wolverr.bivouac.llm.CompletionStats
import io.github.wolverr.bivouac.llm.GenEvent
import io.github.wolverr.bivouac.research.Conversation
import io.github.wolverr.bivouac.research.ResearchEvent
import io.github.wolverr.bivouac.research.ResearchPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EvalQuestion(val id: String, val cat: String = "", val q: String)

@Serializable
data class EvalResult(
    val id: String,
    val question: String,
    val answer: String,
    val sources: List<String>,
    val places: List<String>,
    val route: String,
    val device: String,
    val model: String,
    val search_ms: Long?,
    val total_ms: Long,
    val stats: CompletionStats?,
    val error: String? = null,
)

/**
 * Answers a question file on the device, each question in a fresh conversation,
 * and appends one JSON line per answer (real on-device answers and timings, for
 * grading against the frontier baseline). Started via adb, see docs/EVAL.md.
 */
class EvalRunner(
    private val pipeline: ResearchPipeline,
    private val device: String,
    private val model: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun run(questions: File, out: File, onProgress: (done: Int, total: Int) -> Unit) {
        val qs = withContext(Dispatchers.IO) {
            questions.readLines().filter { it.isNotBlank() }.map { json.decodeFromString<EvalQuestion>(it) }
        }
        val done = withContext(Dispatchers.IO) {
            if (out.exists()) out.readLines().mapNotNull { runCatching { json.decodeFromString<EvalResult>(it).id }.getOrNull() }.toSet()
            else emptySet()
        }
        Log.i(TAG, "eval: ${qs.size} questions, ${done.size} already answered -> ${out.path}")
        qs.forEachIndexed { i, q ->
            if (q.id in done) return@forEachIndexed
            val result = answer(q)
            withContext(Dispatchers.IO) { out.appendText(json.encodeToString(EvalResult.serializer(), result) + "\n") }
            Log.i(TAG, "eval ${i + 1}/${qs.size} ${q.id}: ${result.total_ms} ms, ${result.stats?.n_gen ?: 0} tok, " +
                "route=${result.route}${result.error?.let { ", error=$it" } ?: ""}")
            onProgress(i + 1, qs.size)
        }
        Log.i(TAG, "eval done -> ${out.path}")
    }

    private suspend fun answer(q: EvalQuestion): EvalResult {
        val t0 = System.currentTimeMillis()
        val text = StringBuilder()
        var sources = emptyList<String>()
        var places = emptyList<String>()
        var route = ""
        var searchMs: Long? = null
        var stats: CompletionStats? = null
        val error = runCatching {
            pipeline.answer(q.q, Conversation()).collect { ev ->
                when (ev) {
                    is ResearchEvent.Routed -> route = ev.decision.route.name.lowercase()
                    is ResearchEvent.Sources -> {
                        sources = ev.sources.map { s -> s.passage.title + if (s.passage.section.isNotEmpty()) " — ${s.passage.section}" else "" }
                        searchMs = ev.searchMs
                    }
                    is ResearchEvent.PlacesFound -> {
                        route = "places"
                        places = ev.places.map { "${it.displayName} (${it.categoryLabel}, ${ResearchPipeline.formatDistance(it.distanceM)})" }
                    }
                    is ResearchEvent.Llm -> when (val g = ev.event) {
                        is GenEvent.Token -> text.append(g.text)
                        is GenEvent.Done -> stats = g.stats
                        is GenEvent.Prefill -> Unit
                    }
                    is ResearchEvent.Searching -> Unit
                }
            }
        }.exceptionOrNull()?.message
        return EvalResult(
            id = q.id, question = q.q, answer = text.toString().trim(), sources = sources, places = places,
            route = route, device = device, model = model, search_ms = searchMs,
            total_ms = System.currentTimeMillis() - t0, stats = stats, error = error ?: stats?.error?.ifEmpty { null },
        )
    }

    companion object {
        const val TAG = "bivouac-eval"
    }
}
