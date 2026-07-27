#include "nnue_core.h"

#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  include <arm_neon.h>
#  define NNUE_HAS_NEON 1
#endif

namespace nnue {
namespace {

// 与 Kotlin NnueFeatures / Python dataset.py 一一对应。
// 这三个函数写错一位, 症状是"能跑但棋力莫名其妙地差", 所以宁可啰嗦。
inline int flip_square(int sq) {
    // 只翻行, 列不动。棋盘 10 行 9 列, row = sq/9
    return (9 - sq / 9) * 9 + sq % 9;
}
inline int flip_piece(int idx) { return idx < 7 ? idx + 7 : idx - 7; }

inline int own_feature(int idx, int sq) { return idx * kSquares + sq; }
inline int opp_feature(int idx, int sq) {
    return flip_piece(idx) * kSquares + flip_square(sq);
}

inline int32_t clamp_act(int32_t v) {
    return v < 0 ? 0 : (v > kQA ? kQA : v);
}

}  // namespace

bool validate(const Net& net) {
    if (net.l1 <= 0 || net.l1 > kMaxL1) return false;
    if (net.l2 <= 0 || net.l2 > kMaxL23) return false;
    if (net.l3 <= 0 || net.l3 > kMaxL23) return false;
    // NEON 路径按 8 / 16 个一组处理, 不写收尾循环 —— 宽度必须整除
    if (net.l1 % 16 != 0) return false;
    if (!net.ft_w || !net.ft_b || !net.fc1_w || !net.fc1_b) return false;
    if (!net.fc2_w || !net.fc2_b || !net.out_w) return false;
    return true;
}

int32_t worst_case_accumulator(const Net& net) {
    // 每个输出维度上取绝对值最大的 32 个权重求和, 再加偏置。
    int32_t worst = 0;
    for (int o = 0; o < net.l1; ++o) {
        int32_t top[kMaxPieces] = {0};
        for (int f = 0; f < kFeatures; ++f) {
            int32_t v = net.ft_w[f * net.l1 + o];
            if (v < 0) v = -v;
            // 维持一个大小为 32 的最小值优先的小堆(直接插入排序即可, 只跑一次)
            if (v > top[0]) {
                top[0] = v;
                for (int i = 1; i < kMaxPieces && top[i - 1] > top[i]; ++i) {
                    std::swap(top[i - 1], top[i]);
                }
            }
        }
        int32_t sum = 0;
        for (int i = 0; i < kMaxPieces; ++i) sum += top[i];
        int32_t b = net.ft_b[o];
        sum += (b < 0 ? -b : b);
        worst = std::max(worst, sum);
    }
    return worst;
}

// ---------------------------------------------------------------- 标量基准

int32_t eval_scalar(const Net& net, const uint8_t* pieces, int n, bool black) {
    const int l1 = net.l1;
    const int in_dim = l1 * 2;

    // 累加器。int16 足够, 见头文件里的余量说明。
    int16_t acc[2 * kMaxL1];
    for (int i = 0; i < l1; ++i) {
        acc[i] = net.ft_b[i];
        acc[l1 + i] = net.ft_b[i];
    }

    for (int p = 0; p < n; ++p) {
        const int idx = pieces[p * 2];
        const int sq = pieces[p * 2 + 1];
        const int raw = own_feature(idx, sq);
        const int flp = opp_feature(idx, sq);
        const int16_t* own_row = net.ft_w + (black ? flp : raw) * l1;
        const int16_t* opp_row = net.ft_w + (black ? raw : flp) * l1;
        for (int i = 0; i < l1; ++i) {
            acc[i] = static_cast<int16_t>(acc[i] + own_row[i]);
            acc[l1 + i] = static_cast<int16_t>(acc[l1 + i] + opp_row[i]);
        }
    }

    // ClippedReLU 之后落在 [0,127], 正好装进 int8 —— QA 取 127 就是为了这个,
    // 后面 fc1 才能用 int8 点积。
    int8_t x[2 * kMaxL1];
    for (int i = 0; i < in_dim; ++i) x[i] = static_cast<int8_t>(clamp_act(acc[i]));

    int32_t h1[kMaxL23];
    for (int o = 0; o < net.l2; ++o) {
        int32_t sum = net.fc1_b[o];
        const int8_t* row = net.fc1_w + o * in_dim;
        for (int i = 0; i < in_dim; ++i) sum += row[i] * x[i];
        h1[o] = clamp_act(sum / kQW);   // C 的 / 截断向零, 与 Kotlin 一致
    }

    int32_t h2[kMaxL23];
    for (int o = 0; o < net.l3; ++o) {
        int32_t sum = net.fc2_b[o];
        const int8_t* row = net.fc2_w + o * net.l2;
        for (int i = 0; i < net.l2; ++i) sum += row[i] * h1[i];
        h2[o] = clamp_act(sum / kQW);
    }

    int32_t out = net.out_b;
    for (int i = 0; i < net.l3; ++i) out += net.out_w[i] * h2[i];
    // h2 带着 QA 的尺度, out_w 带着 QW 的尺度, 所以除 QA*QW 才是厘兵分
    return out / (kQA * kQW);
}

// ------------------------------------------------------------------- NEON

#if NNUE_HAS_NEON

bool has_simd() { return true; }

int32_t eval_simd(const Net& net, const uint8_t* pieces, int n, bool black) {
    const int l1 = net.l1;
    const int in_dim = l1 * 2;

    int16_t acc[2 * kMaxL1];
    // 累加器一次搬 8 个 int16。l1 是 32 的倍数, 不需要收尾循环。
    for (int i = 0; i < l1; i += 8) {
        int16x8_t b = vld1q_s16(net.ft_b + i);
        vst1q_s16(acc + i, b);
        vst1q_s16(acc + l1 + i, b);
    }

    for (int p = 0; p < n; ++p) {
        const int idx = pieces[p * 2];
        const int sq = pieces[p * 2 + 1];
        const int raw = own_feature(idx, sq);
        const int flp = opp_feature(idx, sq);
        const int16_t* own_row = net.ft_w + (black ? flp : raw) * l1;
        const int16_t* opp_row = net.ft_w + (black ? raw : flp) * l1;
        for (int i = 0; i < l1; i += 8) {
            vst1q_s16(acc + i, vaddq_s16(vld1q_s16(acc + i), vld1q_s16(own_row + i)));
            vst1q_s16(acc + l1 + i,
                      vaddq_s16(vld1q_s16(acc + l1 + i), vld1q_s16(opp_row + i)));
        }
    }

    // clamp 到 [0,127] 再窄化成 int8: 16 个 int16 -> 16 个 int8
    int8_t x[2 * kMaxL1];
    const int16x8_t lo = vdupq_n_s16(0), hi = vdupq_n_s16(kQA);
    for (int i = 0; i < in_dim; i += 16) {
        int16x8_t a = vminq_s16(vmaxq_s16(vld1q_s16(acc + i), lo), hi);
        int16x8_t b = vminq_s16(vmaxq_s16(vld1q_s16(acc + i + 8), lo), hi);
        vst1q_s8(x + i, vcombine_s8(vmovn_s16(a), vmovn_s16(b)));
    }

    // fc1: int8 × int8 -> int32。这里是整个求值的大头 (l2 × l1*2 次乘加)。
    int32_t h1[kMaxL23];
    for (int o = 0; o < net.l2; ++o) {
        const int8_t* row = net.fc1_w + o * in_dim;
        int32x4_t s = vdupq_n_s32(0);
        for (int i = 0; i < in_dim; i += 16) {
            int8x16_t w = vld1q_s8(row + i);
            int8x16_t v = vld1q_s8(x + i);
            // vmull_s8 一次 8 路 int8×int8->int16, 再用 vpadalq_s16 累进 int32。
            // 不用 SDOT 是因为它要 ARMv8.2 dotprod 扩展, 而 minSdk 26 的机器
            // 不保证有 —— 为了一点速度换来一批设备跑不了不划算。
            int16x8_t p0 = vmull_s8(vget_low_s8(w), vget_low_s8(v));
            int16x8_t p1 = vmull_s8(vget_high_s8(w), vget_high_s8(v));
            s = vpadalq_s16(s, p0);
            s = vpadalq_s16(s, p1);
        }
        int32_t sum = net.fc1_b[o] + vaddvq_s32(s);
        h1[o] = clamp_act(sum / kQW);
    }

    // fc2 与输出层只有 32×32 和 32 次乘加, 走标量就够, 上 SIMD 得不偿失
    int32_t h2[kMaxL23];
    for (int o = 0; o < net.l3; ++o) {
        int32_t sum = net.fc2_b[o];
        const int8_t* row = net.fc2_w + o * net.l2;
        for (int i = 0; i < net.l2; ++i) sum += row[i] * h1[i];
        h2[o] = clamp_act(sum / kQW);
    }

    int32_t out = net.out_b;
    for (int i = 0; i < net.l3; ++i) out += net.out_w[i] * h2[i];
    return out / (kQA * kQW);
}

#else   // 非 ARM (宿主机测试)

bool has_simd() { return false; }

int32_t eval_simd(const Net& net, const uint8_t* pieces, int n, bool black) {
    return eval_scalar(net, pieces, n, black);
}

#endif

}  // namespace nnue
