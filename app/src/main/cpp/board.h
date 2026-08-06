// 局面表示: 与 Kotlin 侧 Square/Piece/Move/Position/Zobrist 逐一对应.
//
// 这是**独立重写**, 不是移植 Pikafish/Stockfish 的代码 —— 规则和编码全部照
// 本项目 Kotlin 源码 (app/src/main/java/com/zhongguoxiangqi/game/) 来, 目的
// 是让 C++ 和 Kotlin 两条实现在同样局面上给出逐位相同的结果, 用 perft 交叉
// 验证。
#ifndef XQ_BOARD_H
#define XQ_BOARD_H

#include <cstdint>
#include <string>

namespace xq {

// ------------------------------------------------------------------ Square
//
// 棋盘 10 行(0..9) x 9 列(0..8), square = row*9 + col, 与 Kotlin Square 一致
// (也与 nnue_core 的 kSquares/king_bucket 用的下标一致, 不需要转换)。
constexpr int kRows = 10;
constexpr int kCols = 9;
constexpr int kSquareCount = 90;
constexpr int kSquareNone = -1;

inline int sq_make(int row, int col) { return row * kCols + col; }
inline int sq_row(int sq) { return sq / kCols; }
inline int sq_col(int sq) { return sq % kCols; }
inline bool sq_valid(int sq) { return sq >= 0 && sq < kSquareCount; }
inline bool sq_valid_rc(int row, int col) {
    return row >= 0 && row < kRows && col >= 0 && col < kCols;
}

// ------------------------------------------------------------- Piece/Color
//
// 与 Kotlin Piece 编码逐位一致: 1..7 红方(帅仕相马车炮兵), 9..15 黑方同序,
// 0=空。piece & 0x07 就是类型, 与位运算刻意保持一致。
constexpr int kPieceNone = 0;
constexpr int kColorNone = -1;
constexpr int kRed = 1;
constexpr int kBlack = 0;

constexpr int kTypeKing = 1;
constexpr int kTypeAdvisor = 2;
constexpr int kTypeElephant = 3;
constexpr int kTypeHorse = 4;
constexpr int kTypeRook = 5;
constexpr int kTypeCannon = 6;
constexpr int kTypePawn = 7;

constexpr int kRKing = 1, kRAdvisor = 2, kRElephant = 3, kRHorse = 4,
              kRRook = 5, kRCannon = 6, kRPawn = 7;
constexpr int kBKing = 9, kBAdvisor = 10, kBElephant = 11, kBHorse = 12,
              kBRook = 13, kBCannon = 14, kBPawn = 15;

inline int color_opposite(int c) { return 1 - c; }
inline bool piece_is_red(int code) { return code >= 1 && code <= 7; }
inline bool piece_is_black(int code) { return code >= 9 && code <= 15; }
inline int piece_color(int code) {
    if (piece_is_red(code)) return kRed;
    if (piece_is_black(code)) return kBlack;
    return kColorNone;
}
inline int piece_type(int code) { return code == 0 ? 0 : (code & 0x07); }

/** 与 Kotlin NnueFeatures.pieceIndex 逐一对应: 0..6 红, 7..13 黑, -1=空 */
inline int piece_nnue_index(int code) {
    if (code == kPieceNone) return -1;
    if (piece_is_red(code)) return piece_type(code) - 1;       // 0..6
    return piece_type(code) - 1 + 7;                            // 7..13
}

// -------------------------------------------------------------------- Move
//
// 与 Kotlin Move 编码一致的低 14 位 (bits0-6 from, bits7-13 to); Kotlin 里的
// type/给将标记位在这里的搜索路径用不到 (MoveType.EN_PASSANT 从未使用),
// 故不移植, 保持结构最简。
struct Move {
    int32_t data = 0;

    Move() = default;
    explicit Move(int32_t d) : data(d) {}
    Move(int from, int to) : data(((to & 0x7F) << 7) | (from & 0x7F)) {}

    int from() const { return data & 0x7F; }
    int to() const { return (data >> 7) & 0x7F; }
    bool is_none() const { return data == 0; }
};
inline bool operator==(const Move& a, const Move& b) { return a.data == b.data; }
inline bool operator!=(const Move& a, const Move& b) { return a.data != b.data; }

// ----------------------------------------------------------------- Zobrist
//
// 用固定种子生成, 与 Kotlin Zobrist(Random(0xCAFEBABEL)) 用同一个 xorshift
// 序列没有意义(两边 RNG 算法不同, 数值天然对不上) —— 这无所谓, Zobrist 键
// 只要求"同一次进程内自洽", C++ 和 Kotlin 各自的哈希值本来就不需要跨语言相等。
struct ZobristTable {
    int64_t piece_square[16][kSquareCount];
    int64_t side;
    ZobristTable();
};
extern const ZobristTable kZobrist;

// ---------------------------------------------------------------- Position
struct UndoInfo {
    int from, to, moved, captured, prev_halfmove_clock;
};

// 一次搜索(含迭代加深的所有深度)展开的最大层数远小于这个值, 留足余量。
// App 会把整局历史重放进来，再在其上展开搜索树；256 在 200 ply 长局 + 64 ply
// 搜索时可能溢出。512 仍只有十几 KB，同时覆盖完整对局历史与搜索栈。
constexpr int kMaxSearchStack = 512;

class Position {
public:
    int squares[kSquareCount];
    int side_to_move = kRed;
    int64_t hash = 0;
    /** 只包含红黑兵/卒位置，不含走子方；供 pawn correction history 索引。 */
    int64_t pawn_hash = 0;
    int halfmove_clock = 0;
    int fullmove_number = 1;

    Position() { setup_start_position(); }

    void setup_start_position();
    int64_t compute_hash() const;
    int64_t compute_pawn_hash() const;

    int piece_at(int sq) const { return squares[sq]; }
    void set_piece(int row, int col, int piece) { squares[sq_make(row, col)] = piece; }
    int count_pieces_in_col(int col, int piece) const;

    // 空着: 只切换行棋权, 供空着裁剪用。故意不进 move_stack_ —— 空着不能用
    // undo_move() 撤销, 必须配对调用 undo_null_move()。
    void do_null_move();
    void undo_null_move();

    bool has_threatening_piece(int color) const;

    // 执行/撤销走法。返回 false 表示走法非法 (from 无子或颜色不对)。
    bool do_move(const Move& m);
    void undo_move();  // 前提: 上一次调用是配对的 do_move() 且返回了 true

    // 走完 m 之后本方是否不被将军。前提: m 已是伪合法。
    bool leaves_king_safe(const Move& m);

    bool is_fifty_move_draw() const { return halfmove_clock >= 120; }
    bool is_threefold_repetition() const;
    /** 搜索专用：空着子树不是合法对局历史，不能触发重复/限着和棋。 */
    bool is_search_rule_draw() const {
        return null_move_depth_ == 0 &&
               (is_fifty_move_draw() || is_threefold_repetition());
    }
    /** 离线评估轨迹采集时排除“空着后”的非真实局面。 */
    bool is_after_null_move() const { return null_move_depth_ != 0; }

    bool set_from_fen(const std::string& fen);
    std::string to_fen() const;

private:
    UndoInfo move_stack_[kMaxSearchStack];
    int move_stack_top_ = 0;
    // history_hashes_[0] 是起始局面；每个真实 do_move 再追加一个。空着不追加。
    int64_t history_hashes_[kMaxSearchStack + 1] = {};
    int history_size_ = 0;
    int null_move_depth_ = 0;
};

}  // namespace xq

#endif  // XQ_BOARD_H
