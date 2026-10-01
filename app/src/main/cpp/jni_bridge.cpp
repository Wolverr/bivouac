// JNI surface for io.github.wolverr.bivouac.llm.NativeEngine.
// Text crosses the boundary as UTF-8 byte arrays: JNI's NewStringUTF uses
// "modified UTF-8" and mangles emoji and other 4-byte characters.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "corpus.h"
#include "engine.h"
#include "json_util.h"
#include "model_probe.h"
#include "sql_pack.h"

static Engine g_engine;
static std::mutex g_mutex;  // serializes load/complete/unload; cancel() stays lock-free

static Corpus g_corpus;
static SqlPack g_places;
static std::mutex g_corpus_mutex;

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

static std::vector<int> to_ints(JNIEnv * env, jintArray arr) {
    std::vector<int> out;
    if (arr) {
        out.resize(env->GetArrayLength(arr));
        env->GetIntArrayRegion(arr, 0, (jsize) out.size(), out.data());
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeLoad)(JNIEnv * env, jobject, jbyteArray path, jint n_ctx, jint n_batch,
                                                jintArray gen_cpus, jintArray batch_cpus, jboolean repack) {
    const auto gen = to_ints(env, gen_cpus);
    const auto batch = to_ints(env, batch_cpus);
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_bytes(env, g_engine.load(to_string(env, path), n_ctx, n_batch, gen, batch, repack));
}

JNIEXPORT void JNICALL JNI_FN(nativeUnload)(JNIEnv *, jobject) {
    g_engine.cancel();
    std::lock_guard<std::mutex> lock(g_mutex);
    g_engine.unload();
}

// roles[i] / contents[i] are the conversation turns, oldest first.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeFormatChat)(JNIEnv * env, jobject, jbyteArray system, jobjectArray roles,
                                                      jobjectArray contents, jboolean thinking) {
    std::vector<std::pair<std::string, std::string>> turns;
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto * r = (jbyteArray) env->GetObjectArrayElement(roles, i);
        auto * c = (jbyteArray) env->GetObjectArrayElement(contents, i);
        turns.emplace_back(to_string(env, r), to_string(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_bytes(env, g_engine.format_chat(to_string(env, system), turns, thinking));
}

JNIEXPORT void JNICALL JNI_FN(nativeCancel)(JNIEnv *, jobject) {
    g_engine.cancel();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeModelInfo)(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_bytes(env, g_engine.model_info_json());
}

// Header-only read, safe while another model is loaded.
JNIEXPORT jbyteArray JNICALL JNI_FN(nativeProbe)(JNIEnv * env, jobject, jbyteArray path) {
    return to_bytes(env, probe_model_json(to_string(env, path)));
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
                                                    jfloat repeat_penalty, jint max_tokens, jint think_budget,
                                                    jobject listener) {
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
    sp.think_budget = think_budget;

    CompletionStats st;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        st = g_engine.complete(to_string(env, prefix), to_string(env, rest), sp, cb);
    }

    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"n_reused\":%d,\"n_prefill\":%d,\"n_gen\":%d,\"t_prefill_ms\":%.1f,\"t_first_token_ms\":%.1f,"
             "\"t_gen_ms\":%.1f,\"n_think\":%d,\"think_cut\":%s,\"cancelled\":%s,\"stop_reason\":\"%s\",\"error\":\"%s\"}",
             st.n_reused, st.n_prefill, st.n_gen, st.t_prefill_us / 1000.0, st.t_first_token_us / 1000.0,
             st.t_gen_us / 1000.0, st.n_think, st.think_cut ? "true" : "false", st.cancelled ? "true" : "false",
             st.stop_reason.c_str(), st.error.c_str());
    return to_bytes(env, buf);
}

// ---- Knowledge pack -------------------------------------------------------

JNIEXPORT jbyteArray JNICALL JNI_FN(corpusOpen)(JNIEnv * env, jobject, jbyteArray path) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    return to_bytes(env, g_corpus.open(to_string(env, path)));
}

JNIEXPORT void JNICALL JNI_FN(corpusClose)(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    g_corpus.close();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(corpusMeta)(JNIEnv * env, jobject, jbyteArray key) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    return to_bytes(env, g_corpus.meta(to_string(env, key)));
}

