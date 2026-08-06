// 从自研搜索真正调用 NNUE 的位置中抽样 FEN，交给 PikaFish 离线重标。
// 只在 build_eval_leaf_sampler.sh 的诊断构建中启用 XQ_ENABLE_EVAL_TRACE；
// App/正式 UCI 构建没有回调分支，不承担任何节点开销。
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

#include "board.h"
#include "nnue_core.h"
#include "search.h"

namespace {

constexpr size_t kRecordBytes = 72;

struct LoadedNet {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b, psqt_w;
    std::vector<int32_t> out_w, out_b, fc1_b, fc2_b;
    std::vector<int8_t> fc1_w, fc2_w;
};

template <typename T>
bool take(std::ifstream& f, std::vector<T>& out, size_t count) {
    out.resize(count);
    f.read(reinterpret_cast<char*>(out.data()), count * sizeof(T));
    return static_cast<size_t>(f.gcount()) == count * sizeof(T);
}

bool load_net(const std::string& path, LoadedNet& out) {
    std::ifstream f(path, std::ios::binary);
    char magic[4];
    if (!f || !f.read(magic, 4) || std::memcmp(magic, "XQNN", 4) != 0) return false;
    int32_t h[7];
    if (!f.read(reinterpret_cast<char*>(h), sizeof(h))) return false;
    const int version = h[0], features = h[1], l1 = h[2], l2 = h[3], l3 = h[4];
    int32_t phase_buckets = 1, psqt_q = 0;
    if (version >= 4 && !f.read(reinterpret_cast<char*>(&phase_buckets), 4)) return false;
    if (version == 5 && !f.read(reinterpret_cast<char*>(&psqt_q), 4)) return false;
    if ((version != 3 && version != 4 && version != 5) ||
        features != nnue::kFeatures || h[5] != nnue::kQA || h[6] != nnue::kQW ||
        (phase_buckets != 1 && phase_buckets != nnue::kPhaseBuckets) ||
        (version == 5 && psqt_q <= 0)) return false;
    const bool ok =
        take(f, out.ft_w, static_cast<size_t>(features) * l1) &&
        take(f, out.ft_b, l1) &&
        take(f, out.fc1_w, static_cast<size_t>(phase_buckets) * l2 * l1 * 2) &&
        take(f, out.fc1_b, static_cast<size_t>(phase_buckets) * l2) &&
        take(f, out.fc2_w, static_cast<size_t>(phase_buckets) * l3 * l2) &&
        take(f, out.fc2_b, static_cast<size_t>(phase_buckets) * l3) &&
        take(f, out.out_w, static_cast<size_t>(phase_buckets) * l3) &&
        take(f, out.out_b, phase_buckets) &&
        (version != 5 || take(f, out.psqt_w, features));
    if (!ok || f.peek() != std::ifstream::traits_type::eof()) return false;
    out.net = nnue::Net{l1, l2, l3, out.ft_w.data(), out.ft_b.data(),
                        out.fc1_w.data(), out.fc1_b.data(), out.fc2_w.data(),
                        out.fc2_b.data(), out.out_w.data(), out.out_b[0]};
    out.net.phase_buckets = phase_buckets;
    out.net.out_buckets = out.out_b.data();
    out.net.psqt_w = version == 5 ? out.psqt_w.data() : nullptr;
    out.net.psqt_q = psqt_q;
    return nnue::validate(out.net);
}

struct Shard {
    std::string path;
    std::vector<uint8_t> bytes;
    size_t records = 0;
};

uint64_t splitmix64(uint64_t x) {
    x += 0x9E3779B97F4A7C15ULL;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31);
}

uint64_t fnv1a(const uint8_t* p, size_t n, uint64_t h = 1469598103934665603ULL) {
    for (size_t i = 0; i < n; ++i) h = (h ^ p[i]) * 1099511628211ULL;
    return h;
}

std::string canonical_fen(const xq::Position& p) {
    std::istringstream in(p.to_fen());
    std::string board, side;
    in >> board >> side;
    return board + " " + side + " - - 0 1";
}

