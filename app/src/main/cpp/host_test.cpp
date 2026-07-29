// 宿主机上验证 C++ 推理核心。**不参与 Android 构建**。
//
//   g++ -O2 -std=c++17 nnue_core.cpp host_test.cpp -o /tmp/nnue_host_test
//   /tmp/nnue_host_test ../test/resources/nnue/test.nnue ../test/resources/nnue/golden.txt
//
// 为什么要有它: 我在开发机上没有 ARM 硬件也没有模拟器, 而 NNUE 的错误
// 不会崩溃, 只会让棋力莫名其妙地差。至少要让标量路径在推上真机之前就
// 用黄金向量证明过 —— 那批向量的期望值出自 Python 参考实现, 和 Kotlin
// 侧比对的是同一批。NEON 路径没法在这里跑, 交给运行时自检。
#include "nnue_core.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct Loaded {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b, out_w;
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
    if (!f) { std::fprintf(stderr, "打不开 %s\n", path); return false; }

    char magic[4];
    f.read(magic, 4);
    if (std::memcmp(magic, "XQNN", 4) != 0) {
        std::fprintf(stderr, "magic 不对\n"); return false;
    }
    int32_t h[7];
    f.read(reinterpret_cast<char*>(h), sizeof(h));
    const int version = h[0], features = h[1];
    const int l1 = h[2], l2 = h[3], l3 = h[4], qa = h[5], qw = h[6];
    if (version != 1 || features != nnue::kFeatures ||
        qa != nnue::kQA || qw != nnue::kQW) {
        std::fprintf(stderr, "文件头对不上: v=%d f=%d qa=%d qw=%d\n",
                     version, features, qa, qw);
        return false;
    }

    bool ok = take(f, out.ft_w, size_t(features) * l1)
           && take(f, out.ft_b, l1)
           && take(f, out.fc1_w, size_t(l2) * l1 * 2)
           && take(f, out.fc1_b, l2)
           && take(f, out.fc2_w, size_t(l3) * l2)
           && take(f, out.fc2_b, l3)
           && take(f, out.out_w, l3);
    f.read(reinterpret_cast<char*>(&out.out_b), 4);
    if (!ok || f.gcount() != 4) { std::fprintf(stderr, "权重读不全\n"); return false; }

    out.net = nnue::Net{l1, l2, l3, out.ft_w.data(), out.ft_b.data(),
                        out.fc1_w.data(), out.fc1_b.data(),
                        out.fc2_w.data(), out.fc2_b.data(),
                        out.out_w.data(), out.out_b};
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "用法: %s <net.nnue> <golden.txt>\n", argv[0]);
        return 2;
    }
    Loaded L;
    if (!load(argv[1], L)) return 1;
    if (!nnue::validate(L.net)) {
        std::fprintf(stderr, "网络尺寸不合法\n");
        return 1;
    }
    std::printf("[host] l1=%d l2=%d l3=%d  SIMD=%s\n",
                L.net.l1, L.net.l2, L.net.l3, nnue::has_simd() ? "有" : "无(标量)");

    const int32_t worst = nnue::worst_case_accumulator(L.net);
    std::printf("[host] 累加器最坏情况 %d, int16 上限 32767 (余量 %.1f 倍)\n",
                worst, 32767.0 / worst);
    if (worst >= 32767) {
        std::fprintf(stderr, "[host] !! 累加器会溢出 int16\n");
        return 1;
    }

    std::ifstream g(argv[2]);
    if (!g) { std::fprintf(stderr, "打不开 %s\n", argv[2]); return 1; }

    std::string line;
    int checked = 0, bad = 0, simd_bad = 0;
    while (std::getline(g, line)) {
        if (line.empty() || line[0] == '#') continue;
        const size_t b1 = line.find('|'), b2 = line.rfind('|');
        if (b1 == std::string::npos || b1 == b2) continue;

        const bool black = line.substr(0, b1) == "1";
        const int32_t expect = std::atoi(line.c_str() + b2 + 1);

        std::vector<uint8_t> pieces;
        std::istringstream ps(line.substr(b1 + 1, b2 - b1 - 1));
        std::string tok;
        while (ps >> tok) {
            const size_t c = tok.find(',');
            pieces.push_back(static_cast<uint8_t>(std::atoi(tok.substr(0, c).c_str())));
            pieces.push_back(static_cast<uint8_t>(std::atoi(tok.substr(c + 1).c_str())));
        }
        const int n = static_cast<int>(pieces.size() / 2);

        const int32_t got = nnue::eval_scalar(L.net, pieces.data(), n, black);
        if (got != expect) {
            if (++bad <= 5) {
                std::fprintf(stderr, "[host] 第 %d 条不一致: 期望 %d 实得 %d (%d 子)\n",
                             checked + 1, expect, got, n);
            }
        }
        // 宿主机上 SIMD 路径是真编出来的 (x86 走 AVX2), 顺手用同一批黄金
        // 向量验它 —— 不然 SIMD 写错了要等到跑分结果莫名其妙才发现。
        if (nnue::has_simd()) {
            const int32_t sgot = nnue::eval_simd(L.net, pieces.data(), n, black);
            if (sgot != expect) {
                if (++simd_bad <= 5) {
                    std::fprintf(stderr,
                                 "[host] SIMD 第 %d 条不一致: 期望 %d 实得 %d (%d 子)\n",
                                 checked + 1, expect, sgot, n);
                }
            }
        }
        ++checked;
    }

    std::printf("[host] 比对 %d 条, 标量不一致 %d 条, SIMD 不一致 %d 条\n",
                checked, bad, simd_bad);
    if (checked < 100) {
        std::fprintf(stderr, "[host] !! 素材太少, 没验证到什么\n");
        return 1;
    }
    if (bad) {
        std::fprintf(stderr, "[host] !! C++ 与 Python 参考实现对不上\n");
        return 1;
    }
    if (simd_bad) {
        std::fprintf(stderr, "[host] !! SIMD 路径与标量对不上\n");
        return 1;
    }
    std::printf("[host] C++ %s路径与 Python 参考实现逐条相同\n",
                nnue::has_simd() ? "标量+SIMD 两条" : "标量");

    // ---- 增量累加器 ----
    //
    // 两条不变式:
    //   1. acc_refresh + eval_acc  ==  eval_scalar        (拼接顺序 / 视角没搞反)
    //   2. acc_apply(父累加器, 一步棋)  ==  acc_refresh(走完之后的子力表)
    // 第 2 条才是增量真正的风险: 错了不会崩, 只会让累加器随着搜索慢慢漂移。
    {
        uint32_t seed = 0xC0FFEEu;
        auto next = [&seed]() {
            seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed;
        };
        int bad_eq = 0, bad_apply = 0;
        nnue::Accum a{}, b{}, c{};
        for (int t = 0; t < 20000; ++t) {
            // 造一批格子互不相同的子 (同格两子在真实棋盘上不存在, 而"吃子"
            // 这一步会短暂产生两个子占同一格, 正是最容易写错的地方)
            const int n = 2 + static_cast<int>(next() % (nnue::kMaxPieces - 1));
            uint8_t pieces[nnue::kMaxPieces * 2];
            bool used[nnue::kSquares] = {false};
            for (int i = 0; i < n; ++i) {
                int sq;
                do { sq = static_cast<int>(next() % nnue::kSquares); } while (used[sq]);
                used[sq] = true;
                pieces[i * 2] = static_cast<uint8_t>(next() % 14);
                pieces[i * 2 + 1] = static_cast<uint8_t>(sq);
            }
            const bool black = (next() & 1) != 0;

            nnue::acc_refresh(L.net, a, pieces, n);
            if (nnue::eval_acc(L.net, a, black) !=
                nnue::eval_scalar(L.net, pieces, n, black)) {
                ++bad_eq;
            }

            // 走一步: mover 从 from 到 to; to 上有子就是吃子
            const int mv = static_cast<int>(next() % n);
            const int from_sq = pieces[mv * 2 + 1];
            const int to_sq = static_cast<int>(next() % nnue::kSquares);
            if (to_sq == from_sq) continue;
            int cap = -1, cap_slot = -1;
            for (int i = 0; i < n; ++i) {
                if (i != mv && pieces[i * 2 + 1] == to_sq) {
                    cap = pieces[i * 2]; cap_slot = i; break;
                }
            }

            uint8_t after[nnue::kMaxPieces * 2];
            int m = 0;
            for (int i = 0; i < n; ++i) {
                if (i == cap_slot) continue;               // 被吃掉了
                after[m * 2] = pieces[i * 2];
                after[m * 2 + 1] = (i == mv) ? static_cast<uint8_t>(to_sq)
                                             : pieces[i * 2 + 1];
                ++m;
            }

            nnue::acc_apply(L.net, b, a, pieces[mv * 2], from_sq, to_sq, cap);
            nnue::acc_refresh(L.net, c, after, m);
            if (std::memcmp(b.raw, c.raw, L.net.l1 * sizeof(int16_t)) != 0 ||
                std::memcmp(b.flip, c.flip, L.net.l1 * sizeof(int16_t)) != 0) {
                ++bad_apply;
            }
        }
        std::printf("[host] 增量累加器: 20000 局面, 与 eval_scalar 不一致 %d 条, "
                    "acc_apply 与全量重算不一致 %d 条\n", bad_eq, bad_apply);
        if (bad_eq || bad_apply) {
            std::fprintf(stderr, "[host] !! 增量累加器不正确\n");
            return 1;
        }
    }

    // 顺带量一下标量吞吐。这不是 NEON 的速度 (宿主机是 x86), 但它能把
    // "慢在语言上"和"慢在没有 SIMD 上"这两件事分开 —— Kotlin 标量实测
    // 28k 次/秒, 若 C++ 标量能到十几万, 说明相当一部分差距只是 JVM 的
    // 边界检查和装箱, 换 native 本身就能拿回来。
    {
        uint8_t full[nnue::kMaxPieces * 2];
        for (int i = 0; i < nnue::kMaxPieces; ++i) {
            full[i * 2] = static_cast<uint8_t>(i % 14);
            full[i * 2 + 1] = static_cast<uint8_t>((i * 7) % nnue::kSquares);
        }
        const int iters = 200000;
        volatile int32_t sink = 0;
        for (int i = 0; i < 20000; ++i) sink += nnue::eval_scalar(L.net, full, 32, false);
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i) sink += nnue::eval_scalar(L.net, full, 32, false);
        const auto t1 = std::chrono::steady_clock::now();
        const double scalar_secs = std::chrono::duration<double>(t1 - t0).count();
        std::printf("[host] C++ 标量吞吐 %.0f 次/秒 (32 子满盘)\n", iters / scalar_secs);

        if (nnue::has_simd()) {
            for (int i = 0; i < 20000; ++i) sink += nnue::eval_simd(L.net, full, 32, false);
            const auto s0 = std::chrono::steady_clock::now();
            for (int i = 0; i < iters; ++i) sink += nnue::eval_simd(L.net, full, 32, false);
            const auto s1 = std::chrono::steady_clock::now();
            const double simd_secs = std::chrono::duration<double>(s1 - s0).count();
            std::printf("[host] C++ SIMD 吞吐 %.0f 次/秒 (加速 %.2f 倍)\n",
                        iters / simd_secs, scalar_secs / simd_secs);
        }
    }
    return 0;
}
