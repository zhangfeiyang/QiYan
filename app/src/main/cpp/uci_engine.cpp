// 极简 UCI 引擎, 只为了让 C++ SearchEngine 能跟 PikafishMatchRunner 那套
// "Kotlin 当裁判驱动 UCI 子进程"的基础设施对战——这里是自己跟自己(新参数
// vs 旧参数)对局, 不是给最终用户用的完整 UCI 实现。**不参与 Android 构建**。
//
//   g++ -O3 -std=c++17 board.cpp movegen.cpp see.cpp nnue_core.cpp search.cpp
//   uci_engine.cpp -o /tmp/xq_uci
//   /tmp/xq_uci <net.nnue> [--lmrdiv=X] [--se=0|1] [--rootsort=0|1]
//
// 支持的命令: uci / isready / ucinewgame / position startpos|fen ... [moves ...]
// / go depth N | go movetime N / setoption name Threads value N(忽略) / quit
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "board.h"
#include "nnue_core.h"
#include "search.h"

namespace {

struct Loaded {
    nnue::Net net{};
    std::vector<int16_t> ft_w, ft_b, psqt_w;
    std::vector<int32_t> out_w, out_b;
    std::vector<int8_t> fc1_w, fc2_w;
    std::vector<int32_t> fc1_b, fc2_b;
};

template <typename T>
bool take(std::ifstream& f, std::vector<T>& v, size_t n) {
    v.resize(n);
    f.read(reinterpret_cast<char*>(v.data()), n * sizeof(T));
    return static_cast<size_t>(f.gcount()) == n * sizeof(T);
}

bool load(const char* path, Loaded& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    char magic[4];
    f.read(magic, 4);
    if (std::memcmp(magic, "XQNN", 4) != 0) return false;
    int32_t h[7];
    f.read(reinterpret_cast<char*>(h), sizeof(h));
    const int l1 = h[2], l2 = h[3], l3 = h[4];
    int32_t phase_buckets = 1;
    int32_t psqt_q = 0;
    if (h[0] >= 4) {
        f.read(reinterpret_cast<char*>(&phase_buckets), 4);
        if (f.gcount() != 4) return false;
    }
    if (h[0] == 5) {
        f.read(reinterpret_cast<char*>(&psqt_q), 4);
        if (f.gcount() != 4 || psqt_q <= 0) return false;
    }
    if ((h[0] != 3 && h[0] != 4 && h[0] != 5) || h[1] != nnue::kFeatures ||
        h[5] != nnue::kQA || h[6] != nnue::kQW ||
        (phase_buckets != 1 && phase_buckets != nnue::kPhaseBuckets)) return false;
    bool ok = take(f, out.ft_w, size_t(h[1]) * l1) && take(f, out.ft_b, l1) &&
              take(f, out.fc1_w, size_t(phase_buckets) * l2 * l1 * 2) &&
              take(f, out.fc1_b, size_t(phase_buckets) * l2) &&
              take(f, out.fc2_w, size_t(phase_buckets) * l3 * l2) &&
              take(f, out.fc2_b, size_t(phase_buckets) * l3) &&
              take(f, out.out_w, size_t(phase_buckets) * l3) &&
              take(f, out.out_b, phase_buckets) &&
              (h[0] != 5 || take(f, out.psqt_w, h[1]));
    if (!ok) return false;
    out.net = nnue::Net{l1, l2, l3, out.ft_w.data(), out.ft_b.data(), out.fc1_w.data(),
                        out.fc1_b.data(), out.fc2_w.data(), out.fc2_b.data(),
                        out.out_w.data(), out.out_b[0]};
    out.net.phase_buckets = phase_buckets;
    out.net.out_buckets = out.out_b.data();
    out.net.psqt_w = h[0] == 5 ? out.psqt_w.data() : nullptr;
    out.net.psqt_q = psqt_q;
    return true;
}

// 与 Kotlin UciCoords 一致: rank digit -> row = 9 - digit (自反)
xq::Move parse_uci_move(const std::string& s) {
    if (s.size() < 4) return xq::Move();
    return xq::Move(xq::sq_make(9 - (s[1] - '0'), s[0] - 'a'),
                    xq::sq_make(9 - (s[3] - '0'), s[2] - 'a'));
}

std::string to_uci(const xq::Move& m) {
    if (m.is_none()) return "0000";
    char buf[5];
    buf[0] = static_cast<char>('a' + xq::sq_col(m.from()));
    buf[1] = static_cast<char>('0' + (9 - xq::sq_row(m.from())));
    buf[2] = static_cast<char>('a' + xq::sq_col(m.to()));
    buf[3] = static_cast<char>('0' + (9 - xq::sq_row(m.to())));
    buf[4] = 0;
    return buf;
}

std::vector<std::string> split(const std::string& s) {
    std::istringstream iss(s);
    std::vector<std::string> out;
    std::string tok;
    while (iss >> tok) out.push_back(tok);
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "用法: %s <net.nnue> [--lmrdiv=X] [--se=0|1] [--rootsort=0|1] [--partialroot=0|1] [--asp=0|1] [--qsee=N]\n", argv[0]);
        return 2;
    }
    Loaded L;
    if (!load(argv[1], L) || !nnue::validate(L.net)) {
        std::fprintf(stderr, "网络加载失败: %s\n", argv[1]);
        return 1;
    }

    double lmr_div = 1.2;
    bool use_se = true;
    bool use_lmp = true;
    bool use_futility = true;
    bool use_cont = true;
    bool use_dlmr = true;
    bool use_rootpvs = true;
    bool use_rootsort = false;
    bool use_partialroot = false;
    bool use_full_aspiration = false;
    bool use_leaf_eval_head = false;
    int use_iir = 1, use_ch = 1, use_corr = 0, tt_samegen = 0, tt_shift = 20;
    int null_move_reduction = 2, history_decay = 0, qsearch_see_margin = 0;
    bool collect_cutoff_stats = false;
    bool use_tt_flag = false;
    bool use_see_order = false;
    bool use_countermove = false;
    bool use_piece_to_hist = false;
    bool use_see_internal_order = false;
    int capture_hist_div = 16;
    bool use_improving_lmr = false;
    int lmp_max_depth = 4, futility_max_depth = 4;
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        if (a.rfind("--lmrdiv=", 0) == 0) lmr_div = std::atof(a.c_str() + 9);
        else if (a.rfind("--se=", 0) == 0) use_se = std::atoi(a.c_str() + 5) != 0;
        else if (a.rfind("--lmp=", 0) == 0) use_lmp = std::atoi(a.c_str() + 6) != 0;
        else if (a.rfind("--futility=", 0) == 0) use_futility = std::atoi(a.c_str() + 11) != 0;
        else if (a.rfind("--lmpd=", 0) == 0) lmp_max_depth = std::atoi(a.c_str() + 7);
        else if (a.rfind("--futd=", 0) == 0) futility_max_depth = std::atoi(a.c_str() + 7);
        else if (a.rfind("--cont=", 0) == 0) use_cont = std::atoi(a.c_str() + 7) != 0;
        else if (a.rfind("--dlmr=", 0) == 0) use_dlmr = std::atoi(a.c_str() + 7) != 0;
        else if (a.rfind("--rootpvs=", 0) == 0) use_rootpvs = std::atoi(a.c_str() + 10) != 0;
        else if (a.rfind("--rootsort=", 0) == 0) use_rootsort = std::atoi(a.c_str() + 11) != 0;
        else if (a.rfind("--partialroot=", 0) == 0) use_partialroot = std::atoi(a.c_str() + 14) != 0;
        else if (a.rfind("--asp=", 0) == 0) use_full_aspiration = std::atoi(a.c_str() + 6) != 0;
        else if (a.rfind("--iir=", 0) == 0) use_iir = std::atoi(a.c_str() + 6);
        else if (a.rfind("--ch=", 0) == 0) use_ch = std::atoi(a.c_str() + 5);
        else if (a.rfind("--histdecay=", 0) == 0) history_decay = std::atoi(a.c_str() + 12);
        else if (a.rfind("--corr=", 0) == 0) use_corr = std::atoi(a.c_str() + 7);
        else if (a.rfind("--nmr=", 0) == 0) null_move_reduction = std::atoi(a.c_str() + 6);
        else if (a.rfind("--ttsamegen=", 0) == 0) tt_samegen = std::atoi(a.c_str() + 12);
        else if (a.rfind("--ttshift=", 0) == 0) tt_shift = std::atoi(a.c_str() + 10);
        else if (a.rfind("--qsee=", 0) == 0) qsearch_see_margin = std::atoi(a.c_str() + 8);
        else if (a.rfind("--leafhead=", 0) == 0) use_leaf_eval_head = std::atoi(a.c_str() + 11) != 0;
        else if (a.rfind("--cutstats=", 0) == 0) collect_cutoff_stats = std::atoi(a.c_str() + 11) != 0;
        else if (a.rfind("--ttoff=", 0) == 0) use_tt_flag = std::atoi(a.c_str() + 8) != 0;
        else if (a.rfind("--seeorder=", 0) == 0) use_see_order = std::atoi(a.c_str() + 11) != 0;
        else if (a.rfind("--countermove=", 0) == 0) use_countermove = std::atoi(a.c_str() + 14) != 0;
        else if (a.rfind("--piecehist=", 0) == 0) use_piece_to_hist = std::atoi(a.c_str() + 12) != 0;
        else if (a.rfind("--seeinternal=", 0) == 0) use_see_internal_order = std::atoi(a.c_str() + 14) != 0;
        else if (a.rfind("--caphw=", 0) == 0) capture_hist_div = std::atoi(a.c_str() + 9);
        else if (a.rfind("--improving=", 0) == 0) use_improving_lmr = std::atoi(a.c_str() + 12) != 0;
    }

    xq::SearchEngine engine(L.net);
    engine.collect_cutoff_stats = collect_cutoff_stats;
    engine.use_tt = !use_tt_flag;
    engine.use_see_order = use_see_order;
    engine.use_countermove = use_countermove;
    engine.use_piece_to_hist = use_piece_to_hist;
    engine.use_see_internal_order = use_see_internal_order;
    engine.capture_hist_div = std::max(1, capture_hist_div);
    engine.use_improving_lmr = use_improving_lmr;
    engine.use_singular_extension = use_se;
    engine.use_lmp = use_lmp;
    engine.use_futility = use_futility;
    engine.lmp_max_depth = std::max(0, lmp_max_depth);
    engine.futility_max_depth = std::max(0, futility_max_depth);
    engine.use_cont_hist = use_cont;
    engine.use_dynamic_lmr = use_dlmr;
    engine.use_root_pvs = use_rootpvs;
    engine.use_root_score_ordering = use_rootsort;
    engine.use_partial_root = use_partialroot;
    engine.use_full_aspiration = use_full_aspiration;
    engine.use_iir = use_iir != 0;
    engine.use_capture_hist = use_ch != 0;
    engine.history_decay_divisor = history_decay;
    engine.use_correction_history = use_corr != 0;
    engine.null_move_reduction = null_move_reduction;
    engine.qsearch_see_margin = std::max(0, qsearch_see_margin);
    engine.use_leaf_eval_head = use_leaf_eval_head;
    engine.tt_require_same_gen = tt_samegen != 0;
    engine.set_tt_size_shift(tt_shift);
    engine.lmr_div = lmr_div;
    engine.rebuild_lmr_table();
    engine.position().setup_start_position();

    std::string line;
    while (std::getline(std::cin, line)) {
        const auto tok = split(line);
        if (tok.empty()) continue;
        const std::string& cmd = tok[0];

        if (cmd == "uci") {
            std::cout << "id name XqEngine(se=" << (use_se ? 1 : 0) << ",lmrdiv=" << lmr_div << ",lmp=" << (use_lmp ? 1 : 0) << ",fut=" << (use_futility ? 1 : 0) << ",cont=" << (use_cont ? 1 : 0) << ",dlmr=" << (use_dlmr ? 1 : 0) << ",rpvs=" << (use_rootpvs ? 1 : 0) << ",rootsort=" << (use_rootsort ? 1 : 0) << ",partialroot=" << (use_partialroot ? 1 : 0) << ",asp=" << (use_full_aspiration ? 1 : 0) << ",iir=" << use_iir << ",ch=" << use_ch << ",histdecay=" << history_decay << ",corr=" << use_corr << ",nmr=" << null_move_reduction << ",qsee=" << qsearch_see_margin << ",leafhead=" << (use_leaf_eval_head ? 1 : 0) << ",ttsg=" << tt_samegen << ",tt=" << tt_shift << ")\n";
            std::cout << "id author zhongguoxiangqi\n";
            std::cout << "uciok\n" << std::flush;
        } else if (cmd == "isready") {
            std::cout << "readyok\n" << std::flush;
        } else if (cmd == "ucinewgame") {
            engine.position().setup_start_position();
        } else if (cmd == "setoption") {
            // 忽略(单线程), 只是不报错
        } else if (cmd == "position") {
            size_t i = 1;
            xq::Position pos;
            if (i < tok.size() && tok[i] == "startpos") {
                pos.setup_start_position();
                ++i;
            } else if (i < tok.size() && tok[i] == "fen") {
                ++i;
                std::string fen;
                while (i < tok.size() && tok[i] != "moves") { fen += tok[i] + " "; ++i; }
                if (!pos.set_from_fen(fen)) { pos.setup_start_position(); }
            }
            if (i < tok.size() && tok[i] == "moves") {
                ++i;
                for (; i < tok.size(); ++i) {
                    pos.do_move(parse_uci_move(tok[i]));
                }
            }
            engine.set_position(pos);
            if (collect_cutoff_stats) {
                std::fprintf(stderr, "[diag] position hash=%016llx\n",
                             (unsigned long long)engine.position().hash);
            }
        } else if (cmd == "go") {
            int depth = 0, movetime = 0;
            for (size_t i = 1; i + 1 < tok.size(); i += 2) {
                if (tok[i] == "depth") depth = std::atoi(tok[i + 1].c_str());
                else if (tok[i] == "movetime") movetime = std::atoi(tok[i + 1].c_str());
            }
            if (depth <= 0 && movetime <= 0) depth = 3;  // 兜底, 避免空 go
            const auto result = engine.search_with_score(depth, movetime);
            if (collect_cutoff_stats) engine.dump_cutoff_stats(stderr);
            if (collect_cutoff_stats) engine.reset_cutoff_stats();
            std::cout << "info depth " << engine.last_search_depth()
                      << " score cp " << result.second
                      << " nodes " << engine.last_search_nodes()
                      << " string partialroot " << engine.last_partial_root_moves()
                      << "/" << engine.last_partial_root_total()
                      << " used " << (engine.last_used_partial_root() ? 1 : 0) << "\n";
            std::cout << "bestmove " << to_uci(result.first) << "\n" << std::flush;
        } else if (cmd == "quit") {
            break;
        }
    }
    return 0;
}
