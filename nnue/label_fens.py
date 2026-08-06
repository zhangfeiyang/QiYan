#!/usr/bin/env python3
"""把 FEN 列表交给 Pikafish 打标签, 写成训练用的 72 字节定长记录.

支持两种标签：

* ``search``：历史口径，记录 ``go depth N`` 的搜索分；
* ``static``：记录 Pikafish ``eval`` 的最终静态评估。后者更贴合本项目 NNUE
  在搜索中的职责，不把教师的多层搜索结果硬蒸馏成一次叶评估。

用途: 给 NNUE 补"深处"的训练样本。实测我们的评估在固定深度 3 下与 Pikafish
打平, 到固定深度 8 就落后 132 分(同一套搜索, 唯一变量是评估)——网络在浅处
够用、深处失准。历史训练数据全来自自对弈对局, 局面分布集中在正常中局, 深搜
会走到的那类残局/简化局面严重欠采样。配合 endgame_gen 随机撒子生成的合法
残局, 补上这块分布。

标签深度默认 1: Pikafish 深度 1 已含静止搜索(挂子已解决), 吞吐是深度 6 的
250 倍(7114 vs 28 条/秒/线程)。而项目实测"教师深度在千万级数据上没有效应"
(d12b 纯 d12 vs all 混合, ΔElo -1.4, t=-0.19), 数据量才是已验证的杠杆 ——
同样算力下用浅标签换两个数量级的数据量是划算的。

用法:
    /tmp/endgame_gen 1000000 --seed 1 > /tmp/eg.fen
    python3 nnue/label_fens.py /tmp/eg.fen -o /data/nnue/endgame.bin -j 8 --depth 1
"""
import argparse
import hashlib
import json
import multiprocessing as mp
import os
import re
import struct
import subprocess
import sys
import time

RECORD_BYTES = 72
MAX_PIECES = 32
SCORE_CLAMP = 10000

# FEN 字符 -> pieceIdx, 顺序必须与 nnue/FORMAT.md 的表一致, 改动即作废已有数据
_FEN2IDX = {
    "K": 0, "A": 1, "B": 2, "N": 3, "R": 4, "C": 5, "P": 6,
    "k": 7, "a": 8, "b": 9, "n": 10, "r": 11, "c": 12, "p": 13,
}


def fen_to_pieces(fen):
    """FEN -> ([(pieceIdx, sq)], side_to_move) ; sq = row*9+col, row0 = 黑方底线."""
    parts = fen.split()
    board, stm = parts[0], (parts[1] if len(parts) > 1 else "w")
    out = []
    row = 0
    col = 0
    for ch in board:
        if ch == "/":
            row += 1
            col = 0
        elif ch.isdigit():
            col += int(ch)
        else:
            idx = _FEN2IDX.get(ch)
            if idx is None:
                raise ValueError(f"未知棋子字符 {ch!r}: {fen}")
            out.append((idx, row * 9 + col))
            col += 1
    return out, (0 if stm == "w" else 1)


def pack_record(pieces, stm, score, depth):
    if len(pieces) > MAX_PIECES:
        raise ValueError(f"棋子数 {len(pieces)} 超过 {MAX_PIECES}")
    score = max(-SCORE_CLAMP, min(SCORE_CLAMP, int(score)))
    buf = bytearray(RECORD_BYTES)
    buf[0] = len(pieces)
    buf[1] = stm
    struct.pack_into("<h", buf, 2, score)
    for i, (idx, sq) in enumerate(pieces):
        buf[4 + i * 2] = idx
        buf[4 + i * 2 + 1] = sq
    struct.pack_into("<h", buf, 68, depth)
    return bytes(buf)


_SCORE_RE = re.compile(r"score (cp|mate) (-?\d+)")
_FINAL_EVAL_RE = re.compile(
    r"^Final evaluation\s+([+-]?\d+(?:\.\d+)?) \(white side\)"
)


def parse_static_eval(line, stm):
    """解析 ``eval`` 最终行，并从白方视角转成走子方视角厘兵。"""
    match = _FINAL_EVAL_RE.match(line)
    if not match:
        return None
    white_cp = int(round(float(match.group(1)) * 100.0))
    return white_cp if stm == 0 else -white_cp


