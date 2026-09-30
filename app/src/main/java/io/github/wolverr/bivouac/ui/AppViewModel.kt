package io.github.wolverr.bivouac.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wolverr.bivouac.llm.CompletionStats
import io.github.wolverr.bivouac.llm.FileStore
import io.github.wolverr.bivouac.llm.LibFile
import io.github.wolverr.bivouac.llm.GenEvent
import io.github.wolverr.bivouac.llm.LlmRuntime
import io.github.wolverr.bivouac.llm.ModelInfo
import io.github.wolverr.bivouac.places.DeviceLocation
import io.github.wolverr.bivouac.places.GeoPoint
import io.github.wolverr.bivouac.places.Place
import io.github.wolverr.bivouac.places.PlaceIntent
import io.github.wolverr.bivouac.places.Places
import io.github.wolverr.bivouac.research.Conversation
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

data class LibraryState(
    val models: List<LibFile> = emptyList(),
    val packs: List<LibFile> = emptyList(),
    val loadedModel: String? = null,
    val modelInfo: ModelInfo? = null,
    val openPack: String? = null,
    val packInfo: String? = null,
    val openPlaces: String? = null,
    val placesInfo: String? = null,
    val busy: String? = null,
    val error: String? = null,
    val benchResult: String? = null,
    val allCores: Boolean = true,
    val repack: Boolean = true,
)

/** One question and its answer in the conversation. */
data class TurnUi(
    val question: String,
    val answer: String = "",
    val status: String? = null,
    val prefill: Pair<Int, Int>? = null,
    val sources: List<Source> = emptyList(),
    val searchMs: Long? = null,
    val stats: CompletionStats? = null,
    val error: String? = null,
    val usedLibrary: Boolean = false,
    val crisis: Boolean = false,
    val places: List<Place> = emptyList(),
    /** Where places were searched around; null with [placeSearch] set means the location wasn't found. */
    val placeCenter: GeoPoint? = null,
    val placeSearch: PlaceIntent? = null,
    val needsLocationPermission: Boolean = false,
)

