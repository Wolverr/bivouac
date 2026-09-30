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
    external fun nativeLoad(path: ByteArray, nCtx: Int, nBatch: Int, cpus: IntArray): ByteArray
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
}
