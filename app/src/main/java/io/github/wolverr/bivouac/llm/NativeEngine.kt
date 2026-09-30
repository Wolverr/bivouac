package io.github.wolverr.bivouac.llm

/** Thin JNI surface over app/src/main/cpp/jni_bridge.cpp. All text is UTF-8 bytes. */
internal object NativeEngine {
    init {
        System.loadLibrary("bivouac")
    }

    interface Listener {
        fun onPrefill(done: Int, total: Int)

        /** Return false to stop generating. */
        fun onToken(piece: ByteArray): Boolean
    }

    external fun nativeInit(nativeLibDir: ByteArray)
    external fun nativeLoad(path: ByteArray, nCtx: Int, nBatch: Int, cpus: IntArray, repack: Boolean): ByteArray
    external fun nativeUnload()
    external fun nativeFormatChat(system: ByteArray, user: ByteArray, thinking: Boolean): ByteArray
    external fun nativeCancel()
    external fun nativeModelInfo(): ByteArray
    external fun nativeBench(nPrompt: Int, nGen: Int): ByteArray
    external fun nativeComplete(
        prefix: ByteArray,
        rest: ByteArray,
        temp: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        listener: Listener,
    ): ByteArray

    // Knowledge pack (wiki.db); JSON results, see jni_bridge.cpp.
    external fun corpusOpen(path: ByteArray): ByteArray
    external fun corpusClose()
    external fun corpusMeta(key: ByteArray): ByteArray
    external fun corpusSearch(query: ByteArray, limit: Int, priorWeight: Double, withText: Boolean): ByteArray
    external fun corpusFindArticle(title: ByteArray): ByteArray
    external fun corpusChunk(chunk: Long): ByteArray
}
