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

    if (bad) {
        std::fprintf(stderr, "[perft] !! %d 项不一致, C++ 与 Kotlin 着法生成器行为不同\n", bad);
        return 1;
    }
    std::printf("[perft] 全部一致: C++ 与 Kotlin 着法生成器在所有测试局面上逐层节点数相同\n");
    return 0;
}
