#include "engine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <sstream>

#include "common.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "sampling.h"

#define TAG "bivouac-engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void llama_log_to_android(ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: break;
    }
    __android_log_write(prio, "llama.cpp", text);
}

void Engine::init_backends(const std::string & native_lib_dir) {
    llama_log_set(llama_log_to_android, nullptr);
    // Loads every libggml-cpu-*.so variant; ggml keeps the best one this CPU supports.
    ggml_backend_load_all_from_path(native_lib_dir.c_str());
    llama_backend_init();
    LOGI("backends loaded from %s", native_lib_dir.c_str());
}

static std::string json_escape(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if ((unsigned char) c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

std::string Engine::load(const std::string & path, int n_ctx, int n_batch, const std::vector<int> & cpus) {
    unload();

    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    mp.n_gpu_layers = 0;
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) {
        return "could not load model file (corrupt, unsupported architecture, or out of memory)";
    }

    n_threads_ = cpus.empty() ? 4 : (int) cpus.size();
    n_batch_ = n_batch;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = n_batch;
    cp.n_ubatch = n_batch;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads_;
    cp.n_threads_batch = n_threads_;
    cp.no_perf = false;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) {
        llama_model_free(model_);
        model_ = nullptr;
        return "could not create context (not enough memory for the requested context size?)";
    }

    // A persistent threadpool pinned to the big cores. Letting the scheduler
    // move inference threads onto little cores roughly halves decode speed.
    ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (cpu_dev) {
        auto * reg = ggml_backend_dev_backend_reg(cpu_dev);
        auto * tp_new = (decltype(ggml_threadpool_new) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
        if (tp_new) {
            ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads_);
            for (int cpu : cpus) {
                if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) tpp.cpumask[cpu] = true;
            }
            tpp.strict_cpu = false;  // threads may use any core in the mask
            tpp.prio = GGML_SCHED_PRIO_HIGH;
            tpp.poll = 50;
            threadpool_ = tp_new(&tpp);
            if (threadpool_) {
                llama_attach_threadpool(ctx_, threadpool_, nullptr);
            } else {
                LOGW("threadpool creation failed; using ggml defaults");
            }
        }
    }

    templates_ = common_chat_templates_init(model_, "");
    kv_tokens_.clear();
    snapshot_tokens_.clear();
    snapshot_.clear();
    LOGI("loaded %s: n_ctx=%d n_batch=%d threads=%d", path.c_str(), n_ctx, n_batch, n_threads_);
    return "";
}

