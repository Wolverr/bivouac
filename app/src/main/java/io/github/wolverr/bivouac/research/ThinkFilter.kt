package io.github.wolverr.bivouac.research

/**
 * Removes reasoning blocks (`<think>…</think>`) and stray special tokens
 * (`<|tool_call_start|>`) from a streamed answer. Some chat templates ignore
 * "thinking off" (LFM2.5 emits at least an empty `<think></think>`), and the
 * user should only see the answer. Mirrored in tools/answer_proto.py.
 *
 * Tags can be split across tokens, so text that could still become a tag is
 * held back until the next piece decides it.
 *
 * [startInThink]: the prompt already opened the block (Qwen3.5 with thinking on ends its prompt
 * with "<think>"), so the stream starts with reasoning and has only the closing tag.
 */
class ThinkFilter(startInThink: Boolean = false) {
    private val pending = StringBuilder()
    private var inThink = startInThink

    /** Feeds one streamed piece; returns the text that is safe to show now. */
    fun feed(piece: String): String {
        pending.append(piece)
        val out = StringBuilder()
        while (pending.isNotEmpty()) {
            if (inThink) {
                val end = pending.indexOf(THINK_CLOSE)
                if (end < 0) {
                    // Keep only a tail that might be the start of "</think>".
                    val keep = partialSuffix(pending, THINK_CLOSE)
                    pending.delete(0, pending.length - keep)
                    break
                }
                pending.delete(0, end + THINK_CLOSE.length)
                inThink = false
                continue
            }
            val lt = pending.indexOf("<")
            if (lt < 0) {
                out.append(pending)
                pending.setLength(0)
                break
            }
            out.append(pending, 0, lt)
            pending.delete(0, lt)
            when {
                pending.startsWith(THINK_OPEN) -> {
                    pending.delete(0, THINK_OPEN.length)
                    inThink = true
                }
                SPECIAL.matchesAt(pending, 0) -> {
                    val m = SPECIAL.matchAt(pending, 0)!!
                    pending.delete(0, m.value.length)
                }
                couldBecomeTag(pending) -> break  // wait for more text
                else -> {
                    out.append('<')
                    pending.deleteCharAt(0)
                }
            }
        }
        return out.toString()
    }

    /** Text still held back at the end of the stream (an unfinished "<" that never became a tag). */
    fun flush(): String {
        val rest = if (inThink) "" else pending.toString()
        pending.setLength(0)
        inThink = false
        return rest
    }

    private fun couldBecomeTag(s: CharSequence): Boolean {
        if (s.length < THINK_OPEN.length && THINK_OPEN.startsWith(s)) return true
        // "<|" followed by only token-name characters so far.
        return s.length < 40 && s.startsWith("<|") && s.drop(2).all { it.isLetterOrDigit() || it == '_' }
    }

    private fun partialSuffix(s: CharSequence, tag: String): Int {
        for (n in minOf(tag.length - 1, s.length) downTo 1) {
            if (tag.startsWith(s.subSequence(s.length - n, s.length))) return n
        }
        return 0
    }

    companion object {
        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
        private val SPECIAL = Regex("<\\|[a-z_]+\\|>")
    }
}
