// 搜索引擎. 与 Kotlin SimpleEngine.kt 的搜索部分(alphaBeta/quiescence/
// searchWithScore 及其辅助)逐一对应.
//
// 独立重写(clean-room), 不是移植 Pikafish —— 用到的技术(空着裁剪/PVS/LMR/
// 渐进窗口/history 引力公式)都是计算机博弈论几十年的公开算法, 具体实现按
// 本项目 Kotlin 源码来。范围只覆盖 searchWithScore 这条路径(match/benchmark
// 用), 不含真实对局的 UCI/监听器/开局库/难度随机化那些 —— 那些不影响"搜索
// 本身多快", 而是 App 集成层的事, 留在 Kotlin。
#ifndef XQ_SEARCH_H
#define XQ_SEARCH_H

#include <atomic>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "board.h"
#include "nnue_core.h"

namespace xq {

constexpr int kMaxPly = 64;
constexpr int kAccCapacity = 64;  // 与 nnue_jni.cpp 的 kAccCapacity 同一量级

class SearchEngine {
public:
    // 权重不归 SearchEngine 所有, 调用方保证 net 在整个生命周期内有效。
    explicit SearchEngine(const nnue::Net& net) : net_(net) { rebuild_lmr_table(); }

    // ---- 走法排序归因统计（诊断用，默认关闭，不影响搜索行为）----
    struct CutoffStats {
        int64_t nodes = 0;          // 统计窗口内的截断节点数
        int64_t by_tt = 0;          // 截断着法来自置换表
        int64_t by_capture = 0;     // 截断着法是吃子
        int64_t by_killer1 = 0;     // 杀手1
        int64_t by_killer2 = 0;     // 杀手2
        int64_t by_quiet = 0;       // 历史/续接历史安静着法
        int64_t first_move = 0;     // 第一手就截断
        int64_t second_move = 0;    // 第二手截断
        int64_t pos_3_4 = 0;        // 第 3-4 手截断
        int64_t pos_5_8 = 0;        // 第 5-8 手截断
        int64_t pos_9_16 = 0;       // 第 9-16 手截断
        int64_t pos_17p = 0;        // 第 17 手及以上截断
    };
    bool collect_cutoff_stats = false;
    bool use_tt = true;             // 诊断用：关闭后 TT 探针直接 miss
    CutoffStats cutoff_midgame;     // 总子力 > 14 归中局
    CutoffStats cutoff_endgame;     // 总子力 <= 14 归残局
    int64_t tt_probe_nodes = 0;     // alpha_beta 内部节点总数（统计窗口）
    int64_t tt_probe_hits = 0;      // 其中槽命中（key 匹配）
    int64_t tt_probe_early = 0;     // 其中满足深度条件直接返回
    int64_t tt_store_calls = 0;     // store_tt 调用次数
    int64_t tt_store_overwrite = 0; // 撞槽覆盖（不同 key）次数
    int64_t tt_hit_depth_ok = 0;    // 命中且深度达标（真正早退）
    int64_t tt_hit_depth_short = 0; // 命中但深度不足
    int64_t tt_gen_mismatch_hit = 0;// key 匹配但世代不同（跨搜索复用命中）
    int per_iter_probes_[64] = {};  // 每轮迭代的探针数
    int per_iter_hits_[64] = {};
    int per_iter_early_[64] = {};
    int64_t tt_pre_search_occupied = -1; // 搜索开始时已占用槽数（-1=未统计）
    int64_t eval_total = 0;         // 求值总次数（网络求值）
    int64_t eval_qsearch = 0;       // 其中来自静止搜索（stand-pat / 应将叶）
    int64_t eval_main = 0;          // 其中来自主搜索 static_eval
    int64_t see_total = 0;          // SEE 计算总次数
    int64_t gen_all_count = 0;      // generate_all_legal 调用次数
    int64_t gen_cap_count = 0;      // generate_captures 调用次数
    int64_t move_acc_count = 0;     // do_move_acc 调用次数（着法尝试）
    int64_t qsearch_nodes = 0;      // quiescence 进入次数
    int64_t ab_nodes = 0;           // alpha_beta 进入次数
    int64_t see_gain_hist[16] = {}; // SEE 输入: victim-attacker 价值差分布
    void record_cutoff_stat(int searched, int tt_move, int ply);
    int see_counted(const Position& pos, const Move& m);
    void gen_all_counted();
    void gen_cap_counted();
    void move_acc_counted();
    void dump_cutoff_stats(FILE* out) const;
    void reset_cutoff_stats();

