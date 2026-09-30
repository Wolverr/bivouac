package io.github.wolverr.bivouac.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wolverr.bivouac.llm.CompletionStats
import io.github.wolverr.bivouac.llm.FileStore
import io.github.wolverr.bivouac.llm.GenEvent
import io.github.wolverr.bivouac.llm.LlmRuntime
import io.github.wolverr.bivouac.llm.ModelInfo
import io.github.wolverr.bivouac.research.Corpus
import io.github.wolverr.bivouac.research.ResearchEvent
import io.github.wolverr.bivouac.research.ResearchPipeline
import io.github.wolverr.bivouac.research.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class LibraryState(
    val models: List<File> = emptyList(),
    val packs: List<File> = emptyList(),
    val loadedModel: String? = null,
    val modelInfo: ModelInfo? = null,
    val openPack: String? = null,
    val packInfo: String? = null,
    val busy: String? = null,
    val importProgress: Float? = null,
    val error: String? = null,
    val benchResult: String? = null,
    val allCores: Boolean = false,
    val repack: Boolean = true,
)

data class AskState(
    val question: String = "",
    val answer: String = "",
    val running: Boolean = false,
    val status: String? = null,
    val prefill: Pair<Int, Int>? = null,
    val sources: List<Source> = emptyList(),
    val searchMs: Long? = null,
    val stats: CompletionStats? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val runtime = LlmRuntime.get(app)
    private val models = FileStore(app, "models", ".gguf")
    private val packs = FileStore(app, "packs", ".db")
    private val corpus = Corpus()
    private val pipeline = ResearchPipeline(runtime, corpus)

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val _library = MutableStateFlow(
        LibraryState(
            models = models.list(),
            packs = packs.list(),
            allCores = prefs.getBoolean("all_cores", false),
            repack = prefs.getBoolean("repack", true),
        ),
    )
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _ask = MutableStateFlow(AskState())
    val ask: StateFlow<AskState> = _ask.asStateFlow()

    private var askJob: Job? = null

    init {
        // Open the first knowledge pack automatically: most people have exactly one.
        packs.list().firstOrNull()?.let(::openPack)
    }

    private fun refresh() = _library.update { it.copy(models = models.list(), packs = packs.list()) }

    fun importFile(uri: Uri) = viewModelScope.launch {
        _library.update { it.copy(busy = "Importing…", importProgress = 0f, error = null) }
        val (name, _) = FileStore.describe(getApplication(), uri)
        val store = if (name.endsWith(".db")) packs else models
        runCatching {
            store.import(uri) { copied, total ->
                if (total > 0) _library.update { it.copy(importProgress = copied.toFloat() / total) }
            }
        }.onSuccess { f -> if (store === packs) openPack(f) }
            .onFailure { e -> _library.update { it.copy(error = e.message) } }
        _library.update { it.copy(busy = null, importProgress = null) }
        refresh()
    }

    fun loadModel(file: File) = viewModelScope.launch {
        _library.update { it.copy(busy = "Loading ${file.name}…", error = null, benchResult = null) }
        val lib = _library.value
        runtime.load(file.absolutePath, LlmRuntime.LoadOptions(allCores = lib.allCores, repack = lib.repack))
            .onSuccess { info -> _library.update { it.copy(loadedModel = file.absolutePath, modelInfo = info) } }
            .onFailure { e -> _library.update { it.copy(loadedModel = null, modelInfo = null, error = e.message) } }
        _library.update { it.copy(busy = null) }
    }

    fun openPack(file: File) = viewModelScope.launch {
        corpus.open(file.absolutePath)
            .onSuccess {
                val info = "${corpus.meta("articles")} articles · ${corpus.meta("source")}"
                _library.update { it.copy(openPack = file.absolutePath, packInfo = info) }
            }
            .onFailure { e -> _library.update { it.copy(openPack = null, packInfo = null, error = e.message) } }
    }

    fun delete(file: File) = viewModelScope.launch {
        val lib = _library.value
        if (lib.loadedModel == file.absolutePath) {
            runtime.unload()
            _library.update { it.copy(loadedModel = null, modelInfo = null) }
        }
        if (lib.openPack == file.absolutePath) {
            corpus.close()
            _library.update { it.copy(openPack = null, packInfo = null) }
        }
        file.delete()
        refresh()
    }

    /** Changes apply on the next model load. */
    fun setAllCores(on: Boolean) {
        prefs.edit().putBoolean("all_cores", on).apply()
        _library.update { it.copy(allCores = on) }
    }

    fun setRepack(on: Boolean) {
        prefs.edit().putBoolean("repack", on).apply()
        _library.update { it.copy(repack = on) }
    }

    fun bench() = viewModelScope.launch {
        _library.update { it.copy(busy = "Benchmarking…", benchResult = null) }
        val r = runtime.bench()
        _library.update { it.copy(busy = null, benchResult = r) }
    }

    fun setQuestion(q: String) = _ask.update { it.copy(question = q) }

    fun askQuestion() {
        val q = _ask.value.question.trim()
        if (q.isEmpty() || _ask.value.running) return
        if (_library.value.loadedModel == null) {
            _ask.update { it.copy(error = "Load a model first (Library tab).") }
            return
        }
        _ask.update { AskState(question = q, running = true, status = "Searching…") }
        askJob = viewModelScope.launch {
            try {
                pipeline.answer(q).collect { ev ->
                    when (ev) {
                        is ResearchEvent.Searching -> _ask.update { it.copy(status = "Searching the library…") }
                        is ResearchEvent.Sources -> _ask.update {
                            it.copy(sources = ev.sources, searchMs = ev.searchMs, status = "Reading sources…")
                        }
                        is ResearchEvent.Llm -> when (val g = ev.event) {
                            is GenEvent.Prefill -> _ask.update { it.copy(prefill = g.done to g.total) }
                            is GenEvent.Token -> _ask.update { it.copy(answer = it.answer + g.text, status = null) }
                            is GenEvent.Done -> _ask.update {
                                it.copy(stats = g.stats, error = g.stats.error.ifEmpty { null }, status = null)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ask.update { it.copy(error = e.message) }
            } finally {
                _ask.update { it.copy(running = false, status = null) }
            }
        }
    }

    fun stop() {
        runtime.cancel()
        askJob?.cancel()
    }
}
