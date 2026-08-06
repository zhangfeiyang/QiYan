// Kotlin <-> C++ 的桥。只做三件事: 解析权重、转发求值、自检。
//
// 每个 NnueEvaluator 持有自己的句柄, 因为句柄里带着可变的临时缓冲 ——
// 多个搜索线程共用一个句柄会互相踩。权重是只读的, 但这里为简单起见每个
// 句柄各存一份; 真要省内存再共享不迟 (l1=256 时 645KB)。
#include "nnue_core.h"

#include <jni.h>

#include <cstring>
#include <new>
#include <vector>

namespace {

// 累加器栈的层数。搜索最深 8 层 + 静止搜索 6 层, 64 是三倍以上的余量。
// 每层 sizeof(Accum) = 4KB (按 kMaxL1=1024 定长), 一个句柄 256KB ——
// 8 个数据生成线程也才 2MB, 换掉 65% 的求值时间, 这个内存花得值。
constexpr int kAccCapacity = 64;

struct Handle {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b, psqt_w;
    std::vector<int32_t> out_w, out_b;
    std::vector<int8_t> fc1_w, fc2_w;
    std::vector<int32_t> fc1_b, fc2_b;
    // 局面缓冲: Kotlin 侧直接往这里写 (pieceIdx, sq), 免得每次求值都
    // 拷一遍数组。JNI 调用本身才十几纳秒, 数组拷贝反而是大头。
    uint8_t pieces[nnue::kMaxPieces * 2]{};

    // 增量累加器栈。stack[depth] 是当前局面, depth 由 push/pop 维护。
    //
    // 超出容量时**不写**栈 (只增计数), 于是栈里 depth < capacity 的那些层
    // 始终保持有效 —— 弹回来之后能直接接着用, 不需要重新 refresh。
    std::vector<nnue::Accum> stack;
    int depth = 0;
};

// 从字节流里顺序取出若干元素
class Reader {
public:
    Reader(const uint8_t* p, size_t n) : p_(p), left_(n) {}

    template <typename T>
    bool take(std::vector<T>& v, size_t count) {
        const size_t bytes = count * sizeof(T);
        if (bytes > left_) return false;
        v.resize(count);
        std::memcpy(v.data(), p_, bytes);
        p_ += bytes;
        left_ -= bytes;
        return true;
    }

    bool take_i32(int32_t& v) {
        if (left_ < 4) return false;
        std::memcpy(&v, p_, 4);
        p_ += 4;
        left_ -= 4;
        return true;
    }

    size_t left() const { return left_; }

private:
    const uint8_t* p_;
    size_t left_;
};

}  // namespace

extern "C" {

// 返回句柄; 0 表示失败。失败原因一律当成"文件不可信", 不做部分加载 ——
// 权重错位不会崩溃, 只会让棋力莫名其妙地差, 那种问题几乎查不出来。
JNIEXPORT jlong JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeCreate(
    JNIEnv* env, jclass, jbyteArray weights) {
    const jsize len = env->GetArrayLength(weights);
    if (len < 32) return 0;

    std::vector<uint8_t> raw(static_cast<size_t>(len));
    env->GetByteArrayRegion(weights, 0, len, reinterpret_cast<jbyte*>(raw.data()));

    if (std::memcmp(raw.data(), "XQNN", 4) != 0) return 0;

    Reader r(raw.data() + 4, raw.size() - 4);
    int32_t version = 0, features = 0, l1 = 0, l2 = 0, l3 = 0, qa = 0, qw = 0;
    if (!r.take_i32(version) || !r.take_i32(features) || !r.take_i32(l1) ||
        !r.take_i32(l2) || !r.take_i32(l3) || !r.take_i32(qa) || !r.take_i32(qw)) {
        return 0;
    }
    int32_t phase_buckets = 1;
    int32_t psqt_q = 0;
    if (version >= 4 && !r.take_i32(phase_buckets)) return 0;
    if (version == 5 && (!r.take_i32(psqt_q) || psqt_q <= 0)) return 0;
    if ((version != 3 && version != 4 && version != 5) || features != nnue::kFeatures ||
        qa != nnue::kQA || qw != nnue::kQW) {
        return 0;
    }
    if (phase_buckets != 1 && phase_buckets != nnue::kPhaseBuckets) return 0;
    if (l1 <= 0 || l1 > nnue::kMaxL1 || l1 % 32 != 0) return 0;   // 同 validate()
    if (l2 <= 0 || l2 > nnue::kMaxL23) return 0;
    if (l3 <= 0 || l3 > nnue::kMaxL23) return 0;

    Handle* h = new (std::nothrow) Handle();
    if (!h) return 0;

    const bool ok =
        r.take(h->ft_w, static_cast<size_t>(features) * l1) &&
        r.take(h->ft_b, static_cast<size_t>(l1)) &&
        r.take(h->fc1_w, static_cast<size_t>(phase_buckets) * l2 * l1 * 2) &&
        r.take(h->fc1_b, static_cast<size_t>(phase_buckets) * l2) &&
        r.take(h->fc2_w, static_cast<size_t>(phase_buckets) * l3 * l2) &&
        r.take(h->fc2_b, static_cast<size_t>(phase_buckets) * l3) &&
        r.take(h->out_w, static_cast<size_t>(phase_buckets) * l3) &&
        r.take(h->out_b, static_cast<size_t>(phase_buckets)) &&
        (version != 5 || r.take(h->psqt_w, static_cast<size_t>(features)));
    // 尾部还有剩余说明双方对格式的理解不一致, 前面读到的很可能已经错位
    if (!ok || r.left() != 0) {
        delete h;
        return 0;
    }

    h->net = nnue::Net{l1, l2, l3, h->ft_w.data(), h->ft_b.data(),
                       h->fc1_w.data(), h->fc1_b.data(),
                       h->fc2_w.data(), h->fc2_b.data(),
                       h->out_w.data(), h->out_b[0]};
    h->net.phase_buckets = phase_buckets;
    h->net.out_buckets = h->out_b.data();
    h->net.psqt_w = version == 5 ? h->psqt_w.data() : nullptr;
    h->net.psqt_q = psqt_q;
    if (!nnue::validate(h->net)) {
        delete h;
        return 0;
    }
    h->stack.resize(kAccCapacity);
    if (h->stack.size() != static_cast<size_t>(kAccCapacity)) {
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Handle*>(handle);
}

// 暴露局面缓冲, Kotlin 侧包成 ByteBuffer 直接写。
JNIEXPORT jobject JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativePieceBuffer(
    JNIEnv* env, jclass, jlong handle) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h) return nullptr;
    return env->NewDirectByteBuffer(h->pieces, sizeof(h->pieces));
}

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeEval(
    JNIEnv*, jclass, jlong handle, jint n, jboolean black) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h || n < 0 || n > nnue::kMaxPieces) return 0;
    return nnue::eval_simd(h->net, h->pieces, n, black == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeHasSimd(JNIEnv*, jclass) {
    return nnue::has_simd() ? JNI_TRUE : JNI_FALSE;
}

// ------------------------------------------------------------ 增量累加器

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeAccCapacity(JNIEnv*, jclass) {
    return kAccCapacity;
}

// 用 pieces 缓冲里的局面全量重建, 并把栈清空。进搜索前调一次。
JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeAccRefresh(
    JNIEnv*, jclass, jlong handle, jint n) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h || n < 0 || n > nnue::kMaxPieces) return;
    h->depth = 0;
    nnue::acc_refresh(h->net, h->stack[0], h->pieces, n);
}