def worker(args):
    """一个 Pikafish 进程负责一段 FEN, 返回打包好的记录字节串."""
    exe, fens, depth, label_mode = args
    p = subprocess.Popen([exe], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         text=True, bufsize=1, cwd=os.path.dirname(exe))

    def send(c):
        p.stdin.write(c + "\n")
        p.stdin.flush()

    send("uci")
    while p.stdout.readline().strip() != "uciok":
        pass
    send("setoption name Threads value 1")
    send("isready")
    while p.stdout.readline().strip() != "readyok":
        pass

    out = bytearray()
    skipped = 0
    for fen in fens:
        try:
            pieces, stm = fen_to_pieces(fen)
        except ValueError:
            skipped += 1
            continue
        send(f"position fen {fen}")
        score = None
        if label_mode == "static":
            send("eval")
            while True:
                line = p.stdout.readline()
                if not line:
                    break
                if line.startswith("Final evaluation"):
                    score = parse_static_eval(line, stm)
                    break
        else:
            send(f"go depth {depth}")
            kind = None
            while True:
                line = p.stdout.readline()
                if not line:
                    break
                m = _SCORE_RE.search(line)
                if m:
                    kind, score = m.group(1), int(m.group(2))
                if line.startswith("bestmove"):
                    break
            # 杀棋分统一压到边界: ±30000 量级会把回归目标的尺度整个带偏
            if kind == "mate" and score is not None:
                score = SCORE_CLAMP if score > 0 else -SCORE_CLAMP
        if score is None:
            skipped += 1
            continue
        out += pack_record(pieces, stm, score, 0 if label_mode == "static" else depth)

    send("quit")
    try:
        p.wait(timeout=5)
    except subprocess.TimeoutExpired:
        p.kill()
    return bytes(out), skipped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("fenfile", help="每行一个 FEN")
    ap.add_argument("-o", "--out", required=True, help="输出 .bin")
    ap.add_argument("-j", "--jobs", type=int, default=os.cpu_count() or 4)
    ap.add_argument("--depth", type=int, default=1)
    ap.add_argument("--label-mode", choices=("search", "static"), default="search",
                    help="search=go depth N 搜索分；static=eval 最终静态评估")
    ap.add_argument("--shard", type=int, default=500000,
                    help="每片多少条 FEN; 内存占用和落盘粒度都由它决定")
    ap.add_argument("--append", action="store_true", help="追加而不是覆盖输出文件")
    ap.add_argument("--resume", action="store_true",
                    help="按 .progress.json 断点恢复；每个 shard 落盘并 fsync 后提交进度")
    ap.add_argument("--exe",
                    default="/home/zhangfy/zhongguoxiangqi/third_party/Pikafish/src/pikafish",
                    help="打标签用的 Pikafish (必须是**未打补丁**的原版)")
    a = ap.parse_args()

    if a.append and a.resume:
        raise SystemExit("--append 与 --resume 不能同时使用")
    if a.resume and a.fenfile == "-":
        raise SystemExit("stdin 无法校验输入身份，不支持 --resume")

    def sha256(path):
        h = hashlib.sha256()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(1 << 20)
                if not chunk:
                    break
                h.update(chunk)
        return h.hexdigest()

    config = None
    progress_path = a.out + ".progress.json"
    input_done = total_written = total_skipped = shard_no = 0
    if a.resume:
        config = {
            "version": 1,
            "fenfile": os.path.realpath(a.fenfile),
            "fen_sha256": sha256(a.fenfile),
            "exe": os.path.realpath(a.exe),
            "exe_sha256": sha256(a.exe),
            "depth": a.depth,
            "record_bytes": RECORD_BYTES,
        }
        # search 模式保持历史 v1 配置逐项不变，已完成/中断的旧任务仍可恢复。
        if a.label_mode == "static":
            config["version"] = 2
            config["label_mode"] = "static"
            config["depth"] = 0
        out_exists = os.path.exists(a.out)
        progress_exists = os.path.exists(progress_path)
        if out_exists != progress_exists:
            raise SystemExit("输出文件与进度文件不成对，拒绝猜测恢复点")
        if progress_exists:
            with open(progress_path) as f:
                progress = json.load(f)
            if progress.get("config") != config:
                raise SystemExit("恢复配置与现有 .progress.json 不一致")
            input_done = int(progress["input_done"])
            total_written = int(progress["written"])
            total_skipped = int(progress["skipped"])
            shard_no = int(progress["shards"])
            actual = os.path.getsize(a.out)
            expected = total_written * RECORD_BYTES
            if actual != expected:
                raise SystemExit(
                    f"输出长度与进度不符: {actual} != {expected}，拒绝带病续写"
                )
            print(
                f"[label] 恢复 shard={shard_no} 输入={input_done:,} "
                f"写入={total_written:,} 跳过={total_skipped:,}", flush=True
            )
        else:
            os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
            open(a.out, "xb").close()

    def commit_progress():
        if not a.resume:
            return
        payload = {
            "config": config,
            "input_done": input_done,
            "written": total_written,
            "skipped": total_skipped,
            "shards": shard_no,
        }
        tmp = progress_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(payload, f, sort_keys=True)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, progress_path)

    if a.resume and not os.path.exists(progress_path):
        commit_progress()

    # 分片流式处理: 一千万条 FEN 全读进内存要好几 GB, 而且中途挂掉就全废。
    # 按 shard 读、按 shard 落盘, 内存恒定, 也天然可断点续跑。
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    t0 = time.time()
    fh = open(a.fenfile) if a.fenfile != "-" else sys.stdin

    # 进度按“非空 FEN 行数”计；恢复时以相同口径跳过，允许输入中夹空行。
    skipped_input = 0
    while skipped_input < input_done:
        line = fh.readline()
        if not line:
            raise SystemExit("输入文件比进度记录短")
        if line.strip():
            skipped_input += 1

    mode = "ab" if (a.append or a.resume) else "wb"
    with open(a.out, mode) as fout:
        while True:
            batch = []
            for line in fh:
                line = line.strip()
                if line:
                    batch.append(line)
                if len(batch) >= a.shard:
                    break
            if not batch:
                break
            shard_no += 1
            chunk = (len(batch) + a.jobs - 1) // a.jobs
            tasks = [(a.exe, batch[i:i + chunk], a.depth, a.label_mode)
                     for i in range(0, len(batch), chunk)]
            with mp.Pool(len(tasks)) as pool:
                results = pool.map(worker, tasks)
            for blob, sk in results:
                fout.write(blob)
                total_written += len(blob) // RECORD_BYTES
                total_skipped += sk
            fout.flush()
            os.fsync(fout.fileno())
            input_done += len(batch)
            commit_progress()
            el = time.time() - t0
            print(f"[label] shard {shard_no}: 累计 {total_written:,} 条  "
                  f"{el:.0f}s  {total_written / max(el, 1e-9):.0f} 条/秒", flush=True)

    dt = time.time() - t0
    print(f"[label] 完成 {total_written:,} 条 -> {a.out}  "
          f"耗时 {dt:.0f}s ({total_written / dt:.0f} 条/秒)  跳过 {total_skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
