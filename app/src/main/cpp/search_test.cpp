// 宿主机上验证 C++ 搜索本体与 Kotlin SimpleEngine.searchWithScore 是否一致.
// **不参与 Android 构建**。
//
//   g++ -O2 -std=c++17 board.cpp movegen.cpp see.cpp nnue_core.cpp search.cpp
//   search_test.cpp -o /tmp/search_test
//   /tmp/search_test <net.nnue>
//
// 期望值来自 Kotlin 侧 SearchGoldenGenerator.kt 在同一串开局着法上的实际
// 输出(app/src/test/java/.../engine/eval/SearchGoldenGenerator.kt)。
//
// **不要求节点数逐位相同**: 排序里遇到并列分数时, Kotlin 的 stableSort 和
// C++ 的 std::stable_sort 只保证"实现内部一致", 不保证两种语言的排序算法
// 对同分着法给出完全相同的相对顺序, 而 LMR 的削减量看的是着法**序号**——
// 序号一旦有一点点不同, 后续节点数就会跟着漂。真正要验的是**着法和分数
// 一致**(这两个不该因为并列顺序而变), 节点数只做量级校验(差距不该离谱)。
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <vector>

#include "board.h"
#include "nnue_core.h"
#include "search.h"

namespace {

struct Loaded {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b, psqt_w;
    std::vector<int32_t> out_w, out_b;
    std::vector<int8_t> fc1_w, fc2_w;
    std::vector<int32_t> fc1_b, fc2_b;
};

template <typename T>
bool take(std::ifstream& f, std::vector<T>& v, size_t n) {
    v.resize(n);
    f.read(reinterpret_cast<char*>(v.data()), n * sizeof(T));
    return static_cast<size_t>(f.gcount()) == n * sizeof(T);
}

bool load(const char* path, Loaded& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { std::fprintf(stderr, "打不开 %s\n", path); return false; }
    char magic[4];
    f.read(magic, 4);
    if (std::memcmp(magic, "XQNN", 4) != 0) { std::fprintf(stderr, "magic 不对\n"); return false; }
    int32_t h[7];
    f.read(reinterpret_cast<char*>(h), sizeof(h));
    const int version = h[0], features = h[1];
    const int l1 = h[2], l2 = h[3], l3 = h[4], qa = h[5], qw = h[6];
    int32_t phase_buckets = 1;
    int32_t psqt_q = 0;
    if (version >= 4) {
        f.read(reinterpret_cast<char*>(&phase_buckets), 4);
        if (f.gcount() != 4) return false;
    }
    if (version == 5) {
        f.read(reinterpret_cast<char*>(&psqt_q), 4);
        if (f.gcount() != 4 || psqt_q <= 0) return false;
    }
    if ((version != 3 && version != 4 && version != 5) || features != nnue::kFeatures ||
        qa != nnue::kQA || qw != nnue::kQW ||
        (phase_buckets != 1 && phase_buckets != nnue::kPhaseBuckets)) {
        std::fprintf(stderr, "文件头对不上\n");
        return false;
    }
    bool ok = take(f, out.ft_w, size_t(features) * l1) && take(f, out.ft_b, l1) &&
              take(f, out.fc1_w, size_t(phase_buckets) * l2 * l1 * 2) &&
              take(f, out.fc1_b, size_t(phase_buckets) * l2) &&
              take(f, out.fc2_w, size_t(phase_buckets) * l3 * l2) &&
              take(f, out.fc2_b, size_t(phase_buckets) * l3) &&
              take(f, out.out_w, size_t(phase_buckets) * l3) &&
              take(f, out.out_b, phase_buckets) &&
              (version != 5 || take(f, out.psqt_w, features));
    if (!ok) { std::fprintf(stderr, "权重读不全\n"); return false; }
    out.net = nnue::Net{l1, l2, l3, out.ft_w.data(), out.ft_b.data(),
                        out.fc1_w.data(), out.fc1_b.data(),
                        out.fc2_w.data(), out.fc2_b.data(), out.out_w.data(), out.out_b[0]};
    out.net.phase_buckets = phase_buckets;
    out.net.out_buckets = out.out_b.data();
    out.net.psqt_w = version == 5 ? out.psqt_w.data() : nullptr;
    out.net.psqt_q = psqt_q;
    return true;
}

// 与 Kotlin UciCoords 一致: rank digit -> row = 9 - digit (自反)
xq::Move parse_uci(const std::string& s) {
    const int from_col = s[0] - 'a';
    const int from_row = 9 - (s[1] - '0');
    const int to_col = s[2] - 'a';
    const int to_row = 9 - (s[3] - '0');
    return xq::Move(xq::sq_make(from_row, from_col), xq::sq_make(to_row, to_col));
}

std::string to_uci(const xq::Move& m) {
    char buf[5];
    buf[0] = static_cast<char>('a' + xq::sq_col(m.from()));
    buf[1] = static_cast<char>('0' + (9 - xq::sq_row(m.from())));
    buf[2] = static_cast<char>('a' + xq::sq_col(m.to()));
    buf[3] = static_cast<char>('0' + (9 - xq::sq_row(m.to())));
    buf[4] = 0;
    return buf;
}

struct Golden {
    int ply;
    std::string move;
    int score;
    int64_t nodes;
};

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "用法: %s <net.nnue>\n", argv[0]); return 2; }
    Loaded L;
    if (!load(argv[1], L)) return 1;
    if (!nnue::validate(L.net)) { std::fprintf(stderr, "网络尺寸不合法\n"); return 1; }

    // 与 SearchGoldenGenerator.kt 里的期望值一一对应(2026-08-01 用
    // net328m.nnue 跑出来的实际输出), 硬编码在这里, 跟 perft/see 是同一个
    // 约定。这三个数(着法/分数/节点数)逐条精确相同, 不是"量级差不多"——
    // 排序稳定性和 LMR 的着法序号敏感度反而让"精确相同"成为更强的信号:
    // 两边从排序到剪枝的每一步都在做完全一样的事。
    const std::vector<Golden> golden = {
        {0, "b2e2", 36, 5814},
        {1, "h7e7", -49, 5754},
        {2, "b0c2", 50, 3968},
        {3, "h7i7", -23, 5170},
        {4, "b0c2", 41, 2994},
        {5, "b9c7", -50, 4699},
        {6, "a0b0", 66, 3090},
        {7, "b9c7", -69, 4957},
    };

    const std::vector<std::string> uci_moves = {
        "b2e2", "h9g7", "g3g4", "c6c5", "b0c2", "b7d7", "h0g2", "d7d2",
    };

    xq::SearchEngine engine(L.net);
    // LMP/futility 是 Kotlin 版没有的新剪枝, 开着的话这个对拍测出来的差异
    // 只会是"C++ 多剪了几支", 不再能证明两边的搜索骨架一致 —— 而后者正是
    // 这个测试存在的意义。奇异延伸/multi-cut 不用关: 它们 depth>=4 才触发,
    // 深度 3 的搜索根本走不到。
    engine.use_lmp = false;
    engine.use_futility = false;
    engine.use_cont_hist = false;
    engine.use_dynamic_lmr = false;
    engine.use_root_pvs = false;
    engine.use_iir = false;
    engine.use_see_prune = false;
    engine.use_razoring = false;
    engine.futility_max_depth = 4;
    engine.lmp_max_depth = 4;
    engine.use_capture_hist = false;
    engine.tt_require_same_gen = true;
    engine.use_bad_capture_ordering = false;  // Kotlin 版是要求同世代的
    xq::Position& pos = engine.position();
    pos.setup_start_position();

    int bad = 0;
    for (size_t i = 0; i < uci_moves.size(); ++i) {
        engine.set_position(pos);
        auto [move, score] = engine.search_with_score(3);
        const int64_t nodes = engine.last_search_nodes();
        const std::string got_move = to_uci(move);
        const Golden& g = golden[i];
        const bool ok = got_move == g.move && score == g.score && nodes == g.nodes;
        std::printf("[search] ply=%zu move=%s score=%d nodes=%lld  期望 move=%s score=%d nodes=%lld  %s\n",
                    i, got_move.c_str(), score, static_cast<long long>(nodes),
                    g.move.c_str(), g.score, static_cast<long long>(g.nodes),
                    ok ? "OK" : "!! 不一致");
        if (!ok) ++bad;

        pos = engine.position();
        const xq::Move next = parse_uci(uci_moves[i]);
        if (!pos.do_move(next)) { std::fprintf(stderr, "着法失败 %s\n", uci_moves[i].c_str()); return 1; }
    }
    if (bad) {
        std::fprintf(stderr, "[search] !! %d/%zu 步不一致, C++ 与 Kotlin 搜索行为不同\n",
                     bad, uci_moves.size());
        return 1;
    }
    std::printf("[search] 全部一致: C++ 搜索与 Kotlin SimpleEngine.searchWithScore 逐条相同"
                "(着法/分数/节点数)\n");
    return 0;
}