void Engine::unload() {
    if (ctx_) {
        llama_free(ctx_);
        ctx_ = nullptr;
    }
    if (threadpool_) {
        ggml_backend_dev_t cpu_dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        auto * reg = ggml_backend_dev_backend_reg(cpu_dev);
        auto * tp_free = (decltype(ggml_threadpool_free) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
        if (tp_free) tp_free(threadpool_);
        threadpool_ = nullptr;
    }
    if (model_) {
        llama_model_free(model_);
        model_ = nullptr;
    }
    templates_.reset();
    kv_tokens_.clear();
    snapshot_tokens_.clear();
    snapshot_.clear();
}

std::string Engine::format_chat(const std::string & system, const std::string & user, bool enable_thinking) const {
    if (!templates_) return "";
    common_chat_templates_inputs in;
    if (!system.empty()) {
        common_chat_msg sys;
        sys.role = "system";
        sys.content = system;
        in.messages.push_back(sys);
    }
    common_chat_msg usr;
    usr.role = "user";
    usr.content = user;
    in.messages.push_back(usr);
    in.add_generation_prompt = true;
    in.use_jinja = true;
    in.enable_thinking = enable_thinking;
    in.chat_template_kwargs["enable_thinking"] = enable_thinking ? "true" : "false";
    return common_chat_templates_apply(templates_.get(), in).prompt;
}

bool Engine::decode_tokens(const std::vector<llama_token> & toks, int n_past, bool last_logits,
                           const std::function<void(int, int)> & progress, int progress_base, int progress_total) {
    const int n = (int) toks.size();
    for (int i = 0; i < n; i += n_batch_) {
        if (cancel_.load()) return false;
        const int cur = std::min(n_batch_, n - i);
        llama_batch batch = llama_batch_init(cur, 0, 1);
        for (int j = 0; j < cur; j++) {
            common_batch_add(batch, toks[i + j], n_past + i + j, {0}, last_logits && (i + j == n - 1));
        }
        const int rc = llama_decode(ctx_, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            LOGE("llama_decode failed: %d", rc);
            return false;
        }
        kv_tokens_.insert(kv_tokens_.end(), toks.begin() + i, toks.begin() + i + cur);
        if (progress) progress(progress_base + i + cur, progress_total);
    }
    return true;
}

static bool starts_with(const std::vector<llama_token> & v, const std::vector<llama_token> & prefix) {
    return v.size() >= prefix.size() && std::equal(prefix.begin(), prefix.end(), v.begin());
}

CompletionStats Engine::complete(const std::string & prefix, const std::string & rest,
                                 const SamplingParams & sp, const CompletionCallbacks & cb) {
    CompletionStats st;
    cancel_.store(false);
    if (!ctx_) {
        st.error = "no model loaded";
        return st;
    }

    const auto pre = common_tokenize(ctx_, prefix, /*add_special*/ true, /*parse_special*/ true);
    const auto suf = common_tokenize(ctx_, rest, /*add_special*/ false, /*parse_special*/ true);
    const int n_ctx = (int) llama_n_ctx(ctx_);
    if ((int) (pre.size() + suf.size()) + 16 >= n_ctx) {
        st.error = "prompt too long for context";
        return st;
    }

    auto * mem = llama_get_memory(ctx_);
    const int64_t t0 = ggml_time_us();

    // 1) Get the prefix into the cache as cheaply as possible.
    bool have_prefix = false;
    if (!pre.empty() && starts_with(kv_tokens_, pre) &&
        llama_memory_seq_rm(mem, 0, (llama_pos) pre.size(), -1)) {
        // Attention-only models can simply drop everything after the prefix.
        kv_tokens_.resize(pre.size());
        have_prefix = true;
    } else if (!pre.empty() && pre == snapshot_tokens_ && !snapshot_.empty()) {
        // Recurrent/hybrid models can't truncate, so restore the saved state.
        llama_memory_clear(mem, true);
        if (llama_state_seq_set_data(ctx_, snapshot_.data(), snapshot_.size(), 0) > 0) {
            kv_tokens_ = pre;
            have_prefix = true;
        }
    }
    const int total = (int) (pre.size() + suf.size());
    if (have_prefix) {
        st.n_reused = (int) pre.size();
    } else {
        llama_memory_clear(mem, true);
        kv_tokens_.clear();
        if (!decode_tokens(pre, 0, false, cb.on_prefill, 0, total)) {
            st.cancelled = cancel_.load();
            st.error = st.cancelled ? "" : "decode failed";
            return st;
        }
        st.n_prefill += (int) pre.size();
        const size_t sz = llama_state_seq_get_size(ctx_, 0);
        snapshot_.resize(sz);
        llama_state_seq_get_data(ctx_, snapshot_.data(), sz, 0);
        snapshot_tokens_ = pre;
    }

    // 2) Evaluate the question-specific part.
    if (!decode_tokens(suf, (int) kv_tokens_.size(), true, cb.on_prefill, (int) pre.size(), total)) {
        st.cancelled = cancel_.load();
        st.error = st.cancelled ? "" : "decode failed";
        return st;
    }
    st.n_prefill += (int) suf.size();
    const int64_t t1 = ggml_time_us();
    st.t_prefill_us = t1 - t0;

    // 3) Generate.
    common_params_sampling sparams;
    sparams.temp = sp.temp;
    sparams.top_p = sp.top_p;
    sparams.top_k = sp.top_k;
    sparams.min_p = sp.min_p;
    sparams.penalty_repeat = sp.repeat_penalty;
    sparams.penalty_last_n = 128;
    common_sampler * smpl = common_sampler_init(model_, sparams);

    const llama_vocab * vocab = llama_model_get_vocab(model_);
    std::string pending;  // bytes of an incomplete UTF-8 sequence
    st.stop_reason = "length";
    for (int i = 0; i < sp.max_tokens; i++) {
        if (cancel_.load()) {
            st.cancelled = true;
            st.stop_reason = "cancelled";
            break;
        }
        if ((int) kv_tokens_.size() + 1 >= n_ctx) {
            st.stop_reason = "context";
            break;
        }
        const llama_token tok = common_sampler_sample(smpl, ctx_, -1);
        common_sampler_accept(smpl, tok, true);
        if (st.n_gen == 0) st.t_first_token_us = ggml_time_us() - t0;
        if (llama_vocab_is_eog(vocab, tok)) {
            st.stop_reason = "eos";
            break;
        }
        st.n_gen++;

        pending += common_token_to_piece(ctx_, tok, /*special*/ false);
        // Emit only complete UTF-8 characters.
        size_t cut = pending.size();
        for (size_t k = 1; k <= std::min<size_t>(4, pending.size()); k++) {
            const unsigned char c = pending[pending.size() - k];
            if ((c & 0xC0) == 0x80) continue;  // continuation byte
            const size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
            if (need > k) cut = pending.size() - k;
            break;
        }
        if (cut > 0) {
            const bool keep_going = cb.on_token ? cb.on_token(pending.substr(0, cut)) : true;
            pending.erase(0, cut);
            if (!keep_going) {
                st.stop_reason = "stop";
                break;
            }
        }

        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&tok), 1);
        if (llama_decode(ctx_, batch) != 0) {
            st.error = "decode failed during generation";
            break;
        }
        kv_tokens_.push_back(tok);
    }
    if (!pending.empty() && cb.on_token) cb.on_token(pending);
    common_sampler_free(smpl);
    st.t_gen_us = ggml_time_us() - t1;
    return st;
}

