#include "movegen.h"

#include <algorithm>

namespace xq {

thread_local bool g_use_fast_legal_filter = true;

// ============================================================ MoveGenerator

namespace {

// 是否在己方九宫(判定"是否能走进/留在九宫"共用)
inline bool in_palace(int row, int col, int color) {
    if (col < 3 || col > 5) return false;
    return color == kRed ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2);
}

void gen_king(const Position& pos, int from, int color, std::vector<Move>& moves,
              bool captures_only) {
    const int r = sq_row(from), c = sq_col(from);
    static const int dr[4] = {-1, 1, 0, 0};
    static const int dc[4] = {0, 0, -1, 1};
    for (int d = 0; d < 4; ++d) {
        const int tr = r + dr[d], tc = c + dc[d];
        if (!sq_valid_rc(tr, tc)) continue;
        if (!in_palace(tr, tc, color)) continue;
        const int to = sq_make(tr, tc);
        const int tgt = pos.squares[to];
        if (tgt != kPieceNone && piece_color(tgt) == color) continue;
        if (captures_only && tgt == kPieceNone) continue;
        moves.emplace_back(from, to);
    }
}

void gen_advisor(const Position& pos, int from, int color, std::vector<Move>& moves,
                  bool captures_only) {
    const int r = sq_row(from), c = sq_col(from);
    static const int dr[4] = {-1, -1, 1, 1};
    static const int dc[4] = {-1, 1, -1, 1};
    for (int d = 0; d < 4; ++d) {
        const int tr = r + dr[d], tc = c + dc[d];
        if (!sq_valid_rc(tr, tc)) continue;
        if (!in_palace(tr, tc, color)) continue;
        const int to = sq_make(tr, tc);
        const int tgt = pos.squares[to];
        if (tgt != kPieceNone && piece_color(tgt) == color) continue;
        if (captures_only && tgt == kPieceNone) continue;
        moves.emplace_back(from, to);
    }
}

void gen_elephant(const Position& pos, int from, int color, std::vector<Move>& moves,
                   bool captures_only) {
    const int r = sq_row(from), c = sq_col(from);
    if (color == kRed && r <= 4) return;
    if (color == kBlack && r >= 5) return;
    static const int dr[4] = {-2, -2, 2, 2};
    static const int dc[4] = {-2, 2, -2, 2};
    for (int d = 0; d < 4; ++d) {
        const int eye_r = r + dr[d] / 2, eye_c = c + dc[d] / 2;
        const int tr = r + dr[d], tc = c + dc[d];
        if (!sq_valid_rc(tr, tc)) continue;
        if (color == kRed && tr <= 4) continue;
        if (color == kBlack && tr >= 5) continue;
        if (pos.squares[sq_make(eye_r, eye_c)] != kPieceNone) continue;  // 塞象眼
        const int to = sq_make(tr, tc);
        const int tgt = pos.squares[to];
        if (tgt != kPieceNone && piece_color(tgt) == color) continue;
        if (captures_only && tgt == kPieceNone) continue;
        moves.emplace_back(from, to);
    }
}

void gen_horse(const Position& pos, int from, int color, std::vector<Move>& moves,
                bool captures_only) {
    const int fr = sq_row(from), fc = sq_col(from);
    // {dr, dc, leg_dr, leg_dc}
    static const int d[8][4] = {
        {-2, -1, -1, 0}, {-2, 1, -1, 0}, {2, -1, 1, 0}, {2, 1, 1, 0},
        {-1, -2, 0, -1}, {1, -2, 0, -1}, {-1, 2, 0, 1}, {1, 2, 0, 1},
    };
    for (auto& e : d) {
        const int tr = fr + e[0], tc = fc + e[1];
        if (!sq_valid_rc(tr, tc)) continue;
        const int leg_r = fr + e[2], leg_c = fc + e[3];
        if (pos.squares[sq_make(leg_r, leg_c)] != kPieceNone) continue;  // 蹩马腿
        const int to = sq_make(tr, tc);
        const int tgt = pos.squares[to];
        if (tgt != kPieceNone && piece_color(tgt) == color) continue;
        if (captures_only && tgt == kPieceNone) continue;
        moves.emplace_back(from, to);
    }
}

void gen_rook(const Position& pos, int from, int color, std::vector<Move>& moves,
              bool captures_only) {
    const int fr = sq_row(from), fc = sq_col(from);
    static const int dr[4] = {-1, 1, 0, 0};
    static const int dc[4] = {0, 0, -1, 1};
    for (int dd = 0; dd < 4; ++dd) {
        int step = 1;
        while (true) {
            const int tr = fr + dr[dd] * step, tc = fc + dc[dd] * step;
            if (!sq_valid_rc(tr, tc)) break;
            const int to = sq_make(tr, tc);
            const int tgt = pos.squares[to];
            if (tgt == kPieceNone) {
                if (!captures_only) moves.emplace_back(from, to);
            } else {
                if (piece_color(tgt) != color) moves.emplace_back(from, to);
                break;
            }
            ++step;
        }
    }
}

void gen_cannon(const Position& pos, int from, int color, std::vector<Move>& moves,
                 bool captures_only) {
    const int fr = sq_row(from), fc = sq_col(from);
    static const int dr[4] = {-1, 1, 0, 0};
    static const int dc[4] = {0, 0, -1, 1};
    for (int dd = 0; dd < 4; ++dd) {
        int step = 1;
        bool jumped = false;
        while (true) {
            const int tr = fr + dr[dd] * step, tc = fc + dc[dd] * step;
            if (!sq_valid_rc(tr, tc)) break;
            const int to = sq_make(tr, tc);
            const int tgt = pos.squares[to];
            if (!jumped) {
                if (tgt == kPieceNone) {
                    if (!captures_only) moves.emplace_back(from, to);
                } else {
                    jumped = true;  // 炮架
                }
            } else {
                if (tgt != kPieceNone) {
                    if (piece_color(tgt) != color) moves.emplace_back(from, to);
                    break;
                }
            }
            ++step;
        }
    }
}

void gen_pawn(const Position& pos, int from, int color, std::vector<Move>& moves,
              bool captures_only) {
    const int r = sq_row(from), c = sq_col(from);
    const int d_forward = (color == kRed) ? -1 : 1;

    const int tr = r + d_forward;
    if (tr >= 0 && tr < kRows) {
        const int to = sq_make(tr, c);
        const int tgt = pos.squares[to];
        if (tgt == kPieceNone || piece_color(tgt) != color) {
            if (!captures_only || tgt != kPieceNone) moves.emplace_back(from, to);
        }
    }

    const bool crossed_river = (color == kRed) ? (r <= 4) : (r >= 5);
    if (crossed_river) {
        for (int dc : {-1, 1}) {
            const int tc = c + dc;
            if (tc < 0 || tc >= kCols) continue;
            const int to = sq_make(r, tc);
            const int tgt = pos.squares[to];
            if (tgt == kPieceNone || piece_color(tgt) != color) {
                if (!captures_only || tgt != kPieceNone) moves.emplace_back(from, to);
            }
        }
    }
}

}  // namespace