// 走一步。cap_idx < 0 表示不吃子。
JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeAccPush(
    JNIEnv*, jclass, jlong handle, jint mover_idx, jint from_sq, jint to_sq,
    jint cap_idx) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h) return;
    if (h->depth + 1 < kAccCapacity) {
        // 走将时 acc_apply 会拒绝(所有特征换桶), 调用方本该改用 AccPushRefresh。
        // 这里再兜一道: 万一漏了, 宁可把这一层标成"桶 -1"让求值退回全量,
        // 也不要拿一份错桶的累加器算出看似正常的分数。
        if (!nnue::acc_apply(h->net, h->stack[h->depth + 1], h->stack[h->depth],
                             mover_idx, from_sq, to_sq, cap_idx)) {
            h->stack[h->depth + 1].raw_bucket = -1;   // 标记不可用
        }
    }
    ++h->depth;
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeAccPop(
    JNIEnv*, jclass, jlong handle) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (h && h->depth > 0) --h->depth;
}

// 调用方必须先确认 depth < capacity (Kotlin 侧自己数得到)。
// 走将之后用这个: 直接对新局面全量重算并压栈。
// 将位决定所有特征的桶, 将一动增量就失效 —— 这是把将位编进特征的固有代价。
JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeAccPushRefresh(
    JNIEnv*, jclass, jlong handle, jint n) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h || n < 0 || n > nnue::kMaxPieces) return;
    if (h->depth + 1 < kAccCapacity) {
        nnue::acc_refresh(h->net, h->stack[h->depth + 1], h->pieces, n);
    }
    ++h->depth;
}

JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeAccEval(
    JNIEnv*, jclass, jlong handle, jboolean black) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h || h->depth >= kAccCapacity) return 0;
    if (h->stack[h->depth].raw_bucket < 0) return 0;   // 见 AccPush 的兜底标记
    return nnue::eval_acc(h->net, h->stack[h->depth], black == JNI_TRUE);
}

