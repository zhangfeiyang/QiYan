// iOS counterpart of third_party/Pikafish/src/pikafish_jni.cpp: the same engine
// lifecycle and callbacks, exposed as plain C for Swift. Compiled into
// libpikafish.a together with the PikaFish sources by tools/build_pikafish.sh.
#include "pikafish_bridge.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <filesystem>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "engine.h"
#include "misc.h"
#include "position.h"
#include "score.h"
#include "search.h"
#include "ucioption.h"

using namespace Stockfish;

namespace {

std::mutex g_mutex;
std::unique_ptr<Engine> g_engine;
std::string g_bestmove;
std::string g_pondermove;
std::atomic<int> g_searching{0};

struct LastInfo {
    int depth = 0;
    int scoreCp = 0;
    int scoreMate = 0;
    long long nodes = 0;
    long long nps = 0;
    long long timeMs = 0;
};
LastInfo g_lastInfo;

struct PvCandidate {
    int scoreCp = 0;
    int scoreMate = 0;
    std::string move;
};
std::vector<PvCandidate> g_lines;

void parseScore(const Score &s, int &cp, int &mate) {
    cp = 0;
    mate = 0;
    if (s.is<Score::Mate>()) mate = s.get<Score::Mate>().plies;
    else if (s.is<Score::InternalUnits>()) cp = s.get<Score::InternalUnits>().value;
}

void setOption(Engine &e, const std::string &name, const std::string &value) {
    std::istringstream is("name " + name + " value " + value);
    e.get_options().setoption(is);
}

int copyOut(const std::string &s, char *buf, int len) {
    int n = (int)std::min(s.size(), (size_t)std::max(len, 0));
    if (n > 0) std::memcpy(buf, s.data(), n);
    return n;
}

}  // namespace

extern "C" {

int pf_init(const char *nnue_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    static bool tablesReady = false;
    if (!tablesReady) {
        Attacks::init();
        Position::init();
        tablesReady = true;
    }
    if (g_engine) {
        g_engine->stop();
        g_engine->wait_for_search_finished();
        g_engine.reset();
    }
    std::string path(nnue_path ? nnue_path : "");
    g_engine = std::make_unique<Engine>(std::filesystem::path(path));
    if (!g_engine) return -1;

    setOption(*g_engine, "Threads", "2");
    setOption(*g_engine, "Hash", "128");
    setOption(*g_engine, "MultiPV", "1");
    setOption(*g_engine, "EvalFile", path);
    g_engine->resize_threads();

    // Every callback must be set: the build has no exceptions, so an empty
    // std::function would abort the search.
    g_engine->set_on_verify_network([](std::string_view) {});
    g_engine->set_on_update_no_moves([](const Engine::InfoShort &) {});
    g_engine->set_on_iter([](const Engine::InfoIter &) {});
    g_engine->set_on_update_full([](const Engine::InfoFull &info) {
        int cp = 0, mate = 0;
        parseScore(info.score, cp, mate);
        std::string pv(info.pv);
        auto sep = pv.find(' ');
        std::string first = sep == std::string::npos ? pv : pv.substr(0, sep);
        if (!first.empty()) {
            PvCandidate c{cp, mate, first};
            size_t idx = (size_t)info.multiPV;
            if (idx >= 1 && idx <= g_lines.size()) g_lines[idx - 1] = c;
            else g_lines.push_back(c);
        }
        if (info.multiPV != 1) return;
        g_lastInfo.scoreCp = cp;
        g_lastInfo.scoreMate = mate;
        g_lastInfo.depth = info.depth;
        g_lastInfo.nodes = (long long)info.nodes;
        g_lastInfo.nps = (long long)info.nps;
        g_lastInfo.timeMs = (long long)info.timeMs;
    });
    g_engine->set_on_bestmove([](std::string_view best, std::string_view ponder) {
        g_bestmove = std::string(best);
        g_pondermove = std::string(ponder);
        g_searching = 0;
    });
    g_searching = 0;
    return 0;
}

void pf_set_option(const char *name, const char *value) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine || !name || !value) return;
    std::string n(name);
    setOption(*g_engine, n, value);
    if (n == "Threads") g_engine->resize_threads();
}

int pf_set_position(const char *fen, const char *const *moves, int count) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine || !fen) return -1;
    std::vector<std::string> list;
    for (int i = 0; i < count; ++i) list.emplace_back(moves[i]);
    auto err = g_engine->set_position(fen, list);
    return err.has_value() ? -1 : 0;
}

void pf_go(long long movetime_ms, int depth, int infinite) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;
    Search::LimitsType limits;
    limits.startTime = now();
    if (movetime_ms > 0) limits.movetime = (TimePoint)movetime_ms;
    if (depth > 0) limits.depth = depth;
    if (infinite > 0) limits.infinite = 1;
    g_bestmove.clear();
    g_pondermove.clear();
    g_lines.clear();
    // Old info belongs to the other side's position; clear it before polling starts.
    g_lastInfo = LastInfo{};
    g_searching = 1;
    g_engine->go(limits);
}

void pf_stop(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_engine) g_engine->stop();
}

void pf_wait_for_search(void) {
    if (g_engine) g_engine->wait_for_search_finished();
}

int pf_is_searching(void) { return g_searching.load(); }

int pf_bestmove(char *buf, int len) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return copyOut(g_bestmove, buf, len);
}

int pf_pondermove(char *buf, int len) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return copyOut(g_pondermove, buf, len);
}

void pf_last_info(long long out[6]) {
    std::lock_guard<std::mutex> lock(g_mutex);
    out[0] = g_lastInfo.depth;
    out[1] = g_lastInfo.scoreCp;
    out[2] = g_lastInfo.scoreMate;
    out[3] = g_lastInfo.timeMs;
    out[4] = g_lastInfo.nodes;
    out[5] = g_lastInfo.nps;
}

int pf_multipv(char *buf, int len) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string out;
    for (const auto &c : g_lines) {
        if (!out.empty()) out += ';';
        out += c.move + ":" + std::to_string(c.scoreCp) + ":" + std::to_string(c.scoreMate);
    }
    return copyOut(out, buf, len);
}

void pf_search_clear(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_engine) g_engine->search_clear();
}

void pf_shutdown(void) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;
    g_engine->stop();
    g_engine->wait_for_search_finished();
    g_engine.reset();
    g_searching = 0;
}

}  // extern "C"