data class AskState(
    val input: String = "",
    val turns: List<TurnUi> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val runtime = LlmRuntime.get(app)
    private val models = FileStore(app, "models", ".gguf")
    private val packs = FileStore(app, "packs", ".db")
    private val corpus = Corpus()
    private val places = Places()
    private val pipeline = ResearchPipeline(runtime, corpus, places) { DeviceLocation.current(app) }
    private val conversation = Conversation()

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val _library = MutableStateFlow(
        LibraryState(
            models = models.list(),
            packs = packs.list(),
            allCores = prefs.getBoolean("all_cores_reading", true),
            repack = prefs.getBoolean("repack", true),
        ),
    )
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _ask = MutableStateFlow(AskState())
    val ask: StateFlow<AskState> = _ask.asStateFlow()

    private var askJob: Job? = null

    init {
        // Open packs automatically: one Wikipedia pack and one places pack at most.
        packs.list().filter(::isPlacesPack).firstOrNull()?.let(::openPack)
        packs.list().filterNot(::isPlacesPack).firstOrNull()?.let(::openPack)
    }

    private fun refresh() = _library.update { it.copy(models = models.list(), packs = packs.list()) }

    /** Links picked files in place (no copy); .db files are packs, .gguf files are models. */
    fun linkFiles(uris: List<Uri>) = viewModelScope.launch {
        _library.update { it.copy(error = null) }
        for (uri in uris) {
            val (name, _) = FileStore.describe(getApplication(), uri)
            val store = if (name.endsWith(".db")) packs else models
            runCatching { store.link(uri) }
                .onSuccess { f -> if (store === packs) openPack(f) }
                .onFailure { e -> _library.update { it.copy(error = "$name: ${e.message}") } }
        }
        refresh()
    }

    fun loadModel(file: LibFile) = viewModelScope.launch {
        _library.update { it.copy(busy = "Loading ${file.name}…", error = null, benchResult = null) }
        val lib = _library.value
        runtime.load(models.path(file), LlmRuntime.LoadOptions(allCores = lib.allCores, repack = lib.repack))
            .onSuccess { info -> _library.update { it.copy(loadedModel = file.id, modelInfo = info) } }
            .onFailure { e -> _library.update { it.copy(loadedModel = null, modelInfo = null, error = e.message) } }
        _library.update { it.copy(busy = null) }
    }

    private fun isPlacesPack(f: LibFile) = f.name.startsWith("places")

    fun openPack(file: LibFile) = viewModelScope.launch {
        val path = packs.path(file)
        if (isPlacesPack(file)) {
            places.open(path)
                .onSuccess {
                    val info = "${places.meta("places")} places · ${places.meta("source")}"
                    _library.update { it.copy(openPlaces = file.id, placesInfo = info) }
                }
                .onFailure { e -> _library.update { it.copy(openPlaces = null, placesInfo = null, error = e.message) } }
            return@launch
        }
        corpus.open(path)
            .onSuccess {
                val info = "${corpus.meta("articles")} articles · ${corpus.meta("source")}"
                _library.update { it.copy(openPack = file.id, packInfo = info) }
            }
            .onFailure { e -> _library.update { it.copy(openPack = null, packInfo = null, error = e.message) } }
    }

    fun delete(file: LibFile) = viewModelScope.launch {
        val lib = _library.value
        if (lib.loadedModel == file.id) {
            runtime.unload()
            _library.update { it.copy(loadedModel = null, modelInfo = null) }
        }
        if (lib.openPack == file.id) {
            corpus.close()
            _library.update { it.copy(openPack = null, packInfo = null) }
        }
        if (lib.openPlaces == file.id) {
            places.close()
            _library.update { it.copy(openPlaces = null, placesInfo = null) }
        }
        (if (file.name.endsWith(".db")) packs else models).remove(file)
        refresh()
    }

    /** Changes apply on the next model load. */
    fun setAllCores(on: Boolean) {
        prefs.edit().putBoolean("all_cores_reading", on).apply()
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

    fun setInput(q: String) = _ask.update { it.copy(input = q) }

    fun newChat() {
        if (_ask.value.running) return
        conversation.clear()
        _ask.update { AskState() }
    }

    private fun updateLast(f: (TurnUi) -> TurnUi) =
        _ask.update { st -> st.copy(turns = st.turns.dropLast(1) + f(st.turns.last())) }

    fun askQuestion() {
        val q = _ask.value.input.trim()
        if (q.isEmpty() || _ask.value.running) return
        if (_library.value.loadedModel == null) {
            _ask.update { it.copy(error = "Load a model first (Library tab).") }
            return
        }
        _ask.update { it.copy(input = "", running = true, error = null, turns = it.turns + TurnUi(q, status = "Thinking…")) }
        askJob = viewModelScope.launch {
            try {
                pipeline.answer(q, conversation).collect { ev ->
                    when (ev) {
                        is ResearchEvent.Routed -> updateLast { it.copy(crisis = ev.decision.crisis) }
                        is ResearchEvent.Searching -> updateLast { it.copy(status = "Searching the library…", usedLibrary = true) }
                        is ResearchEvent.PlacesFound -> updateLast {
                            it.copy(
                                places = ev.places, placeCenter = ev.center, placeSearch = ev.intent,
                                status = "Reading places…",
                                needsLocationPermission = ev.intent.nearMe && !DeviceLocation.hasPermission(getApplication()),
                            )
                        }
                        is ResearchEvent.Sources -> updateLast {
                            it.copy(sources = ev.sources, searchMs = ev.searchMs, status = "Reading sources…")
                        }
                        is ResearchEvent.Llm -> when (val g = ev.event) {
                            is GenEvent.Prefill -> updateLast { it.copy(prefill = g.done to g.total) }
                            is GenEvent.Token -> updateLast { it.copy(answer = it.answer + g.text, status = null) }
                            is GenEvent.Done -> updateLast {
                                it.copy(stats = g.stats, error = g.stats.error.ifEmpty { null }, status = null)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateLast { it.copy(error = e.message) }
            } finally {
                _ask.update { it.copy(running = false) }
                if (_ask.value.turns.isNotEmpty()) updateLast { it.copy(status = null) }
            }
        }
    }

    /** Asks the last question again, e.g. after location permission was granted. */
    fun retryLast() {
        if (_ask.value.running) return
        val q = _ask.value.turns.lastOrNull()?.question ?: return
        _ask.update { it.copy(turns = it.turns.dropLast(1), input = q) }
        askQuestion()
    }

    fun stop() {
        runtime.cancel()
        askJob?.cancel()
    }
}
