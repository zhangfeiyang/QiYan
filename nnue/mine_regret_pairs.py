#!/usr/bin/env python3
"""从现有 DAgger 局面中挖掘 PikaFish 高后悔值着法对。

对每个抽中的根局面：

1. 当前 DAgger 引擎按最终验收的固定用时给出学生着；
2. PikaFish 正常搜索给出教师最佳着与分数；
3. 若着法不同，再用 ``go searchmoves <学生着>`` 得到学生着的教师分数；
4. 分差达到阈值时，写出“教师着后子局面 + 学生着后子局面”。

输出每对 144 字节，由两个既有 72 字节 NNUE 记录拼接。进度逐局写 journal；
``--resume`` 会按 journal 截掉尚未提交的尾部，电脑重启后可以安全续跑。
"""
import argparse
import bisect
import hashlib
import json
import mmap
import os
import random
import re
import select
import struct
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

RECORD_BYTES = 72
PAIR_RECORD_BYTES = RECORD_BYTES * 2
MAX_PIECES = 32
SCORE_CAP = 10_000
MATE_PLY_CAP = 200

PIECE_FEN = (
    "K", "A", "B", "N", "R", "C", "P",
    "k", "a", "b", "n", "r", "c", "p",
)
MOVE_RE = re.compile(r"^[a-i][0-9][a-i][0-9]$")
SCORE_RE = re.compile(r"(?:^| )score (cp|mate) (-?\d+)(?: |$)")


def build_go_command(depth, movetime_ms=0, searchmove=None):
    """构造 UCI go；searchmoves 必须位于最后，避免 Pikafish 吞掉时间参数。"""
    if movetime_ms > 0:
        command = f"go movetime {movetime_ms}"
    elif depth > 0:
        command = f"go depth {depth}"
    else:
        raise ValueError("depth 与 movetime_ms 至少一个必须为正数")
    if searchmove is not None:
        command += f" searchmoves {searchmove}"
    return command


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            block = f.read(1024 * 1024)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


def discover_shards(inputs):
    paths = []
    for value in inputs:
        p = Path(value).expanduser().resolve()
        if p.is_dir():
            paths.extend(sorted(p.glob("shard-*.bin")))
        elif p.is_file():
            paths.append(p)
        else:
            raise FileNotFoundError(f"数据路径不存在: {p}")
    paths = [p for p in paths if p.stat().st_size >= RECORD_BYTES]
    if not paths:
        raise RuntimeError("没有可用的 shard-*.bin")
    for p in paths:
        if p.stat().st_size % RECORD_BYTES:
            print(f"[mine] 警告: {p} 尾部有不完整记录，将忽略", file=sys.stderr)
    return paths


def parse_record(raw):
    if len(raw) != RECORD_BYTES:
        raise ValueError("记录长度错误")
    count = raw[0]
    stm = raw[1]
    if count < 2 or count > MAX_PIECES or stm not in (0, 1):
        raise ValueError(f"记录头不合法: count={count} stm={stm}")
    board = [-1] * 90
    for i in range(count):
        piece = raw[4 + i * 2]
        square = raw[5 + i * 2]
        if piece >= len(PIECE_FEN) or square >= 90 or board[square] >= 0:
            raise ValueError(f"子力记录不合法: piece={piece} square={square}")
        board[square] = piece
    if 0 not in board or 7 not in board:
        raise ValueError("局面缺少将/帅")
    return board, stm


def board_to_fen(board, stm):
    rows = []
    for row in range(10):
        text = []
        empty = 0
        for col in range(9):
            piece = board[row * 9 + col]
            if piece < 0:
                empty += 1
            else:
                if empty:
                    text.append(str(empty))
                    empty = 0
                text.append(PIECE_FEN[piece])
        if empty:
            text.append(str(empty))
        rows.append("".join(text))
    return "/".join(rows) + (" w - - 0 1" if stm == 0 else " b - - 0 1")


def uci_square(text):
    if len(text) != 2 or text[0] < "a" or text[0] > "i" or not text[1].isdigit():
        raise ValueError(f"UCI 格子不合法: {text}")
    return (9 - int(text[1])) * 9 + ord(text[0]) - ord("a")


def apply_uci_move(board, stm, move):
    if not MOVE_RE.match(move):
        raise ValueError(f"UCI 着法不合法: {move}")
    source, target = uci_square(move[:2]), uci_square(move[2:])
    piece = board[source]
    if piece < 0:
        raise ValueError(f"起点没有棋子: {move}")
    if (piece >= 7) != (stm == 1):
        raise ValueError(f"起点不是走子方棋子: {move}")
    captured = board[target]
    if captured >= 0 and (captured >= 7) == (piece >= 7):
        raise ValueError(f"目标格是己方棋子: {move}")
    child = list(board)
    child[source] = -1
    child[target] = piece
    return child, 1 - stm


