#include "nnue_core.h"

#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#  include <arm_neon.h>
#  define NNUE_HAS_NEON 1
#elif defined(__AVX2__)
#  include <immintrin.h>
#  define NNUE_HAS_AVX2 1
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
    // SIMD 路径按 8 / 16 / 32 个一组处理, 不写收尾循环 —— 宽度必须整除。
    //
    // 要 32 而不是 16, 是因为增量累加器的 clamp+窄化要对 raw / flip **各**跑
    // 一遍 (两个视角在 Accum 里按 kMaxL1 对齐, 用到的那段并不相邻), AVX2 一次
    // 吃 32 个 int16。实际网络都是 128/256/512, 这个约束不会碰到; 真碰到了
    // 也是在加载时明确失败, 而不是悄悄算错。
    if (net.l1 % 32 != 0) return false;
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

// ------------------------------------------------------------------- AVX2
//
// 只在桌面 (数据生成 / 跑分) 上用得到, Android 侧永远走上面的 NEON。
// 但数据生成一跑就是几个小时, 求值快一倍就等于数据多一倍, 值得写。
#elif NNUE_HAS_AVX2

bool has_simd() { return true; }

namespace {

// 8 路 int32 横向求和。
inline int32_t hsum_i32(__m256i v) {
    __m128i s = _mm_add_epi32(_mm256_castsi256_si128(v),
                              _mm256_extracti128_si256(v, 1));
    s = _mm_add_epi32(s, _mm_shuffle_epi32(s, 0x4E));  // [2,3,0,1]
    s = _mm_add_epi32(s, _mm_shuffle_epi32(s, 0xB1));  // [1,0,3,2]
    return _mm_cvtsi128_si32(s);
}

}  // namespace

int32_t eval_simd(const Net& net, const uint8_t* pieces, int n, bool black) {
    const int l1 = net.l1;
    const int in_dim = l1 * 2;

    // validate() 保证 l1 % 16 == 0, 于是 in_dim % 32 == 0 —— 下面所有循环
    // 都不需要收尾。改宽度约束前先改 validate。
    alignas(32) int16_t acc[2 * kMaxL1];
    for (int i = 0; i < l1; i += 16) {
        __m256i b = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(net.ft_b + i));
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(acc + i), b);
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(acc + l1 + i), b);
    }

    for (int p = 0; p < n; ++p) {
        const int idx = pieces[p * 2];
        const int sq = pieces[p * 2 + 1];
        const int raw = own_feature(idx, sq);
        const int flp = opp_feature(idx, sq);
        const int16_t* own_row = net.ft_w + (black ? flp : raw) * l1;
        const int16_t* opp_row = net.ft_w + (black ? raw : flp) * l1;
        for (int i = 0; i < l1; i += 16) {
            __m256i a = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(acc + i));
            __m256i o = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(own_row + i));
            _mm256_storeu_si256(reinterpret_cast<__m256i*>(acc + i),
                                _mm256_add_epi16(a, o));
            __m256i a2 = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(acc + l1 + i));
            __m256i o2 = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(opp_row + i));
            _mm256_storeu_si256(reinterpret_cast<__m256i*>(acc + l1 + i),
                                _mm256_add_epi16(a2, o2));
        }
    }

    // clamp 到 [0,127] 再窄化成 int8。packs 是**每 128 位车道内**打包的,
    // 结果会变成 [a0..7 b0..7 a8..15 b8..15], 所以要用 permute4x64(0xD8)
    // 把 64 位块换回 [a0..7 a8..15 b0..7 b8..15]。漏掉这一步不会崩, 只会
    // 让 fc1 的权重和激活错位相乘 —— 又是一个"能跑但棋力莫名其妙差"。
    alignas(32) int8_t x[2 * kMaxL1];
    const __m256i zero = _mm256_setzero_si256();
    const __m256i qa = _mm256_set1_epi16(kQA);
    for (int i = 0; i < in_dim; i += 32) {
        __m256i a = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(acc + i));
        __m256i b = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(acc + i + 16));
        a = _mm256_min_epi16(_mm256_max_epi16(a, zero), qa);
        b = _mm256_min_epi16(_mm256_max_epi16(b, zero), qa);
        __m256i packed = _mm256_permute4x64_epi64(_mm256_packs_epi16(a, b), 0xD8);
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(x + i), packed);
    }

    // fc1: int8 × int8 -> int32, 整个求值的大头 (l2 × l1*2 次乘加)。
    //
    // maddubs 的**第一个**操作数按无符号字节解释, 第二个才是有符号。
    // 权重是有符号的 (-1 会被读成 255), 但激活刚被 ClippedReLU 夹在
    // [0,127], 无符号解释与有符号完全一致 —— 所以激活放第一位、权重放
    // 第二位就天然正确, 不需要 Stockfish 那套 XOR 0x80 再补偿的技巧。
    // 顺序写反是这段代码唯一会静默算错的地方。
    //
    // 中间的 int16 会不会饱和: 单项最大 127*127, 相邻两项相加 32258,
    // 仍在 int16 的 32767 以内, 不会截断。
    const __m256i ones = _mm256_set1_epi16(1);
    int32_t h1[kMaxL23];
    for (int o = 0; o < net.l2; ++o) {
        const int8_t* row = net.fc1_w + o * in_dim;
        __m256i s = _mm256_setzero_si256();
        for (int i = 0; i < in_dim; i += 32) {
            __m256i v = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(x + i));
            __m256i w = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(row + i));
            s = _mm256_add_epi32(s, _mm256_madd_epi16(_mm256_maddubs_epi16(v, w), ones));
        }
        h1[o] = clamp_act((net.fc1_b[o] + hsum_i32(s)) / kQW);
    }

    // fc2 与输出层只有 32×32 和 32 次乘加, 走标量就够
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

