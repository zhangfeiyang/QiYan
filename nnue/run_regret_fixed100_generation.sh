#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
student_exe=${STUDENT_EXE:-/data/nnue/tools/xq_uci_regret_fixed100_v1}
student_net=${STUDENT_NET:-/home/zhangfy/nnue-nets/net_dagger_blend_v1.nnue}
pika_exe=${PIKA_EXE:-"$repo_dir/third_party/Pikafish/src/pikafish"}
train_src=${TRAIN_SRC:-/data/nnue/dagger-train-blend-d10-v1}
val_src=${VAL_SRC:-/data/nnue/dagger-val-blend-d10-v1}
train_out=${TRAIN_OUT:-/data/nnue/regret-fixed100-train-v1}
val_out=${VAL_OUT:-/data/nnue/regret-fixed100-val-v1}
log_dir=${LOG_DIR:-/home/zhangfy/nnue-logs}
student_movetime=${STUDENT_MOVETIME:-100}
pika_depth=${PIKA_DEPTH:-10}
min_regret=${MIN_REGRET:-40}
train_positions=${TRAIN_POSITIONS_PER_JOB:-10000}
val_positions=${VAL_POSITIONS:-6000}

[[ -f "$student_net" ]] || { echo "学生网络不存在: $student_net" >&2; exit 2; }
[[ -x "$pika_exe" ]] || { echo "Pikafish 不可执行: $pika_exe" >&2; exit 2; }
for shard in 0 1 2 3 4 5; do
  [[ -f "$train_src/shard-2026080203001-$shard.bin" ]] || {
    echo "缺少训练分片 $shard" >&2; exit 2;
  }
done
for shard in 0 1; do
  [[ -f "$val_src/shard-2026080204001-$shard.bin" ]] || {
    echo "缺少验证分片 $shard" >&2; exit 2;
  }
done

if [[ ! -x "$student_exe" ]]; then
  mkdir -p "$(dirname "$student_exe")"
  bash "$repo_dir/app/src/main/cpp/build_host_uci.sh" "$student_exe"
fi
mkdir -p "$train_out" "$val_out" "$log_dir"

run_job() {
  local out=$1
  local positions=$2
  local seed=$3
  local log=$4
  shift 4
  local -a resume_arg=()
  [[ -f "$out.config.json" ]] && resume_arg=(--resume)
  python3 "$repo_dir/nnue/mine_regret_pairs.py" \
    --data "$@" \
    --out "$out" \
    --positions "$positions" \
    --seed "$seed" \
    --student-exe "$student_exe" \
    --student-net "$student_net" \
    --student-movetime "$student_movetime" \
    --pika-exe "$pika_exe" \
    --pika-depth "$pika_depth" \
    --pika-hash 64 \
    --min-regret "$min_regret" \
    --progress-every 100 \
    "${resume_arg[@]}" >"$log" 2>&1
}

run_job "$train_out/pair-train-0.bin" "$train_positions" 2026080331 \
  "$log_dir/regret_fixed100_train_v1_job0.log" \
  "$train_src/shard-2026080203001-0.bin" \
  "$train_src/shard-2026080203001-1.bin" &
pid0=$!
run_job "$train_out/pair-train-1.bin" "$train_positions" 2026080332 \
  "$log_dir/regret_fixed100_train_v1_job1.log" \
  "$train_src/shard-2026080203001-2.bin" \
  "$train_src/shard-2026080203001-3.bin" &
pid1=$!
run_job "$train_out/pair-train-2.bin" "$train_positions" 2026080333 \
  "$log_dir/regret_fixed100_train_v1_job2.log" \
  "$train_src/shard-2026080203001-4.bin" \
  "$train_src/shard-2026080203001-5.bin" &
pid2=$!
run_job "$val_out/pair-val.bin" "$val_positions" 2026080334 \
  "$log_dir/regret_fixed100_val_v1.log" \
  "$val_src/shard-2026080204001-0.bin" \
  "$val_src/shard-2026080204001-1.bin" &
pid3=$!

status=0
for pid in "$pid0" "$pid1" "$pid2" "$pid3"; do
  wait "$pid" || status=1
done
if (( status != 0 )); then
  echo "至少一个后悔值采集任务失败；查看 $log_dir/regret_fixed100_*_v1*.log" >&2
  exit "$status"
fi

echo "固定 100ms 后悔值数据完成: train=$train_out val=$val_out"
