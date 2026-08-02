#include "see.h"

#include <algorithm>
#include <cstring>

namespace xq {

int see_value(int piece_code) {
    switch (piece_type(piece_code)) {
        case kTypeKing: return kSeeKingValue;
        case kTypeAdvisor: case kTypeElephant: return 200;
        case kTypeHorse: return 400;
        case kTypeRook: return 900;
        case kTypeCannon: return 450;
        case kTypePawn: return 100;
        default: return 0;
    }
}

int see_least_valuable_attacker(const int* board, int to, int color) {
    int best = -1;
    int best_val = INT32_MAX;
    see_for_each_attacker(board, to, color, [&](int from) {
        const int v = see_value(board[from]);
        if (v < best_val) { best_val = v; best = from; }
    });
    return best;
}

int see_evaluate(const Position& pos, const Move& m) {
    const int to = m.to();
    const int victim = pos.squares[to];
    if (victim == kPieceNone) return 0;
    const int attacker = pos.squares[m.from()];
    if (attacker == kPieceNone) return 0;

    int board[kSquareCount];
    std::memcpy(board, pos.squares, sizeof(board));
    int side = color_opposite(piece_color(attacker));

    int gain[kSeeMaxSwaps] = {0};
    gain[0] = see_value(victim);
    board[to] = attacker;
    board[m.from()] = kPieceNone;

    int d = 1;
    int on_square = attacker;
    while (d < kSeeMaxSwaps) {
        const int from = see_least_valuable_attacker(board, to, side);
        if (from < 0) break;
        gain[d] = see_value(on_square) - gain[d - 1];
        if (std::max(-gain[d - 1], gain[d]) < 0) break;
        on_square = board[from];
        board[to] = on_square;
        board[from] = kPieceNone;
        side = color_opposite(side);
        ++d;
    }
    while (d > 1) {
        --d;
        gain[d - 1] = -std::max(-gain[d - 1], gain[d]);
    }
    return gain[0];
}

}  // namespace xq
