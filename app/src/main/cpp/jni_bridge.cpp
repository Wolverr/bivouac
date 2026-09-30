// JNI surface for io.github.wolverr.bivouac.llm.NativeEngine.
// Text crosses the boundary as UTF-8 byte arrays: JNI's NewStringUTF uses
// "modified UTF-8" and mangles emoji and other 4-byte characters.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "engine.h"

static Engine g_engine;
static std::mutex g_mutex;  // serializes load/complete/unload; cancel() stays lock-free

static std::string to_string(JNIEnv * env, jbyteArray arr) {
    if (!arr) return "";
    const jsize n = env->GetArrayLength(arr);
    std::string s(n, '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

static jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

#define JNI_FN(name) Java_io_github_wolverr_bivouac_llm_NativeEngine_##name

extern "C" {

JNIEXPORT void JNICALL JNI_FN(nativeInit)(JNIEnv * env, jobject, jbyteArray lib_dir) {
    Engine::init_backends(to_string(env, lib_dir));
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeLoad)(JNIEnv * env, jobject, jbyteArray path, jint n_ctx, jint n_batch,
                                                jintArray cpus) {
    std::vector<int> cpu_list;
    if (cpus) {
        const jsize n = env->GetArrayLength(cpus);
        cpu_list.resize(n);
        env->GetIntArrayRegion(cpus, 0, n, cpu_list.data());
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_bytes(env, g_engine.load(to_string(env, path), n_ctx, n_batch, cpu_list));
}

JNIEXPORT void JNICALL JNI_FN(nativeUnload)(JNIEnv *, jobject) {
    g_engine.cancel();
    std::lock_guard<std::mutex> lock(g_mutex);
    g_engine.unload();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeFormatChat)(JNIEnv * env, jobject, jbyteArray system, jbyteArray user,
                                                      jboolean thinking) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_bytes(env, g_engine.format_chat(to_string(env, system), to_string(env, user), thinking));
}

JNIEXPORT void JNICALL JNI_FN(nativeCancel)(JNIEnv *, jobject) {
    g_engine.cancel();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeModelInfo)(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_bytes(env, g_engine.model_info_json());
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeBench)(JNIEnv * env, jobject, jint n_prompt, jint n_gen) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_bytes(env, g_engine.bench(n_prompt, n_gen));
}

// listener implements NativeEngine.Listener:
//   fun onPrefill(done: Int, total: Int)
//   fun onToken(piece: ByteArray): Boolean
// Returns stats as a JSON byte array.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeComplete)(JNIEnv * env, jobject, jbyteArray prefix, jbyteArray rest,
                                                    jfloat temp, jfloat top_p, jint top_k, jfloat min_p,
                                                    jfloat repeat_penalty, jint max_tokens, jobject listener) {
    jclass cls = env->GetObjectClass(listener);
    jmethodID on_prefill = env->GetMethodID(cls, "onPrefill", "(II)V");
    jmethodID on_token = env->GetMethodID(cls, "onToken", "([B)Z");

    CompletionCallbacks cb;
    cb.on_prefill = [&](int done, int total) {
        env->CallVoidMethod(listener, on_prefill, done, total);
    };
    cb.on_token = [&](const std::string & piece) -> bool {
        jbyteArray arr = to_bytes(env, piece);
        const jboolean keep = env->CallBooleanMethod(listener, on_token, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) return false;
        return keep;
    };

    SamplingParams sp;
    sp.temp = temp;
    sp.top_p = top_p;
    sp.top_k = top_k;
    sp.min_p = min_p;
    sp.repeat_penalty = repeat_penalty;
    sp.max_tokens = max_tokens;

    CompletionStats st;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        st = g_engine.complete(to_string(env, prefix), to_string(env, rest), sp, cb);
    }

    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"n_reused\":%d,\"n_prefill\":%d,\"n_gen\":%d,\"t_prefill_ms\":%.1f,\"t_first_token_ms\":%.1f,"
             "\"t_gen_ms\":%.1f,\"cancelled\":%s,\"stop_reason\":\"%s\",\"error\":\"%s\"}",
             st.n_reused, st.n_prefill, st.n_gen, st.t_prefill_us / 1000.0, st.t_first_token_us / 1000.0,
             st.t_gen_us / 1000.0, st.cancelled ? "true" : "false", st.stop_reason.c_str(), st.error.c_str());
    return to_bytes(env, buf);
}

}  // extern "C"
