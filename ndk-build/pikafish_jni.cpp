/*
 * JNI wrapper for Pikafish (Stockfish-derived Xiangqi engine).
 *
 * Exposes 11 native functions callable from Kotlin via
 * `com.zhongguoxiangqi.engine.PikafishNative`. All calls are serialized
 * behind g_mutex — the Stockfish Engine is not thread-safe for concurrent
 * access.
 *
 * Build: drop this file into third_party/Pikafish/src/, then:
 *   EXTRACXXFLAGS=-fPIC make build ARCH=armv8 COMP=ndk EXE=libpikafish.so
 *
 * DO NOT confuse this with a subprocess executor — the engine runs IN-PROCESS,
 * loaded via System.loadLibrary("pikafish"). The .so is a real ET_DYN shared
 * object with JNI_OnLoad, not a renamed PIE executable.
 */

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
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

#define TAG "PikafishNative"

// ── global state ──────────────────────────────────────────────────────────
static std::mutex              g_mutex;
static std::unique_ptr<Engine> g_engine;
static std::string             g_bestmove;
static std::string             g_pondermove;
static std::atomic<int>        g_searching{0};

// Last search info — populated by on_update_full callback, read by
// getLastInfo JNI function. Protected by g_mutex (same lock as all
// other engine API calls).
struct LastInfo {
    int      depth     = 0;
    int      scoreCp   = 0;  // centipawns, >0 = red advantage
    int      scoreMate = 0;  // mate in N plies, 0 = not mate
    jlong    nodes     = 0;
    jlong    nps       = 0;
    jlong    timeMs    = 0;
};
static LastInfo g_lastInfo;

// Parse a Pikafish Score (std::variant<Mate, InternalUnits>) into
// centipawns and mate-in-N plies.
static void parseScore(const Score& s, int& outCp, int& outMate) {
    outCp   = 0;
    outMate = 0;
    if (s.is<Score::Mate>()) {
        outMate = s.get<Score::Mate>().plies;
    } else if (s.is<Score::InternalUnits>()) {
        outCp = s.get<Score::InternalUnits>().value;
    }
}

// ── helpers ───────────────────────────────────────────────────────────────

static std::string jstr2str(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* utf = env->GetStringUTFChars(js, nullptr);
    std::string s(utf);
    env->ReleaseStringUTFChars(js, utf);
    return s;
}

static void set_option(Engine& e, const std::string& name, const std::string& value) {
    std::string cmd = "name " + name + " value " + value;
    std::istringstream is(cmd);
    e.get_options().setoption(is);
}

