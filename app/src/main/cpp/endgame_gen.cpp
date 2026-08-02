// 随机残局生成器 —— 给 NNUE 训练补"深处"的样本。
//
// 起因: 实测发现我们的评估在固定深度 3 下与 Pikafish 打平(+1 Elo), 到固定
// 深度 8 就落后 132 分(同一套 Pikafish 搜索, 唯一变量是评估)。也就是说网络
// 在浅处够用、在深处失准。而历史训练数据全部来自自对弈对局, 局面分布集中在
// "正常中局", 深搜会走到的那类尖锐/简化局面严重欠采样。
//
// 这个工具随机撒子生成**合法**残局, 覆盖自对弈几乎不会产生的局面分布。
//
//   g++ -O2 -std=c++17 board.cpp movegen.cpp see.cpp endgame_gen.cpp -o /tmp/endgame_gen
//   /tmp/endgame_gen <条数> [--seed N] [--max-per-side N] [--min-per-side N]
//
// 每行输出一个 FEN。合法性由本项目自己的规则代码把关(generate_all_legal /
// is_in_check), 不另写一套 —— 另写一套就意味着两套规则可能不一致, 而不一致
// 的那部分会静默变成训练集里的垃圾局面。
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "board.h"
#include "movegen.h"

namespace {

using namespace xq;

// 象的七个合法点(己方半场)。象走田且不能过河, 可达点就这七个。
const int kElephantRed[7][2]   = {{9, 2}, {9, 6}, {7, 0}, {7, 4}, {7, 8}, {5, 2}, {5, 6}};
const int kElephantBlack[7][2] = {{0, 2}, {0, 6}, {2, 0}, {2, 4}, {2, 8}, {4, 2}, {4, 6}};

/**
 * 兵/卒的合法落点。
 *
 * 未过河时只能停在本方那两条横线、且必须还在原来的纵线(兵没过河不能横走);
 * 过河之后可以在对方半场的任意点。**不能后退**, 所以红兵永远不会出现在
 * row 7..9。漏掉任何一条都会造出真实对局里不可能出现的局面。
 */
bool pawn_square_ok(int row, int col, int color) {
    if (color == kRed) {
        if (row >= 7) return false;              // 红兵不可能退回本方底线区
        if (row == 6 || row == 5)                 // 未过河: 只能在原纵线
            return (col % 2) == 0;
        return true;                              // row 0..4 已过河, 任意纵线
    }
    if (row <= 2) return false;
    if (row == 3 || row == 4) return (col % 2) == 0;
    return true;
}

bool advisor_square_ok(int row, int col, int color) {
    if (col < 3 || col > 5) return false;
    // 士只走九宫斜线, 可达点是九宫的四角 + 中心
    const bool center = (col == 4);
    if (color == kRed) {
        if (row < 7 || row > 9) return false;
        return center ? (row == 8) : (row == 7 || row == 9);
    }
    if (row < 0 || row > 2) return false;
    return center ? (row == 1) : (row == 0 || row == 2);
}

bool king_square_ok(int row, int col, int color) {
    if (col < 3 || col > 5) return false;
    return color == kRed ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2);
}

/** 将帅照面: 同一纵线且中间无子 —— 这是象棋独有的、最容易漏掉的合法性约束 */
bool kings_face_each_other(const Position& pos) {
    int rk = -1, bk = -1;
    for (int sq = 0; sq < kSquareCount; ++sq) {
        if (pos.squares[sq] == kRKing) rk = sq;
        else if (pos.squares[sq] == kBKing) bk = sq;
    }
    if (rk < 0 || bk < 0) return true;            // 缺将, 当作非法
    if (sq_col(rk) != sq_col(bk)) return false;
    const int c = sq_col(rk);
    const int lo = std::min(sq_row(rk), sq_row(bk)), hi = std::max(sq_row(rk), sq_row(bk));
    for (int r = lo + 1; r < hi; ++r)
        if (pos.squares[sq_make(r, c)] != kPieceNone) return false;
    return true;
}

struct PieceSpec {
    int type_red, type_black, max_count;
};

// 每种子力的数量上限, 与真实棋局一致
const PieceSpec kPool[] = {
    {kRRook, kBRook, 2},
    {kRHorse, kBHorse, 2},
    {kRCannon, kBCannon, 2},
    {kRAdvisor, kBAdvisor, 2},
    {kRElephant, kBElephant, 2},
    {kRPawn, kBPawn, 5},
};
constexpr int kPoolSize = sizeof(kPool) / sizeof(kPool[0]);