    bool set_position_fen(const std::string& fen);
    /** 从起始 FEN 重放 UCI 着法；保留完整历史，供重复局面规则使用。 */
    bool set_position_fen_moves(const std::string& fen,
                                const std::vector<std::string>& moves);
    void set_position(const Position& pos) { position_ = pos; }
    Position& position() { return position_; }

    /**
     * JNI/App 的同步停止开关。
     *
     * clear_stop() 必须在启动搜索线程之前调用；这样即使 stop 请求先于工作线程
     * 真正进入 search_with_score() 到达，也不会被搜索入口错误地清掉。
     */
    void clear_stop() { stop_requested_.store(false, std::memory_order_release); }
    void request_stop() { stop_requested_.store(true, std::memory_order_release); }

    /**
     * 固定深度搜出 (最佳着法, 分数). 与 Kotlin searchWithScore 语义一致:
     *
     * movetime_ms <= 0: 固定深度搜索, depth 是硬顶。
     * movetime_ms >  0: 限时搜索(真实力模式), depth 参数被忽略, 名义深度
     *                    放开到 32, 由 deadline 打断迭代加深。
     */
    std::pair<Move, int> search_with_score(int depth, int movetime_ms = 0);

    int64_t last_search_nodes() const { return last_search_nodes_; }
    /** 上一次搜索**完成**的最大深度(限时搜索下被 deadline 打断的那层不算) */
    int last_search_depth() const { return last_search_depth_; }
    /** 最后一层完整迭代完成时，距搜索开始的时间与累计节点。 */
    int64_t last_completed_time_ms() const { return last_completed_time_ms_; }
    int64_t last_completed_nodes() const { return last_completed_nodes_; }
    /** 被限时打断的最后一层中，完整搜完的根着法数/根着法总数。 */
    int last_partial_root_moves() const { return last_partial_root_moves_; }
    int last_partial_root_total() const { return last_partial_root_total_; }
    /** 本次搜索最终是否采用了残缺迭代里一个已经完整搜完的根着法。 */
    bool last_used_partial_root() const { return last_used_partial_root_; }

#ifdef XQ_ENABLE_EVAL_TRACE
    // 仅供离线叶局面采集器。生产构建不定义该宏，因此每次 evaluate() 连一次
    // 空指针判断都不会多，固定用时性能不受诊断设施影响。
    using EvalTraceCallback = void (*)(const Position&, void*);
    void set_eval_trace(EvalTraceCallback callback, void* context) {
        eval_trace_ = callback;
        eval_trace_context_ = context;
    }
#endif

    // 基准测试用: 关掉对应剪枝以便同进程 A/B, 语义与 Kotlin 同名字段一致。
    bool use_rfp = true;
    bool use_null_move = true;
    // 空着裁剪的固定减量。默认 2 保持现有生产搜索；做成运行时参数用于固定
    // 用时 A/B，只有棋力过门后才允许改默认值。
    int null_move_reduction = 2;
    bool use_lmr_table = true;
    bool use_see = true;
    // 静止搜索允许暂时亏损多少厘兵的吃子继续展开。0 保持旧行为；Pikafish
    // 的 -106 按双方兵值换算约为本项目 75cp，作为固定用时候选测试。
    int qsearch_see_margin = 0;
    // v4 多职责网络实验：alpha-beta 静态评估强制 head0，qsearch stand-pat
    // 强制 head1。默认关闭，普通 v3/v4 网络继续沿用历史阶段桶语义。
    bool use_leaf_eval_head = false;
    bool use_delta = true;
    int quiescence_budget = 6;  // MAX_QUIESCENCE_PLY

