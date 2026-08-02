// 一次性诊断: 同样深度6, 我们的C++引擎和Pikafish各花多少时间/节点数.
// 局面用 Pikafish 自己在深度6下给出的着法序列(由 pika_depth6_nodes.py 跑出,
// 保证合法且是两边一致的同一批局面)。不参与常规构建, 用法同 perft_test.cpp。
//
//   g++ -O2 -std=c++17 board.cpp movegen.cpp see.cpp nnue_core.cpp search.cpp \
//   depth6_probe.cpp -o /tmp/depth6_probe
//   /tmp/depth6_probe <net.nnue>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "board.h"
#include "nnue_core.h"
#include "search.h"

namespace { long g_depth_sum = 0; }
namespace {
struct Loaded {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b;
    std::vector<int32_t> out_w;
    std::vector<int8_t> fc1_w, fc2_w;
    std::vector<int32_t> fc1_b, fc2_b;
    int32_t out_b = 0;
};
template <typename T>
bool take(std::ifstream& f, std::vector<T>& v, size_t n) {
    v.resize(n);
    f.read(reinterpret_cast<char*>(v.data()), n * sizeof(T));
    return static_cast<size_t>(f.gcount()) == n * sizeof(T);
}
bool load(const char* path, Loaded& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    char magic[4]; f.read(magic, 4);
    if (std::memcmp(magic, "XQNN", 4) != 0) return false;
    int32_t h[7]; f.read(reinterpret_cast<char*>(h), sizeof(h));
    const int l1 = h[2], l2 = h[3], l3 = h[4];
    bool ok = take(f, out.ft_w, size_t(h[1]) * l1) && take(f, out.ft_b, l1) &&
              take(f, out.fc1_w, size_t(l2) * l1 * 2) && take(f, out.fc1_b, l2) &&
              take(f, out.fc2_w, size_t(l3) * l2) && take(f, out.fc2_b, l3) &&
              take(f, out.out_w, l3);
    f.read(reinterpret_cast<char*>(&out.out_b), 4);
    if (!ok || f.gcount() != 4) return false;
    out.net = nnue::Net{l1, l2, l3, out.ft_w.data(), out.ft_b.data(), out.fc1_w.data(),
                        out.fc1_b.data(), out.fc2_w.data(), out.fc2_b.data(),
                        out.out_w.data(), out.out_b};
    return true;
}
xq::Move parse_uci(const std::string& s) {
    return xq::Move(xq::sq_make(9 - (s[1] - '0'), s[0] - 'a'),
                    xq::sq_make(9 - (s[3] - '0'), s[2] - 'a'));
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
}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "用法: %s <net.nnue> [depth=6]\n", argv[0]); return 2; }
    Loaded L;
    if (!load(argv[1], L) || !nnue::validate(L.net)) { std::fprintf(stderr, "网络加载失败\n"); return 1; }
    // 深度可调: 深度 6 太浅, 空着裁剪/LMR 这些 depth>=3 才生效的剪枝几乎
    // 没展开, 量不出剪枝改动的真实效果。要看分支因子得往深了搜。
    const int probe_depth = (argc >= 3) ? std::atoi(argv[2]) : 6;
    // argv[3..] 里可以逐个关掉新特性, 用来把"哪一项让节点数变多了"隔离出来。
    // 形如: cont=0 dlmr=0 lmp=0 fut=0
    bool f_cont = true, f_dlmr = true, f_lmp = true, f_fut = true;
    bool f_iir = true, f_seep = true, f_razor = true, f_caps4 = false;
    bool f_ch = true; int f_cp = 2; int f_mt = 0; int f_tt = -1; bool f_samegen = false; int f_dp = -1; int f_badcap = -1; int f_bcb = -1;   // -1 = 用引擎默认   // -1 = 用引擎默认, 不覆盖
    for (int i = 3; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "cont=0") f_cont = false;
        else if (a == "dlmr=0") f_dlmr = false;
        else if (a == "lmp=0") f_lmp = false;
        else if (a == "fut=0") f_fut = false;
        else if (a == "iir=0") f_iir = false;
        else if (a == "seep=0") f_seep = false;
        else if (a == "razor=0") f_razor = false;
        else if (a == "caps=4") f_caps4 = true;
        else if (a == "ch=0") f_ch = false;
        else if (a.rfind("cp=", 0) == 0) f_cp = std::atoi(a.c_str() + 3);
        else if (a.rfind("tt=", 0) == 0) f_tt = std::atoi(a.c_str() + 3);
        else if (a == "samegen=1") f_samegen = true;
        else if (a.rfind("mt=", 0) == 0) f_mt = std::atoi(a.c_str() + 3);
        else if (a == "badcap=0") f_badcap = 0;
        else if (a == "badcap=1") f_badcap = 1;
        else if (a.rfind("bcb=", 0) == 0) { f_bcb = std::atoi(a.c_str() + 4); f_badcap = 1; }
        else if (a.rfind("dp=", 0) == 0) f_dp = std::atoi(a.c_str() + 3);
    }

    // Pikafish 自己在深度6下给出的着法序列(2026-08-01 用 pika_depth6_nodes.py 跑出)
    const std::vector<std::string> uci_moves = {
        "h2e2", "h9g7", "h0g2", "i9h9", "i0h0", "g6g5", "c3c4", "h7h3",
    };

    xq::SearchEngine engine(L.net);
    engine.use_cont_hist = f_cont;
    engine.use_dynamic_lmr = f_dlmr;
    engine.use_lmp = f_lmp;
    engine.use_futility = f_fut;
    engine.use_iir = f_iir;
    engine.use_see_prune = f_seep;
    engine.use_razoring = f_razor;
    engine.use_capture_hist = f_ch;
    engine.cont_plies = f_cp;
    if (f_tt > 0) engine.set_tt_size_shift(f_tt);
    engine.tt_require_same_gen = f_samegen;
    if (f_badcap >= 0) engine.use_bad_capture_ordering = (f_badcap != 0);
    if (f_bcb != -1) engine.bad_capture_base = f_bcb;
    if (f_dp >= 0) engine.tt_depth_preference = f_dp;
    if (f_caps4) { engine.futility_max_depth = 4; engine.lmp_max_depth = 4; }
    xq::Position& pos = engine.position();
    pos.setup_start_position();

    std::vector<double> times;
    std::vector<int64_t> nodes_list;
    for (size_t i = 0; i < uci_moves.size(); ++i) {
        engine.set_position(pos);
        const auto t0 = std::chrono::steady_clock::now();
        auto [move, score] = engine.search_with_score(probe_depth, f_mt);
        const auto t1 = std::chrono::steady_clock::now();
        const double dt = std::chrono::duration<double, std::milli>(t1 - t0).count();
        const int64_t nodes = engine.last_search_nodes();
        times.push_back(dt);
        nodes_list.push_back(nodes);
        std::printf("[d%d] 第%zu步 耗时=%.2fms nodes=%lld 到达深度=%d move=%s score=%d\n",
                    probe_depth, i + 1, dt, static_cast<long long>(nodes),
                    engine.last_search_depth(), to_uci(move).c_str(), score);
        g_depth_sum += engine.last_search_depth();
        pos = engine.position();
        pos.do_move(parse_uci(uci_moves[i]));
    }

    double sum_t = 0; int64_t sum_n = 0;
    for (size_t i = 0; i < times.size(); ++i) { sum_t += times[i]; sum_n += nodes_list[i]; }
    std::printf("[d%d] 平均到达深度=%.2f\n", probe_depth, double(g_depth_sum)/times.size());
    std::printf("[d%d] 平均耗时=%.2fms 平均节点=%.1f NPS≈%.0f\n",
                probe_depth, sum_t / times.size(), double(sum_n) / nodes_list.size(),
                double(sum_n) / (sum_t / 1000.0));
    return 0;
}
