// 静态交换评估 (Static Exchange Evaluation). 与 Kotlin See.kt 逐一对应.
//
// 象棋不能照抄国象的"一次性算出攻击者 + 增量补x-ray"做法: 炮吃子要求恰好隔
// 一个子, 移除任意一个子既可能多一个攻击者也可能少一个, 增量更新会算错。
// 所以这里跟 Kotlin 版一样, 每吃一步都重新枚举攻击者。
#ifndef XQ_SEE_H
#define XQ_SEE_H

#include <cstdint>

#include "board.h"

namespace xq {

constexpr int kSeeMaxSwaps = 32;
constexpr int kSeeKingValue = 10000;

/** 与 Move 排序用的子力估值是同一套, 两处不一致会让排序和裁剪各说各话 */
int see_value(int piece_code);

/** 走 [m] 这个吃子之后, 连续兑完的净子力收益(走子方视角, 厘兵). 非吃子返回 0 */
int see_evaluate(const Position& pos, const Move& m);

/** [color] 方能吃到 [to] 的子里最便宜的那个, 返回起点; 没有则 -1 */
int see_least_valuable_attacker(const int* board, int to, int color);

namespace detail {
inline bool see_in_palace(int row, int col, int color) {
    if (col < 3 || col > 5) return false;
    return color == kRed ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2);
}
inline bool see_crossed_river(int row, int color) {
    return color == kRed ? (row <= 4) : (row >= 5);
}
}  // namespace detail

/**
 * 枚举 [color] 方所有能吃到 [to] 的子的起点, 对每个调一次 visit(from).
 *
 * 模板(而不是 .cpp 里的具体函数)是为了让 lambda 内联 —— 这是静止搜索的
 * 热路径, 与 Kotlin `inline fun forEachAttacker` 的用意相同。
 */
template <typename Visit>
void see_for_each_attacker(const int* board, int to, int color, Visit visit) {
    const int tr = sq_row(to), tc = sq_col(to);

    // ---- 车 / 炮 / 将(飞将): 四条直线 ----
    static const int line_dr[4] = {-1, 1, 0, 0};
    static const int line_dc[4] = {0, 0, -1, 1};
    for (int d = 0; d < 4; ++d) {
        int step = 1;
        int screen = 0;
        while (true) {
            const int r = tr + line_dr[d] * step, c = tc + line_dc[d] * step;
            if (!sq_valid_rc(r, c)) break;
            const int sq = sq_make(r, c);
            const int p = board[sq];
            if (p != kPieceNone) {
                const int pc_color = piece_color(p), pc_type = piece_type(p);
                if (pc_color == color) {
                    if (screen == 0 && pc_type == kTypeRook) {
                        visit(sq);
                    } else if (screen == 1 && pc_type == kTypeCannon) {
                        visit(sq);
                    } else if (screen == 0 && pc_type == kTypeKing && step == 1 &&
                               detail::see_in_palace(tr, tc, color)) {
                        visit(sq);
                    }
                }
                ++screen;
                if (screen > 1) break;
            }
            ++step;
        }
    }

    // ---- 马: 从目标格反推八个起点 ----
    static const int horse_from[8][4] = {
        {-2, -1, 1, 0}, {-2, 1, 1, 0}, {2, -1, -1, 0}, {2, 1, -1, 0},
        {-1, -2, 0, 1}, {1, -2, 0, 1}, {-1, 2, 0, -1}, {1, 2, 0, -1},
    };
    for (auto& e : horse_from) {
        const int r = tr + e[0], c = tc + e[1];
        if (!sq_valid_rc(r, c)) continue;
        const int sq = sq_make(r, c);
        const int p = board[sq];
        if (p == kPieceNone) continue;
        if (piece_color(p) != color || piece_type(p) != kTypeHorse) continue;
        const int leg_r = r + e[2], leg_c = c + e[3];
        if (board[sq_make(leg_r, leg_c)] == kPieceNone) visit(sq);
    }

    // ---- 相/象: 田字, 塞象眼, 不能过河 ----
    static const int elephant_dirs[4][2] = {{-2, -2}, {-2, 2}, {2, -2}, {2, 2}};
    for (auto& e : elephant_dirs) {
        const int r = tr + e[0], c = tc + e[1];
        if (!sq_valid_rc(r, c)) continue;
        const int sq = sq_make(r, c);
        const int p = board[sq];
        if (p == kPieceNone) continue;
        if (piece_color(p) != color || piece_type(p) != kTypeElephant) continue;
        if (detail::see_crossed_river(tr, color)) continue;
        if (board[sq_make(r + e[0] / -2, c + e[1] / -2)] != kPieceNone) continue;
        visit(sq);
    }

    // ---- 仕/士: 九宫斜一格 ----
    static const int advisor_dirs[4][2] = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    for (auto& e : advisor_dirs) {
        const int r = tr + e[0], c = tc + e[1];
        if (!sq_valid_rc(r, c)) continue;
        const int sq = sq_make(r, c);
        const int p = board[sq];
        if (p == kPieceNone) continue;
        if (piece_color(p) != color || piece_type(p) != kTypeAdvisor) continue;
        if (!detail::see_in_palace(tr, tc, color) || !detail::see_in_palace(r, c, color)) continue;
        visit(sq);
    }

    // ---- 兵/卒: 从目标格反推, 前进方向相反; 横吃要它已过河 ----
    {
        const int back = (color == kRed) ? 1 : -1;
        const int r = tr + back;
        if (r >= 0 && r < kRows) {
            const int sq = sq_make(r, tc);
            const int p = board[sq];
            if (p != kPieceNone && piece_color(p) == color && piece_type(p) == kTypePawn) {
                visit(sq);
            }
        }
        for (int dc : {-1, 1}) {
            const int c = tc + dc;
            if (c < 0 || c >= kCols) continue;
            const int sq = sq_make(tr, c);
            const int p = board[sq];
            if (p == kPieceNone) continue;
            if (piece_color(p) != color || piece_type(p) != kTypePawn) continue;
            if (detail::see_crossed_river(tr, color)) visit(sq);
        }
    }
}

}  // namespace xq

#endif  // XQ_SEE_H
