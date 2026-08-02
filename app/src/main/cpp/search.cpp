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
constexpr int kRfpMaxDepth = 4;
constexpr int kRfpMarginPerDepth = 140;

constexpr int kNullMoveMinDepth = 3;
constexpr int kNullMoveReduction = 2;

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

void SearchEngine::reset_search_state() {
    tt_gen_ = (tt_gen_ + 1) & 0xFF;
    for (auto& k : killers_) { k[0] = 0; k[1] = 0; }
    for (auto& row : history_) std::fill(std::begin(row), std::end(row), 0);
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

int SearchEngine::evaluate() {
    if (acc_depth_ < kAccCapacity && acc_stack_[acc_depth_].raw_bucket >= 0) {
        return nnue::eval_acc(net_, acc_stack_[acc_depth_], position_.side_to_move == kBlack);
    }
    uint8_t pieces[nnue::kMaxPieces * 2];
    const int n = extract_pieces(position_, pieces);
    return nnue::eval_scalar(net_, pieces, n, position_.side_to_move == kBlack);
}

bool SearchEngine::aborted(int64_t node_count) {
    if (time_up_) return true;
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

    std::vector<std::pair<int, Move>> scored;
    scored.reserve(moves.size());
    for (auto& m : moves) {
        int score;
        if (m.data == tt_move) {
            score = kOrderTt;
        } else {
            const int victim = position_.squares[m.to()];
            if (victim != kPieceNone) {
                // 亏本吃子(SEE<0)降到最低档: 它排在杀手着法前面是纯粹的浪费,
                // 因为杀手是"本层已经验证过能截断"的着法, 而亏本吃子多半会被
                // 立刻反吃回来。
                const bool bad = use_bad_capture_ordering && see_evaluate(position_, m) < 0;
                score = (bad ? bad_capture_base : kOrderCapture) +
                        mvv_lva(victim, position_.squares[m.from()]);
                if (use_capture_hist) {
                    const int pidx = piece_nnue_index(position_.squares[m.from()]);
                    // 除以 16: 只当同价值吃子之间的**平手判定**, 不能盖过
                    // MVV-LVA 本身(量程约 7000), 更不能把吃子压到杀手着法之下。
                    if (pidx >= 0) {
                        score += capture_hist_[capt_key(pidx, m.to(), piece_type(victim))] / 16;
                    }
                }
            } else if (m.data == k0) {
                score = kOrderKiller1;
            } else if (m.data == k1) {
                score = kOrderKiller2;
            } else {
                score = history_[m.from()][m.to()];
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

void SearchEngine::store_tt(int64_t key, int depth, int score, int flag, int move_data) {
    if (score < -kTtScoreLimit || score > kTtScoreLimit) return;
    const int slot = tt_slot(key);
    const int64_t old = tt_data_[slot];
    const bool same_key = tt_key_[slot] == key;
    const bool old_stale = tt_gen_of(old) != tt_gen_;
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
    if (aborted(nodes)) return 0;

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
        (!tt_require_same_gen || tt_gen_of(tt_data_[slot]) == tt_gen_);
    const int tt_move = hit ? tt_move_of(tt_data_[slot]) : 0;
    // 奇异延伸验证时排除了 tt_move, 这条早退命中的是"含 tt_move 在内"的完整
    // 结果, 不能用来代替"排除 tt_move 之后能不能达到窗口"这个问题的答案。
    if (hit && excluded_move == 0 && tt_depth_of(tt_data_[slot]) >= depth) {
        const int sc = tt_score_of(tt_data_[slot]);
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
    if (!in_check && depth <= static_need_depth) static_eval = evaluate();

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

    if (use_null_move && allow_null && !in_check && depth >= kNullMoveMinDepth &&
        beta < kMateScore - kMaxPly && position_.has_threatening_piece(position_.side_to_move)) {
        position_.do_null_move();
        cont_key_[ply] = -1;  // 空着不是一手真棋, 不能当作下一层的"上一手"
        const int null_score = -alpha_beta(depth - 1 - kNullMoveReduction, -beta, -beta + 1,
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
            see_evaluate(position_, m) < -kSeePruneMargin * depth) {
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
            const int tts = tt_score_of(tt_data_[slot]);
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
            if (!capture) record_cutoff(m, depth, ply, quiets_tried);
            else record_capture_cutoff(m, depth, caps_tried);
            break;
        }
    }

    // 排除了一手的搜索结果不代表这个局面的真实最优, 存进去会腐蚀置换表。
    if (excluded_move == 0) store_tt(key, depth, best_score, best_flag, best_move_data);
    return best_score;
}

int SearchEngine::quiescence(int alpha, int beta, int64_t& nodes, int ply, int qply) {
    ++nodes;
    if (aborted(nodes)) return 0;

    const bool in_check_q = is_in_check(position_, position_.side_to_move);
    const int stand_pat = evaluate();
    if (stand_pat >= beta) return stand_pat;
    if (qply >= quiescence_budget || ply >= kMaxPly - 1) return stand_pat;

    int alpha_local = std::max(stand_pat, alpha);

    std::vector<Move> caps;
    generate_captures(position_, caps);
    if (caps.empty()) return stand_pat;

    std::stable_sort(caps.begin(), caps.end(), [&](const Move& a, const Move& b) {
        return mvv_lva(position_.squares[a.to()], position_.squares[a.from()]) >
               mvv_lva(position_.squares[b.to()], position_.squares[b.from()]);
    });

    int best = stand_pat;
    for (auto& m : caps) {
        if (!in_check_q) {
            const int victim = position_.squares[m.to()];
            if (use_delta && victim != kPieceNone &&
                stand_pat + see_value(victim) + kDeltaMargin <= alpha_local) {
                continue;
            }
            if (use_see && see_evaluate(position_, m) < 0) continue;
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
    deadline_ms_ = (movetime_ms > 0) ? now_ms() + movetime_ms : INT64_MAX;
    const int effective_depth = (movetime_ms > 0) ? 32 : depth;
    time_up_ = false;
    reset_search_state();

    std::vector<Move> moves;
    generate_all_legal(position_, moves);
    if (moves.empty()) {
        last_search_nodes_ = 0;
        return {Move(), 0};
    }

    int64_t nodes = 0;
    last_search_depth_ = 0;
    acc_refresh_root();

    Move best = moves[0];
    int alpha = kMinScore;
    int prev_score = 0;
    constexpr int kFirstDepth = 1;
    for (int d = kFirstDepth; d <= effective_depth; ++d) {
        int seeded_alpha = (d >= kAspirationMinDepth && d > kFirstDepth)
            ? std::max(prev_score - kAspirationWindow, kMinScore)
            : kMinScore;

        Move iter_best;
        int iter_alpha = seeded_alpha;
        bool completed = true;
        while (true) {
            iter_best = moves[0];
            iter_alpha = seeded_alpha;
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
                    sc = -alpha_beta(d - 1, -kMaxScore, -iter_alpha, nodes, 1, true, 0, false);
                } else {
                    sc = -alpha_beta(d - 1, -iter_alpha - 1, -iter_alpha, nodes, 1, true, 0, true);
                    if (sc > iter_alpha) {  // 零窗口没能证伪, 补一次全窗口拿真分
                        sc = -alpha_beta(d - 1, -kMaxScore, -iter_alpha, nodes, 1, true, 0, false);
                    }
                }
                undo_move_acc();
                ++root_searched;
                if (aborted(nodes)) { completed = false; break; }
                if (sc > iter_alpha) { iter_alpha = sc; iter_best = m; }
            }
            if (!completed) break;
            if (iter_alpha <= seeded_alpha && seeded_alpha > kMinScore) {
                seeded_alpha = kMinScore;
                continue;
            }
            break;
        }
        if (!completed) break;

        best = iter_best;
        alpha = iter_alpha;
        last_search_depth_ = d;
        prev_score = iter_alpha;
        moves.erase(std::remove(moves.begin(), moves.end(), iter_best), moves.end());
        moves.insert(moves.begin(), iter_best);

        if (is_mate_score(alpha)) break;
    }

    last_search_nodes_ = nodes;
    return {best, alpha};
}

}  // namespace xq