#else   // 非 ARM、非 x86 SIMD (宿主机测试)

bool has_simd() { return false; }

int32_t eval_simd(const Net& net, const uint8_t* pieces, int n, bool black) {
    return eval_scalar(net, pieces, n, black);
}

#endif

// --------------------------------------------------------- 增量累加器实现
//
// 上面三条求值路径各写一遍是历史包袱 (NEON 那份是先写的, 不敢乱动)。增量
// 这部分改成: 只有下面四个原语按 ISA 分叉, acc_refresh / acc_apply /
// eval_acc 全平台共用一份。少两份拷贝就少两处能写错的地方。
namespace {

// acc[0..l1) += row[0..l1)
inline void row_add(int16_t* acc, const int16_t* row, int l1) {
#if NNUE_HAS_AVX2
    for (int i = 0; i < l1; i += 16) {
        __m256i a = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(acc + i));
        __m256i r = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(row + i));
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(acc + i), _mm256_add_epi16(a, r));
    }
#elif NNUE_HAS_NEON
    for (int i = 0; i < l1; i += 8) {
        vst1q_s16(acc + i, vaddq_s16(vld1q_s16(acc + i), vld1q_s16(row + i)));
    }
#else
    for (int i = 0; i < l1; ++i) acc[i] = static_cast<int16_t>(acc[i] + row[i]);
#endif
}

// acc[0..l1) -= row[0..l1)
inline void row_sub(int16_t* acc, const int16_t* row, int l1) {
#if NNUE_HAS_AVX2
    for (int i = 0; i < l1; i += 16) {
        __m256i a = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(acc + i));
        __m256i r = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(row + i));
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(acc + i), _mm256_sub_epi16(a, r));
    }
#elif NNUE_HAS_NEON
    for (int i = 0; i < l1; i += 8) {
        vst1q_s16(acc + i, vsubq_s16(vld1q_s16(acc + i), vld1q_s16(row + i)));
    }
#else
    for (int i = 0; i < l1; ++i) acc[i] = static_cast<int16_t>(acc[i] - row[i]);
#endif
}

// ClippedReLU + 窄化成 int8。n 必须是 32 的倍数 (validate 保证)。
inline void clamp_narrow(const int16_t* src, int8_t* dst, int n) {
#if NNUE_HAS_AVX2
    const __m256i zero = _mm256_setzero_si256();
    const __m256i qa = _mm256_set1_epi16(kQA);
    for (int i = 0; i < n; i += 32) {
        __m256i a = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(src + i));
        __m256i b = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(src + i + 16));
        a = _mm256_min_epi16(_mm256_max_epi16(a, zero), qa);
        b = _mm256_min_epi16(_mm256_max_epi16(b, zero), qa);
        // packs 按 128 位车道打包, 要 permute4x64 换回来 —— 同 eval_simd
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(dst + i),
                            _mm256_permute4x64_epi64(_mm256_packs_epi16(a, b), 0xD8));
    }
#elif NNUE_HAS_NEON
    const int16x8_t lo = vdupq_n_s16(0), hi = vdupq_n_s16(kQA);
    for (int i = 0; i < n; i += 16) {
        int16x8_t a = vminq_s16(vmaxq_s16(vld1q_s16(src + i), lo), hi);
        int16x8_t b = vminq_s16(vmaxq_s16(vld1q_s16(src + i + 8), lo), hi);
        vst1q_s8(dst + i, vcombine_s8(vmovn_s16(a), vmovn_s16(b)));
    }
