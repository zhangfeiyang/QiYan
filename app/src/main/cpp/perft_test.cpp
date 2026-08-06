// 宿主机上验证 C++ 着法生成与 Kotlin 版逐层节点数完全一致。**不参与 Android
// 构建**(有 main(), 跟 host_test.cpp 是同一个约定)。
//
//   g++ -O2 -std=c++17 board.cpp movegen.cpp see.cpp perft_test.cpp -o /tmp/perft_test
//   /tmp/perft_test
//
// 期望值来自 Kotlin 侧 PerftGoldenGenerator.kt 在同一批局面上的实际输出
// (app/src/test/java/.../game/PerftGoldenGenerator.kt, 2026-08-01 跑的),
// Kotlin 引擎已经在真实对局和既有单测里跑了很久, 拿它当 ground truth。
#include <cstdio>
#include <cstdint>
#include <algorithm>
#include <string>

#include "board.h"
#include "movegen.h"

namespace {

int64_t perft(xq::Position& pos, int depth) {
    if (depth == 0) return 1;
    std::vector<xq::Move> moves;
    xq::generate_all_legal(pos, moves);
    if (depth == 1) return static_cast<int64_t>(moves.size());
    int64_t nodes = 0;
    for (auto& m : moves) {
        pos.do_move(m);
        nodes += perft(pos, depth - 1);
        pos.undo_move();
    }
    return nodes;
}

struct Case {
    const char* label;
    const char* fen;  // nullptr = 起始局面
    int max_depth;
    int64_t expected[5];  // expected[d] = perft(d), d 从 1 开始, 0 号不用
};

xq::Move parse_uci(const std::string& s) {
    return xq::Move(
        xq::sq_make(9 - (s[1] - '0'), s[0] - 'a'),
        xq::sq_make(9 - (s[3] - '0'), s[2] - 'a'));
}

bool play_uci(xq::Position& pos, const std::string& uci) {
    const xq::Move wanted = parse_uci(uci);
    std::vector<xq::Move> legal;
    xq::generate_all_legal(pos, legal);
    const auto it = std::find(legal.begin(), legal.end(), wanted);
    return it != legal.end() && pos.do_move(*it);
}

}  // namespace

int main() {
    const Case cases[] = {
        {"start", nullptr, 4, {0, 44, 1920, 79666, 3290240}},
        {"midgame", "rnbakab1r/9/6nc1/p3p1p1p/2p6/6P2/P1P1P3P/2NcC1NC1/9/R1BAKAB1R w - - 8 5",
         3, {0, 32, 1158, 38954, 0}},
        {"endgame", "4k4/9/9/9/9/9/9/4C4/4R4/4K4 w - - 0 1", 4, {0, 24, 59, 1802, 3211}},
    };

    int bad = 0;
    for (auto& c : cases) {
        xq::Position pos;
        if (c.fen == nullptr) {
            pos.setup_start_position();
        } else if (!pos.set_from_fen(c.fen)) {
            std::fprintf(stderr, "[perft] !! FEN 解析失败: %s\n", c.fen);
            ++bad;
            continue;
        }
        std::printf("[perft] %s fen=%s\n", c.label, pos.to_fen().c_str());
        for (int d = 1; d <= c.max_depth; ++d) {
            const int64_t got = perft(pos, d);
            const int64_t want = c.expected[d];
            const bool ok = got == want;
            std::printf("[perft]   depth=%d got=%lld want=%lld %s\n",
                        d, static_cast<long long>(got), static_cast<long long>(want),
                        ok ? "OK" : "!! 不一致");
            if (!ok) ++bad;
        }
    }

    // 搜索规则状态也属于 Position 的 make/unmake 不变式。马各自回原位两轮后，
    // 初始盘面共出现三次；撤销一步后应立即不再重复。
    {
        xq::Position rep;
        const int64_t initialPawnHash = rep.pawn_hash;
        const std::vector<std::string> cycle = {
            "b0c2", "b9c7", "c2b0", "c7b9",
            "b0c2", "b9c7", "c2b0", "c7b9",
        };
        for (const auto& uci : cycle) {
            if (!play_uci(rep, uci)) { ++bad; break; }
        }
        if (!rep.is_threefold_repetition() || !rep.is_search_rule_draw()) {
            std::fprintf(stderr, "[perft] !! C++ 三次重复历史未被识别\n");
            ++bad;
        }
        if (rep.pawn_hash != initialPawnHash || rep.pawn_hash != rep.compute_pawn_hash()) {
            std::fprintf(stderr, "[perft] !! 非兵着法污染 pawn hash\n");
            ++bad;
        }
        rep.undo_move();
        if (rep.is_threefold_repetition()) {
            std::fprintf(stderr, "[perft] !! undo 后重复历史没有回退\n");
            ++bad;
        }
    }

    {
        xq::Position pawn;
        const int64_t before = pawn.pawn_hash;
        if (!play_uci(pawn, "a3a4") || pawn.pawn_hash == before ||
            pawn.pawn_hash != pawn.compute_pawn_hash()) {
            std::fprintf(stderr, "[perft] !! 兵着法 pawn hash 增量错误\n");
            ++bad;
        }
        pawn.undo_move();
        if (pawn.pawn_hash != before || pawn.pawn_hash != pawn.compute_pawn_hash()) {
            std::fprintf(stderr, "[perft] !! undo 后 pawn hash 未恢复\n");
            ++bad;
        }
    }

    {
        xq::Position rule60;
        if (!rule60.set_from_fen(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 120 61") ||
            !rule60.is_search_rule_draw()) {
            std::fprintf(stderr, "[perft] !! 60 回合规则未被识别\n");
            ++bad;
        }
        rule60.do_null_move();
        if (rule60.is_search_rule_draw()) {
            std::fprintf(stderr, "[perft] !! 空着子树错误触发真实对局和棋\n");
            ++bad;
        }
        rule60.undo_null_move();
        if (!rule60.is_search_rule_draw()) {
            std::fprintf(stderr, "[perft] !! undo null 后规则状态未恢复\n");
            ++bad;
        }
    }

    if (bad) {
        std::fprintf(stderr, "[perft] !! %d 项不一致, C++ 与 Kotlin 着法生成器行为不同\n", bad);
        return 1;
    }
    std::printf("[perft] 全部一致: C++ 与 Kotlin 着法生成器在所有测试局面上逐层节点数相同\n");
    return 0;
}
