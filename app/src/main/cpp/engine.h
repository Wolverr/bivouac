#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "chat.h"
#include "llama.h"

struct common_sampler;

struct SamplingParams {
    float temp = 0.6f;
    float top_p = 0.95f;
    int   top_k = 20;
    float min_p = 0.0f;
    float repeat_penalty = 1.05f;
    int   max_tokens = 1024;
};

struct CompletionStats {
    int     n_reused = 0;    // prompt tokens served from the KV cache / prefix snapshot
    int     n_prefill = 0;   // prompt tokens actually evaluated
    int     n_gen = 0;
    int64_t t_prefill_us = 0;
    int64_t t_first_token_us = 0;
    int64_t t_gen_us = 0;
    bool    cancelled = false;
    std::string stop_reason;
    std::string error;
};

struct CompletionCallbacks {
    std::function<void(int done, int total)> on_prefill;
    // Receives UTF-8 text pieces; return false to stop generation.
    std::function<bool(const std::string & piece)> on_token;
};

// Single-model, single-sequence inference engine tuned for "long fixed
// instructions + per-question evidence" prompts.
class Engine {
public:
    static void init_backends(const std::string & native_lib_dir);

    // Returns an empty string on success, otherwise an error message.
    // `gen_cpus` run token-by-token generation (bandwidth-bound: fast cores only);
    // `batch_cpus` run prompt processing (compute-bound: more cores help).
    std::string load(const std::string & path, int n_ctx, int n_batch, const std::vector<int> & gen_cpus,
                     const std::vector<int> & batch_cpus, bool repack);
    void unload();
    bool loaded() const { return model_ != nullptr; }

    // Renders a conversation (system prompt + alternating user/assistant turns,
    // ending with a user turn) with the model's own chat template.
    std::string format_chat(const std::string & system,
                            const std::vector<std::pair<std::string, std::string>> & turns,
                            bool enable_thinking) const;

    // Evaluates prefix + rest and generates a reply. `prefix` should be the
    // stable part of the prompt (system instructions): it is evaluated once
    // and restored from a snapshot on later calls.
    CompletionStats complete(const std::string & prefix, const std::string & rest,
                             const SamplingParams & sp, const CompletionCallbacks & cb);

    void cancel() { cancel_.store(true); }

    std::string model_info_json() const;
    std::string bench(int n_prompt, int n_gen);

private:
    bool decode_tokens(const std::vector<llama_token> & toks, int n_past, bool last_logits,
                       const std::function<void(int, int)> & progress, int progress_base, int progress_total);

    llama_model   * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    ggml_threadpool_t threadpool_ = nullptr;
    ggml_threadpool_t threadpool_batch_ = nullptr;
    common_chat_templates_ptr templates_;
    int n_batch_ = 256;
    int n_threads_ = 4;
    int n_threads_batch_ = 4;

    std::vector<llama_token> kv_tokens_;        // tokens currently held in sequence 0
    std::vector<llama_token> snapshot_tokens_;  // prefix covered by snapshot_
    std::vector<uint8_t>     snapshot_;

    std::atomic<bool> cancel_{false};
};