void generate_for_square(const Position& pos, int sq, std::vector<Move>& moves,
                          bool captures_only) {
    const int p = pos.squares[sq];
    if (p == kPieceNone) return;
    const int color = piece_color(p);
    switch (piece_type(p)) {
        case kTypeKing: gen_king(pos, sq, color, moves, captures_only); break;
        case kTypeAdvisor: gen_advisor(pos, sq, color, moves, captures_only); break;
        case kTypeElephant: gen_elephant(pos, sq, color, moves, captures_only); break;
        case kTypeHorse: gen_horse(pos, sq, color, moves, captures_only); break;
        case kTypeRook: gen_rook(pos, sq, color, moves, captures_only); break;
        case kTypeCannon: gen_cannon(pos, sq, color, moves, captures_only); break;
        case kTypePawn: gen_pawn(pos, sq, color, moves, captures_only); break;
        default: break;
    }
}

void generate_pseudo(const Position& pos, std::vector<Move>& moves, bool captures_only) {
    for (int sq = 0; sq < kSquareCount; ++sq) {
        const int p = pos.squares[sq];
        if (p == kPieceNone || piece_color(p) != pos.side_to_move) continue;
        generate_for_square(pos, sq, moves, captures_only);
    }
}

namespace {
// filter in-place: 去掉走完会送将的着法(要求 [begin, moves.size()) 段是新加的)
void filter_legal(Position& pos, std::vector<Move>& moves, size_t begin) {
    // 快速路径: 非被将局面下, 一条着法只有两种方式会暴露己方王:
    //   1) 移动子与王同行/同列(原本挡着攻击线, 或落点成为敌方炮架);
    //   2) 移动子原本是某个敌方马的"腿"。
    // 其余着法(王不动、子不在王线上、不是马腿)必然安全, 无需试走验证。
    // 王位置在循环外找一次, 避免每条着法重复全盘扫描。
    const int mover_color = pos.side_to_move;
    const int king_code = (mover_color == kRed) ? kRKing : kBKing;
    int king_sq = -1;
    for (int sq = 0; sq < kSquareCount; ++sq) {
        if (pos.squares[sq] == king_code) { king_sq = sq; break; }
    }
    const bool fast_ok = g_use_fast_legal_filter && (king_sq >= 0) &&
                         !is_in_check(pos, mover_color);
    const int kr = fast_ok ? sq_row(king_sq) : -1;
    const int kc = fast_ok ? sq_col(king_sq) : -1;

    size_t w = begin;
    for (size_t i = begin; i < moves.size(); ++i) {
        const Move& m = moves[i];
        bool safe;
        if (fast_ok && piece_type(pos.squares[m.from()]) != kTypeKing) {
            const int fr = sq_row(m.from()), fc = sq_col(m.from());
            const int tr = sq_row(m.to()), tc = sq_col(m.to());
            bool might = (fr == kr || fc == kc) || (tr == kr || tc == kc);
            if (!might) {
                // 王周围的 8 个马位, 对应 8 个腿位; mover 若是某敌马的腿,
                // 移开可能让该马将军。
                static const int hd[8][2] = {
                    {-2, -1}, {-2, 1}, {2, -1}, {2, 1},
                    {-1, -2}, {1, -2}, {-1, 2}, {1, 2},
                };
                for (auto& e : hd) {
                    const int tr2 = kr + e[0], tc2 = kc + e[1];
                    if (!sq_valid_rc(tr2, tc2)) continue;
                    const int leg_r = kr + (e[0] > 0 ? 1 : -1);
                    const int leg_c = kc + (e[1] > 0 ? 1 : -1);
                    if (sq_make(leg_r, leg_c) != m.from()) continue;
                    const int p = pos.squares[sq_make(tr2, tc2)];
                    if (p != kPieceNone && piece_color(p) != mover_color &&
                        piece_type(p) == kTypeHorse) {
                        might = true;
                        break;
                    }
                }
            }
            safe = !might || pos.leaves_king_safe(m);
        } else {
            safe = pos.leaves_king_safe(m);
        }
        if (safe) moves[w++] = m;
    }
    moves.resize(w);
}
}  // namespace