    // 奇异延伸开关, 以及 LMR 削减表的形状参数 —— 做成运行时可配置(而不是
    // 编译期常量), 是为了同一个可执行文件能跑"新参数 vs 旧参数"的自对弈,
    // 不用维护两份编译产物。改 lmr_div 后必须调 rebuild_lmr_table() 才生效
    // (表是预计算好的, 不会自动跟着字段变)。
    bool use_singular_extension = true;
    double lmr_div = 1.2;
    void rebuild_lmr_table();

    // 后期着法裁剪(LMP)与无用着法裁剪(futility) —— 这两个是 Kotlin 版没有的
    // 新剪枝, 会改变搜索结果, 所以跟 Kotlin 的逐条对拍测试(search_test.cpp)
    // 里必须关掉, 否则那个测试就退化成"两边都错得一样"而失去意义。默认开,
    // 因为分支因子才是我们跟 Pikafish 差距的全部来源(实测我方 EBF≈2.0,
    // Pikafish≈1.4; 单节点速度我方反而快 2 倍)。
    bool use_lmp = true;
    bool use_futility = true;

    // 续接历史(continuation history)与 LMR 动态调整。同样是 Kotlin 版没有的
    // 东西, 对拍测试里要关掉。
    //
    // 这两项是实测下来跟 Pikafish 差距最大的地方: 分支因子由"第一手就截断的
    // 比例"决定, 而那个比例几乎全靠走法排序质量。我们原本只有一张
    // from-to 的历史表, Pikafish 有七类(其中续接历史按前 1/2 层的着法索引);
    // LMR 我们是一张死表, 它有十几项动态调制 —— 上一轮把 lmr_div 从 1.2
    // 调到 0.9 反而变差, 正说明"整体调激进度"走不通, 得让减量跟着节点性质走。
    bool use_cont_hist = true;
    bool use_dynamic_lmr = true;
    /**
     * improving 检测: 当前节点静态评估是否好于两步前的"我"。不 improving
     * 时 LMR 多砍一层(局面在变差, 后续着法大概率也差); improving 保持原减量。
     * 这是 Stockfish 系 LMR 的标准调制项, 只改搜索深度、不动排序序号。
     * 默认关, 确定性门禁通过才晋升。
     */
    bool use_improving_lmr = false;
    /**
     * piece-to 主历史: "某个子走到某格"本身好不好(不依赖上一手)。
     * PikaFish 系 main history 是 piece-to, 我们当前只有 from-to 的
     * history_; 两者信号不同, 排序时相加。索引与续接历史同构:
     * piece_nnue_index * kSquareCount + to_sq。
     * 默认关, M1 门禁(中局节点 -10% 且着法/分数一致率 >=90%)通过才晋升。
     */
    bool use_piece_to_hist = false;
    // 续接历史往回看几层。1/2 层是"对手刚走完这手我怎么应"和"我上一手走了
    // 这个接着走什么"; 再往回是更长的计划。
    //
    // 扫参结果(深度12, 节点数是确定性的所以可直接比):
    //   cp=1 619312 / cp=2 596256 / cp=3 613695 / cp=4 609362 / cp=6 584981
    // cp=6 节点最少, 但重复计时三轮下来跟 cp=2 完全分不出来(897.9 vs 902.0ms):
    // 省下的 1.9% 节点正好被多出的 4 次表查询吃掉。**节点省了却没换成时间就
    // 不算数**, 所以取更简单的 2。
    int cont_plies = 2;
    // 吃子历史表。现状是吃子截断**完全不更新任何历史**(record_cutoff 只在
    // 非吃子时调用), 吃子排序全靠静态的 MVV-LVA —— 而 MVV-LVA 只看子力价值,
    // 分不出"同样吃一个马, 这一手是妙手还是送死"。
    bool use_capture_hist = true;
    /**
     * from-to 主历史跨搜索衰减除数。0 保持旧行为（每步清零）；>=2 时保留并
     * 衰减，供固定用时扫参。killers 仍每步清零，避免 ply 错位。
     */
    int history_decay_divisor = 0;
    /**
     * 按 SEE 区分好吃子/亏本吃子, 亏本的排到所有安静着法之后。
     *
     * 依据: 归因统计显示杀手着法造成 38% 的截断, 却只有 24.9% 被排在第一位 ——
     * 因为吃子的排序基分(100000)无条件高于杀手(90000/80000), 用车换卒这种
     * 明显亏本的吃子也照样排在"已知能在本层造成截断"的杀手前面。无置换表
     * 着法时"第2手截断"占 40%, 正是这个错序的直接后果。
     *
     * **实测这个推断是错的, 默认关。** 降级位置扫参(深度12 节点数):
     *   不动 463240 / 杀手1之后 479651 / 两个杀手之后 510542 / 安静之后 562608
     * 单调变差 —— 降得越多越差。
     *
     * 事后想明白为什么: "第1手截断率"是**诊断指标, 不是优化目标**。改排序
     * 不只是换先搜谁, 它同时改变了所有后续着法的 LMR/LMP 序号 —— 把亏本吃子
     * 往后压, 安静着法就整体前移、拿到更小的削减量, 多出来的节点远超排序
     * 本身省下的。杀手排第一的比例低, 不等于把它提前就划算。
     */
    bool use_bad_capture_ordering = false;
    // 亏本吃子的排序基分。扫参用: 100000=不降(等同关闭), 70000=杀手之后,
    // -1000000=所有安静着法之后。
    int bad_capture_base = 70000;
    /** capture history 参与吃子排序时的除数（默认 16 = 原行为）。 */
    int capture_hist_div = 16;
    /**
     * 按 SEE 把吃子分成三档: 赢子(SEE>0) / 平子(SEE=0) / 亏本(SEE<0)。
     * 亏本吃子排到所有安静着法之后, 平子吃子排在杀手与安静之间。
     * 这是 PikaFish/Stockfish 系吃子排序的核心结构; 我们当前只有 MVV-LVA。
     * 默认关: 改排序会连带改变 LMR/LMP 序号(见 use_bad_capture_ordering 的
     * 教训), 必须先确定性验证节点数再决定是否晋升。
     */
    bool use_see_order = false;
    /**
     * 吃子内部排序用 SEE 值而非 MVV-LVA, 但**不改变档位**: 所有吃子仍落在
     * kOrderCapture 档(不降档、不升档), 只调整吃子之间的先后。
     * 这样"哪个吃子先搜"更准(SEE 反映实际得失), 而安静着法的 LMR/LMP 序号
     * 完全不变 —— 规避 use_see_order 三档方案的 LMR 扰动陷阱。
     * 默认关, 确定性门禁通过才晋升。
     */
    bool use_see_internal_order = false;
    /**
     * qsearch 的 SEE 静态预筛（victim 比 attacker 便宜 >100 兵直接跳过）。
     * 纯性能优化，A/B 用；默认开。
     */
    bool use_see_static_skip = true;
    /**
     * 是否启用 countermove 排序。实测(2026-08-04): 残局固定深度省 9.9%
     * 节点, 但中局无收益(-0.2%), 限时深度持平(10.6 vs 10.7)——按项目纪律
     * 默认关, 不改变发布行为; 保留实现与开关供组合实验。
     */
    bool use_countermove = false;
    // 根节点 PVS。它**不改变着法和分数**, 只是少搜节点(零窗口能证伪的根着法
    // 不必给全窗口), 所以对拍测试里着法/分数照样逐条相同, 只有节点数会少。
    // 仍然做成开关: 那个测试是靠"节点数也逐位相同"来证明两边每一步都在做
    // 一样的事的, 保住这个信号比省几行代码值。
    bool use_root_pvs = true;

