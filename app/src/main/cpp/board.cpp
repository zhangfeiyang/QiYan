#include "board.h"

#include <cctype>
#include <sstream>
#include <vector>

namespace xq {

// 前向声明: 定义在 rules.cpp。board.cpp 不 #include "rules.h" 是为了不让
// board.h 依赖 rules.h (Rules 反过来要用 Position), 避免头文件互相依赖。
bool is_in_check(const Position& pos, int color);

namespace {

// splitmix64, 只用来在编译期/启动时把一个固定种子铺成一堆看起来随机的
// 64 位数, 不追求密码学质量 —— Zobrist 键只要求"同进程内不同局面大概率
// 不同哈希", 这个强度足够。
uint64_t splitmix64(uint64_t& state) {
    uint64_t z = (state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

}  // namespace

ZobristTable::ZobristTable() {
    uint64_t state = 0xCAFEBABEULL;
    for (int p = 1; p <= 15; ++p) {
        for (int sq = 0; sq < kSquareCount; ++sq) {
            piece_square[p][sq] = static_cast<int64_t>(splitmix64(state));
        }
    }
    side = static_cast<int64_t>(splitmix64(state));
}

const ZobristTable kZobrist;

void Position::setup_start_position() {
    for (int i = 0; i < kSquareCount; ++i) squares[i] = kPieceNone;
    // 黑方 (上方, row 0..4)
    set_piece(0, 0, kBRook);    set_piece(0, 1, kBHorse);
    set_piece(0, 2, kBElephant); set_piece(0, 3, kBAdvisor);
    set_piece(0, 4, kBKing);    set_piece(0, 5, kBAdvisor);
    set_piece(0, 6, kBElephant); set_piece(0, 7, kBHorse);
    set_piece(0, 8, kBRook);
    set_piece(2, 1, kBCannon);  set_piece(2, 7, kBCannon);
    set_piece(3, 0, kBPawn); set_piece(3, 2, kBPawn); set_piece(3, 4, kBPawn);
    set_piece(3, 6, kBPawn); set_piece(3, 8, kBPawn);
    // 红方 (下方, row 5..9)
    set_piece(9, 0, kRRook);    set_piece(9, 1, kRHorse);
    set_piece(9, 2, kRElephant); set_piece(9, 3, kRAdvisor);
    set_piece(9, 4, kRKing);    set_piece(9, 5, kRAdvisor);
    set_piece(9, 6, kRElephant); set_piece(9, 7, kRHorse);
    set_piece(9, 8, kRRook);
    set_piece(7, 1, kRCannon);  set_piece(7, 7, kRCannon);
    set_piece(6, 0, kRPawn); set_piece(6, 2, kRPawn); set_piece(6, 4, kRPawn);
    set_piece(6, 6, kRPawn); set_piece(6, 8, kRPawn);

    side_to_move = kRed;
    halfmove_clock = 0;
    fullmove_number = 1;
    hash = compute_hash();
    pawn_hash = compute_pawn_hash();
    move_stack_top_ = 0;
    history_size_ = 1;
    history_hashes_[0] = hash;
    null_move_depth_ = 0;
}

int64_t Position::compute_hash() const {
    int64_t h = 0;
    for (int sq = 0; sq < kSquareCount; ++sq) {
        const int p = squares[sq];
        if (p != kPieceNone) h ^= kZobrist.piece_square[p][sq];
    }
    if (side_to_move == kBlack) h ^= kZobrist.side;
    return h;
}

int64_t Position::compute_pawn_hash() const {
    int64_t h = 0;
    for (int sq = 0; sq < kSquareCount; ++sq) {
        const int p = squares[sq];
        if (p != kPieceNone && piece_type(p) == kTypePawn) {
            h ^= kZobrist.piece_square[p][sq];
        }
    }
    return h;
}

int Position::count_pieces_in_col(int col, int piece) const {
    int cnt = 0;
    for (int r = 0; r < kRows; ++r) {
        if (squares[sq_make(r, col)] == piece) ++cnt;
    }
    return cnt;
}

void Position::do_null_move() {
    hash ^= kZobrist.side;
    side_to_move = color_opposite(side_to_move);
    ++null_move_depth_;
}

void Position::undo_null_move() {
    side_to_move = color_opposite(side_to_move);
    hash ^= kZobrist.side;
    --null_move_depth_;
}

bool Position::has_threatening_piece(int color) const {
    for (int sq = 0; sq < kSquareCount; ++sq) {
        const int p = squares[sq];
        if (p == kPieceNone || piece_color(p) != color) continue;
        const int t = piece_type(p);
        if (t == kTypeRook || t == kTypeCannon || t == kTypeHorse) return true;
    }
    return false;
}

bool Position::do_move(const Move& m) {
    const int from = m.from();
    const int to = m.to();
    if (!sq_valid(from) || !sq_valid(to)) return false;

    const int piece = squares[from];
    if (piece == kPieceNone) return false;
    if (piece_color(piece) != side_to_move) return false;

    const int captured = squares[to];

    hash ^= kZobrist.piece_square[piece][from];
    hash ^= kZobrist.piece_square[piece][to];
    if (captured != kPieceNone) hash ^= kZobrist.piece_square[captured][to];
    hash ^= kZobrist.side;

    if (piece_type(piece) == kTypePawn) {
        pawn_hash ^= kZobrist.piece_square[piece][from];
        pawn_hash ^= kZobrist.piece_square[piece][to];
    }
    if (captured != kPieceNone && piece_type(captured) == kTypePawn) {
        pawn_hash ^= kZobrist.piece_square[captured][to];
    }

    squares[from] = kPieceNone;
    squares[to] = piece;

    move_stack_[move_stack_top_++] = UndoInfo{from, to, piece, captured, halfmove_clock};

    if (side_to_move == kBlack) fullmove_number++;
    side_to_move = color_opposite(side_to_move);
    halfmove_clock = (captured != kPieceNone) ? 0 : halfmove_clock + 1;
    history_hashes_[history_size_++] = hash;
    return true;
}

void Position::undo_move() {
    const UndoInfo info = move_stack_[--move_stack_top_];
    if (history_size_ > 1) --history_size_;

    side_to_move = color_opposite(side_to_move);
    if (side_to_move == kBlack) fullmove_number--;
    halfmove_clock = info.prev_halfmove_clock;

    squares[info.to] = info.captured;
    squares[info.from] = info.moved;

    hash ^= kZobrist.side;
    hash ^= kZobrist.piece_square[info.moved][info.from];
    hash ^= kZobrist.piece_square[info.moved][info.to];
    if (info.captured != kPieceNone) hash ^= kZobrist.piece_square[info.captured][info.to];

    if (piece_type(info.moved) == kTypePawn) {
        pawn_hash ^= kZobrist.piece_square[info.moved][info.from];
        pawn_hash ^= kZobrist.piece_square[info.moved][info.to];
    }
    if (info.captured != kPieceNone && piece_type(info.captured) == kTypePawn) {
        pawn_hash ^= kZobrist.piece_square[info.captured][info.to];
    }
}

bool Position::is_threefold_repetition() const {
    if (history_size_ < 9) return false;
    int count = 0;
    for (int i = 0; i < history_size_; ++i) {
        if (history_hashes_[i] == hash && ++count >= 3) return true;
    }
    return false;
}

bool Position::leaves_king_safe(const Move& m) {
    const int mover_color = side_to_move;
    if (!do_move(m)) return false;
    const bool safe = !is_in_check(*this, mover_color);
    undo_move();
    return safe;
}

namespace {
char piece_fen_char(int code) {
    static const char kFen[] = " KABNRCP";  // index by type 0..7 (红大写)
    const int t = piece_type(code);
    if (t == 0) return ' ';
    const char c = kFen[t];
    return piece_is_red(code) ? c : static_cast<char>(std::tolower(c));
}

int fen_char_to_piece(char c) {
    static const char kUpper[] = "KABNRCP";
    const char up = static_cast<char>(std::toupper(c));
    for (int t = 1; t <= 7; ++t) {
        if (kUpper[t - 1] == up) return std::isupper(c) ? t : (t + 8);
    }
    return kPieceNone;
}
}  // namespace

std::string Position::to_fen() const {
    std::string s;
    for (int r = 0; r < kRows; ++r) {
        int empty = 0;
        for (int c = 0; c < kCols; ++c) {
            const int p = squares[sq_make(r, c)];
            if (p == kPieceNone) {
                ++empty;
            } else {
                if (empty > 0) { s += std::to_string(empty); empty = 0; }
                s += piece_fen_char(p);
            }
        }
        if (empty > 0) s += std::to_string(empty);
        if (r < kRows - 1) s += '/';
    }
    s += side_to_move == kRed ? " w" : " b";
    s += " - - " + std::to_string(halfmove_clock) + " " + std::to_string(fullmove_number);
    return s;
}

bool Position::set_from_fen(const std::string& fen) {
    std::istringstream iss(fen);
    std::string board_part;
    if (!(iss >> board_part)) return false;

    int parsed[kSquareCount];
    for (int i = 0; i < kSquareCount; ++i) parsed[i] = kPieceNone;

    int row = 0, col = 0;
    for (char ch : board_part) {
        if (ch == '/') {
            if (col != kCols) return false;
            ++row; col = 0;
        } else if (std::isdigit(static_cast<unsigned char>(ch))) {
            col += ch - '0';
        } else {
            const int piece = fen_char_to_piece(ch);
            if (piece == kPieceNone) return false;
            if (col >= kCols || row >= kRows) return false;
            parsed[sq_make(row, col)] = piece;
            ++col;
        }
    }
    if (row != kRows - 1 || col != kCols) return false;

    for (int i = 0; i < kSquareCount; ++i) squares[i] = parsed[i];

    std::string side_tok;
    side_to_move = kRed;
    if (iss >> side_tok && side_tok == "b") side_to_move = kBlack;

    std::string dash1, dash2;
    int hmc = 0, fmn = 1;
    if (iss >> dash1 >> dash2) {
        if (!(iss >> hmc)) hmc = 0;
        if (!(iss >> fmn)) fmn = 1;
    }
    halfmove_clock = hmc;
    fullmove_number = fmn;
    hash = compute_hash();
    pawn_hash = compute_pawn_hash();
    move_stack_top_ = 0;
    history_size_ = 1;
    history_hashes_[0] = hash;
    null_move_depth_ = 0;
    return true;
}

}  // namespace xq
