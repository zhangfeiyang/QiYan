// Kotlin <-> C++ 搜索引擎的桥. 粒度是**整次搜索**(局面进去, 最佳着法+分数+
// 节点数出来), 不是每节点一次 —— 见 NativeSearch.kt 顶部注释里的理由。
//
// 权重解析这部分与 nnue_jni.cpp 的 nativeCreate 几乎一样, 没有抽公共函数:
// 两边各自持有一份权重数据(Handle/SearchHandle 结构不同), 硬抽反而要为了
// 复用引入一层间接, 这点重复不值得。
#include "nnue_core.h"
#include "search.h"

#include <jni.h>

#include <cstring>
#include <new>
#include <string>
#include <vector>

namespace {

struct SearchHandle {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b;
    std::vector<int32_t> out_w;
    std::vector<int8_t> fc1_w, fc2_w;
    std::vector<int32_t> fc1_b, fc2_b;
    int32_t out_b = 0;
    xq::SearchEngine* engine = nullptr;

    ~SearchHandle() { delete engine; }
};

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

// 返回句柄; 0 表示失败(格式不对/权重损坏)。解析逻辑与 nnue_jni.cpp 的
// nativeCreate 一致: 失败一律当"文件不可信", 不做部分加载。
JNIEXPORT jlong JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NativeSearch_nativeSearchCreate(
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
    if (version != 3 || features != nnue::kFeatures || qa != nnue::kQA || qw != nnue::kQW) {
        return 0;
    }
    if (l1 <= 0 || l1 > nnue::kMaxL1 || l1 % 32 != 0) return 0;
    if (l2 <= 0 || l2 > nnue::kMaxL23) return 0;
    if (l3 <= 0 || l3 > nnue::kMaxL23) return 0;

    SearchHandle* h = new (std::nothrow) SearchHandle();
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
    if (!ok || r.left() != 0) { delete h; return 0; }

    h->net = nnue::Net{l1, l2, l3, h->ft_w.data(), h->ft_b.data(),
                       h->fc1_w.data(), h->fc1_b.data(),
                       h->fc2_w.data(), h->fc2_b.data(), h->out_w.data(), h->out_b};
    if (!nnue::validate(h->net)) { delete h; return 0; }

    h->engine = new (std::nothrow) xq::SearchEngine(h->net);
    if (!h->engine) { delete h; return 0; }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NativeSearch_nativeSearchDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SearchHandle*>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NativeSearch_nativeSearchSetPositionFen(
    JNIEnv* env, jclass, jlong handle, jstring fen) {
    SearchHandle* h = reinterpret_cast<SearchHandle*>(handle);
    if (!h) return JNI_FALSE;
    const char* cfen = env->GetStringUTFChars(fen, nullptr);
    if (!cfen) return JNI_FALSE;
    const bool ok = h->engine->set_position_fen(cfen);
    env->ReleaseStringUTFChars(fen, cfen);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 返回 [moveData, score, nodes]。整次搜索(含所有迭代加深的深度)都在这一次
// 调用里跑完 —— 这是与 NnueNative 的求值 JNI 桥最本质的区别: 那边每个搜索
// 节点跨一次语言边界, 这里整棵树只跨一次。
JNIEXPORT jlongArray JNICALL
Java_com_zhongguoxiangqi_engine_nnue_NativeSearch_nativeSearchGo(
    JNIEnv* env, jclass, jlong handle, jint depth, jint movetime_ms) {
    SearchHandle* h = reinterpret_cast<SearchHandle*>(handle);
    if (!h) return nullptr;

    const auto result = h->engine->search_with_score(depth, movetime_ms);
    const xq::Move& move = result.first;
    const int score = result.second;
    const int64_t nodes = h->engine->last_search_nodes();

    jlongArray arr = env->NewLongArray(3);
    if (!arr) return nullptr;
    const jlong out[3] = {move.data, score, nodes};
    env->SetLongArrayRegion(arr, 0, 3, out);
    return arr;
}

}  // extern "C"
