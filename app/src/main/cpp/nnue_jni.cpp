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

struct Handle {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b, out_w;
    std::vector<int8_t> fc1_w, fc2_w;
    std::vector<int32_t> fc1_b, fc2_b;
    int32_t out_b = 0;
    // 局面缓冲: Kotlin 侧直接往这里写 (pieceIdx, sq), 免得每次求值都
    // 拷一遍数组。JNI 调用本身才十几纳秒, 数组拷贝反而是大头。
    uint8_t pieces[nnue::kMaxPieces * 2]{};
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
    if (len < 36) return 0;

    std::vector<uint8_t> raw(static_cast<size_t>(len));
    env->GetByteArrayRegion(weights, 0, len, reinterpret_cast<jbyte*>(raw.data()));

    if (std::memcmp(raw.data(), "XQNN", 4) != 0) return 0;

    Reader r(raw.data() + 4, raw.size() - 4);
    int32_t version = 0, features = 0, l1 = 0, l2 = 0, l3 = 0, qa = 0, qw = 0;
    if (!r.take_i32(version) || !r.take_i32(features) || !r.take_i32(l1) ||
        !r.take_i32(l2) || !r.take_i32(l3) || !r.take_i32(qa) || !r.take_i32(qw)) {
        return 0;
    }
    if (version != 1 || features != nnue::kFeatures ||
        qa != nnue::kQA || qw != nnue::kQW) {
        return 0;
    }
    if (l1 <= 0 || l1 > nnue::kMaxL1 || l1 % 16 != 0) return 0;
    if (l2 <= 0 || l2 > nnue::kMaxL23) return 0;
    if (l3 <= 0 || l3 > nnue::kMaxL23) return 0;

    Handle* h = new (std::nothrow) Handle();
    if (!h) return 0;

    const bool ok =
        r.take(h->ft_w, static_cast<size_t>(features) * l1) &&
        r.take(h->ft_b, static_cast<size_t>(l1)) &&
        r.take(h->fc1_w, static_cast<size_t>(l2) * l1 * 2) &&
        r.take(h->fc1_b, static_cast<size_t>(l2)) &&
        r.take(h->fc2_w, static_cast<size_t>(l3) * l2) &&
        r.take(h->fc2_b, static_cast<size_t>(l3)) &&
        r.take(h->out_w, static_cast<size_t>(l3)) &&
        r.take_i32(h->out_b);
    // 尾部还有剩余说明双方对格式的理解不一致, 前面读到的很可能已经错位
    if (!ok || r.left() != 0) {
        delete h;
        return 0;
    }

    h->net = nnue::Net{l1, l2, l3, h->ft_w.data(), h->ft_b.data(),
                       h->fc1_w.data(), h->fc1_b.data(),
                       h->fc2_w.data(), h->fc2_b.data(),
                       h->out_w.data(), h->out_b};
    if (!nnue::validate(h->net)) {
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