// 增量路径自检, 返回不一致条数。验两条不变式:
//   1. acc_refresh + eval_acc  ==  eval_scalar
//   2. acc_apply(父, 一步棋)   ==  acc_refresh(走完之后)
//
// 与 nativeSelfCheck 同样的理由: NEON 版本我在开发机上验不了, 只能让它
// 上了真机自证。增量错了不会崩, 只会让累加器随搜索漂移, 症状是棋力莫名
// 其妙地差 —— 这种问题几乎查不出来, 所以宁可加载时多花几毫秒。
JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeAccSelfCheck(
    JNIEnv*, jclass, jlong handle, jint samples) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h) return -1;

    uint32_t seed = 0x5BD1E995u;
    auto next = [&seed]() {
        seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
        return seed;
    };

    int bad = 0;
    uint8_t pieces[nnue::kMaxPieces * 2];
    uint8_t after[nnue::kMaxPieces * 2];
    nnue::Accum a{}, b{}, c{};
    for (int s = 0; s < samples; ++s) {
        // 格子必须互不相同: "吃子"那一步会短暂让两个子占同一格, 正是最
        // 容易写错的地方, 而随机重复格会把这个信号淹掉。
        // 造得像真实局面: 红黑各恰好一个将且都在九宫内。纯随机会造出"同色两个
        // 将""吃掉将"这类真实对局不存在的情况, 而将位桶正由将决定 —— 那些样本
        // 会让 acc_apply 和全量重算得出不同的桶, 报出一堆假的不一致。
        const int n = 2 + static_cast<int>(next() % (nnue::kMaxPieces - 1));
        bool used[nnue::kSquares] = {false};
        const int rk = (7 + static_cast<int>(next() % 3)) * 9 + 3 + static_cast<int>(next() % 3);
        const int bk = static_cast<int>(next() % 3) * 9 + 3 + static_cast<int>(next() % 3);
        used[rk] = true; used[bk] = true;
        pieces[0] = 0; pieces[1] = static_cast<uint8_t>(rk);
        pieces[2] = 7; pieces[3] = static_cast<uint8_t>(bk);
        for (int i = 2; i < n; ++i) {
            int sq;
            do { sq = static_cast<int>(next() % nnue::kSquares); } while (used[sq]);
            used[sq] = true;
            const int k = static_cast<int>(next() % 12);
            pieces[i * 2] = static_cast<uint8_t>(k < 6 ? k + 1 : k + 2);
            pieces[i * 2 + 1] = static_cast<uint8_t>(sq);
        }
        const bool black = (next() & 1) != 0;

        nnue::acc_refresh(h->net, a, pieces, n);
        if (nnue::eval_acc(h->net, a, black) !=
            nnue::eval_scalar(h->net, pieces, n, black)) {
            ++bad;
            continue;
        }

        const int mv = static_cast<int>(next() % n);
        const int from_sq = pieces[mv * 2 + 1];
        const int to_sq = static_cast<int>(next() % nnue::kSquares);
        if (to_sq == from_sq) continue;
        int cap = -1, cap_slot = -1;
        for (int i = 0; i < n; ++i) {
            if (i != mv && pieces[i * 2 + 1] == to_sq) {
                cap = pieces[i * 2]; cap_slot = i; break;
            }
        }
        int m = 0;
        for (int i = 0; i < n; ++i) {
            if (i == cap_slot) continue;
            after[m * 2] = pieces[i * 2];
            after[m * 2 + 1] = (i == mv) ? static_cast<uint8_t>(to_sq)
                                         : pieces[i * 2 + 1];
            ++m;
        }
        // 走将时 acc_apply 拒绝增量, 那条路径由 AccPushRefresh 覆盖, 不在这里验
        if (!nnue::acc_apply(h->net, b, a, pieces[mv * 2], from_sq, to_sq, cap)) continue;
        nnue::acc_refresh(h->net, c, after, m);
        const size_t bytes = static_cast<size_t>(h->net.l1) * sizeof(int16_t);
        if (std::memcmp(b.raw, c.raw, bytes) != 0 ||
            std::memcmp(b.flip, c.flip, bytes) != 0 || b.phase != c.phase) {
            ++bad;
        }
    }
    return bad;
}

// NEON 与标量路径的一致性自检, 返回不一致的条数。
//
// 这是整个 SIMD 改造里我唯一无法在开发机上验证的部分 —— 手边没有 ARM 硬件
// 也没有模拟器。与其赌 NEON 写对了, 不如让它到了真机自己证明: 加载网络时
// 跑一遍, 对不上就退回标量, 宁可慢也不能算错。
JNIEXPORT jint JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NnueNative_nativeSelfCheck(
    JNIEnv*, jclass, jlong handle, jint samples) {
    Handle* h = reinterpret_cast<Handle*>(handle);
    if (!h) return -1;

    uint32_t seed = 0x9E3779B9u;
    auto next = [&seed]() {
        seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
        return seed;
    };

    int bad = 0;
    uint8_t pieces[nnue::kMaxPieces * 2];
    for (int s = 0; s < samples; ++s) {
        // 造合法范围内的随机局面: 子数 2..32, 格子不重复不是必需的
        // (求值不关心), 但下标必须在范围内, 否则读越界。
        const int n = 2 + static_cast<int>(next() % (nnue::kMaxPieces - 1));
        for (int i = 0; i < n; ++i) {
            pieces[i * 2] = static_cast<uint8_t>(next() % 14);
            pieces[i * 2 + 1] = static_cast<uint8_t>(next() % nnue::kSquares);
        }
        const bool black = (next() & 1) != 0;
        if (nnue::eval_simd(h->net, pieces, n, black) !=
            nnue::eval_scalar(h->net, pieces, n, black)) {
            ++bad;
        }
    }
    return bad;
}

}  // extern "C"