def pack_record(board, stm, score, depth):
    pieces = [(piece, square) for square, piece in enumerate(board) if piece >= 0]
    if len(pieces) > MAX_PIECES:
        raise ValueError(f"棋子数超过 {MAX_PIECES}")
    out = bytearray(RECORD_BYTES)
    out[0] = len(pieces)
    out[1] = stm
    struct.pack_into("<h", out, 2, max(-SCORE_CAP, min(SCORE_CAP, int(score))))
    for i, (piece, square) in enumerate(pieces):
        out[4 + i * 2] = piece
        out[5 + i * 2] = square
    struct.pack_into("<h", out, 68, max(0, min(32767, int(depth))))
    return bytes(out)


def parse_score(line):
    match = SCORE_RE.search(line)
    if not match:
        return None
    kind, value = match.group(1), int(match.group(2))
    if kind == "cp":
        return max(-SCORE_CAP, min(SCORE_CAP, value))
    magnitude = SCORE_CAP - min(abs(value), MATE_PLY_CAP)
    return magnitude if value >= 0 else -magnitude


class UciClient:
    def __init__(self, command, options=None, timeout=60.0, need_score=True):
        self.command = [str(x) for x in command]
        self.options = dict(options or {})
        self.timeout = timeout
        self.need_score = need_score
        self.proc = None
        self.read_buffer = bytearray()
        self.start()

    def send(self, line):
        if self.proc is None or self.proc.stdin is None:
            raise RuntimeError("UCI 进程未启动")
        self.proc.stdin.write((line + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def read_line(self, deadline):
        if self.proc is None or self.proc.stdout is None:
            raise RuntimeError("UCI 进程未启动")
        # 不能在 TextIOWrapper.readline() 前直接 select：wrapper 可能已经把后续
        # 多行读进自己的用户态缓冲，此时 fd 上没有新字节，select 会误判超时。
        # 用无缓冲二进制管道和自己的逐行缓冲，握手/搜索输出都不会丢唤醒。
        while b"\n" not in self.read_buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([self.proc.stdout], [], [], remaining)[0]:
                raise TimeoutError(f"UCI 查询超时: {' '.join(self.command)}")
            chunk = os.read(self.proc.stdout.fileno(), 4096)
            if not chunk:
                raise RuntimeError(
                    f"UCI 进程退出 code={self.proc.poll()}: {' '.join(self.command)}"
                )
            self.read_buffer.extend(chunk)
        line, _, rest = self.read_buffer.partition(b"\n")
        self.read_buffer = bytearray(rest)
        return line.decode("utf-8", errors="replace").strip()

    def wait_for(self, token):
        deadline = time.monotonic() + self.timeout
        while True:
            if self.read_line(deadline) == token:
                return

    def start(self):
        self.close()
        exe = os.path.realpath(self.command[0])
        self.proc = subprocess.Popen(
            self.command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
            cwd=os.path.dirname(exe),
        )
        self.read_buffer.clear()
        self.send("uci")
        self.wait_for("uciok")
        for name, value in self.options.items():
            self.send(f"setoption name {name} value {value}")
        self.send("isready")
        self.wait_for("readyok")

    def query(self, fen, depth=0, searchmove=None, movetime_ms=0):
        self.send("position fen " + fen)
        # Stockfish/Pikafish 的 parser 在遇到 searchmoves 后会把**后续所有 token**
        # 都当成着法，因此它必须放在最后。写成
        # ``go searchmoves a0a1 depth 8`` 会把 depth/8 吞成着法并丢失搜索上限，
        # 表现为单核无限搜索。
        self.send(build_go_command(depth, movetime_ms, searchmove))
        deadline = time.monotonic() + self.timeout
        score = None
        while True:
            line = self.read_line(deadline)
            if line.startswith("info") and " pv " in (" " + line + " "):
                parsed = parse_score(line)
                if parsed is not None:
                    score = parsed
            if not line.startswith("bestmove"):
                continue
            move = line.split()[1] if len(line.split()) >= 2 else ""
            if not MOVE_RE.match(move):
                raise RuntimeError(f"UCI 未返回有效 bestmove: {line}")
            if self.need_score and score is None:
                raise RuntimeError(f"UCI 未返回带 PV 的 score: {line}")
            return move, score

    def close(self):
        p = self.proc
        self.proc = None
        if p is None:
            return
        try:
            if p.stdin:
                p.stdin.write(b"quit\n")
                p.stdin.flush()
            p.wait(timeout=2)
        except Exception:
            p.kill()
            try:
                p.wait(timeout=2)
            except Exception:
                pass


def atomic_write_json(path, value):
    tmp = str(path) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def load_journal(path):
    if not os.path.exists(path):
        return [], 0
    entries = []
    valid_bytes = 0
    with open(path, "rb") as f:
        while True:
            line = f.readline()
            if not line:
                break
            if not line.endswith(b"\n"):
                break
            try:
                entries.append(json.loads(line))
            except json.JSONDecodeError:
                break
            valid_bytes = f.tell()
    return entries, valid_bytes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", nargs="+", required=True, help="DAgger 数据目录或 shard 文件")
    ap.add_argument("--out", required=True, help="输出配对 .bin")
    ap.add_argument("--positions", type=int, default=10_000, help="随机抽取多少个根局面")
    ap.add_argument("--seed", type=int, default=20260803)
    ap.add_argument("--student-exe", default="/tmp/xq_uci")
    ap.add_argument("--student-net", required=True)
    ap.add_argument("--student-depth", type=int, default=3)
    ap.add_argument("--student-movetime", type=int, default=0,
                    help="学生每局面固定用时（毫秒）；>0 时覆盖 student-depth")
    ap.add_argument("--pika-exe", required=True)
    ap.add_argument("--pika-depth", type=int, default=8)
    ap.add_argument("--pika-hash", type=int, default=64)
    ap.add_argument("--min-regret", type=int, default=80,
                    help="教师最佳着与学生着至少相差多少厘兵才写入")
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--progress-every", type=int, default=25)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    if (args.positions <= 0 or args.pika_depth <= 0 or args.student_movetime < 0 or
            (args.student_movetime == 0 and args.student_depth <= 0)):
        raise ValueError("positions/pika-depth 必须为正，学生 depth 或 movetime 必须有效")
    if args.min_regret < 0:
        raise ValueError("min-regret 不能为负")

    shards = discover_shards(args.data)
    counts = [p.stat().st_size // RECORD_BYTES for p in shards]
    offsets = [0]
    for count in counts:
        offsets.append(offsets[-1] + count)
    total = offsets[-1]
    selected_n = min(args.positions, total)
    selected = random.Random(args.seed).sample(range(total), selected_n)

    student_exe = Path(args.student_exe).expanduser().resolve()
    student_net = Path(args.student_net).expanduser().resolve()
    pika_exe = Path(args.pika_exe).expanduser().resolve()
    for p in (student_exe, student_net, pika_exe):
        if not p.is_file():
            raise FileNotFoundError(p)
    if not os.access(student_exe, os.X_OK) or not os.access(pika_exe, os.X_OK):
        raise PermissionError("student-exe 与 pika-exe 必须可执行")

    config = {
        "version": 2 if args.student_movetime > 0 else 1,
        "sources": [
            {"path": str(p), "size": p.stat().st_size, "mtime_ns": p.stat().st_mtime_ns}
            for p in shards
        ],
        "positions": selected_n,
        "seed": args.seed,
        "student_exe": str(student_exe),
        "student_exe_sha256": sha256_file(student_exe),
        "student_net": str(student_net),
        "student_net_sha256": sha256_file(student_net),
        "student_depth": args.student_depth,
        "pika_exe": str(pika_exe),
        "pika_exe_sha256": sha256_file(pika_exe),
        "pika_depth": args.pika_depth,
        "pika_hash": args.pika_hash,
        "min_regret": args.min_regret,
    }
    if args.student_movetime > 0:
        config["student_movetime"] = args.student_movetime
    pika_net = pika_exe.parent / "pikafish.nnue"
    if pika_net.is_file():
        config["pika_net"] = str(pika_net)
        config["pika_net_sha256"] = sha256_file(pika_net)

    out = Path(args.out).expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    config_path = Path(str(out) + ".config.json")
    journal_path = Path(str(out) + ".progress.jsonl")
    existing = [p for p in (out, config_path, journal_path) if p.exists()]
    if existing and not args.resume:
        raise FileExistsError(
            "输出/断点已存在；用 --resume 续跑或换路径: " + ", ".join(map(str, existing))
        )

    if args.resume:
        if not config_path.is_file():
            raise FileNotFoundError(f"缺少断点配置: {config_path}")
        old_config = json.loads(config_path.read_text(encoding="utf-8"))
        if old_config != config:
            raise RuntimeError("断点配置与本次参数或数据文件不一致，拒绝混用")
    else:
        atomic_write_json(config_path, config)

    entries, journal_bytes = load_journal(journal_path)
    if len(entries) > selected_n:
        raise RuntimeError("journal 条目多于抽样局面数")
    if journal_path.exists() and journal_path.stat().st_size != journal_bytes:
        with open(journal_path, "r+b") as f:
            f.truncate(journal_bytes)
    pair_count = sum(entry.get("status") == "pair" for entry in entries)
    expected_bytes = pair_count * PAIR_RECORD_BYTES
    if out.exists() and out.stat().st_size < expected_bytes:
        raise RuntimeError("配对文件短于 journal 已提交长度，无法安全恢复")
    with open(out, "a+b") as f:
        f.truncate(expected_bytes)

    maps = []
    files = []
    for path in shards:
        f = open(path, "rb")
        files.append(f)
        maps.append(mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ))

    def raw_at(global_index):
        shard = bisect.bisect_right(offsets, global_index) - 1
        local = global_index - offsets[shard]
        start = local * RECORD_BYTES
        return maps[shard][start:start + RECORD_BYTES], shard, local

    def start_clients():
        student = UciClient(
            [str(student_exe), str(student_net)], timeout=args.timeout, need_score=False
        )
        pika = UciClient(
            [str(pika_exe)],
            options={"Threads": "1", "Hash": str(args.pika_hash)},
            timeout=args.timeout,
            need_score=True,
        )
        return student, pika

    stats = Counter(entry.get("status", "unknown") for entry in entries)
    t0 = time.time()
    student = pika = None
    try:
        student, pika = start_clients()
        mode = "ab" if args.resume else "wb"
        with open(out, mode) as pair_out, open(journal_path, "ab") as journal:
            if args.resume:
                pair_out.seek(0, os.SEEK_END)
            for done, global_index in enumerate(selected[len(entries):], start=len(entries) + 1):
                result = None
                for attempt in range(2):
                    try:
                        raw, shard_no, local = raw_at(global_index)
                        board, stm = parse_record(raw)
                        fen = board_to_fen(board, stm)
                        actor_move, _ = student.query(
                            fen, args.student_depth, movetime_ms=args.student_movetime
                        )
                        teacher_move, teacher_score = pika.query(fen, args.pika_depth)
                        if actor_move == teacher_move:
                            result = {"status": "agreement"}
                        else:
                            forced_move, actor_score = pika.query(
                                fen, args.pika_depth, searchmove=actor_move
                            )
                            if forced_move != actor_move:
                                raise RuntimeError(
                                    f"searchmoves 返回了其它着法: {actor_move} -> {forced_move}"
                                )
                            regret = teacher_score - actor_score
                            # 着法不同但固定深度分数可能恰好相同；这种样本没有
                            # 排序方向，不能写成 preferred/rejected。正式阈值通常
                            # 更高，但 min-regret=0 的烟测也必须保持格式不变量。
                            if regret <= 0 or regret < args.min_regret:
                                result = {
                                    "status": "low_regret", "actor": actor_move,
                                    "teacher": teacher_move, "regret": regret,
                                }
                            else:
                                good_board, child_stm = apply_uci_move(board, stm, teacher_move)
                                bad_board, bad_stm = apply_uci_move(board, stm, actor_move)
                                if child_stm != bad_stm:
                                    raise AssertionError("两个子局面走子方不一致")
                                # Pika 分数是根局面走子方视角；子局面轮到对手走，必须翻号。
                                blob = (
                                    pack_record(good_board, child_stm, -teacher_score, args.pika_depth)
                                    + pack_record(bad_board, bad_stm, -actor_score, args.pika_depth)
                                )
                                pair_out.write(blob)
                                pair_out.flush()
                                os.fsync(pair_out.fileno())
                                result = {
                                    "status": "pair", "actor": actor_move,
                                    "teacher": teacher_move, "teacher_score": teacher_score,
                                    "actor_score": actor_score, "regret": regret,
                                }
                        result.update({"global": global_index, "shard": shard_no, "local": local})
                        break
                    except Exception as exc:
                        if attempt == 0:
                            if student is not None:
                                student.close()
                            if pika is not None:
                                pika.close()
                            student, pika = start_clients()
                            continue
                        result = {
                            "status": "error", "global": global_index,
                            "error": f"{type(exc).__name__}: {exc}"[:500],
                        }

                encoded = (json.dumps(result, ensure_ascii=False, sort_keys=True) + "\n").encode()
                journal.write(encoded)
                journal.flush()
                os.fsync(journal.fileno())
                stats[result["status"]] += 1

                if done % args.progress_every == 0 or done == selected_n:
                    elapsed = time.time() - t0
                    fresh = done - len(entries)
                    rate = fresh / max(elapsed, 1e-9)
                    eta = (selected_n - done) / max(rate, 1e-9)
                    print(
                        f"[mine] {done}/{selected_n} pair={stats['pair']} "
                        f"agree={stats['agreement']} low={stats['low_regret']} "
                        f"error={stats['error']} {rate:.2f}局面/秒 ETA={eta:.0f}s",
                        flush=True,
                    )
    finally:
        if student is not None:
            student.close()
        if pika is not None:
            pika.close()
        for mm in maps:
            mm.close()
        for f in files:
            f.close()

    print(
        f"[mine] 完成: 抽样={selected_n} 配对={stats['pair']} "
        f"一致={stats['agreement']} 低后悔={stats['low_regret']} 错误={stats['error']} -> {out}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