    /**
     * 根节点完整渐进窗口。旧实现只把 alpha 设为上一层分数减 25cp，beta 仍为
     * 正无穷，因此只有 fail-low 回退、没有窄窗带来的 fail-high 截断。打开后
     * 先试 [prev-25, prev+25]；任一方向越界就用全窗重搜，最终结果仍由完整
     * 搜索确认。默认关闭，固定用时 A/B 通过后再晋升。
     */
    bool use_full_aspiration = false;

    /**
     * 每层完成后按该层根分数重排全部着法，而不只是把第一名移到最前。
     * 默认关闭，固定用时门控通过后才允许晋升。
     */
    bool use_root_score_ordering = false;

    /**
     * 限时搜索被打断时，允许采用本层已经完整搜完、且确实击败上一层首选的
     * 根着法。正在搜索时超时的那一手永远不采用；默认关闭，先做固定用时 A/B。
     */
    bool use_partial_root = false;

    /** 置换表大小 = 2^shift 个槽, 每槽 16 字节。改了会清空表。 */
    void set_tt_size_shift(int shift);
    /**
     * 命中是否要求世代号相同。
     *
     * 原本要求相同, 意味着**上一步搜索存下的结果全部作废** —— 实战里连续
     * 两步棋的搜索树高度重叠, 这等于每步都从零开始。世代号本该只用于替换
     * 时的优先级(旧的优先被覆盖), 不该用于判断能不能用。
     *
     * 实测(深度12, 8 局面, 节点数确定性): 要求同世代 596256 节点/888ms,
     * 允许跨搜索复用 505745 节点/754ms —— **-15.2%**。探针那 8 个局面正是
     * 同一盘棋的连续着法, 跟实战场景一致, 所以这个收益是真实可用的。
     * 顺带一提置换表**加大没有用**(2^19 最优, 2^20/2^22 反而更差且非单调),
     * 说明瓶颈从来不是容量, 而是这条把旧结果一律作废的规则。
     */
    bool tt_require_same_gen = false;
    /**
     * 深度优先替换。
     *
     * 原策略只保护"同一局面"的条目, 不同局面撞槽时无条件覆盖 —— 一个深度 12
     * 算出来的条目会被深度 1 的随手盖掉。置换表大小扫参出现非单调(2^20 好、
     * 2^21 差、2^22 又好)正是这个的症状: 换大小只是换了一套撞车模式, 而不是
     * 减少了有价值条目的损失。>0 时, 撞槽的旧条目比新条目深这么多就保住它。
     *
     * **实测这个想法是错的, 默认关**: dp=3 在 2^19/2^20/2^21/2^22 四个大小上
     * 全部比"总是覆盖"更差(如 2^20: 499859 vs 463240 节点)。迭代加深里最近
     * 写入的条目才是当前搜索路径要用的, 保护旧的深条目等于用无关的陈数据
     * 占着坑、挡住新鲜数据。留着开关备查, 别再重复这个假设。
     */
    int tt_depth_preference = 0;