std::string record_fen(const uint8_t* rec) {
    static constexpr char chars[14] = {
        'K', 'A', 'B', 'N', 'R', 'C', 'P', 'k', 'a', 'b', 'n', 'r', 'c', 'p'
    };
    const int count = rec[0];
    const int stm = rec[1];
    if (count < 2 || count > nnue::kMaxPieces || stm > 1) return {};
    char board[xq::kSquareCount];
    std::fill(std::begin(board), std::end(board), '\0');
    for (int i = 0; i < count; ++i) {
        const int idx = rec[4 + i * 2], sq = rec[5 + i * 2];
        if (idx < 0 || idx >= 14 || !xq::sq_valid(sq) || board[sq] != '\0') return {};
        board[sq] = chars[idx];
    }
    std::string fen;
    for (int row = 0; row < xq::kRows; ++row) {
        if (row) fen.push_back('/');
        int empty = 0;
        for (int col = 0; col < xq::kCols; ++col) {
            const char c = board[xq::sq_make(row, col)];
            if (!c) {
                ++empty;
            } else {
                if (empty) { fen.push_back(static_cast<char>('0' + empty)); empty = 0; }
                fen.push_back(c);
            }
        }
        if (empty) fen.push_back(static_cast<char>('0' + empty));
    }
    fen += stm == 0 ? " w - - 0 1" : " b - - 0 1";
    return fen;
}

struct Collector {
    size_t target = 0;
    size_t max_per_root = 0;
    uint64_t rng = 1;
    uint64_t calls = 0;
    uint64_t root_candidates = 0;
    int sample_every = 1;
    std::unordered_set<std::string> seen;
    std::unordered_set<std::string> root_seen;
    std::vector<std::string> pending;
};

void trace_eval(const xq::Position& p, void* opaque) {
    auto& c = *static_cast<Collector*>(opaque);
    ++c.calls;
    if (c.seen.size() >= c.target || p.is_after_null_move()) return;
    c.rng = splitmix64(c.rng);
    if (c.rng % static_cast<uint64_t>(c.sample_every) != 0) return;
    std::string fen = canonical_fen(p);
    if (c.seen.count(fen) || !c.root_seen.insert(fen).second) return;

    // 同一个根局面的搜索树有数千次求值。全部收下会让 10 万条样本只覆盖几百
    // 个高度相关的根局面，独立验证也会被同类簇分布误导。先按 sample_every
    // 稀疏取候选，再用蓄水池抽样让每个根最多贡献 max_per_root 条。
    ++c.root_candidates;
    const size_t cap = std::min(c.max_per_root, c.target - c.seen.size());
    if (c.pending.size() < cap) {
        c.pending.push_back(std::move(fen));
        return;
    }
    c.rng = splitmix64(c.rng);
    const uint64_t slot = c.rng % c.root_candidates;
    if (slot < cap) c.pending[static_cast<size_t>(slot)] = std::move(fen);
}

