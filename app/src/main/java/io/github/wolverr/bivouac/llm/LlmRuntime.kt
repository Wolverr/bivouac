package io.github.wolverr.bivouac.llm

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CompletionStats(
    val n_reused: Int = 0,
    val n_prefill: Int = 0,
    val n_gen: Int = 0,
    val t_prefill_ms: Double = 0.0,
    val t_first_token_ms: Double = 0.0,
    val t_gen_ms: Double = 0.0,
    val cancelled: Boolean = false,
    val stop_reason: String = "",
    val error: String = "",
) {
    val prefillTps: Double get() = if (t_prefill_ms > 0) n_prefill / (t_prefill_ms / 1000) else 0.0
    val genTps: Double get() = if (t_gen_ms > 0) n_gen / (t_gen_ms / 1000) else 0.0
}

@Serializable
data class ModelInfo(
    val desc: String = "",
    val size_bytes: Long = 0,
    val n_params: Long = 0,
    val n_ctx: Int = 0,
    val n_ctx_train: Int = 0,
    val threads: Int = 0,
    val batch_threads: Int = 0,
    val recurrent: Boolean = false,
    val system: String = "",
)

data class Sampling(
    val temperature: Float = 0.6f,
    val topP: Float = 0.95f,
    val topK: Int = 20,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.05f,
    val maxTokens: Int = 1024,
)

data class ChatTurn(val role: String, val content: String)

sealed interface GenEvent {
    data class Prefill(val done: Int, val total: Int) : GenEvent
    data class Token(val text: String) : GenEvent
    data class Done(val stats: CompletionStats) : GenEvent
}

/**
 * Owns the native engine. Every native call runs on one dedicated thread, so
 * the engine never sees concurrent use.
 */
class LlmRuntime private constructor(context: Context) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llmThread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true }
    val device: DeviceProfile = DeviceProfile.detect(context)

    init {
        NativeEngine.nativeInit(context.applicationInfo.nativeLibraryDir.toByteArray())
    }

    data class LoadOptions(
        val nCtx: Int = 4096,
        val nBatch: Int = 256,
        /**
         * Read prompts on every core. Prompt processing is compute-bound, so the efficiency
         * cores add real throughput (+33% on a 2-big-core phone); generation stays on the big cores.
         */
        val allCores: Boolean = true,
        /** Let llama.cpp copy weights into a faster layout (costs about the model's size in extra RAM). */
        val repack: Boolean = true,
    )

    suspend fun load(path: String, opts: LoadOptions = LoadOptions()): Result<ModelInfo> = withContext(llmThread) {
        val batchCpus = if (opts.allCores) device.allCores else device.bigCores
        val err = NativeEngine.nativeLoad(
            path.toByteArray(), opts.nCtx, opts.nBatch, device.bigCores, batchCpus, opts.repack,
        ).decodeToString()
        if (err.isNotEmpty()) Result.failure(IllegalStateException(err))
        else Result.success(json.decodeFromString<ModelInfo>(NativeEngine.nativeModelInfo().decodeToString()))
    }

    suspend fun unload() = withContext(llmThread) { NativeEngine.nativeUnload() }

    suspend fun formatChat(system: String, turns: List<ChatTurn>, thinking: Boolean = false): String =
        withContext(llmThread) {
            NativeEngine.nativeFormatChat(
                system.toByteArray(),
                turns.map { it.role.toByteArray() }.toTypedArray(),
                turns.map { it.content.toByteArray() }.toTypedArray(),
                thinking,
            ).decodeToString()
        }

    /**
     * Renders a conversation (ending with a user turn) and splits it into the
     * stable prefix (system instructions, snapshotted natively) and the rest.
     * The engine additionally reuses earlier turns that are still in its cache.
     */
    suspend fun chatPrompt(system: String, turns: List<ChatTurn>, thinking: Boolean = false): Pair<String, String> {
        val probe = formatChat(system, listOf(ChatTurn("user", USER_MARKER)), thinking)
        val prefix = probe.substring(0, probe.indexOf(USER_MARKER).also { check(it >= 0) { "chat template dropped the user message" } })
        val full = formatChat(system, turns, thinking)
        return if (full.startsWith(prefix)) prefix to full.substring(prefix.length) else "" to full
    }

    fun complete(prefix: String, rest: String, sampling: Sampling = Sampling()): Flow<GenEvent> = callbackFlow {
        // The native call blocks this coroutine, so a cancelled collector must
        // stop it through the engine's cancel flag.
        coroutineContext[Job]?.invokeOnCompletion { if (it != null) NativeEngine.nativeCancel() }
        val listener = object : NativeEngine.Listener {
            override fun onPrefill(done: Int, total: Int) {
                trySend(GenEvent.Prefill(done, total))
            }

            override fun onToken(piece: ByteArray): Boolean {
                trySend(GenEvent.Token(piece.decodeToString()))
                return !isClosedForSend
            }
        }
        val raw = NativeEngine.nativeComplete(
            prefix.toByteArray(), rest.toByteArray(),
            sampling.temperature, sampling.topP, sampling.topK, sampling.minP,
            sampling.repeatPenalty, sampling.maxTokens, listener,
        )
        send(GenEvent.Done(json.decodeFromString<CompletionStats>(raw.decodeToString())))
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED).flowOn(llmThread)

    /** Safe to call from any thread; the running completion returns promptly. */
    fun cancel() = NativeEngine.nativeCancel()

    suspend fun bench(nPrompt: Int = 256, nGen: Int = 64): String = withContext(llmThread) {
        NativeEngine.nativeBench(nPrompt, nGen).decodeToString()
    }

    companion object {
        private const val USER_MARKER = "\u0001BIVOUAC_USER\u0001"

        @Volatile private var instance: LlmRuntime? = null

        fun get(context: Context): LlmRuntime =
            instance ?: synchronized(this) {
                instance ?: LlmRuntime(context.applicationContext).also { instance = it }
            }
    }
}
