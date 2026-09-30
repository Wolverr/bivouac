package io.github.wolverr.bivouac.llm

import kotlinx.serialization.Serializable
import java.util.Locale

/** What a model's context will cost, read from its GGUF header (model_probe.cpp). */
@Serializable
data class ModelProbe(
    val arch: String = "",
    val n_layer: Int = 0,
    val n_attn_layers: Int = 0,
    val n_embd: Int = 0,
    val n_vocab: Int = 0,
    val n_ctx_train: Int = 0,
    val kv_full_per_token: Long = 0,
    val kv_swa_per_token: Long = 0,
    val swa_window: Int = 0,
    val error: String = "",
) {
    /** KV cache bytes for a context of [nCtx]; windowed layers keep window + one batch. */
    fun kvBytes(nCtx: Int, nBatch: Int): Long =
        kv_full_per_token * nCtx + kv_swa_per_token * minOf(nCtx, swa_window + nBatch)
}

/**
 * Picks context size and weight repacking from the RAM that is free right now.
 *
 * Measured on the Redmi (Qwen3.5-2B): the mmapped weights count about the file size,
 * repacking adds a heap copy of about the same size, and it roughly doubles prompt
 * reading speed. So repacking is worth more than a long context, down to 4096 tokens
 * (what one question with six excerpts plus a little history needs).
 */
object MemoryPlanner {
    data class Plan(val nCtx: Int, val repack: Boolean, val needBytes: Long, val availBytes: Long, val note: String)

    private val CONTEXTS = listOf(8192, 6144, 4096, 3072, 2048)
    private const val MIN_REPACK_CTX = 4096
    private const val MB = 1_000_000L

    /** Compute buffers + recurrent state + the prefix snapshot: 30–65 MB measured at batch 256; padded. */
    private const val OVERHEAD = 160 * MB

    /** Left free so the phone (keyboard, launcher, our own UI) stays usable. */
    private const val RESERVE = 450 * MB

    fun need(fileBytes: Long, probe: ModelProbe, nCtx: Int, repack: Boolean, nBatch: Int): Long =
        fileBytes * (if (repack) 2 else 1) + probe.kvBytes(nCtx, nBatch) + OVERHEAD

    /**
     * [safety] below 1 shrinks the budget after Android killed us for memory
     * (see CrashGuard). Returns null when even the smallest setup doesn't fit.
     */
    fun plan(fileBytes: Long, probe: ModelProbe, availBytes: Long, safety: Double = 1.0, nBatch: Int = 256): Plan? {
        val budget = (availBytes * safety).toLong() - RESERVE
        val ctxs = CONTEXTS.filter { probe.n_ctx_train <= 0 || it <= probe.n_ctx_train }.ifEmpty { listOf(2048) }
        val choice = ctxs.filter { it >= MIN_REPACK_CTX }.firstOrNull { need(fileBytes, probe, it, true, nBatch) <= budget }
            ?.let { it to true }
            ?: ctxs.firstOrNull { need(fileBytes, probe, it, false, nBatch) <= budget }?.let { it to false }
            ?: return null
        val (nCtx, repack) = choice
        val needBytes = need(fileBytes, probe, nCtx, repack, nBatch)
        val note = String.format(
            Locale.US, "auto: ctx %d, %s · needs %.1f of %.1f GB free%s",
            nCtx, if (repack) "repacked" else "no repack", needBytes / 1e9, availBytes / 1e9,
            if (safety < 1.0) String.format(Locale.US, " (safety %.0f%% after a memory crash)", safety * 100) else "",
        )
        return Plan(nCtx, repack, needBytes, availBytes, note)
    }

    fun tooBig(fileBytes: Long, probe: ModelProbe, availBytes: Long, nBatch: Int = 256): String = String.format(
        Locale.US, "Not enough free memory: this model needs about %.1f GB, the phone has %.1f GB free. " +
            "Close other apps or pick a smaller model.",
        (need(fileBytes, probe, CONTEXTS.last(), false, nBatch) + RESERVE) / 1e9, availBytes / 1e9,
    )
}