void generate_captures(Position& pos, std::vector<Move>& moves) {
    const size_t begin = moves.size();
    for (int sq = 0; sq < kSquareCount; ++sq) {
        const int p = pos.squares[sq];
        if (p == kPieceNone || piece_color(p) != pos.side_to_move) continue;
        generate_for_square(pos, sq, moves, /*captures_only=*/true);
    }
    filter_legal(pos, moves, begin);
}

void generate_all(Position& pos, std::vector<Move>& moves) {
    const size_t begin = moves.size();
    generate_pseudo(pos, moves);
    filter_legal(pos, moves, begin);
}

// =================================================================== Rules

bool is_flying_general(const Position& pos, int king_sq) {
    const int king_col = sq_col(king_sq);
    const int king_color = piece_color(pos.squares[king_sq]);
    const int opp_king_code = (king_color == kRed) ? kBKing : kRKing;
    for (int r = 0; r < kRows; ++r) {
        const int sq = sq_make(r, king_col);
        if (sq == king_sq) continue;
        if (pos.squares[sq] == opp_king_code) {
            const int min_r = std::min(r, sq_row(king_sq));
            const int max_r = std::max(r, sq_row(king_sq));
            for (int rr = min_r + 1; rr < max_r; ++rr) {
                if (pos.squares[sq_make(rr, king_col)] != kPieceNone) return false;
            }
            return true;
        }
    }
    return false;
}