#else
    for (int i = 0; i < n; ++i) dst[i] = static_cast<int8_t>(clamp_act(src[i]));
#endif
}

// int8 点积。n 必须是 32 (AVX2) / 16 (NEON) 的倍数。
inline int32_t dot_i8(const int8_t* w, const int8_t* x, int n) {
#if NNUE_HAS_AVX2
    // x 是激活值, 已被 ClippedReLU 夹在 [0,127], 无符号解释与有符号一致,
    // 所以它必须放 maddubs 的**第一个**操作数; 权重是有符号的, 放第二个。
    // 顺序写反不会崩, 只会静默算错 —— 见 eval_simd 里的详述。
    const __m256i ones = _mm256_set1_epi16(1);
    __m256i s = _mm256_setzero_si256();
    for (int i = 0; i < n; i += 32) {
        __m256i v = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(x + i));
        __m256i wv = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(w + i));
        s = _mm256_add_epi32(s, _mm256_madd_epi16(_mm256_maddubs_epi16(v, wv), ones));
    }
    return hsum_i32(s);
#elif NNUE_HAS_NEON
    int32x4_t s = vdupq_n_s32(0);
    for (int i = 0; i < n; i += 16) {
        int8x16_t wv = vld1q_s8(w + i);
        int8x16_t v = vld1q_s8(x + i);
        s = vpadalq_s16(s, vmull_s8(vget_low_s8(wv), vget_low_s8(v)));
        s = vpadalq_s16(s, vmull_s8(vget_high_s8(wv), vget_high_s8(v)));
    }
    return vaddvq_s32(s);
#else
    int32_t s = 0;
    for (int i = 0; i < n; ++i) s += w[i] * x[i];
    return s;
#endif
}

}  // namespace

void acc_refresh(const Net& net, Accum& a, const uint8_t* pieces, int n) {
    const int l1 = net.l1;
    for (int i = 0; i < l1; ++i) {
        a.raw[i] = net.ft_b[i];
        a.flip[i] = net.ft_b[i];
    }
    for (int p = 0; p < n; ++p) {
        const int idx = pieces[p * 2];
        const int sq = pieces[p * 2 + 1];
        row_add(a.raw, net.ft_w + own_feature(idx, sq) * l1, l1);
        row_add(a.flip, net.ft_w + opp_feature(idx, sq) * l1, l1);
    }
}

void acc_apply(const Net& net, Accum& dst, const Accum& src,
               int mover_idx, int from_sq, int to_sq, int cap_idx) {
    const int l1 = net.l1;
    if (&dst != &src) {
        for (int i = 0; i < l1; ++i) { dst.raw[i] = src.raw[i]; dst.flip[i] = src.flip[i]; }
    }
    // 走子: 从起点拿走, 放到落点
    row_sub(dst.raw, net.ft_w + own_feature(mover_idx, from_sq) * l1, l1);
    row_sub(dst.flip, net.ft_w + opp_feature(mover_idx, from_sq) * l1, l1);
    row_add(dst.raw, net.ft_w + own_feature(mover_idx, to_sq) * l1, l1);
    row_add(dst.flip, net.ft_w + opp_feature(mover_idx, to_sq) * l1, l1);
    // 吃子: 被吃的子原本就在落点上
    if (cap_idx >= 0) {
        row_sub(dst.raw, net.ft_w + own_feature(cap_idx, to_sq) * l1, l1);
        row_sub(dst.flip, net.ft_w + opp_feature(cap_idx, to_sq) * l1, l1);
    }
}

int32_t eval_acc(const Net& net, const Accum& a, bool black) {
    const int l1 = net.l1;
    const int in_dim = l1 * 2;

    // "我方"永远是走子方: 黑走棋时两个视角对调。累加器本身不含走子方信息,
    // 换边只是换个拼接顺序 —— 这正是增量更新能成立的原因。
    const int16_t* own = black ? a.flip : a.raw;
    const int16_t* opp = black ? a.raw : a.flip;

    alignas(32) int8_t x[2 * kMaxL1];
    clamp_narrow(own, x, l1);
    clamp_narrow(opp, x + l1, l1);

    int32_t h1[kMaxL23];
    for (int o = 0; o < net.l2; ++o) {
        const int32_t sum = net.fc1_b[o] + dot_i8(net.fc1_w + o * in_dim, x, in_dim);
        h1[o] = clamp_act(sum / kQW);
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
    return out / (kQA * kQW);
}

}  // namespace nnue
