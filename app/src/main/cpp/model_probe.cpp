#include "model_probe.h"

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <vector>

#include "gguf.h"
#include "json_util.h"

namespace {

// Integer value of a scalar key, or `def` when missing / not an integer.
int64_t get_int(const gguf_context * g, const std::string & key, int64_t def) {
    const int64_t id = gguf_find_key(g, key.c_str());
    if (id < 0) return def;
    switch (gguf_get_kv_type(g, id)) {
        case GGUF_TYPE_UINT8:  return gguf_get_val_u8(g, id);
        case GGUF_TYPE_INT8:   return gguf_get_val_i8(g, id);
        case GGUF_TYPE_UINT16: return gguf_get_val_u16(g, id);
        case GGUF_TYPE_INT16:  return gguf_get_val_i16(g, id);
        case GGUF_TYPE_UINT32: return gguf_get_val_u32(g, id);
        case GGUF_TYPE_INT32:  return gguf_get_val_i32(g, id);
        case GGUF_TYPE_UINT64: return (int64_t) gguf_get_val_u64(g, id);
        case GGUF_TYPE_INT64:  return gguf_get_val_i64(g, id);
        default: return def;
    }
}

// A per-layer key that may be stored as one scalar or as an array with one entry
// per layer (hybrid models use 0 for layers without attention).
std::vector<int64_t> get_per_layer(const gguf_context * g, const std::string & key, int n_layer, int64_t def) {
    std::vector<int64_t> out(n_layer, def);
    const int64_t id = gguf_find_key(g, key.c_str());
    if (id < 0) return out;
    if (gguf_get_kv_type(g, id) != GGUF_TYPE_ARRAY) {
        std::fill(out.begin(), out.end(), get_int(g, key, def));
        return out;
    }
    const size_t n = std::min<size_t>(gguf_get_arr_n(g, id), n_layer);
    const void * data = gguf_get_arr_data(g, id);
    for (size_t i = 0; i < n; i++) {
        switch (gguf_get_arr_type(g, id)) {
            case GGUF_TYPE_BOOL:
            case GGUF_TYPE_INT8:
            case GGUF_TYPE_UINT8:  out[i] = ((const uint8_t *) data)[i]; break;
            case GGUF_TYPE_INT32:  out[i] = ((const int32_t *) data)[i]; break;
            case GGUF_TYPE_UINT32: out[i] = ((const uint32_t *) data)[i]; break;
            case GGUF_TYPE_INT64:
            case GGUF_TYPE_UINT64: out[i] = ((const int64_t *) data)[i]; break;
            default: break;
        }
    }
    return out;
}

}  // namespace

// Output fields:
//   kv_full_per_token  bytes of f16 K+V per context token, layers with full attention
//   kv_swa_per_token   same for sliding-window layers, which only keep `swa_window` tokens
//   n_vocab, n_embd    for sizing the compute buffers (logits dominate them)
// Deliberately errs on the high side: an overestimate costs some context,
// an underestimate gets the app killed.
std::string probe_model_json(const std::string & path) {
    gguf_init_params params = {/*no_alloc =*/ true, /*ctx =*/ nullptr};
    gguf_context * g = gguf_init_from_file(path.c_str(), params);
    if (!g) return "{\"error\":\"not a readable GGUF file\"}";

    std::string arch;
    if (const int64_t id = gguf_find_key(g, "general.architecture"); id >= 0) arch = gguf_get_val_str(g, id);
    const std::string a = arch + ".";

    const int n_layer = (int) get_int(g, a + "block_count", 0);
    const int64_t n_embd = get_int(g, a + "embedding_length", 0);
    const auto n_head = get_per_layer(g, a + "attention.head_count", n_layer, 0);
    auto n_head_kv = get_per_layer(g, a + "attention.head_count_kv", n_layer, -1);
    for (int i = 0; i < n_layer; i++) {
        if (n_head_kv[i] < 0) n_head_kv[i] = n_head[i];  // no GQA: one KV head per query head
    }
    const int64_t head_dim = n_head.empty() || n_head[0] == 0 ? 0 : n_embd / n_head[0];
    const int64_t k_len = get_int(g, a + "attention.key_length", head_dim);
    const int64_t v_len = get_int(g, a + "attention.value_length", k_len);
    const int64_t k_len_swa = get_int(g, a + "attention.key_length_swa", k_len);
    const int64_t v_len_swa = get_int(g, a + "attention.value_length_swa", v_len);
    // Gemma 3n/4: the last layers reuse earlier layers' KV instead of keeping their own.
    const int64_t n_kv_layers = n_layer - get_int(g, a + "attention.shared_kv_layers", 0);

    // Qwen3-Next style hybrids: every Nth layer has attention, the rest are recurrent.
    const int64_t full_attn_interval = get_int(g, a + "full_attention_interval", 0);
    // Gemma style sliding windows: per-layer pattern, true = windowed.
    const int64_t swa_window = get_int(g, a + "attention.sliding_window", 0);
    const auto swa_pattern = get_per_layer(g, a + "attention.sliding_window_pattern", n_layer, -1);

    int64_t full = 0, swa = 0;
    int n_attn = 0;
    for (int i = 0; i < n_kv_layers; i++) {
        if (full_attn_interval > 0 && (i + 1) % full_attn_interval != 0) continue;
        if (n_head_kv[i] == 0) continue;
        n_attn++;
        if (swa_window > 0 && swa_pattern[i] == 1) swa += n_head_kv[i] * (k_len_swa + v_len_swa) * 2;  // f16 cache
        else full += n_head_kv[i] * (k_len + v_len) * 2;
    }

    int64_t n_vocab = 0;
    if (const int64_t id = gguf_find_key(g, "tokenizer.ggml.tokens"); id >= 0) n_vocab = (int64_t) gguf_get_arr_n(g, id);

    std::ostringstream o;
    o << "{\"arch\":\"" << json_escape(arch) << "\""
      << ",\"n_layer\":" << n_layer << ",\"n_attn_layers\":" << n_attn
      << ",\"n_embd\":" << n_embd << ",\"n_vocab\":" << n_vocab
      << ",\"n_ctx_train\":" << get_int(g, a + "context_length", 0)
      << ",\"kv_full_per_token\":" << full << ",\"kv_swa_per_token\":" << swa
      << ",\"swa_window\":" << swa_window << ",\"error\":\"\"}";
    gguf_free(g);
    return o.str();
}
