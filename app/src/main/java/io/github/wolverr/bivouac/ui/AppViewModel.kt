package io.github.wolverr.bivouac.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wolverr.bivouac.eval.EvalRunner
import io.github.wolverr.bivouac.llm.Catalog
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
import java.io.File

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
    /** Pick context size and repacking from free RAM at load time. */
    val autoMemory: Boolean = true,
    /** Think harder by itself for calculations and timelines (Router.needsReasoning). */
    val autoThink: Boolean = true,
    /** How the loaded model was configured (auto mode). */
    val loadNote: String? = null,
    /** Shown once after Android killed the app while it was busy with a model. */
    val notice: String? = null,
    /** "Add downloaded files" needs storage access first (see FileStore.hasStorageAccess). */
    val needsStorageAccess: Boolean = false,
    /** First-run setup finished (a model and a Wikipedia pack added) or skipped for the Library. */
    val setupDone: Boolean = false,
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
    /** Answered with "Think harder" (reasoning first); thinking = hidden tokens so far / budget. */
    val thoughtHarder: Boolean = false,
    /** A calculation answered without thinking (the phone writes too slowly to start it unasked). */
    val calculation: Boolean = false,
    val thinking: Pair<Int, Int>? = null,
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
    private val places = Places(Places.parseKnownPlaces(app.assets.open("place_names.tsv").use { it.readBytes().decodeToString() }))
    private fun prompt(name: String) = getApplication<Application>().assets.open("prompts/$name").use { it.readBytes().decodeToString().trim() }

    /** The model's own system prompt from the catalog, else the default one. */
    private fun promptFor(modelFile: String): String =
        runCatching { Catalog.load(getApplication()).firstOrNull { it.file == modelFile }?.prompt }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { runCatching { prompt(it) }.getOrNull() }
            ?: prompt("system.txt")

    private val pipeline = ResearchPipeline(
        runtime,
        system = app.assets.open("prompts/system.txt").use { it.readBytes().decodeToString().trim() },
        corpus = corpus,
        places = places,
    ) { DeviceLocation.current(app) }
    private val conversation = Conversation()

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private val _library = MutableStateFlow(
        LibraryState(
            models = models.list(),
            packs = packs.list(),
            allCores = prefs.getBoolean("all_cores_reading", true),
            repack = prefs.getBoolean("repack", true),
            autoMemory = prefs.getBoolean("auto_memory", true),
            autoThink = prefs.getBoolean("auto_think", true),
            setupDone = prefs.getBoolean("setup_done", false),
            notice = runtime.crashGuard.previous?.let { c ->
                "Bivouac was stopped while ${c.phase}${if (c.lowMemory) ", probably because the phone ran out of memory" else ""}. " +
                    "The next load of that model uses smaller memory settings."
            },
        ),
    )
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _ask = MutableStateFlow(AskState())
    val ask: StateFlow<AskState> = _ask.asStateFlow()

    private var askJob: Job? = null

    /** The running or last model load; loads queue behind it. Declared before init, which starts one. */
    private var loadJob: Job? = null

    init {
        // Open packs automatically: one Wikipedia pack and one places pack at most.
        packs.list().filter(::isPlacesPack).firstOrNull()?.let(::openPack)
        packs.list().filterNot(::isPlacesPack).firstOrNull()?.let(::openPack)
        refresh()  // phones set up before the first-run screen existed skip it
        // The model loaded last time loads again, unless Android killed the app for memory: then
        // the notice explains, and the user loads it (with smaller settings) when ready.
        val last = prefs.getString("last_model", null)
        if (runtime.crashGuard.previous?.lowMemory != true) {
            models.list().firstOrNull { it.name == last }?.let { loadModel(it) }
        }
    }

    private fun refresh() {
        _library.update { it.copy(models = models.list(), packs = packs.list()) }
        val lib = _library.value
        if (!lib.setupDone && lib.models.isNotEmpty() && lib.packs.any { !isPlacesPack(it) }) finishSetup()
    }

    /** Leaves the first-run setup for good: everything needed is added, or the user picks in the Library. */
    fun finishSetup() {
        prefs.edit().putBoolean("setup_done", true).apply()
        _library.update { it.copy(setupDone = true) }
    }

    /**
     * "Add downloaded files": links every model (.gguf) and pack (wiki*.db, places*.db) in Downloads
     * by path, without copying. Asks for storage access first when the app doesn't have it.
     */
    fun addDownloads() = viewModelScope.launch {
        val app = getApplication<Application>()
        if (!FileStore.hasStorageAccess(app)) {
            _library.update { it.copy(needsStorageAccess = true, error = null) }
            return@launch
        }
        _library.update { it.copy(needsStorageAccess = false, error = null, busy = "Looking in Downloads…") }
        val have = (models.list() + packs.list()).map { it.name }.toSet()
        val found = withContext(Dispatchers.IO) {
            FileStore.downloadsDir().listFiles().orEmpty().filter { f ->
                f.isFile && f.name !in have &&
                    (f.name.endsWith(".gguf") || (f.name.endsWith(".db") && (f.name.startsWith("wiki") || f.name.startsWith("places"))))
            }
        }
        for (f in found) {
            val store = if (f.name.endsWith(".db")) packs else models
            runCatching { store.linkPath(f) }
                .onSuccess { lf -> if (store === packs) openPack(lf).join() }
                .onFailure { e -> _library.update { it.copy(error = "${f.name}: ${e.message}") } }
        }
        refresh()
        // No model loaded yet (first run): load the one recommended for this phone, or any added.
        if (_library.value.loadedModel == null && loadJob?.isActive != true) {
            val added = models.list().filter { m -> found.any { it.name == m.name } }
            val pick = Catalog.recommendedModel(Catalog.load(app), runtime.device.totalRamGb)?.file
            (added.firstOrNull { it.name == pick } ?: added.firstOrNull())?.let { loadModel(it) }
        }
        _library.update {
            it.copy(
                busy = null,
                notice = if (found.isEmpty()) "No new models or packs in Downloads. Download them first (above), " +
                    "or use \"Pick a file\" if they are somewhere else."
                else "Added from Downloads: ${found.joinToString { f -> f.name }}",
            )
        }
    }

    /** After the user came back from the storage-access screen. */
    fun storageAccessChanged() {
        if (_library.value.needsStorageAccess && FileStore.hasStorageAccess(getApplication())) addDownloads()
    }

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

    /** [forceRepack] (adb eval/bench only) overrides the planner's weight-repacking choice, for measurements. */
    fun loadModel(file: LibFile, forceRepack: Boolean? = null): Job {
        val previous = loadJob
        return viewModelScope.launch {
            previous?.join()
            load(file, forceRepack)
        }.also { loadJob = it }
    }

    private suspend fun load(file: LibFile, forceRepack: Boolean?) {
        _library.update { it.copy(busy = "Loading ${file.name}…", error = null, benchResult = null) }
        val lib = _library.value
        val path = models.path(file)
        var opts = LlmRuntime.LoadOptions(allCores = lib.allCores, repack = lib.repack, batchCores = prefs.getInt("batch_cores", 0))
        var note: String? = null
        if (lib.autoMemory) {
            val plan = runtime.planLoad(path, file.size, runtime.crashGuard.safety(file.id)).getOrElse { e ->
                _library.update { it.copy(busy = null, loadedModel = null, modelInfo = null, error = e.message) }
                return
            }
            opts = opts.copy(nCtx = plan.nCtx, repack = forceRepack ?: plan.repack)
            note = plan.note + (forceRepack?.let { " · repack forced $it" } ?: "")
        }
        runtime.crashGuard.begin(file.id, "loading ${file.name}", note ?: "manual: repack=${opts.repack}")
        runtime.load(path, opts)
            .onSuccess { info ->
                pipeline.system = promptFor(file.name)
                prefs.edit().putString("last_model", file.name).apply()
                _library.update { it.copy(loadedModel = file.id, modelInfo = info, loadNote = note) }
            }
            .onFailure { e -> _library.update { it.copy(loadedModel = null, modelInfo = null, loadNote = null, error = e.message) } }
        runtime.crashGuard.end()
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
            prefs.edit().remove("last_model").apply()
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

    fun setAutoMemory(on: Boolean) {
        prefs.edit().putBoolean("auto_memory", on).apply()
        _library.update { it.copy(autoMemory = on) }
    }

    fun setAutoThink(on: Boolean) {
        prefs.edit().putBoolean("auto_think", on).apply()
        _library.update { it.copy(autoThink = on) }
    }

    fun dismissNotice() = _library.update { it.copy(notice = null) }

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
        _ask.update { it.copy(input = "") }
        run(q, thinkHarder = false)
    }

    /**
     * Asks the last question again with reasoning on ("Think harder"): slower (a few minutes
     * on a phone) but it fixes most multi-step mistakes. Replaces the last answer.
     */
    fun thinkHarder() {
        if (_ask.value.running) return
        val q = _ask.value.turns.lastOrNull()?.question ?: return
        conversation.dropLast(q)
        _ask.update { it.copy(turns = it.turns.dropLast(1)) }
        run(q, thinkHarder = true)
    }

    private fun run(q: String, thinkHarder: Boolean) {
        val loading = loadJob?.takeIf { it.isActive }
        if (_library.value.loadedModel == null && loading == null) {
            _ask.update { it.copy(error = "Load a model first (Library tab).") }
            return
        }
        val status = if (thinkHarder) "Thinking harder…" else "Thinking…"
        val turn = TurnUi(q, status = if (loading != null) "Loading the model…" else status, thoughtHarder = thinkHarder)
        _ask.update { it.copy(running = true, error = null, turns = it.turns + turn) }
        askJob = viewModelScope.launch {
            loading?.join()
            val modelId = _library.value.loadedModel
            if (modelId == null) {
                updateLast { it.copy(error = "The model did not load: ${_library.value.error ?: "see Library"}.", status = null) }
                _ask.update { it.copy(running = false) }
                return@launch
            }
            if (loading != null) updateLast { it.copy(status = status) }
            runtime.crashGuard.begin(modelId, "answering", "ctx ${runtime.contextSize}")
            try {
                pipeline.answer(q, conversation, thinkHarder = thinkHarder, autoThink = _library.value.autoThink).collect { ev ->
                    when (ev) {
                        is ResearchEvent.Routed -> updateLast {
                            if (ev.thinkHarder && !it.thoughtHarder) {
                                it.copy(crisis = ev.decision.crisis, thoughtHarder = true, status = "Thinking harder (calculation)…")
                            } else {
                                it.copy(crisis = ev.decision.crisis, calculation = ev.decision.reasoning)
                            }
                        }
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
                        is ResearchEvent.Thinking -> updateLast { it.copy(thinking = ev.tokens to ev.budget) }
                        is ResearchEvent.Llm -> when (val g = ev.event) {
                            is GenEvent.Prefill -> updateLast { it.copy(prefill = g.done to g.total) }
                            is GenEvent.Token -> updateLast {
                                // After a hidden reasoning block the answer starts with blank lines.
                                it.copy(answer = if (it.answer.isEmpty()) g.text.trimStart() else it.answer + g.text, status = null, thinking = null)
                            }
                            is GenEvent.Done -> {
                                if (g.stats.error.isEmpty()) runtime.crashGuard.recovered(modelId)
                                updateLast { it.copy(stats = g.stats, error = g.stats.error.ifEmpty { null }, status = null) }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateLast { it.copy(error = e.message) }
            } finally {
                runtime.crashGuard.end()
                _ask.update { it.copy(running = false) }
                if (_ask.value.turns.isNotEmpty()) updateLast { it.copy(status = null) }
            }
        }
    }

    private val _eval = MutableStateFlow<String?>(null)

    /** Progress text while an adb-started eval runs; null otherwise. */
    val eval: StateFlow<String?> = _eval.asStateFlow()

    /**
     * Loads [modelName] (a .gguf in the Library) and answers every question in
     * [questionsName] (a .jsonl in the app folder's eval/ directory). Results go to
     * eval/results-<model>.jsonl, resuming if that file exists. See docs/EVAL.md.
     */
    private var evalJob: kotlinx.coroutines.Job? = null

    fun startEval(questionsName: String, modelName: String, thinkHarder: Boolean = false, forceRepack: Boolean? = null) {
        if (evalJob?.isActive == true) return
        evalJob = viewModelScope.launch {
            val dir = File(getApplication<Application>().getExternalFilesDir(null), "eval").apply { mkdirs() }
            val model = models.list().firstOrNull { it.name == modelName }
            if (model == null) {
                _eval.value = "Eval failed: model $modelName not found"
                android.util.Log.e(EvalRunner.TAG, "model $modelName not found")
                return@launch
            }
            loadJob?.join()
            if (_library.value.loadedModel != model.id || forceRepack != null) loadModel(model, forceRepack).join()
            val info = _library.value.modelInfo ?: run {
                _eval.value = "Eval failed: ${_library.value.error ?: "model did not load"}"
                android.util.Log.e(EvalRunner.TAG, "load failed: ${_library.value.error}")
                return@launch
            }
            val device = runtime.device.let { "${it.model} (${it.soc})" }
            val out = File(dir, "results-${modelName.removeSuffix(".gguf")}${if (thinkHarder) "-think" else ""}.jsonl")
            _eval.value = "Eval starting…"
            EvalRunner(pipeline, device, info.desc, thinkHarder).run(File(dir, questionsName), out) { done, total ->
                _eval.value = "Eval $done/$total"
            }
            _eval.value = "Eval finished: ${out.name}"
        }
    }

    /** Loads [modelName] like the eval does and logs the engine benchmark (pp256, tg32) for adb. */
    fun startBench(modelName: String, forceRepack: Boolean? = null) {
        viewModelScope.launch {
            val model = models.list().firstOrNull { it.name == modelName }
            if (model == null) {
                android.util.Log.e(EvalRunner.TAG, "bench: model $modelName not found")
                return@launch
            }
            loadJob?.join()
            if (_library.value.loadedModel != model.id || forceRepack != null) loadModel(model, forceRepack).join()
            android.util.Log.i(EvalRunner.TAG, "load: ${_library.value.loadNote ?: _library.value.error}")
            android.util.Log.i(EvalRunner.TAG, "bench ${model.name}: ${runtime.bench(256, 32)}")
        }
    }

    /** Asks the last question again, e.g. after location permission was granted. */
    fun retryLast() {
        if (_ask.value.running) return
        val q = _ask.value.turns.lastOrNull()?.question ?: return
        conversation.dropLast(q)
        _ask.update { it.copy(turns = it.turns.dropLast(1), input = q) }
        askQuestion()
    }

    fun stop() {
        runtime.cancel()
        askJob?.cancel()
    }
}
