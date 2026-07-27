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

constexpr int kFeatures = 1260;   // 14 种子 × 90 格, 由数据格式定死
constexpr int kSquares = 90;
constexpr int kQA = 127;          // 激活值尺度 (ClippedReLU 上界 1.0)
constexpr int kQW = 64;           // 权重尺度
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
    const int16_t* out_w;   // [l3]
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

// 累加器用 int16。最坏情况是"某个输出维度上最大的 32 个权重之和 + 偏置",
// 实测约 600, 对 int16 有 54 倍余量 —— 但那是**某一个网络**的实测值,
// 换个训练得更久的网络可能变大。所以导出时会校验这个上界 (见 export.py),
// 这里再留一个运行时可查的函数, 便于出问题时定位。
int32_t worst_case_accumulator(const Net& net);

}  // namespace nnue

#endif  // NNUE_CORE_H
