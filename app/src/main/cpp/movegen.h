// 走法生成 + 规则判定. 与 Kotlin MoveGenerator.kt / Rules.kt 逐一对应.
//
// 独立重写(clean-room), 不是移植 Pikafish —— 规则细节(蹩马腿/塞象眼/炮架/
// 兵过河/飞将)全部照本项目 Kotlin 源码来, 用 perft 交叉验证两边逐层合法着法
// 数完全一致。
#ifndef XQ_MOVEGEN_H
#define XQ_MOVEGEN_H

#include <vector>

#include "board.h"

namespace xq {

// ------------------------------------------------------------ MoveGenerator

/** 生成某一格棋子的全部伪合法走法(不检查送将) */
void generate_for_square(const Position& pos, int sq, std::vector<Move>& moves,
                          bool captures_only = false);

/** 生成所有伪合法走法(供 Rules::is_pseudo_legal 和内部搜索用) */
void generate_pseudo(const Position& pos, std::vector<Move>& moves,
                      bool captures_only = false);

/**
 * 生成所有合法吃子走法. 只给静止搜索用.
 *
 * pos 是非 const 引用: 剔除送将要靠 leaves_king_safe 做"走一步/判将/撤销",
 * 净效果不改局面, 但过程中会临时改写它, 与 Kotlin 版签名(非 immutable)一致。
 */
void generate_captures(Position& pos, std::vector<Move>& moves);

/** 生成所有合法走法(伪合法 + 剔除送将) */
void generate_all(Position& pos, std::vector<Move>& moves);

// ------------------------------------------------------------------- Rules

/** 是否处于被将军状态 */
bool is_in_check(const Position& pos, int color);

/** 是否对脸将(飞行将军) */
bool is_flying_general(const Position& pos, int king_sq);

/** 走法是否符合该棋子的行棋规则(可能送将) */
bool is_pseudo_legal(const Position& pos, const Move& m);

/** 生成所有合法走法, 与 generate_all 同义, 供搜索代码按 Kotlin 命名习惯调用 */
inline void generate_all_legal(Position& pos, std::vector<Move>& moves) {
    generate_all(pos, moves);
}

/** 是否将死 */
bool is_checkmate(Position& pos);

/** 是否困毙 */
bool is_stalemate(Position& pos);

}  // namespace xq

#endif  // XQ_MOVEGEN_H