    // 第二批: 目标是"固定时间里搜得更深", 所以全都冲着少搜节点去。
    //
    // use_iir       内部迭代减量: 深度够但置换表没给着法时, 说明这个节点从没
    //               被认真搜过, 排序无从谈起 —— 与其用烂排序全深度搜, 不如
    //               减一层, 让它先在浅一层把置换表着法填出来。
    // use_see_prune 主搜索里剪掉明显亏子的吃子(静止搜索早就有, 主搜索一直没有)。
    // use_razoring  浅层静态分离 alpha 太远时直接转静止搜索。
    // futility_max_depth / lmp_max_depth 原本写死 4, 过于保守。
    // 实测结论(深度 12, 8 局面平均, 逐项消融):
    //   IIR          1059ms/750466节点 -> 849ms/596805节点   留下
    //   SEE 剪枝     关掉反而更快(951 vs 1005ms)             默认关
    //   razoring     关掉反而更快(916 vs 1005ms)             默认关
    //   深度上限放到8 退回 4 反而更快(905 vs 1005ms)          退回 4
    //
    // 后三项不只是"收益不够", 是**连节点数都变多了** —— 剪枝剪掉的分支省下的,
    // 少于它们把搜索带偏所多付的: 提前返回的近似分(razoring 的静止搜索分、
    // 被 SEE 跳过的吃子)会污染置换表, 让上层反复重搜。留着开关和参数, 想调
    // margin 再试可以, 但按当前实现默认必须关。
    bool use_iir = true;
    bool use_see_prune = false;
    bool use_razoring = false;
    int futility_max_depth = 4;
    int lmp_max_depth = 4;