bool write_progress(const std::string& path, const std::string& config, size_t roots_done) {
    const std::string tmp = path + ".tmp";
    {
        std::ofstream f(tmp, std::ios::trunc);
        if (!f) return false;
        f << config << '\n' << roots_done << '\n';
        f.flush();
        if (!f) return false;
    }
    std::error_code ec;
    std::filesystem::rename(tmp, path, ec);
    return !ec;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 10) {
        std::fprintf(stderr,
            "用法: %s <net.nnue> <out.fen> <target> <max-roots> <depth> <seed> "
            "<sample-every> <max-per-root> <shard.bin> [shard.bin...]\n", argv[0]);
        return 2;
    }
    const std::string net_path = argv[1], out_path = argv[2];
    const size_t target = std::stoull(argv[3]), max_roots = std::stoull(argv[4]);
    const int depth = std::stoi(argv[5]);
    const uint64_t seed = std::stoull(argv[6]);
    const int sample_every = std::stoi(argv[7]);
    const size_t max_per_root = std::stoull(argv[8]);
    if (!target || !max_roots || depth <= 0 || sample_every <= 0 || !max_per_root) return 2;

    LoadedNet loaded;
    if (!load_net(net_path, loaded)) {
        std::fprintf(stderr, "网络加载失败: %s\n", net_path.c_str());
        return 1;
    }

    std::vector<Shard> shards;
    size_t total_records = 0;
    uint64_t source_hash = 1469598103934665603ULL;
    for (int i = 9; i < argc; ++i) {
        Shard shard;
        shard.path = std::filesystem::absolute(argv[i]).string();
        std::ifstream f(shard.path, std::ios::binary);
        if (!f) { std::fprintf(stderr, "打不开 %s\n", argv[i]); return 1; }
        shard.bytes.assign(std::istreambuf_iterator<char>(f), {});
        shard.records = shard.bytes.size() / kRecordBytes;
        shard.bytes.resize(shard.records * kRecordBytes);
        if (!shard.records) continue;
        source_hash = fnv1a(shard.bytes.data(), shard.bytes.size(), source_hash);
        total_records += shard.records;
        shards.push_back(std::move(shard));
    }
    if (!total_records) { std::fprintf(stderr, "没有训练记录\n"); return 1; }

    const std::string progress_path = out_path + ".progress";
    std::ostringstream config_builder;
    config_builder << "leaf-v2 net=" << std::filesystem::absolute(net_path).string()
                   << " source=" << std::hex << source_hash << std::dec
                   << " target=" << target << " depth=" << depth
                   << " seed=" << seed << " every=" << sample_every
                   << " max_per_root=" << max_per_root;
    const std::string config = config_builder.str();

    Collector collector;
    collector.target = target;
    collector.sample_every = sample_every;
    collector.max_per_root = max_per_root;
    size_t roots_done = 0;
    if (std::filesystem::exists(out_path) || std::filesystem::exists(progress_path)) {
        if (!std::filesystem::exists(out_path) || !std::filesystem::exists(progress_path)) {
            std::fprintf(stderr, "输出与进度文件不成对，拒绝猜测恢复点\n"); return 1;
        }
        std::ifstream pf(progress_path);
        std::string old_config;
        std::getline(pf, old_config);
        pf >> roots_done;
        if (!pf || old_config != config) {
            std::fprintf(stderr, "恢复配置不一致\n旧: %s\n新: %s\n",
                         old_config.c_str(), config.c_str());
            return 1;
        }
        std::ifstream existing(out_path);
        std::string line;
        while (std::getline(existing, line)) if (!line.empty()) collector.seen.insert(line);
        std::printf("[leaf] 恢复 roots=%zu leaves=%zu\n", roots_done, collector.seen.size());
    } else {
        std::ofstream create(out_path);
        if (!create || !write_progress(progress_path, config, 0)) return 1;
    }

    xq::SearchEngine engine(loaded.net);
    engine.set_eval_trace(trace_eval, &collector);
    std::ofstream out(out_path, std::ios::app);
    uint64_t traced_calls = 0;
    for (size_t root_no = roots_done;
         root_no < max_roots && collector.seen.size() < target; ++root_no) {
        size_t global = splitmix64(seed + root_no * 0x9E3779B97F4A7C15ULL) % total_records;
        const uint8_t* rec = nullptr;
        for (const auto& shard : shards) {
            if (global < shard.records) { rec = shard.bytes.data() + global * kRecordBytes; break; }
            global -= shard.records;
        }
        if (!rec) return 1;
        const std::string fen = record_fen(rec);
        xq::Position p;
        if (fen.empty() || !p.set_from_fen(fen)) continue;
        collector.pending.clear();
        collector.root_seen.clear();
        collector.root_candidates = 0;
        collector.rng = splitmix64(seed ^ (root_no + 1));
        const uint64_t before_calls = collector.calls;
        engine.set_position(p);
        engine.search_with_score(depth);
        traced_calls += collector.calls - before_calls;
        for (const auto& leaf : collector.pending) {
            if (collector.seen.insert(leaf).second) out << leaf << '\n';
        }
        out.flush();
        roots_done = root_no + 1;
        if (!out || !write_progress(progress_path, config, roots_done)) return 1;
        if (roots_done % 10 == 0 || collector.seen.size() >= target) {
            std::printf("[leaf] roots=%zu leaves=%zu/%zu eval_calls=%llu depth=%d\n",
                        roots_done, collector.seen.size(), target,
                        static_cast<unsigned long long>(traced_calls), depth);
            std::fflush(stdout);
        }
    }
    std::printf("[leaf] 完成 roots=%zu leaves=%zu/%zu -> %s\n",
                roots_done, collector.seen.size(), target, out_path.c_str());
    return collector.seen.size() >= target ? 0 : 3;
}