bool place_random(Position& pos, int piece, int color, std::mt19937& rng) {
    std::vector<int> cand;
    cand.reserve(kSquareCount);
    for (int sq = 0; sq < kSquareCount; ++sq) {
        if (pos.squares[sq] != kPieceNone) continue;
        const int r = sq_row(sq), c = sq_col(sq);
        const int t = piece_type(piece);
        bool ok;
        switch (t) {
            case kTypeAdvisor: ok = advisor_square_ok(r, c, color); break;
            case kTypePawn:    ok = pawn_square_ok(r, c, color); break;
            case kTypeElephant: {
                ok = false;
                const auto& tbl = (color == kRed) ? kElephantRed : kElephantBlack;
                for (int i = 0; i < 7; ++i)
                    if (tbl[i][0] == r && tbl[i][1] == c) { ok = true; break; }
                break;
            }
            default: ok = true; break;            // 车/马/炮: 任意空点
        }
        if (ok) cand.push_back(sq);
    }
    if (cand.empty()) return false;
    pos.squares[cand[std::uniform_int_distribution<int>(0, int(cand.size()) - 1)(rng)]] = piece;
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr,
                     "用法: %s <条数> [--seed N] [--min-per-side N] [--max-per-side N]\n",
                     argv[0]);
        return 2;
    }
    const long want = std::atol(argv[1]);
    unsigned seed = 20260802;
    int min_side = 1, max_side = 5;
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "--seed" && i + 1 < argc) seed = unsigned(std::atol(argv[++i]));
        else if (a == "--min-per-side" && i + 1 < argc) min_side = std::atoi(argv[++i]);
        else if (a == "--max-per-side" && i + 1 < argc) max_side = std::atoi(argv[++i]);
    }

    std::mt19937 rng(seed);
    std::uniform_int_distribution<int> n_dist(min_side, max_side);
    std::uniform_int_distribution<int> pool_dist(0, kPoolSize - 1);
    std::uniform_int_distribution<int> side_dist(0, 1);

    long emitted = 0, tries = 0;
    const long tries_cap = want * 500 + 100000;
    std::vector<Move> legal;

    while (emitted < want && tries < tries_cap) {
        ++tries;
        Position pos;
        for (int i = 0; i < kSquareCount; ++i) pos.squares[i] = kPieceNone;

        // 两个将先放, 后面所有子都要避开它们占的点
        bool ok = true;
        for (int color : {kRed, kBlack}) {
            std::vector<int> cand;
            for (int sq = 0; sq < kSquareCount; ++sq) {
                if (pos.squares[sq] != kPieceNone) continue;
                if (king_square_ok(sq_row(sq), sq_col(sq), color)) cand.push_back(sq);
            }
            if (cand.empty()) { ok = false; break; }
            pos.squares[cand[std::uniform_int_distribution<int>(0, int(cand.size()) - 1)(rng)]] =
                (color == kRed) ? kRKing : kBKing;
        }
        if (!ok) continue;

        // 两边各自随机若干子; 两边数量独立抽样, 自然产生子力不均衡的局面 ——
        // 那正是评估最需要学会判断的一类。
        for (int color : {kRed, kBlack}) {
            const int n = n_dist(rng);
            int used[kPoolSize] = {0};
            for (int k = 0; k < n; ++k) {
                int slot = pool_dist(rng), guard = 0;
                while (used[slot] >= kPool[slot].max_count && guard++ < 32) slot = pool_dist(rng);
                if (used[slot] >= kPool[slot].max_count) continue;
                ++used[slot];
                const int piece = (color == kRed) ? kPool[slot].type_red : kPool[slot].type_black;
                if (!place_random(pos, piece, color, rng)) { ok = false; break; }
            }
            if (!ok) break;
        }
        if (!ok) continue;

        if (kings_face_each_other(pos)) continue;

        pos.side_to_move = side_dist(rng) == 0 ? kRed : kBlack;
        pos.halfmove_clock = 0;
        pos.fullmove_number = 1;
        pos.hash = pos.compute_hash();

        // 未轮到走的一方不能正在被将 —— 那种局面在真实对局里不可能出现
        // (上一手就该把王吃了), 喂给网络只会教会它一些永远用不到的东西。
        const int opp = (pos.side_to_move == kRed) ? kBlack : kRed;
        if (is_in_check(pos, opp)) continue;

        // 终局(将死/困毙)对回归评估没有意义, 分数由搜索给杀棋分即可, 排除。
        legal.clear();
        generate_all_legal(pos, legal);
        if (legal.empty()) continue;

        std::printf("%s\n", pos.to_fen().c_str());
        ++emitted;
    }

    std::fprintf(stderr, "[endgame_gen] 生成 %ld 条, 尝试 %ld 次 (接受率 %.1f%%)\n",
                 emitted, tries, tries ? 100.0 * emitted / tries : 0.0);
    return emitted == want ? 0 : 1;
}