// Returns [{"chunk":..,"article":..,"title":"..","section":"..","score":..,"text":".."}] as JSON.
// boost_articles: chunks of these articles score `boost` higher, before the cut to `limit`.
JNIEXPORT jbyteArray JNICALL JNI_FN(corpusSearch)(JNIEnv * env, jobject, jbyteArray query, jint limit,
                                                  jdouble prior_weight, jboolean with_text,
                                                  jlongArray boost_articles, jdouble boost) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    std::vector<int64_t> boosted(env->GetArrayLength(boost_articles));
    if (!boosted.empty()) {
        env->GetLongArrayRegion(boost_articles, 0, (jsize) boosted.size(), reinterpret_cast<jlong *>(boosted.data()));
    }
    const auto hits = g_corpus.search(to_string(env, query), limit, prior_weight, boosted, boost);
    std::string out = "[";
    for (size_t i = 0; i < hits.size(); i++) {
        const auto & h = hits[i];
        std::string text, section;
        int64_t article = 0;
        if (with_text) g_corpus.chunk_text(h.chunk, text, section, article);
        char head[96];
        snprintf(head, sizeof(head), "{\"chunk\":%lld,\"article\":%lld,\"score\":%.4f,", (long long) h.chunk,
                 (long long) h.article, h.score);
        out += head;
        out += "\"title\":\"" + json_escape(h.title) + "\",\"section\":\"" + json_escape(h.section) +
               "\",\"text\":\"" + json_escape(text) + "\"}";
        if (i + 1 < hits.size()) out += ",";
    }
    out += "]";
    return to_bytes(env, out);
}

// Returns {"id":..,"title":"..","first_chunk":..,"n_chunks":..[,"lat":..,"lon":..]} or {} if missing.
JNIEXPORT jbyteArray JNICALL JNI_FN(corpusFindArticle)(JNIEnv * env, jobject, jbyteArray title, jbyteArray context) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    ArticleRef a;
    if (!g_corpus.find_article(to_string(env, title), a, to_string(env, context))) return to_bytes(env, "{}");
    char buf[192];
    int n = snprintf(buf, sizeof(buf), "{\"id\":%lld,\"first_chunk\":%lld,\"n_chunks\":%d,\"prior\":%.3f,",
                     (long long) a.id, (long long) a.first_chunk, a.n_chunks, a.prior);
    double lat, lon;
    if (g_corpus.coords(a.id, lat, lon)) snprintf(buf + n, sizeof(buf) - n, "\"lat\":%.6f,\"lon\":%.6f,", lat, lon);
    return to_bytes(env, std::string(buf) + "\"title\":\"" + json_escape(a.title) + "\"}");
}

// Returns {"article":..,"section":"..","text":".."} or {} if missing.
// JSON array of strings.
JNIEXPORT jbyteArray JNICALL JNI_FN(corpusOtherNames)(JNIEnv * env, jobject, jlong article, jint limit) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    std::string o = "[";
    for (const auto & n : g_corpus.other_names(article, limit)) {
        if (o.size() > 1) o += ",";
        o += "\"" + json_escape(n) + "\"";
    }
    return to_bytes(env, o + "]");
}

JNIEXPORT jint JNICALL JNI_FN(corpusDocFreq)(JNIEnv * env, jobject, jbyteArray query, jint cap) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    return g_corpus.doc_freq(to_string(env, query), cap);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(corpusChunk)(JNIEnv * env, jobject, jlong chunk) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    std::string text, section;
    int64_t article = 0;
    if (!g_corpus.chunk_text(chunk, text, section, article)) return to_bytes(env, "{}");
    return to_bytes(env, "{\"article\":" + std::to_string(article) + ",\"section\":\"" + json_escape(section) +
                             "\",\"text\":\"" + json_escape(text) + "\"}");
}

// ---- Places pack (generic read-only SQL) -----------------------------------

JNIEXPORT jbyteArray JNICALL JNI_FN(placesOpen)(JNIEnv * env, jobject, jbyteArray path) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    return to_bytes(env, g_places.open(to_string(env, path)));
}

JNIEXPORT void JNICALL JNI_FN(placesClose)(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    g_places.close();
}

// Returns {"rows":[[...],...],"error":"..."}.
JNIEXPORT jbyteArray JNICALL JNI_FN(placesQuery)(JNIEnv * env, jobject, jbyteArray sql, jobjectArray args) {
    std::vector<std::string> a;
    const jsize n = args ? env->GetArrayLength(args) : 0;
    for (jsize i = 0; i < n; i++) {
        auto * b = (jbyteArray) env->GetObjectArrayElement(args, i);
        a.push_back(to_string(env, b));
        env->DeleteLocalRef(b);
    }
    std::lock_guard<std::mutex> lock(g_corpus_mutex);
    std::string err;
    const std::string rows = g_places.query_json(to_string(env, sql), a, err);
    return to_bytes(env, "{\"rows\":" + rows + ",\"error\":\"" + json_escape(err) + "\"}");
}

}  // extern "C"
