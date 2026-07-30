// NNUE 整数推理核心.
//
// 这个文件**不含任何 JNI/Android 依赖**, 目的是能用宿主机的 g++ 直接编译,
// 拿黄金向量跑一遍。手机上跑不动的东西, 在这里几秒钟就能验完。
//
// 尺度约定与 nnue/FORMAT.md、Kotlin 侧 NnueEvaluator、Python 侧 int_infer
// 必须**逐位一致**。四个实现算出不同的数就等于网络白练。
#ifndef NNUE_CORE_H
#define NNUE_CORE_H

#include <cstdint>

namespace nnue {

// 特征 = (己方将所在九宫格位, 子类型, 格子)。
//
// 只按 (子类型,格子) 编码时网络无法表达"这个子的价值取决于将在哪", 而象棋的
// 评估极度依赖将位。标准 NNUE 的做法(HalfKP/HalfKA)是把己方将编进特征; 国象里
// 特征会爆到几十万维, 但象棋的将困在九宫只有 9 个位置, 所以只到 11340。
constexpr int kKingBuckets = 9;
constexpr int kPieceKinds = 14;
constexpr int kFeatures = kKingBuckets * kPieceKinds * 90;   // 11340
constexpr int kSquares = 90;
constexpr int kQA = 127;          // 激活值尺度 (ClippedReLU 上界 1.0)
constexpr int kQW = 256;          // fc1/fc2 权重尺度
// 从 64 提到 256 是为了让 fc1/fc2 用满 int8 量程: 实测权重最大只到 0.15~0.35,
// 乘 64 之后只占到 9~22/127, 二三十个档位, 四舍五入把精度磨掉了。逐层消融显示
// 98% 的量化误差出在这两层(ft 占 7.4 厘兵, fc1/fc2 占 40.5, out 占 0.0)。
constexpr int kMaxPieces = 32;

// 层宽上限。推理用的是栈上定长缓冲, 所以这个上限是**硬约束**, 不是建议值 ——
// Kotlin 侧 NnueNetwork 必须用同样的数字拦住, 否则一个层宽 2000 的文件会在
// 这里直接踩坏栈。
constexpr int kMaxL1 = 1024;
constexpr int kMaxL23 = 256;


// 权重视图。不持有内存 —— 数据由 Kotlin 侧读进 DirectByteBuffer 或
// 由宿主测试 mmap, 这里只负责算。
struct Net {
    int l1, l2, l3;
    const int16_t* ft_w;    // [kFeatures * l1] 行主序
    const int16_t* ft_b;    // [l1]
    const int8_t*  fc1_w;   // [l2 * l1*2]
    const int32_t* fc1_b;   // [l2]
    const int8_t*  fc2_w;   // [l3 * l2]
    const int32_t* fc2_b;   // [l3]
    // out_w 是 int32 而非 int16: |w| 最大 233, 乘 QW=256 是 59648, int16 装不下。
    // 它的量化误差本来就是 0, 加宽只多 l3*4 字节。
    const int32_t* out_w;   // [l3]
    int32_t        out_b;
};

// 权重指针与尺寸是否自洽且在上限内。JNI 层在每次换网络时必须调一遍。
bool validate(const Net& net);

// pieces 是 (pieceIdx, sq) 交替排列, 长度 2*n。返回走子方视角的厘兵分。
//
// scalar 版是**唯一的正确性基准**; simd 版必须与它逐位相同。
// 在 ARM 上两个版本都会被编译, 好让运行时能互相校验 —— 我在开发机上没有
// ARM 硬件也没有模拟器, 与其赌 NEON 写对了, 不如让它到了真机自己证明。
int32_t eval_scalar(const Net& net, const uint8_t* pieces, int n, bool black_to_move);
int32_t eval_simd(const Net& net, const uint8_t* pieces, int n, bool black_to_move);

// 有没有真正的 SIMD 实现可用 (非 ARM 平台上 eval_simd 就是 scalar 的别名)
bool has_simd();

// ------------------------------------------------------------- 增量累加器
//
// 求值时间的 65% 花在累加器上 (32 个子 × 256 维 × 2 视角)。但走一步棋只动
// 2~3 个特征, 从父局面的累加器加减一下就够了 —— 这就是 NNUE 里 "U" 的由来。
//
// 关键点: raw/flip 两个视角与**走子方无关**。
//   raw[i]  = ft_b[i] + Σ ft_w[own_feature(子) * l1 + i]
//   flip[i] = ft_b[i] + Σ ft_w[opp_feature(子) * l1 + i]
// 走子方只决定求值时哪一半拼在前面, 不影响累加器本身。所以走一步棋不需要
// 因为"轮到对方了"而重算 —— 这一点搞错的话就退化成全量重算了。
struct Accum {
    int16_t raw[kMaxL1];
    int16_t flip[kMaxL1];
    // 这份累加器是按哪个将位桶算出来的。acc_apply 要用它去索引特征;
    // 走将之后桶会变, 那时只能 acc_refresh 重算。
    int raw_bucket = 0;
    int flip_bucket = 0;
};

// 全量重算。进搜索前做一次, 之后靠 acc_apply 推进。
void acc_refresh(const Net& net, Accum& a, const uint8_t* pieces, int n);

// dst = src 走一步棋之后的累加器。src 与 dst 可以是同一个对象。
//
// mover_idx 从 from_sq 走到 to_sq; cap_idx >= 0 表示吃掉了原本在 to_sq 上的子。
// 注意被吃的子在 **to_sq**, 与落点重合 —— 象棋没有吃过路兵这类例外, 所以
// 不需要额外的被吃格参数。
// 返回 false 表示**无法增量更新, 调用方必须改用 acc_refresh**。
//
// 走的是将时会发生: 将位决定了所有特征的桶下标, 将一动, 盘上每个子的特征
// 都换了桶 —— 这是把将位编进特征的固有代价(HalfKP 系都有), 不是 bug。
// 象棋里将走得不算多, 但残局里比较频繁。
bool acc_apply(const Net& net, Accum& dst, const Accum& src,
               int mover_idx, int from_sq, int to_sq, int cap_idx);

// 走子方一侧的将是不是这一步的执行者 —— 供调用方提前判断要不要走 refresh
inline bool is_king_index(int piece_idx) { return piece_idx == 0 || piece_idx == 7; }

// 从累加器求值, 返回走子方视角的厘兵分。
//
// 不变式: acc_refresh(a, pieces, n) 之后, eval_acc(a, black) 必须与
// eval_scalar(pieces, n, black) **逐位相同**。JNI 自检和 host_test 都验这条。
int32_t eval_acc(const Net& net, const Accum& a, bool black_to_move);

// 累加器用 int16。最坏情况是"某个输出维度上最大的 32 个权重之和 + 偏置",
// 实测约 600, 对 int16 有 54 倍余量 —— 但那是**某一个网络**的实测值,
// 换个训练得更久的网络可能变大。所以导出时会校验这个上界 (见 export.py),
// 这里再留一个运行时可查的函数, 便于出问题时定位。
int32_t worst_case_accumulator(const Net& net);

}  // namespace nnue

#endif  // NNUE_CORE_H
