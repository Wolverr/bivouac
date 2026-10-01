// Desktop test of the app's inference engine (app/src/main/cpp/engine.cpp) with a real model:
// the "Think harder" thinking budget, and a normal answer for comparison.
//
//   tools/native/engine_test/build.sh
//   tools/native/engine_test/build/engine_test ~/models/Qwen3.5-4B-Q4_K_M.gguf [budget]
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>

#include "engine.h"

static std::string read_file(const std::string & path) {
    std::ifstream f(path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

static void run(Engine & e, const std::string & system, const std::string & q, bool think, int budget) {
    const std::string full = e.format_chat(system, {{"user", q}}, think);
    // The app splits the prompt at the user turn so the system part is cached; one call is enough here.
    const size_t cut = full.find(q);
    const std::string prefix = full.substr(0, cut), rest = full.substr(cut);
    const std::string tail = rest.substr(rest.size() > 40 ? rest.size() - 40 : 0);
    std::printf("\n===== think=%d budget=%d  prompt tail: [%s]\n", think, budget, tail.c_str());

    SamplingParams sp;
    sp.temp = 0.4f;
    sp.max_tokens = 700 + (think ? budget : 0);
    sp.think_budget = think ? budget : -1;
    CompletionCallbacks cb;
    cb.on_token = [](const std::string & piece) {
        std::fputs(piece.c_str(), stdout);
        std::fflush(stdout);
        return true;
    };
    const CompletionStats st = e.complete(prefix, rest, sp, cb);
    std::printf("\n----- n_gen=%d n_think=%d think_cut=%d stop=%s error=%s\n", st.n_gen, st.n_think, st.think_cut,
                st.stop_reason.c_str(), st.error.c_str());
}

int main(int argc, char ** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s model.gguf [budget]\n", argv[0]);
        return 1;
    }
    const int budget = argc > 2 ? std::atoi(argv[2]) : 200;
    Engine::init_backends(LLAMA_PC_BIN);
    std::vector<int> cpus;
    for (int i = 0; i < 16; i++) cpus.push_back(i);
    Engine e;
    const std::string err = e.load(argv[1], 4096, 512, cpus, cpus, false);
    if (!err.empty()) {
        std::fprintf(stderr, "load failed: %s\n", err.c_str());
        return 1;
    }
    const std::string system = read_file(BIVOUAC_ROOT "/app/src/main/assets/prompts/system.txt");
    const std::string q = "A train leaves at 14:40 and the trip takes 3 h 55 min, crossing into a time zone one hour "
                          "ahead. What local time does it arrive?";
    run(e, system, q, /*think*/ false, 0);
    run(e, system, q, /*think*/ true, budget);
    return 0;
}
