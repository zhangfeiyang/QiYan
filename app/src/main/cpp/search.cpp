#include "search.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>

#include "movegen.h"
#include "see.h"

namespace xq {

namespace {

constexpr int kTtExact = 0;
constexpr int kTtLower = 1;
constexpr int kTtUpper = 2;

constexpr int kMateScore = 30000;
constexpr int kMaxMatePly = 200;
constexpr int32_t kMaxScore = INT32_MAX - 1;
constexpr int32_t kMinScore = INT32_MIN + 1;

constexpr int64_t kTtScoreBias = 1LL << 21;
constexpr int64_t kTtScoreLimit = (1LL << 21) - 1;

constexpr int kOrderTt = 1000000;
constexpr int kOrderCapture = 100000;
constexpr int kOrderKiller1 = 90000;
constexpr int kOrderKiller2 = 80000;
// 亏本吃子: 排在所有安静着法(量程 ±3 万左右)之后
constexpr int kOrderBadCapture = -1000000;
constexpr int kHistoryMax = 16384;

constexpr int kDeltaMargin = 150;
// qsearch 里 SEE 的静态预筛阈值: victim 比 attacker 便宜超过 100 兵就
// 直接跳过(完整 SEE 只会得出"净亏、跳过"的同一结论)。PikaFish 系做法。
constexpr int kSeeStaticMargin = 100;
constexpr int kRfpMaxDepth = 4;
constexpr int kRfpMarginPerDepth = 140;

constexpr int kNullMoveMinDepth = 3;

constexpr double kLmrBase = 0.20;
constexpr int kLmrMinDepth = 3;
constexpr int kLmrMinMoves = 4;

constexpr int kAspirationMinDepth = 3;
constexpr int kAspirationWindow = 25;

/**
 * 奇异延伸(singular extension).
 *
 * 置换表着法在浅一半深度、排除自己之后的窄窗口([ttScore-margin-1, ttScore-
 * margin])下试探: 如果**没有任何其他着法**能达到这个下限, 说明它明显比同层
 * 所有对手都好("奇异"), 值得多搜一层。
 *
 * 这是 Pikafish/Stockfish 系敢把 LMR/空着裁剪削得比我们狠的底气所在: 那些
 * 削减本质上是"赌排序好的那手不需要搜那么深", 赌错了会漏算; 奇异延伸从另一
 * 头把"明显最好的那手"单独拎出来多搜, 把"赌错"的风险兜住了一部分。我们
 * 之前没有这层保护, 所以 LMR/空着裁剪一直刻意保守(见 LMR_DIV 的注释)。
 *
 * 这里只做最基础的一种(延伸 1 层), 不做双重延伸/负延伸/multi-cut 那些进阶
 * 变体 —— 先验证核心机制本身有没有用, 参数是没调过的保守起点。
 */
constexpr int kSeMinDepth = 4;
constexpr int kSeTtDepthMargin = 3;
constexpr int kSeMarginPerDepth = 2;

/**
 * 后期着法裁剪(LMP) 与 无用着法裁剪(futility pruning).
 *
 * 加这两个的依据是实测的分支因子: 我方 EBF≈2.0, Pikafish≈1.40, 而单节点
 * 速度我方反而是它的 2 倍(NPS 1.28M vs 0.63M)。也就是说"搜得慢"从来不是
 * 实现不够快, 而是同样深度我们要多看几十倍的节点 —— 同样 140 万节点,
 * Pikafish 能搜到 22 层, 我们只到 11 层。继续抠指令级性能是没有出路的,
 * 唯一的杠杆是把每层的分支数压下来。
 *
 * LMP: 浅层里排在很后面的**安静着法**(非吃子/非将军), 排序已经把它们判为
 *      没希望, 直接不搜。阈值随深度平方增长 —— 深了就不敢这么武断。
 * futility: 浅层里静态分加上一个乐观余量都够不到 alpha 的安静着法, 搜了
 *      也翻不了盘, 直接跳过。
 *
 * 两者都只对安静着法生效, 且都在"被将军 / 已找到杀棋分"时禁用 —— 那些
 * 场景下"看起来没希望"的着法恰恰可能是唯一解。
 */
constexpr int kFutilityBase = 100;
constexpr int kFutilityPerDepth = 120;

// 内部迭代减量(IIR): 置换表没着法时排序必然很差, 减一层先把着法探出来
constexpr int kIirMinDepth = 6;
// 主搜索的 SEE 剪枝: 亏得越多、深度越浅, 越该直接跳过
constexpr int kSeePruneMaxDepth = 6;
constexpr int kSeePruneMargin = 30;
// razoring: 静态分离 alpha 太远, 直接转静止搜索
constexpr int kRazorMaxDepth = 3;
constexpr int kRazorBase = 300;
constexpr int kRazorPerDepth = 180;

/** 深度 d 上, 搜过多少个安静着法之后就不再往下搜 */
int lmp_limit(int d) { return 3 + d * d; }

constexpr int64_t kTimeCheckMask = 0x1FF;

// ---- TT 位打包辅助: 与 Kotlin storeTT/ttXxxOf 逐位一致 ----
int tt_move_of(int64_t d) { return static_cast<int>(d & 0x3FFFFLL); }
int tt_score_of(int64_t d) {
    return static_cast<int>((static_cast<uint64_t>(d) >> 18) & 0x3FFFFFULL) -
           static_cast<int>(kTtScoreBias);
}
int tt_depth_of(int64_t d) {
    return static_cast<int>((static_cast<uint64_t>(d) >> 40) & 0xFFULL);
}
int tt_flag_of(int64_t d) {
    return static_cast<int>((static_cast<uint64_t>(d) >> 48) & 0x3ULL);
}
int tt_gen_of(int64_t d) {
    return static_cast<int>((static_cast<uint64_t>(d) >> 50) & 0xFFULL);
}

int mvv_lva(int victim_code, int attacker_code) {
    return (see_value(victim_code) << 3) - see_value(attacker_code);
}

bool is_mate_score(int score) { return std::abs(score) >= kMateScore - kMaxMatePly; }

// 置换表条目会在不同 ply 命中。存时去掉根距离、读时按当前 ply 加回；否则
// 同一个三步杀换条路径命中后会变成两/四步杀，搜索可能反而偏好更慢的杀法。
int score_to_tt(int score, int ply) {
    if (score >= kMateScore - kMaxMatePly) return score + ply;
    if (score <= -kMateScore + kMaxMatePly) return score - ply;
    return score;
}

int score_from_tt(int score, int ply) {
    if (score >= kMateScore - kMaxMatePly) return score - ply;
    if (score <= -kMateScore + kMaxMatePly) return score + ply;
    return score;
}

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

/** 把局面摊成 (pieceIdx, sq) 列表, 与 Kotlin NnueFeatures.extract 对应 */
int extract_pieces(const Position& pos, uint8_t* out) {
    int n = 0;
    for (int sq = 0; sq < kSquareCount; ++sq) {
        const int code = pos.squares[sq];
        if (code == kPieceNone) continue;
        const int idx = piece_nnue_index(code);
        if (idx < 0) continue;
        if (n >= nnue::kMaxPieces) break;
        out[n * 2] = static_cast<uint8_t>(idx);
        out[n * 2 + 1] = static_cast<uint8_t>(sq);
        ++n;
    }
    return n;
}

}  // namespace

int SearchEngine::see_counted(const Position& pos, const Move& m) {
    if (collect_cutoff_stats) {
        ++see_total;
        const int victim = pos.squares[m.to()];
        const int attacker = pos.squares[m.from()];
        int diff = 0;
        if (victim != kPieceNone && attacker != kPieceNone) {
            diff = see_value(victim) - see_value(attacker);
        }
        const int idx = std::clamp(diff / 100 + 7, 0, 15);
        ++see_gain_hist[idx];
    }
    return see_evaluate(pos, m);
}

void SearchEngine::gen_all_counted() {
    if (collect_cutoff_stats) ++gen_all_count;
}

void SearchEngine::gen_cap_counted() {
    if (collect_cutoff_stats) ++gen_cap_count;
}

void SearchEngine::move_acc_counted() {
    if (collect_cutoff_stats) ++move_acc_count;
}

void SearchEngine::rebuild_lmr_table() {
    for (int d = 0; d <= kLmrTableMax; ++d) {
        for (int m = 0; m <= kLmrTableMax; ++m) {
            lmr_table_[d][m] = (d < 1 || m < 1)
                ? 0
                : static_cast<int>(kLmrBase + std::log(static_cast<double>(d)) *
                                                   std::log(static_cast<double>(m)) / lmr_div);
        }
    }
}

int SearchEngine::lmr_reduction(int depth, int move_count) const {
    const int d = std::min(depth, kLmrTableMax);
    const int m = std::min(move_count, kLmrTableMax);
    return lmr_table_[d][m];
}

void SearchEngine::set_tt_size_shift(int shift) {
    tt_shift_ = std::clamp(shift, 10, 26);
    const size_t n = size_t(1) << tt_shift_;
    tt_mask_ = static_cast<int>(n - 1);
    tt_key_.assign(n, 0);
    tt_data_.assign(n, 0);
}

bool SearchEngine::set_position_fen(const std::string& fen) {
    Position p;
    if (!p.set_from_fen(fen)) return false;
    position_ = p;
    return true;
}

bool SearchEngine::set_position_fen_moves(const std::string& fen,
                                           const std::vector<std::string>& moves) {
    Position p;
    if (!p.set_from_fen(fen)) return false;

    for (const auto& uci : moves) {
        if (uci.size() < 4) return false;
        const int from_col = uci[0] - 'a';
        const int to_col = uci[2] - 'a';
        const int from_rank = uci[1] - '0';
        const int to_rank = uci[3] - '0';
        if (from_col < 0 || from_col >= kCols || to_col < 0 || to_col >= kCols ||
            from_rank < 0 || from_rank >= kRows || to_rank < 0 || to_rank >= kRows) {
            return false;
        }
        // UCI rank 0 是红方底线；内部 row 0 是黑方底线。
        const Move parsed(sq_make(kRows - 1 - from_rank, from_col),
                          sq_make(kRows - 1 - to_rank, to_col));
        std::vector<Move> legal;
        generate_all_legal(p, legal);
        const auto it = std::find(legal.begin(), legal.end(), parsed);
        if (it == legal.end() || !p.do_move(*it)) return false;
    }

    position_ = p;
    return true;
}

void SearchEngine::reset_search_state() {
    tt_gen_ = (tt_gen_ + 1) & 0xFF;
    if (collect_cutoff_stats) {
        int64_t occupied = 0;
        for (size_t i = 0; i < tt_key_.size(); ++i) {
            if (tt_key_[i] != 0) ++occupied;
        }
        tt_pre_search_occupied = occupied;
    }
    for (auto& e : static_eval_hist_) e = INT32_MIN;
    for (auto& k : killers_) { k[0] = 0; k[1] = 0; }
    if (history_decay_divisor >= 2) {
        for (auto& row : history_) {
            for (auto& entry : row) entry /= history_decay_divisor;
        }
    } else {
        for (auto& row : history_) std::fill(std::begin(row), std::end(row), 0);
    }
    if (use_piece_to_hist) {
        if (history_decay_divisor >= 2) {
            for (auto& e : piece_to_hist_) e = static_cast<int16_t>(e / history_decay_divisor);
        } else {
            std::fill(piece_to_hist_.begin(), piece_to_hist_.end(), 0);
        }
    }
    // 续接历史**不清零, 只衰减一半**。
    //
    // 这张表有 159 万个格子, 而一次搜索只走十几万个节点 —— 每次搜索都清零的话,
    // 绝大多数格子永远是 0, 排序时它提供的与其说是信号不如说是噪声, 反而把
    // 本来密集可靠的 from-to 历史表(只有 8100 个格子)给稀释了。实测清零版本
    // 在深度 10 上比不开还多搜 2.4% 的节点, 就是这个原因。
    // 保留上一次搜索的统计并衰减, 表才能真正被"喂饱"。
    for (auto& e : cont_hist_) e = static_cast<int16_t>(e / 2);
    for (auto& e : capture_hist_) e = static_cast<int16_t>(e / 2);
    for (auto& c : cont_key_) c = -1;
}

/**
 * 前两层的续接历史之和。
 *
 * 取前 1 层和前 2 层: 前 1 层是"对手刚走完这手, 我该怎么应", 前 2 层是"我上
 * 一手走了这个, 接着走什么" —— 后者对识别多步计划特别有用。再往回(3/4/6 层)
 * 收益递减, 先不做。
 */
int SearchEngine::cont_score(int ply, int piece_to) const {
    int sum = 0;
    int used = 0;
    for (int back = 1; back <= cont_plies; ++back) {
        if (ply - back < 0) break;
        const int prev = cont_key_[ply - back];
        if (prev < 0) continue;   // 根节点或空着, 没有"上一手"
        sum += cont_hist_[static_cast<size_t>(prev) * kContDim + piece_to];
        ++used;
    }
    // 取平均而不是求和: 多层相加会让续接历史的量程数倍于 from-to 历史表,
    // 排序分就被它单方面主导。两者信号不同但可信度相当, 量程该对齐 ——
    // 也因此换层数时不必重调其它权重。
    return used > 0 ? sum / used : 0;
}

void SearchEngine::update_cont_hist(int ply, int piece_to, int bonus) {
    for (int back = 1; back <= cont_plies; ++back) {
        if (ply - back < 0) break;
        const int prev = cont_key_[ply - back];
        if (prev < 0) continue;
        int16_t& e = cont_hist_[static_cast<size_t>(prev) * kContDim + piece_to];
        // 与 update_history 同一条引力公式: 越接近上限, 同样的 bonus 推得越少,
        // 保证表项自然收敛在 [-kHistoryMax, kHistoryMax] 内而不会溢出 int16。
        const int clamped = std::clamp(bonus, -kHistoryMax, kHistoryMax);
        const int cur = e;
        e = static_cast<int16_t>(cur + clamped - cur * std::abs(clamped) / kHistoryMax);
    }
}

void SearchEngine::acc_refresh_root() {
    uint8_t pieces[nnue::kMaxPieces * 2];
    const int n = extract_pieces(position_, pieces);
    acc_depth_ = 0;
    nnue::acc_refresh(net_, acc_stack_[0], pieces, n);
}

bool SearchEngine::do_move_acc(const Move& m) {
    move_acc_counted();
    const int mover_code = position_.squares[m.from()];
    const int mover_idx = piece_nnue_index(mover_code);
    const int cap_code = position_.squares[m.to()];
    const int cap_idx = (cap_code == kPieceNone) ? -1 : piece_nnue_index(cap_code);
    const bool is_king = nnue::is_king_index(mover_idx);

    if (!position_.do_move(m)) return false;

    if (acc_depth_ + 1 < kAccCapacity) {
        if (is_king) {
            uint8_t pieces[nnue::kMaxPieces * 2];
            const int n = extract_pieces(position_, pieces);
            nnue::acc_refresh(net_, acc_stack_[acc_depth_ + 1], pieces, n);
        } else if (!nnue::acc_apply(net_, acc_stack_[acc_depth_ + 1], acc_stack_[acc_depth_],
                                     mover_idx, m.from(), m.to(), cap_idx)) {
            acc_stack_[acc_depth_ + 1].raw_bucket = -1;  // 标记不可用, 求值时退回全量
        }
    }
    ++acc_depth_;
    return true;
}

void SearchEngine::undo_move_acc() {
    position_.undo_move();
    if (acc_depth_ > 0) --acc_depth_;
}

int SearchEngine::evaluate(int forced_head) {
    if (collect_cutoff_stats) ++eval_total;
#ifdef XQ_ENABLE_EVAL_TRACE
    if (eval_trace_) eval_trace_(position_, eval_trace_context_);
#endif
    if (acc_depth_ < kAccCapacity && acc_stack_[acc_depth_].raw_bucket >= 0) {
        return forced_head >= 0
            ? nnue::eval_acc_bucket(net_, acc_stack_[acc_depth_],
                                    position_.side_to_move == kBlack, forced_head)
            : nnue::eval_acc(net_, acc_stack_[acc_depth_],
                             position_.side_to_move == kBlack);
    }
    uint8_t pieces[nnue::kMaxPieces * 2];
    const int n = extract_pieces(position_, pieces);
    return forced_head >= 0
        ? nnue::eval_scalar_bucket(net_, pieces, n,
                                   position_.side_to_move == kBlack, forced_head)
        : nnue::eval_scalar(net_, pieces, n, position_.side_to_move == kBlack);
}

int SearchEngine::corrected_evaluate(int forced_head) {
    const int raw = evaluate(forced_head);
    if (!use_correction_history) return raw;
    const int side = position_.side_to_move;
    const int slot = static_cast<int>(position_.pawn_hash) & kCorrMask;
    const int correction = pawn_corr_[side * kCorrSize + slot] / 32;
    return std::clamp(raw + correction,
                      -kMateScore + kMaxMatePly + 1,
                      kMateScore - kMaxMatePly - 1);
}

void SearchEngine::update_correction(int static_eval, int search_score, int depth) {
    if (!use_correction_history || is_mate_score(search_score)) return;
    const int side = position_.side_to_move;
    const int slot = static_cast<int>(position_.pawn_hash) & kCorrMask;
    int16_t& entry = pawn_corr_[side * kCorrSize + slot];
    // 与其它历史表相同的有界“引力”更新。单项表最大修正约 32cp，先取保守量程。
    const int bonus = std::clamp((search_score - static_eval) * depth / 12,
                                 -kCorrLimit / 4, kCorrLimit / 4);
    const int current = entry;
    entry = static_cast<int16_t>(
        current + bonus - current * std::abs(bonus) / kCorrLimit);
}

bool SearchEngine::aborted(int64_t node_count) {
    if (stop_requested_.load(std::memory_order_acquire) || time_up_) return true;
    if ((node_count & kTimeCheckMask) == 0 && now_ms() >= deadline_ms_) {
        time_up_ = true;
        return true;
    }
    return false;
}

void SearchEngine::update_history(int from, int to, int bonus) {
    const int clamped = std::clamp(bonus, -kHistoryMax, kHistoryMax);
    const int current = history_[from][to];
    history_[from][to] = current + clamped - current * std::abs(clamped) / kHistoryMax;
    if (use_piece_to_hist) {
        const int pidx = piece_nnue_index(position_.squares[from]);
        if (pidx >= 0) {
            const size_t key = static_cast<size_t>(pidx) * kSquareCount + to;
            int16_t& e = piece_to_hist_[key];
            e = static_cast<int16_t>(e + clamped - e * std::abs(clamped) / kHistoryMax);
        }
    }
}

void SearchEngine::record_countermove(int ply, const Move& m) {
    if (ply <= 0) return;
    const int prev = cont_key_[ply - 1];
    if (prev < 0) return;
    // 只记录安静着法: 吃子已经由 MVV-LVA/吃子历史排序, 安静着法才需要
    // 额外信号来对抗"上次这手奏效"的记忆。
    if (position_.squares[m.to()] != kPieceNone) return;
    countermove_[static_cast<size_t>(prev)] = m.data;
}

void SearchEngine::record_cutoff(const Move& m, int depth, int ply,
                                  const std::vector<Move>& quiets_tried) {
    if (ply < kMaxPly) {
        int* k = killers_[ply];
        if (k[0] != m.data) { k[1] = k[0]; k[0] = m.data; }
    }
    const int bonus = depth * depth;
    update_history(m.from(), m.to(), bonus);
    if (use_cont_hist) {
        const int pidx = piece_nnue_index(position_.squares[m.from()]);
        if (pidx >= 0) update_cont_hist(ply, piece_to_key(pidx, m.to()), bonus);
    }
    for (size_t i = 0; i + 1 < quiets_tried.size(); ++i) {
        update_history(quiets_tried[i].from(), quiets_tried[i].to(), -bonus);
        if (use_cont_hist) {
            const int p = piece_nnue_index(position_.squares[quiets_tried[i].from()]);
            if (p >= 0) update_cont_hist(ply, piece_to_key(p, quiets_tried[i].to()), -bonus);
        }
    }
}

void SearchEngine::record_capture_cutoff(const Move& m, int depth,
                                          const std::vector<Move>& caps_tried) {
    if (!use_capture_hist) return;
    const int bonus = depth * depth;
    auto bump = [&](const Move& mv, int b) {
        const int pidx = piece_nnue_index(position_.squares[mv.from()]);
        const int victim = position_.squares[mv.to()];
        if (pidx < 0 || victim == kPieceNone) return;
        int16_t& e = capture_hist_[capt_key(pidx, mv.to(), piece_type(victim))];
        const int clamped = std::clamp(b, -kHistoryMax, kHistoryMax);
        const int cur = e;
        e = static_cast<int16_t>(cur + clamped - cur * std::abs(clamped) / kHistoryMax);
    };
    bump(m, bonus);
    for (size_t i = 0; i + 1 < caps_tried.size(); ++i) bump(caps_tried[i], -bonus);
}

void SearchEngine::order_moves(std::vector<Move>& moves, int tt_move, int ply) {
    const int k0 = (ply < kMaxPly) ? killers_[ply][0] : 0;
    const int k1 = (ply < kMaxPly) ? killers_[ply][1] : 0;
    const int cm = use_countermove && (ply > 0 && cont_key_[ply - 1] >= 0)
        ? countermove_[static_cast<size_t>(cont_key_[ply - 1])] : 0;

    std::vector<std::pair<int, Move>> scored;
    scored.reserve(moves.size());
    for (auto& m : moves) {
        int score;
        if (m.data == tt_move) {
            score = kOrderTt;
        } else {
            const int victim = position_.squares[m.to()];
            if (victim != kPieceNone) {
                if (use_see_internal_order) {
                    // 保持 kOrderCapture 档位, 内部按 SEE 值排序: 真正吃到
                    // 净收益的吃子先搜, 明显亏本的吃子仍然在杀手之上但排到
                    // 好吃子后面 —— 不改变安静着法序号。
                    score = kOrderCapture + see_counted(position_, m);
                } else if (use_see_order) {
                    const int see = see_counted(position_, m);
                    if (see > 0) {
                        score = kOrderCapture + mvv_lva(victim, position_.squares[m.from()]);
                    } else if (see == 0) {
                        // 平子吃子: 排在杀手之后、安静历史之前。
                        score = kOrderKiller2 + 1 + mvv_lva(victim, position_.squares[m.from()]) / 8;
                    } else {
                        // 亏本吃子: 沉到所有安静着法之后。
                        score = kOrderBadCapture + mvv_lva(victim, position_.squares[m.from()]);
                    }
                } else {
                    // 亏本吃子(SEE<0)降到最低档: 它排在杀手着法前面是纯粹的浪费,
                    // 因为杀手是"本层已经验证过能截断"的着法, 而亏本吃子多半会被
                    // 立刻反吃回来。
                    const bool bad = use_bad_capture_ordering && see_counted(position_, m) < 0;
                    score = (bad ? bad_capture_base : kOrderCapture) +
                            mvv_lva(victim, position_.squares[m.from()]);
                }
                if (use_capture_hist) {
                    const int pidx = piece_nnue_index(position_.squares[m.from()]);
                    // 吃子历史分除以 16: 只当同价值吃子之间的**平手判定**,
                    // 不能盖过 MVV-LVA 本身。除以 4 实测中局 -1.1%、残局
                    // +3.5%, 无净收益, 恢复原值。
                    if (pidx >= 0) {
                        score += capture_hist_[capt_key(pidx, m.to(), piece_type(victim))] / capture_hist_div;
                    }
                }
            } else if (m.data == k0) {
                score = kOrderKiller1;
            } else if (m.data == k1) {
                score = kOrderKiller2;
            } else if (cm != 0 && m.data == cm) {
                // 对手刚走这手后, 上次这手应法奏效过 —— 排在两个杀手之间,
                // 紧跟在已验证的截断着法后面。
                score = kOrderKiller2 + 1;
            } else {
                score = history_[m.from()][m.to()];
                if (use_piece_to_hist) {
                    const int pidx = piece_nnue_index(position_.squares[m.from()]);
                    if (pidx >= 0) {
                        score += piece_to_hist_[static_cast<size_t>(pidx) * kSquareCount + m.to()];
                    }
                }
                if (use_cont_hist) {
                    const int pidx = piece_nnue_index(position_.squares[m.from()]);
                    if (pidx >= 0) score += cont_score(ply, piece_to_key(pidx, m.to()));
                }
            }
        }
        scored.emplace_back(score, m);
    }
    std::stable_sort(scored.begin(), scored.end(),
                      [](const auto& a, const auto& b) { return a.first > b.first; });
    for (size_t i = 0; i < moves.size(); ++i) moves[i] = scored[i].second;
}

void SearchEngine::record_cutoff_stat(int searched, int tt_move, int ply) {
    int pieces = 0;
    for (int sq = 0; sq < 90; ++sq) {
        if (position_.squares[sq] != kPieceNone) ++pieces;
    }
    CutoffStats& s = (pieces <= 14) ? cutoff_endgame : cutoff_midgame;
    ++s.nodes;
    const Move& m = last_cutoff_move_;
    if (m.data == tt_move) {
        ++s.by_tt;
    } else if (position_.squares[m.to()] != kPieceNone) {
        ++s.by_capture;
    } else if (ply < kMaxPly && m.data == killers_[ply][0]) {
        ++s.by_killer1;
    } else if (ply < kMaxPly && m.data == killers_[ply][1]) {
        ++s.by_killer2;
    } else {
        ++s.by_quiet;
    }
    if (searched <= 1) ++s.first_move;
    else if (searched == 2) ++s.second_move;
    else if (searched <= 4) ++s.pos_3_4;
    else if (searched <= 8) ++s.pos_5_8;
    else if (searched <= 16) ++s.pos_9_16;
    else ++s.pos_17p;
}

void SearchEngine::dump_cutoff_stats(FILE* out) const {
    if (eval_total > 0) {
        fprintf(out, "[cutstats] 求值: 总=%lld 主搜索=%lld (%.1f%%) 静止=%lld (%.1f%%)\n",
                (long long)eval_total, (long long)eval_main,
                eval_main * 100.0 / eval_total, (long long)eval_qsearch,
                eval_qsearch * 100.0 / eval_total);
        fprintf(out, "[cutstats] SEE: %lld 次\n", (long long)see_total);
        fprintf(out, "[cutstats] 走法生成: generate_all=%lld generate_captures=%lld\n",
                (long long)gen_all_count, (long long)gen_cap_count);
        fprintf(out, "[cutstats] do_move_acc: %lld 次\n", (long long)move_acc_count);
        fprintf(out, "[cutstats] 节点构成: alpha_beta=%lld qsearch=%lld\n",
                (long long)ab_nodes, (long long)qsearch_nodes);
        fprintf(out, "[cutstats] SEE 价值差分布(victim-attacker 百兵):");
        for (int i = 0; i < 16; ++i) {
            if (see_gain_hist[i] > 0) {
                fprintf(out, " %d:%lld", (i - 7) * 100, (long long)see_gain_hist[i]);
            }
        }
        fprintf(out, "\n");
    }
    if (tt_pre_search_occupied >= 0) {
        fprintf(out, "[cutstats] TT 搜索前占用槽=%lld/%zu (%.1f%%)\n",
                (long long)tt_pre_search_occupied, tt_key_.size(),
                tt_pre_search_occupied * 100.0 / tt_key_.size());
    }
    if (tt_probe_nodes > 0) {
        fprintf(out, "[cutstats] TT: 内部节点=%lld 槽命中=%lld (%.1f%%) 深度达标早退=%lld (%.1f%%)\n",
                (long long)tt_probe_nodes, (long long)tt_probe_hits,
                tt_probe_hits * 100.0 / tt_probe_nodes,
                (long long)tt_probe_early, tt_probe_early * 100.0 / tt_probe_nodes);
        fprintf(out, "[cutstats] TT 写入: store=%lld 撞槽覆盖=%lld (%.1f%%)\n",
                (long long)tt_store_calls, (long long)tt_store_overwrite,
                tt_store_calls ? tt_store_overwrite * 100.0 / tt_store_calls : 0.0);
        fprintf(out, "[cutstats] TT 命中细分: 深度达标早退=%lld (%.1f%%) 深度不足=%lld (%.1f%%) 跨世代命中=%lld (%.1f%%)\n",
                (long long)tt_hit_depth_ok,
                tt_probe_hits ? tt_hit_depth_ok * 100.0 / tt_probe_hits : 0.0,
                (long long)tt_hit_depth_short,
                tt_probe_hits ? tt_hit_depth_short * 100.0 / tt_probe_hits : 0.0,
                (long long)tt_gen_mismatch_hit,
                tt_probe_hits ? tt_gen_mismatch_hit * 100.0 / tt_probe_hits : 0.0);
        fprintf(out, "[cutstats] 按迭代探针/命中/早退:");
        for (int d = 1; d < 64; ++d) {
            if (per_iter_probes_[d] > 0) {
                fprintf(out, " d%d=%d/%d/%d", d, per_iter_probes_[d],
                        per_iter_hits_[d], per_iter_early_[d]);
            }
        }
        fprintf(out, "\n");
    }
    const CutoffStats* buckets[2] = {&cutoff_midgame, &cutoff_endgame};
    const char* names[2] = {"中局(>14子)", "残局(<=14子)"};
    for (int b = 0; b < 2; ++b) {
        const CutoffStats& s = *buckets[b];
        if (s.nodes == 0) continue;
        const int64_t n = s.nodes;
        fprintf(out, "[cutstats] %s 截断节点=%lld\n", names[b], (long long)n);
        fprintf(out, "[cutstats]   着法类型: TT=%4.1f%% 吃子=%4.1f%% 杀手1=%4.1f%% 杀手2=%4.1f%% 安静历史=%4.1f%%\n",
                s.by_tt * 100.0 / n, s.by_capture * 100.0 / n,
                s.by_killer1 * 100.0 / n, s.by_killer2 * 100.0 / n,
                s.by_quiet * 100.0 / n);
        fprintf(out, "[cutstats]   截断手次: 第1手=%4.1f%% 第2手=%4.1f%% 第3手+=%4.1f%%\n",
                s.first_move * 100.0 / n, s.second_move * 100.0 / n,
                (s.pos_3_4 + s.pos_5_8 + s.pos_9_16 + s.pos_17p) * 100.0 / n);
        fprintf(out, "[cutstats]   截断位置细分: 3-4手=%4.1f%% 5-8手=%4.1f%% 9-16手=%4.1f%% 17手+=%4.1f%%\n",
                s.pos_3_4 * 100.0 / n, s.pos_5_8 * 100.0 / n,
                s.pos_9_16 * 100.0 / n, s.pos_17p * 100.0 / n);
    }
}

void SearchEngine::reset_cutoff_stats() {
    cutoff_midgame = CutoffStats{};
    cutoff_endgame = CutoffStats{};
    tt_probe_nodes = 0;
    tt_probe_hits = 0;
    tt_probe_early = 0;
    tt_store_calls = 0;
    tt_store_overwrite = 0;
    tt_hit_depth_ok = 0;
    tt_hit_depth_short = 0;
    tt_gen_mismatch_hit = 0;
    for (int d = 0; d < 64; ++d) {
        per_iter_probes_[d] = 0;
        per_iter_hits_[d] = 0;
        per_iter_early_[d] = 0;
    }
    tt_pre_search_occupied = -1;
    eval_total = 0;
    see_total = 0;
    eval_qsearch = 0;
    eval_main = 0;
    see_total = 0;
    gen_all_count = 0;
    gen_cap_count = 0;
    move_acc_count = 0;
    qsearch_nodes = 0;
    ab_nodes = 0;
    for (auto& h : see_gain_hist) h = 0;
}

void SearchEngine::store_tt(int64_t key, int depth, int score, int flag, int move_data, int ply) {
    score = score_to_tt(score, ply);
    if (score < -kTtScoreLimit || score > kTtScoreLimit) return;
    const int slot = tt_slot(key);
    const int64_t old = tt_data_[slot];
    const bool same_key = tt_key_[slot] == key;
    const bool old_stale = tt_gen_of(old) != tt_gen_;
    if (collect_cutoff_stats) {
        ++tt_store_calls;
        if (!same_key && !old_stale && tt_key_[slot] != 0) ++tt_store_overwrite;
    }
    // 同一局面: 已有更深的结果就别用浅的盖掉。
    if (same_key && !old_stale && tt_depth_of(old) > depth) return;
    // 不同局面撞槽: 本轮搜索刚算出来的深条目, 不该被浅得多的新条目挤掉。
    // 过期(上一轮搜索)的条目不保护 —— 那是正常的老化让位。
    if (!same_key && !old_stale && tt_depth_preference > 0 &&
        tt_depth_of(old) > depth + tt_depth_preference) {
        return;
    }
    tt_key_[slot] = key;
    const int clamped_depth = std::clamp(depth, 0, 255);
    const uint64_t packed = (static_cast<uint64_t>(move_data) & 0x3FFFFULL) |
        (static_cast<uint64_t>(score + kTtScoreBias) << 18) |
        (static_cast<uint64_t>(clamped_depth) << 40) |
        (static_cast<uint64_t>(flag) << 48) |
        (static_cast<uint64_t>(tt_gen_) << 50);
    tt_data_[slot] = static_cast<int64_t>(packed);
}

int SearchEngine::alpha_beta(int depth, int alpha, int beta, int64_t& nodes, int ply,
                              bool allow_null, int excluded_move, bool cut_node) {
    ++nodes;
    if (collect_cutoff_stats) ++ab_nodes;
    if (aborted(nodes)) return 0;

    // 裁判在“无着可走”之后才判和；被将局面先排除恰好在第 120 半回合将死，
    // 其它重复/限着节点可直接按 0 分返回。
    if (ply > 0 && position_.is_search_rule_draw()) {
        if (is_in_check(position_, position_.side_to_move)) {
            std::vector<Move> evasions;
            generate_all_legal(position_, evasions);
            gen_all_counted();
            if (evasions.empty()) return -kMateScore + ply;
        }
        return 0;
    }

    // Mate-distance pruning：当前节点可达到的杀棋分只在这个范围内。
    alpha = std::max(alpha, -kMateScore + ply);
    beta = std::min(beta, kMateScore - ply - 1);
    if (alpha >= beta) return alpha;

    // 硬性栈保护: 奇异延伸给的 depth-1+extension 在 extension=1 时深度**不减**,
    // 打破了"每步棋深度必减 1"这条保证递归总会触底的不变式。连续触发的延伸
    // 链可以让 ply(原生调用栈深度)远超名义搜索深度暗示的量级 —— 这是真实
    // 对局里段错误崩溃的根因(静态探针只测了几个孤立局面, 没撞上这种病态链)。
    // 不管深度参数被续了多少命, ply 逼近数组上限就强制转静止搜索, 不再往下递归。
    if (ply >= kMaxPly - 1) return quiescence(alpha, beta, nodes, ply, 0);

    if (depth <= 0) return quiescence(alpha, beta, nodes, ply, 0);

    const int64_t key = position_.hash;
    const int slot = tt_slot(key);
    const bool hit = tt_key_[slot] == key &&
        use_tt && (!tt_require_same_gen || tt_gen_of(tt_data_[slot]) == tt_gen_);
    const int tt_move = hit ? tt_move_of(tt_data_[slot]) : 0;
    if (collect_cutoff_stats) {
        ++tt_probe_nodes;
        if (d_iter_depth >= 0 && d_iter_depth < 64) ++per_iter_probes_[d_iter_depth];
        if (hit) ++tt_probe_hits;
        if (hit) {
            if (tt_gen_of(tt_data_[slot]) != tt_gen_) ++tt_gen_mismatch_hit;
            if (excluded_move == 0 && tt_depth_of(tt_data_[slot]) >= depth) {
                ++tt_probe_early;
                ++tt_hit_depth_ok;
                if (d_iter_depth >= 0 && d_iter_depth < 64) ++per_iter_early_[d_iter_depth];
            } else {
                ++tt_hit_depth_short;
            }
            if (d_iter_depth >= 0 && d_iter_depth < 64) ++per_iter_hits_[d_iter_depth];
        }
    }
    // 奇异延伸验证时排除了 tt_move, 这条早退命中的是"含 tt_move 在内"的完整
    // 结果, 不能用来代替"排除 tt_move 之后能不能达到窗口"这个问题的答案。
    if (hit && excluded_move == 0 && tt_depth_of(tt_data_[slot]) >= depth) {
        const int sc = score_from_tt(tt_score_of(tt_data_[slot]), ply);
        switch (tt_flag_of(tt_data_[slot])) {
            case kTtExact: return sc;
            case kTtLower: if (sc >= beta) return sc; break;
            case kTtUpper: if (sc <= alpha) return sc; break;
            default: break;
        }
    }

    const bool in_check = is_in_check(position_, position_.side_to_move);
    const bool pv_node = beta > alpha + 1;

    // 静态分给 RFP 和 futility 共用, 按需算一次 —— 求值是整个搜索最贵的
    // 单项(实测占 54%), 同一个节点算两遍纯属浪费。
    int static_eval = INT32_MIN;
    const int static_need_depth = std::max(
        kRfpMaxDepth,
        std::max(use_futility ? futility_max_depth : 0, use_razoring ? kRazorMaxDepth : 0));
    if (!in_check && depth <= static_need_depth) {
        static_eval = corrected_evaluate(use_leaf_eval_head ? 0 : -1);
    }

    if (use_rfp && !in_check && depth <= kRfpMaxDepth && beta < kMateScore - kMaxPly) {
        if (static_eval - kRfpMarginPerDepth * depth >= beta) return static_eval;
    }

    // razoring: 浅层里静态分离 alpha 还差一大截, 说明这条线大概率翻不了身,
    // 与其全深度搜, 不如直接交给静止搜索定分。只在非 PV 节点做 —— PV 上
    // 错过一条线的代价是走错棋, 不值得为省这点节点冒险。
    if (use_razoring && !pv_node && !in_check && depth <= kRazorMaxDepth &&
        static_eval != INT32_MIN && !is_mate_score(alpha) &&
        static_eval + kRazorBase + kRazorPerDepth * depth * depth <= alpha) {
        return quiescence(alpha, beta, nodes, ply, 0);
    }
    if (collect_cutoff_stats || use_improving_lmr) {
        static_eval_hist_[ply] = static_eval;
    }
    if (collect_cutoff_stats && static_eval != INT32_MIN) ++eval_main;

    if (use_null_move && allow_null && !in_check && depth >= kNullMoveMinDepth &&
        beta < kMateScore - kMaxPly && position_.has_threatening_piece(position_.side_to_move)) {
        position_.do_null_move();
        cont_key_[ply] = -1;  // 空着不是一手真棋, 不能当作下一层的"上一手"
        const int reduction = std::clamp(null_move_reduction, 1, depth - 1);
        const int null_score = -alpha_beta(depth - 1 - reduction, -beta, -beta + 1,
                                            nodes, ply + 1, false, 0, !cut_node);
        position_.undo_null_move();
        if (aborted(nodes)) return 0;
        if (null_score >= beta && null_score < kMateScore - kMaxPly) return null_score;
    }

    // IIR: 深度够却没有置换表着法, 说明这个节点从来没被认真搜过, 排序里
    // 最重要的那一项(置换表着法)是空的。用这种烂排序去做全深度搜索, LMR
    // 和各种剪枝的前提"排序基本可信"都不成立。减一层, 让它先把着法探出来。
    if (use_iir && depth >= kIirMinDepth && tt_move == 0) --depth;

    std::vector<Move> moves;
    generate_all_legal(position_, moves);
    gen_all_counted();

    if (moves.empty()) {
        return in_check ? -kMateScore + ply : 0;
    }

    order_moves(moves, tt_move, ply);

    int best_score = INT32_MIN;
    int best_flag = kTtUpper;
    int best_move_data = 0;
    int alpha_local = alpha;
    int searched = 0;
    std::vector<Move> quiets_tried;
    std::vector<Move> caps_tried;

    for (auto& m : moves) {
        if (m.data == excluded_move) continue;
        const bool capture = position_.squares[m.to()] != kPieceNone;

        // LMP / futility: 只裁安静着法, 且必须已经搜到过至少一手(searched>0),
        // 否则可能一手都不搜就返回一个凭空捏造的分数。已经摸到杀棋分时全部
        // 禁用 —— 杀棋线路里"看着没希望"的着法常常正是唯一解。
        if (!in_check && !capture && searched > 0 && !is_mate_score(best_score) &&
            !is_mate_score(alpha_local)) {
            if (use_lmp && depth <= lmp_max_depth && searched >= lmp_limit(depth)) continue;
            if (use_futility && depth <= futility_max_depth && static_eval != INT32_MIN &&
                static_eval + kFutilityBase + kFutilityPerDepth * depth <= alpha_local) {
                continue;
            }
        }

        // 主搜索的 SEE 剪枝: 明显亏子的吃子直接跳过。静止搜索里早就有这一条,
        // 主搜索一直没有 —— 于是"送吃"这类着法在主搜索里还要被完整搜一遍。
        // 容忍度随深度放宽(深了不敢乱剪), 且同样在被将军/杀棋分时禁用。
        if (use_see_prune && !in_check && capture && searched > 0 &&
            depth <= kSeePruneMaxDepth && !is_mate_score(best_score) &&
            see_counted(position_, m) < -kSeePruneMargin * depth) {
            continue;
        }

        // 奇异延伸: 只在搜置换表着法(必然是第一手, 排序把它顶到最前)时试。
        // 条件缺一不可: 深度够(浅了验证的开销划不来)、有可信的置换表命中
        // (深度不比当前浅太多、不是"分数上界"这种本来就不精确的记录)、
        // 分数不是杀棋(杀棋分不适合线性地减一个 margin 去试探)。
        int extension = 0;
        if (use_singular_extension && searched == 0 && tt_move != 0 && m.data == tt_move &&
            excluded_move == 0 && depth >= kSeMinDepth && hit &&
            tt_depth_of(tt_data_[slot]) >= depth - kSeTtDepthMargin &&
            tt_flag_of(tt_data_[slot]) != kTtUpper) {
            const int tts = score_from_tt(tt_score_of(tt_data_[slot]), ply);
            if (!is_mate_score(tts)) {
                const int singular_beta = tts - kSeMarginPerDepth * depth;
                const int singular_depth = (depth - 1) / 2;
                const int se_score = alpha_beta(singular_depth, singular_beta - 1, singular_beta,
                                                 nodes, ply, /*allow_null=*/true, /*excluded=*/tt_move,
                                                 cut_node);
                if (se_score < singular_beta) {
                    extension = 1;
                } else if (singular_beta >= beta) {
                    // multi-cut: 复用同一次验证搜索, 不额外加开销. 连"排除掉
                    // 置换表着法之后, 随便一手都能压过"这个更低的门槛
                    // (singular_beta)都能摸到, 而它本身已经 >= 这个节点真正
                    // 的 beta —— 这个节点大概率会 fail-high, 不值得再正常
                    // 搜一遍。跟空着裁剪的信任级别一样(缩减深度的验证, 不是
                    // 严格证明), 所以也跟空着裁剪一样直接返回、不写置换表。
                    return singular_beta;
                }
            }
        }

        // 记下这一手的 (子力,落点), 供下面两层当"上一手"用。必须在 do_move
        // 之前取子力 —— 走完之后 from 格就空了。
        const int mover_idx = piece_nnue_index(position_.squares[m.from()]);
        const int move_key = (mover_idx >= 0) ? piece_to_key(mover_idx, m.to()) : -1;

        if (!do_move_acc(m)) continue;
        cont_key_[ply] = move_key;

        // 节点类型的正确传播。
        //
        // PVS 的经典结论: PV 节点的第一个子节点还是 PV, 其余是 CUT; CUT 节点的
        // 子节点是 ALL; ALL 节点的子节点是 CUT。我第一版对所有零窗口子节点一律
        // 传 cut_node=true, 结果是**整棵树都成了 CUT 节点**, "cut 节点多砍一层"
        // 于是退化成全局加大减量 —— 那正是上一轮 lmr_div 从 1.2 调到 0.9 被证明
        // 走不通的做法, 难怪没有收益。
        const bool child_cut_first = pv_node ? false : !cut_node;
        const bool child_cut_rest = pv_node ? true : !cut_node;

        int score;
        if (searched == 0) {
            score = -alpha_beta(depth - 1 + extension, -beta, -alpha_local, nodes, ply + 1,
                                true, 0, child_cut_first);
        } else {
            int reduction = 0;
            if (depth >= kLmrMinDepth && searched >= kLmrMinMoves && !capture &&
                !is_in_check(position_, position_.side_to_move)) {
                reduction = use_lmr_table ? std::min(lmr_reduction(depth, searched), depth - 1) : 1;

                // ---- LMR 动态调制 ----
                //
                // 关键改动: 减量不再对所有节点一视同仁。之前整体把 lmr_div 从
                // 1.2 调到 0.9(即全局加大减量)反而变弱, 说明"该多砍的地方砍得
                // 不够、不该砍的地方砍过头"同时存在, 靠一个全局系数没法同时修。
    if (use_dynamic_lmr) {
                    int adj = 0;
                    // cut 节点预期会 fail-high, 后面这几手大概率是白搜, 多砍一层
                    if (cut_node) adj += 1;
                    // improving: 我的局面在变差(两步前比我现在的静态评估好),
                    // 后续着法大多没希望, 多砍一层。
                    if (use_improving_lmr && ply >= 2 &&
                        static_eval_hist_[ply - 2] != INT32_MIN &&
                        static_eval <= static_eval_hist_[ply - 2]) {
                        adj += 1;
                    }
                    // 历史分明显差的安静着法多砍一层。
                    //
                    // 只做"差的多砍", 不做"好的少砍": 后者每次都让树长大, 而
                    // 它想买的保险(别把好手砍漏)其实已经由 LMR 失败高后的全深度
                    // 重搜提供了 —— 实测带上"好的少砍"这一项, 深度 10 的节点数
                    // 反而涨 9.5%, 白付成本买重复的保险。
                    // 门槛取满量程的 3/4 而不是 1/2: 加进续接历史之后 h 的量程
                    // 翻了倍, 1/2 会让这一项几乎次次命中, 退化成无差别加大减量。
                    if (move_key >= 0) {
                        const int h = history_[m.from()][m.to()] +
                                      (use_cont_hist ? cont_score(ply, move_key) : 0);
                        if (h < -(kHistoryMax * 3 / 4)) adj += 1;
                    }
                    reduction = std::clamp(reduction + adj, 0, depth - 1);
                }
            }
            score = -alpha_beta(depth - 1 - reduction, -alpha_local - 1, -alpha_local, nodes,
                                ply + 1, true, 0, child_cut_rest);
            if (score > alpha_local && (reduction > 0 || score < beta)) {
                score = -alpha_beta(depth - 1, -beta, -alpha_local, nodes, ply + 1,
                                    true, 0, /*cut_node=*/false);
            }
        }
        undo_move_acc();
        ++searched;
        if (!capture) quiets_tried.push_back(m); else caps_tried.push_back(m);

        if (aborted(nodes)) return 0;
        if (score > best_score) {
            best_score = score;
            best_move_data = m.data;
        }
        if (score > alpha_local) {
            alpha_local = score;
            best_flag = kTtExact;
        }
        if (alpha_local >= beta) {
            best_flag = kTtLower;
            if (collect_cutoff_stats) {
                last_cutoff_move_ = m;
                record_cutoff_stat(searched, tt_move, ply);
            }
            if (!capture) {
                record_cutoff(m, depth, ply, quiets_tried);
                if (use_countermove) record_countermove(ply, m);
            }
            else record_capture_cutoff(m, depth, caps_tried);
            break;
        }
    }

    // 只用安静最佳着法更新：吃子后的搜索分包含立刻发生的物质变化，不是静态
    // 评估器对当前局面的系统偏差。fail-low/fail-high 还需与误差方向一致。
    if (excluded_move == 0 && !in_check && static_eval != INT32_MIN && best_move_data != 0 &&
        position_.squares[Move(best_move_data).to()] == kPieceNone &&
        ((best_flag == kTtUpper && best_score <= static_eval) ||
         (best_flag != kTtUpper && best_score > static_eval))) {
        update_correction(static_eval, best_score, depth);
    }

    // 排除了一手的搜索结果不代表这个局面的真实最优, 存进去会腐蚀置换表。
    if (excluded_move == 0) store_tt(key, depth, best_score, best_flag, best_move_data, ply);
    return best_score;
}

int SearchEngine::quiescence(int alpha, int beta, int64_t& nodes, int ply, int qply) {
    ++nodes;
    if (collect_cutoff_stats) ++qsearch_nodes;
    if (aborted(nodes)) return 0;

    if (ply > 0 && position_.is_search_rule_draw()) {
        if (is_in_check(position_, position_.side_to_move)) {
    std::vector<Move> evasions;
    generate_all_legal(position_, evasions);
    gen_all_counted();
            if (evasions.empty()) return -kMateScore + ply;
        }
        return 0;
    }

    alpha = std::max(alpha, -kMateScore + ply);
    beta = std::min(beta, kMateScore - ply - 1);
    if (alpha >= beta) return alpha;

    const bool in_check_q = is_in_check(position_, position_.side_to_move);

    // 被将时没有 stand-pat 权利，也不能只搜吃子：移将/垫子经常是唯一应手。
    // 旧实现虽然用 in_check_q 关闭了 delta/SEE，却仍然先拿静态分截断并只生成
    // captures，导致将死被当成普通 NNUE 分、安静应将完全消失。
    if (in_check_q) {
        std::vector<Move> evasions;
        generate_all_legal(position_, evasions);
        if (evasions.empty()) return -kMateScore + ply;
        order_moves(evasions, 0, ply);

        int alpha_local = alpha;
        int best = INT32_MIN;
        for (auto& m : evasions) {
            if (!do_move_acc(m)) continue;
            // 即使静止预算/硬栈上限触顶，也要先走完一手强制应将，
            // 但不让将军链无视 quiescence_budget 一直展开到 64 层。
            const int score = (qply >= quiescence_budget || ply >= kMaxPly - 1)
                ? -corrected_evaluate(use_leaf_eval_head ? 1 : -1)
                : -quiescence(-beta, -alpha_local, nodes, ply + 1, qply + 1);
            undo_move_acc();
            if (aborted(nodes)) return 0;
            if (score > best) best = score;
            if (score > alpha_local) alpha_local = score;
            if (alpha_local >= beta) break;
        }
        return best;
    }

    const int stand_pat = corrected_evaluate(use_leaf_eval_head ? 1 : -1);
    if (collect_cutoff_stats) ++eval_qsearch;
    if (stand_pat >= beta) return stand_pat;
    if (qply >= quiescence_budget || ply >= kMaxPly - 1) return stand_pat;

    int alpha_local = std::max(stand_pat, alpha);

    std::vector<Move> caps;
    generate_captures(position_, caps);
    gen_cap_counted();
    if (caps.empty()) return stand_pat;

    std::stable_sort(caps.begin(), caps.end(), [&](const Move& a, const Move& b) {
        return mvv_lva(position_.squares[a.to()], position_.squares[a.from()]) >
               mvv_lva(position_.squares[b.to()], position_.squares[b.from()]);
    });

    int best = stand_pat;
    for (auto& m : caps) {
        const int victim = position_.squares[m.to()];
        if (use_delta && victim != kPieceNone &&
            stand_pat + see_value(victim) + kDeltaMargin <= alpha_local) {
            continue;
        }
        if (use_see && use_see_static_skip) {
            // SEE 前置静态过滤: 只有价值接近的交换才需要完整 SEE。
            // 吃子的 SEE 结果主要取决于"对方反吃后是否还赚"——如果
            // victim 比 attacker 便宜超过 kSeeStaticMargin, 无论怎么反吃
            // 都是净亏, 完整 SEE 只会得出同一个"跳过"结论, 白付成本。
            // PikaFish/Stockfish 同样只在接近价值的吃子上跑 SEE。
            const int attacker = position_.squares[m.from()];
            if (attacker != kPieceNone &&
                see_value(victim) < see_value(attacker) - kSeeStaticMargin) {
                continue;
            }
            if (see_counted(position_, m) < -qsearch_see_margin) continue;
        } else if (use_see) {
            if (see_counted(position_, m) < -qsearch_see_margin) continue;
        }
        if (!do_move_acc(m)) continue;
        const int score = -quiescence(-beta, -alpha_local, nodes, ply + 1, qply + 1);
        undo_move_acc();
        if (aborted(nodes)) return 0;
        if (score > best) best = score;
        if (score > alpha_local) alpha_local = score;
        if (alpha_local >= beta) break;
    }
    return best;
}

std::pair<Move, int> SearchEngine::search_with_score(int depth, int movetime_ms) {
    const int64_t search_start_ms = now_ms();
    deadline_ms_ = (movetime_ms > 0) ? now_ms() + movetime_ms : INT64_MAX;
    const int effective_depth = (movetime_ms > 0) ? 32 : depth;
    time_up_ = false;
    reset_search_state();
    last_search_nodes_ = 0;
    last_search_depth_ = 0;
    last_completed_time_ms_ = 0;
    last_completed_nodes_ = 0;
    last_partial_root_moves_ = 0;
    last_partial_root_total_ = 0;
    last_used_partial_root_ = false;

    std::vector<Move> moves;
    generate_all_legal(position_, moves);
    gen_all_counted();
    if (moves.empty()) {
        last_search_nodes_ = 0;
        return {Move(), 0};
    }

    int64_t nodes = 0;
    acc_refresh_root();

    Move best = moves[0];
    int alpha = kMinScore;
    int prev_score = 0;
    constexpr int kFirstDepth = 1;
    for (int d = kFirstDepth; d <= effective_depth; ++d) {
        d_iter_depth = d;
        int seeded_alpha = (d >= kAspirationMinDepth && d > kFirstDepth)
            ? std::max(prev_score - kAspirationWindow, kMinScore)
            : kMinScore;
        int seeded_beta = (use_full_aspiration && d >= kAspirationMinDepth &&
                           d > kFirstDepth)
            ? std::min(prev_score + kAspirationWindow, kMaxScore)
            : kMaxScore;

        Move iter_best;
        int iter_alpha = seeded_alpha;
        std::vector<std::pair<Move, int>> iter_scores;
        iter_scores.reserve(moves.size());
        bool completed = true;
        while (true) {
            iter_best = moves[0];
            iter_alpha = seeded_alpha;
            iter_scores.clear();
            completed = true;
            int root_searched = 0;
            for (auto& m : moves) {
                const int mi = piece_nnue_index(position_.squares[m.from()]);
                const int mk = (mi >= 0) ? piece_to_key(mi, m.to()) : -1;
                if (!do_move_acc(m)) continue;
                cont_key_[0] = mk;

                // 根节点也走 PVS。以前每个根着法都用全窗口搜, 一来白白多搜
                // (零窗口能证伪的手不必给全窗口), 二来会让整棵子树都被当成
                // PV 节点 —— LMR 的"PV 少砍一层"于是对四十来个根着法全部生效,
                // 实测让节点数暴涨 42%。真正的 PV 只有第一手那一支。
                int sc;
                if (!use_root_pvs || root_searched == 0) {
                    sc = -alpha_beta(d - 1, -seeded_beta, -iter_alpha,
                                     nodes, 1, true, 0, false);
                } else {
                    sc = -alpha_beta(d - 1, -iter_alpha - 1, -iter_alpha, nodes, 1, true, 0, true);
                    if (sc > iter_alpha) {  // 零窗口没能证伪, 补一次全窗口拿真分
                        sc = -alpha_beta(d - 1, -seeded_beta, -iter_alpha,
                                         nodes, 1, true, 0, false);
                    }
                }
                undo_move_acc();
                if (aborted(nodes)) { completed = false; break; }
                // alpha_beta() 在 deadline/stop 后返回的 0 只是中止哨兵，不是
                // 这一手的合法分数。只有通过上面的 aborted() 检查才算完整。
                ++root_searched;
                iter_scores.emplace_back(m, sc);
                if (sc > iter_alpha) { iter_alpha = sc; iter_best = m; }
                // 真正的双边渐进窗口允许根节点 fail-high。当前轮只需证明分数
                // 越过 beta；下面会全窗重搜确认最终着法与精确分数。
                if (iter_alpha >= seeded_beta) break;
            }
            if (!completed) {
                last_partial_root_moves_ = root_searched;
                last_partial_root_total_ = static_cast<int>(moves.size());
                // 上一层最佳着法始终排在 moves[0]。只有本层另一个完整搜完的
                // 着法真正提高了 alpha，才有新信息可保留；否则仍返回上一层。
                if (movetime_ms > 0 && use_partial_root && iter_best != best) {
                    best = iter_best;
                    alpha = iter_alpha;
                    last_used_partial_root_ = true;
                }
                break;
            }
            if ((iter_alpha <= seeded_alpha && seeded_alpha > kMinScore) ||
                (iter_alpha >= seeded_beta && seeded_beta < kMaxScore)) {
                // 简单而稳健的一次回退：窄窗任一方向失败后直接恢复全窗，避免
                // 多轮逐步扩窗在 100ms 预算下反复重搜。全窗不可能再次越界。
                seeded_alpha = kMinScore;
                seeded_beta = kMaxScore;
                continue;
            }
            break;
        }
        if (!completed) break;

        best = iter_best;
        alpha = iter_alpha;
        last_search_depth_ = d;
        last_completed_time_ms_ = now_ms() - search_start_ms;
        last_completed_nodes_ = nodes;
        prev_score = iter_alpha;
        if (use_root_score_ordering) {
            // 非 PV 根着法大多只拿到零窗口界，但这个界仍比原始格子编号更适合
            // 给下一层排序。stable_sort 让同分着法保留上一层相对顺序。
            std::stable_sort(iter_scores.begin(), iter_scores.end(),
                [](const auto& a, const auto& b) { return a.second > b.second; });
            moves.clear();
            moves.reserve(iter_scores.size());
            for (const auto& scored : iter_scores) moves.push_back(scored.first);
        } else {
            moves.erase(std::remove(moves.begin(), moves.end(), iter_best), moves.end());
            moves.insert(moves.begin(), iter_best);
        }

        if (is_mate_score(alpha)) break;
    }
    d_iter_depth = -1;

    last_search_nodes_ = nodes;
    return {best, alpha};
}

}  // namespace xq