bool is_in_check(const Position& pos, int color) {
    int king_sq = -1;
    const int king_code = (color == kRed) ? kRKing : kBKing;
    for (int sq = 0; sq < kSquareCount; ++sq) {
        if (pos.squares[sq] == king_code) { king_sq = sq; break; }
    }
    if (king_sq == -1) return false;

    const int opp_color = color_opposite(color);

    if (is_flying_general(pos, king_sq)) return true;

    const int kr = sq_row(king_sq), kc = sq_col(king_sq);

    // 车/炮 沿四条直线
    static const int dr[4] = {-1, 1, 0, 0};
    static const int dc[4] = {0, 0, -1, 1};
    for (int d = 0; d < 4; ++d) {
        int step = 1;
        bool screened = false;
        while (true) {
            const int tr = kr + dr[d] * step, tc = kc + dc[d] * step;
            if (!sq_valid_rc(tr, tc)) break;
            ++step;
            const int p = pos.squares[sq_make(tr, tc)];
            if (p == kPieceNone) continue;
            const int pc_color = piece_color(p), pc_type = piece_type(p);
            if (!screened) {
                if (pc_color == opp_color && pc_type == kTypeRook) return true;
                screened = true;
            } else {
                if (pc_color == opp_color && pc_type == kTypeCannon) return true;
                break;
            }
        }
    }

    // 马
    static const int hd[8][2] = {
        {-2, -1}, {-2, 1}, {2, -1}, {2, 1}, {-1, -2}, {1, -2}, {-1, 2}, {1, 2},
    };
    for (auto& e : hd) {
        const int tr = kr + e[0], tc = kc + e[1];
        if (!sq_valid_rc(tr, tc)) continue;
        const int leg_r = kr + (e[0] > 0 ? 1 : -1);
        const int leg_c = kc + (e[1] > 0 ? 1 : -1);
        if (pos.squares[sq_make(leg_r, leg_c)] != kPieceNone) continue;
        const int p = pos.squares[sq_make(tr, tc)];
        if (p != kPieceNone && piece_color(p) == opp_color && piece_type(p) == kTypeHorse) {
            return true;
        }
    }

    // 兵/卒: 直线临近
    const int opp_pawn_forward_row = (color == kRed) ? kr - 1 : kr + 1;
    if (opp_pawn_forward_row >= 0 && opp_pawn_forward_row < kRows) {
        const int p = pos.squares[sq_make(opp_pawn_forward_row, kc)];
        if (p != kPieceNone && piece_color(p) == opp_color && piece_type(p) == kTypePawn) {
            return true;
        }
    }
    // 兵/卒: 过河后横吃
    for (int dcv : {-1, 1}) {
        const int side_c = kc + dcv;
        if (side_c < 0 || side_c >= kCols) continue;
        const int p = pos.squares[sq_make(kr, side_c)];
        if (p != kPieceNone && piece_color(p) == opp_color && piece_type(p) == kTypePawn) {
            const bool pawn_crossed = (opp_color == kRed) ? (kr <= 4) : (kr >= 5);
            if (pawn_crossed) return true;
        }
    }

    return false;
}

bool is_pseudo_legal(const Position& pos, const Move& m) {
    const int from = m.from(), to = m.to();
    if (from == to) return false;
    if (!sq_valid(from) || !sq_valid(to)) return false;
    const int piece = pos.squares[from];
    if (piece == kPieceNone) return false;
    if (piece_color(piece) != pos.side_to_move) return false;

    std::vector<Move> candidates;
    generate_for_square(pos, from, candidates);
    for (auto& c : candidates) {
        if (c.to() == to) return true;
    }
    return false;
}

bool is_checkmate(Position& pos) {
    if (!is_in_check(pos, pos.side_to_move)) return false;
    std::vector<Move> moves;
    generate_all(pos, moves);
    return moves.empty();
}

bool is_stalemate(Position& pos) {
    if (is_in_check(pos, pos.side_to_move)) return false;
    std::vector<Move> moves;
    generate_all(pos, moves);
    return moves.empty();
}

}  // namespace xq