    /** 兵形 correction history；默认关闭，固定用时 A/B 通过后再晋升。 */
    bool use_correction_history = false;

private:
    const nnue::Net& net_;
    Position position_;
    int64_t last_search_nodes_ = 0;
    int last_search_depth_ = 0;
    int64_t last_completed_time_ms_ = 0;
    int64_t last_completed_nodes_ = 0;
    int last_partial_root_moves_ = 0;
    int last_partial_root_total_ = 0;
    bool last_used_partial_root_ = false;
    int d_iter_depth = -1;          // 当前迭代深度（-1=非迭代内），统计用

    // ---- 置换表: 扁平数组 + 世代号, 打包布局与 Kotlin 版一致 ----
    //
    // 大小做成运行时可调: 原本写死 2^19=52万槽, 而深度 12 一次搜索就有 78 万
    // 个内部节点(还没算静止搜索), 负载因子 1.5 且单槽直接映射 —— 表在疯狂
    // 自我覆盖, 实测只有 21% 的内部节点能命中置换表着法(正常应在 50%+)。
    // 而置换表着法是走法排序里最重要的一项, 它缺失直接推高分支因子。
    // 2^20 = 16MB。扫参: 2^19 505745 / 2^20 463240 / 2^21 516919 / 2^22 463000
    // 节点。2^20 已经吃掉全部收益, 再大(64MB)没有额外好处, 不值那个内存。
    // 注意 2^21 反常地差, 且在两种替换策略下都复现 —— 没解释, 但既然 2^20
    // 就够, 没有深挖的必要。
    int tt_shift_ = 20;
    int tt_mask_ = (1 << 20) - 1;
    std::vector<int64_t> tt_key_ = std::vector<int64_t>(1 << 20, 0);
    std::vector<int64_t> tt_data_ = std::vector<int64_t>(1 << 20, 0);
    int tt_gen_ = 0;

    // ---- 启发表 ----
    int killers_[kMaxPly][2] = {};
    int history_[kSquareCount][kSquareCount] = {};

    // 续接历史: "上一手走了 A, 那么这一手走 B 好不好"。
    //
    // 索引是 (子力种类, 落点) 的组合 —— 用落点而不是 from-to, 是因为"某个子
    // 走到某格"这件事跨局面的可迁移性比"从哪走到哪"强得多, 这也是主流引擎
    // 都按 piece-to 建表的原因。表大 1260x1260 个 int16 ≈ 3.2MB, 放得下。
    //
    // 单独的 from-to 历史表(history_)保留: 两者信号不同, 前者是"这手棋本身
    // 好不好", 后者是"接在上一手后面好不好", 排序时相加。
    static constexpr int kContDim = 14 * kSquareCount;  // 14 种子力 x 90 格
    std::vector<int16_t> cont_hist_ =
        std::vector<int16_t>(static_cast<size_t>(kContDim) * kContDim, 0);
    std::vector<int16_t> piece_to_hist_ = std::vector<int16_t>(kContDim, 0);
    // 每层实际走的那手棋的 (子力,落点) 键; -1 表示根节点/空着, 没有上一手
    int cont_key_[kMaxPly + 2] = {};
    // 每层静态评估历史, 供 improving 检测(比较 ply 与 ply-2 两步前的"我")。
    int static_eval_hist_[kMaxPly] = {};