std::string Engine::model_info_json() const {
    if (!model_) return "{}";
    char desc[256];
    llama_model_desc(model_, desc, sizeof(desc));
    std::ostringstream o;
    o << "{\"desc\":\"" << json_escape(desc) << "\""
      << ",\"size_bytes\":" << llama_model_size(model_)
      << ",\"n_params\":" << llama_model_n_params(model_)
      << ",\"n_ctx\":" << (ctx_ ? llama_n_ctx(ctx_) : 0)
      << ",\"n_ctx_train\":" << llama_model_n_ctx_train(model_)
      << ",\"threads\":" << n_threads_
      << ",\"recurrent\":" << (llama_model_is_recurrent(model_) || llama_model_is_hybrid(model_) ? "true" : "false")
      << ",\"system\":\"" << json_escape(llama_print_system_info()) << "\"}";
    return o.str();
}

std::string Engine::bench(int n_prompt, int n_gen) {
    if (!ctx_) return "{\"error\":\"no model loaded\"}";
    auto * mem = llama_get_memory(ctx_);
    const llama_vocab * vocab = llama_model_get_vocab(model_);
    const int n_vocab = llama_vocab_n_tokens(vocab);

    std::vector<llama_token> toks(n_prompt);
    for (int i = 0; i < n_prompt; i++) toks[i] = (llama_token) ((i * 7919 + 13) % n_vocab);

    llama_memory_clear(mem, true);
    kv_tokens_.clear();
    snapshot_tokens_.clear();
    snapshot_.clear();

    const int64_t t0 = ggml_time_us();
    const bool ok = decode_tokens(toks, 0, true, nullptr, 0, 0);
    const int64_t t1 = ggml_time_us();
    if (!ok) return "{\"error\":\"prompt decode failed\"}";

    llama_token tok = toks.back();
    for (int i = 0; i < n_gen; i++) {
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx_, batch) != 0) return "{\"error\":\"gen decode failed\"}";
        tok = (llama_token) ((tok * 31 + 7) % n_vocab);
    }
    const int64_t t2 = ggml_time_us();
    llama_memory_clear(mem, true);
    kv_tokens_.clear();

    std::ostringstream o;
    o << "{\"pp_tokens\":" << n_prompt << ",\"pp_tps\":" << (n_prompt / ((t1 - t0) / 1e6))
      << ",\"tg_tokens\":" << n_gen << ",\"tg_tps\":" << (n_gen / ((t2 - t1) / 1e6)) << "}";
    return o.str();
}