// ── JNI exports ───────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_initGlobal(
    JNIEnv* env, jclass /*cls*/, jstring nnuePath)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    // One-time table generation. These are idempotent — calling
    // twice is harmless but costs nothing to guard.
    static bool tablesReady = false;
    if (!tablesReady) {
        Attacks::init();
        Position::init();
        tablesReady = true;
    }

    // Destroy any previous engine instance first
    if (g_engine) {
        g_engine->stop();
        g_engine->wait_for_search_finished();
        g_engine.reset();
    }

    std::string pathStr = jstr2str(env, nnuePath);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "initGlobal: nnue=%s", pathStr.c_str());

    // Engine constructor may throw std::bad_alloc on OOM, but upstream
    // is compiled with -fno-exceptions and uses nullptr checks instead.
    // The path hint helps the binary locate EvalFile relative to the
    // nnue directory.
    auto optPath = std::filesystem::path(pathStr);
    g_engine = std::make_unique<Engine>(optPath);
    if (!g_engine) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "initGlobal: failed to create Engine");
        return -1;
    }

    // Apply default options BEFORE resize_threads, so the ThreadPool
    // is created with the right thread count.
    set_option(*g_engine, "Threads", "2");
    set_option(*g_engine, "Hash",    "128");
    set_option(*g_engine, "MultiPV", "1");
    // Set EvalFile to the absolute path of the .nnue — this is the
    // defence-in-depth default that prevents the engine from searching
    // its own binary directory for a net that isn't there.
    set_option(*g_engine, "EvalFile", pathStr);
    g_engine->resize_threads();

    // The Engine's search output callback chain. Every one of these must be
    // set or the search will throw bad_function_call and abort() because the
    // binary is compiled with -fno-exceptions. The on_bestmove callback is
    // the only one we actually consume; the rest are no-ops that just log.

    g_engine->set_on_verify_network(
        [](std::string_view msg) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "verify_network: %.*s", (int) msg.size(), msg.data());
        });

    g_engine->set_on_update_no_moves(
        [](const Engine::InfoShort& info) {
            __android_log_print(ANDROID_LOG_VERBOSE, TAG,
                "info: depth=%d", info.depth);
        });

    g_engine->set_on_update_full(
        [](const Engine::InfoFull& info) {
            // With MultiPV>1 this fires once per PV line, best line first.
            // Only line 1 is the engine's actual evaluation of the position;
            // recording lines 2..N would leave g_lastInfo holding the score
            // of the Nth-*best* move, i.e. a systematically pessimistic eval.
            if (info.multiPV != 1) return;
            parseScore(info.score, g_lastInfo.scoreCp, g_lastInfo.scoreMate);
            g_lastInfo.depth  = info.depth;
            g_lastInfo.nodes  = static_cast<jlong>(info.nodes);
            g_lastInfo.nps    = static_cast<jlong>(info.nps);
            g_lastInfo.timeMs = static_cast<jlong>(info.timeMs);
            __android_log_print(ANDROID_LOG_VERBOSE, TAG,
                "info-full: depth=%d cp=%d mate=%d nodes=%zu",
                info.depth,
                g_lastInfo.scoreCp, g_lastInfo.scoreMate, info.nodes);
        });

    g_engine->set_on_iter(
        [](const Engine::InfoIter& info) {
            __android_log_print(ANDROID_LOG_VERBOSE, TAG,
                "info-iter: depth=%d currmove=%.*s",
                info.depth,
                (int) info.currmove.size(), info.currmove.data());
        });

    // The one callback we actually consume
    g_engine->set_on_bestmove(
        [](std::string_view best, std::string_view ponder) {
            g_bestmove   = std::string(best);
            g_pondermove = std::string(ponder);
            g_searching  = 0;
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "bestmove=%s ponder=%s",
                g_bestmove.c_str(), g_pondermove.c_str());
        });

    g_searching = 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "initGlobal: result=0");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_setoption(
    JNIEnv* env, jclass /*cls*/, jstring name, jstring value)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;

    auto nameStr  = jstr2str(env, name);
    auto valueStr = jstr2str(env, value);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "setoption: %s=%s", nameStr.c_str(), valueStr.c_str());

    set_option(*g_engine, nameStr, valueStr);

    // Threads and Hash need extra action after the option value is set
    if (nameStr == "Threads") {
        g_engine->resize_threads();
    }
    // Hash is handled by the OnChange callback registered in engine.cpp
}

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_setposition(
    JNIEnv* env, jclass /*cls*/,
    jstring fen, jobjectArray movesUci, jint movesCount)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return -1;

    auto fenStr = jstr2str(env, fen);

    std::vector<std::string> moves;
    for (jint i = 0; i < movesCount; ++i) {
        auto elem = (jstring) env->GetObjectArrayElement(movesUci, i);
        moves.push_back(jstr2str(env, elem));
        env->DeleteLocalRef(elem);
    }

    auto err = g_engine->set_position(fenStr, moves);
    return err.has_value() ? -1 : 0;
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_go(
    JNIEnv* /*env*/, jclass /*cls*/,
    jlong movetimeMs, jint depth, jint infinite)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;

    Search::LimitsType limits;
    // The UCI layer sets startTime = now() before go(). Without this, the
    // time-management machinery (tm.elapsed() → elapsed_time()) computes
    // elapsed relative to epoch=0, and the movetime check fires incorrectly.
    limits.startTime  = now();
    if (movetimeMs > 0) limits.movetime = static_cast<TimePoint>(movetimeMs);
    if (depth      > 0) limits.depth     = depth;
    if (infinite   > 0) limits.infinite  = 1;

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "go: movetime=%lld depth=%d infinite=%d startTime=%lld",
        (long long)movetimeMs, depth, infinite,
        (long long)limits.startTime);

    g_bestmove.clear();
    g_pondermove.clear();
    // Drop the previous search's info. Without this, the first getLastInfo()
    // poll of a new search returns the *old* search's depth/score — which
    // belongs to the other side's position, so the win rate shows up
    // inverted, and because that stale depth is higher than anything the new
    // search has reached yet, the caller's "only report deeper" filter then
    // freezes the display for the whole search.
    g_lastInfo = LastInfo{};
    g_searching = 1;
    g_engine->go(limits);
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_stop(
    JNIEnv* /*env*/, jclass /*cls*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;
    g_engine->stop();
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_waitForSearch(
    JNIEnv* /*env*/, jclass /*cls*/)
{
    // Do NOT hold g_mutex here — the search threads need to run.
    // wait_for_search_finished() is thread-safe on its own.
    if (!g_engine) return;
    g_engine->wait_for_search_finished();
}

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_getBestmove(
    JNIEnv* env, jclass /*cls*/, jbyteArray buf)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    jsize len = env->GetArrayLength(buf);
    jsize n   = (jsize) std::min(g_bestmove.size(), (size_t) len);
    if (n > 0) {
        env->SetByteArrayRegion(buf, 0, n,
            reinterpret_cast<const jbyte*>(g_bestmove.data()));
    }
    return n;
}

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_getPondermove(
    JNIEnv* env, jclass /*cls*/, jbyteArray buf)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    jsize len = env->GetArrayLength(buf);
    jsize n   = (jsize) std::min(g_pondermove.size(), (size_t) len);
    if (n > 0) {
        env->SetByteArrayRegion(buf, 0, n,
            reinterpret_cast<const jbyte*>(g_pondermove.data()));
    }
    return n;
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_searchClear(
    JNIEnv* /*env*/, jclass /*cls*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;
    g_engine->search_clear();
}

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_isSearching(
    JNIEnv* /*env*/, jclass /*cls*/)
{
    return g_searching.load();
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_getLastInfo(
    JNIEnv* env, jclass /*cls*/, jintArray out)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    // out[0]=depth,  out[1]=scoreCp, out[2]=scoreMate,
    // out[3]=timeMs (low 32), out[4]=nodes (low 32), out[5]=nps (low 32)
    jint data[6] = {
        static_cast<jint>(g_lastInfo.depth),
        static_cast<jint>(g_lastInfo.scoreCp),
        static_cast<jint>(g_lastInfo.scoreMate),
        static_cast<jint>(g_lastInfo.timeMs),
        static_cast<jint>(g_lastInfo.nodes),
        static_cast<jint>(g_lastInfo.nps)
    };
    env->SetIntArrayRegion(out, 0, 6, data);
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_PikafishNative_shutdown(
    JNIEnv* /*env*/, jclass /*cls*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) return;
    g_engine->stop();
    g_engine->wait_for_search_finished();
    g_engine.reset();
    g_searching = 0;
}

} // extern "C"
