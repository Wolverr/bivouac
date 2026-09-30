package io.github.wolverr.bivouac.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wolverr.bivouac.llm.CompletionStats
import io.github.wolverr.bivouac.llm.GenEvent
import io.github.wolverr.bivouac.llm.LlmRuntime
import io.github.wolverr.bivouac.llm.ModelInfo
import io.github.wolverr.bivouac.llm.ModelStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ModelsState(
    val files: List<File> = emptyList(),
    val loadedPath: String? = null,
    val loadedInfo: ModelInfo? = null,
    val busy: String? = null,
    val importProgress: Float? = null,
    val error: String? = null,
    val benchResult: String? = null,
)

data class AskState(
    val question: String = "",
    val answer: String = "",
    val running: Boolean = false,
    val prefill: Pair<Int, Int>? = null,
    val stats: CompletionStats? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val runtime = LlmRuntime.get(app)
    private val store = ModelStore(app)

    private val _models = MutableStateFlow(ModelsState(files = store.list()))
    val models: StateFlow<ModelsState> = _models.asStateFlow()

    private val _ask = MutableStateFlow(AskState())
    val ask: StateFlow<AskState> = _ask.asStateFlow()

    private var askJob: Job? = null

    fun refreshModels() = _models.update { it.copy(files = store.list()) }

    fun importModel(uri: Uri) = viewModelScope.launch {
        _models.update { it.copy(busy = "Importing…", importProgress = 0f, error = null) }
        runCatching {
            store.import(uri) { copied, total ->
                if (total > 0) _models.update { it.copy(importProgress = copied.toFloat() / total) }
            }
        }.onFailure { e -> _models.update { it.copy(error = e.message) } }
        _models.update { it.copy(busy = null, importProgress = null, files = store.list()) }
    }

    fun loadModel(file: File) = viewModelScope.launch {
        _models.update { it.copy(busy = "Loading ${file.name}…", error = null, benchResult = null) }
        runtime.load(file.absolutePath)
            .onSuccess { info -> _models.update { it.copy(loadedPath = file.absolutePath, loadedInfo = info) } }
            .onFailure { e -> _models.update { it.copy(loadedPath = null, loadedInfo = null, error = e.message) } }
        _models.update { it.copy(busy = null) }
    }

    fun deleteModel(file: File) = viewModelScope.launch {
        if (_models.value.loadedPath == file.absolutePath) {
            runtime.unload()
            _models.update { it.copy(loadedPath = null, loadedInfo = null) }
        }
        store.delete(file)
        refreshModels()
    }

    fun bench() = viewModelScope.launch {
        _models.update { it.copy(busy = "Benchmarking…", benchResult = null) }
        val r = runtime.bench()
        _models.update { it.copy(busy = null, benchResult = r) }
    }

    fun setQuestion(q: String) = _ask.update { it.copy(question = q) }

    fun askQuestion() {
        val q = _ask.value.question.trim()
        if (q.isEmpty() || _ask.value.running) return
        if (_models.value.loadedPath == null) {
            _ask.update { it.copy(error = "Load a model first (Models tab).") }
            return
        }
        _ask.update { AskState(question = q, running = true) }
        askJob = viewModelScope.launch {
            try {
                val (prefix, rest) = runtime.chatPrompt(SYSTEM_PROMPT, q)
                runtime.complete(prefix, rest).collect { ev ->
                    when (ev) {
                        is GenEvent.Prefill -> _ask.update { it.copy(prefill = ev.done to ev.total) }
                        is GenEvent.Token -> _ask.update { it.copy(answer = it.answer + ev.text) }
                        is GenEvent.Done -> _ask.update {
                            it.copy(stats = ev.stats, error = ev.stats.error.ifEmpty { null })
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _ask.update { it.copy(error = e.message) }
            } finally {
                _ask.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        runtime.cancel()
        askJob?.cancel()
    }

    companion object {
        const val SYSTEM_PROMPT =
            "You are Bivouac, an offline research assistant on a phone. Answer accurately and concisely. " +
                "Lead with the direct answer, then give the key supporting details. If you are not sure " +
                "about a fact, say so instead of guessing."
    }
}
