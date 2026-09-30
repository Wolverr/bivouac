#pragma once

#include <string>

// Reads a GGUF header (no weights) and estimates what a context will cost in
// RAM, so the app can pick a context size and repack mode before loading.
// Returns JSON (see model_probe.cpp) or {"error":"..."}.
std::string probe_model_json(const std::string & path);