    // 吃子历史: [走子方子力][落点][被吃子的种类]。8820 项, 比续接历史小得多,
    // 因为吃子本来就少, 表小反而更容易被喂满。
    static constexpr int kCaptDim = 14 * kSquareCount * 8;
    std::vector<int16_t> capture_hist_ = std::vector<int16_t>(kCaptDim, 0);
    static int capt_key(int piece_idx, int to_sq, int victim_type) {
        return (piece_idx * kSquareCount + to_sq) * 8 + victim_type;
    }

    // countermove: 对手刚走 (piece-to) 之后, 上次截断/提高上限的应手。
    // 按"上一手 piece-to 键"索引, 存应手的 move.data(0 表示未学习)。
    std::vector<int> countermove_ = std::vector<int>(kContDim, 0);
    void record_countermove(int ply, const Move& m);

    // [走子方][兵形 hash 低 16 位]。单表先验证核心机制，避免一次叠加多个
    // correction 来源后即使赢了也不知道收益来自哪里。
    static constexpr int kCorrSize = 1 << 16;
    static constexpr int kCorrMask = kCorrSize - 1;
    static constexpr int kCorrLimit = 1024;
    std::vector<int16_t> pawn_corr_ = std::vector<int16_t>(2 * kCorrSize, 0);

    // ---- 增量累加器栈, 与搜索的 do_move/undo_move 一一配对 ----
    nnue::Accum acc_stack_[kAccCapacity];
    int acc_depth_ = 0;

    // ---- 时间控制 ----
    int64_t deadline_ms_ = INT64_MAX;
    bool time_up_ = false;
    std::atomic<bool> stop_requested_{false};

#ifdef XQ_ENABLE_EVAL_TRACE
    EvalTraceCallback eval_trace_ = nullptr;
    void* eval_trace_context_ = nullptr;
#endif

    void reset_search_state();
    void acc_refresh_root();
    bool do_move_acc(const Move& m);
    void undo_move_acc();

    // cut_node: 调用方预期这个节点会 fail-high(通常是零窗口试探的那一侧)。
    // PV 节点则由窗口宽度直接判定(beta > alpha + 1), 不用额外传。这两个信息
    // 本身不改变搜索结果, 只是给 LMR/剪枝当调制输入 —— 缺了它们, 减量就只能
    // 一视同仁地砍, 这正是我们 LMR 调不动的根因。
    int alpha_beta(int depth, int alpha, int beta, int64_t& nodes, int ply,
                   bool allow_null = true, int excluded_move = 0, bool cut_node = false);
    int quiescence(int alpha, int beta, int64_t& nodes, int ply, int qply);

    void order_moves(std::vector<Move>& moves, int tt_move, int ply);
    void record_cutoff(const Move& m, int depth, int ply, const std::vector<Move>& quiets_tried);
    // 吃子截断的奖惩, 与 record_cutoff 对应但走吃子历史表
    void record_capture_cutoff(const Move& m, int depth, const std::vector<Move>& caps_tried);
    Move last_cutoff_move_{};
    void update_history(int from, int to, int bonus);

    // 续接历史读写。key 为 (子力,落点) 组合, prev_ply 指向"上一手"所在层。
    int cont_score(int ply, int piece_to) const;
    void update_cont_hist(int ply, int piece_to, int bonus);
    static int piece_to_key(int piece_idx, int to_sq) { return piece_idx * kSquareCount + to_sq; }

    void store_tt(int64_t key, int depth, int score, int flag, int move_data, int ply);
    int tt_slot(int64_t key) const { return static_cast<int>(key) & tt_mask_; }

    int evaluate(int forced_head = -1);
    int corrected_evaluate(int forced_head = -1);
    void update_correction(int static_eval, int search_score, int depth);
    bool aborted(int64_t node_count);
    int lmr_reduction(int depth, int move_count) const;

    static constexpr int kLmrTableMax = 63;
    int lmr_table_[kLmrTableMax + 1][kLmrTableMax + 1] = {};
};

}  // namespace xq

#endif  // XQ_SEARCH_H
