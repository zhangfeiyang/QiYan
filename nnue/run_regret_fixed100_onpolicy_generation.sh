#!/usr/bin/env bash
set -euo pipefail

# 在生产 C++ 搜索真实走出的 100ms DAgger 轨迹上挖后悔值配对。
# 训练和验证各有四个独立分片；每一波固定四个并发任务，使学生 100ms 搜索
# 面对的 CPU 负载尽量贴近最终四线程对战，而不是在空闲机器上得到更深的标签。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
student_exe=${STUDENT_EXE:-/data/nnue/tools/xq_uci_regret_fixed100_v1}
student_net=${STUDENT_NET:-/home/zhangfy/nnue-nets/net_dagger_blend_v1.nnue}
pika_exe=${PIKA_EXE:-"$repo_dir/third_party/Pikafish/src/pikafish"}
train_src=${TRAIN_SRC:-/data/nnue/dagger-fixed100-train-d10-v1}
val_src=${VAL_SRC:-/data/nnue/dagger-fixed100-val-d10-v1}
train_out=${TRAIN_OUT:-/data/nnue/regret-fixed100-onpolicy-train-v1}
val_out=${VAL_OUT:-/data/nnue/regret-fixed100-onpolicy-val-v1}
log_dir=${LOG_DIR:-/home/zhangfy/nnue-logs}
student_movetime=${STUDENT_MOVETIME:-100}
pika_depth=${PIKA_DEPTH:-10}
min_regret=${MIN_REGRET:-40}
# 设得高于单分片实际条数，mine_regret_pairs.py 会自动取 min，从而不丢样本。
positions_per_shard=${POSITIONS_PER_SHARD:-20000}

train_seed=2026080342
val_seed=2026080343

[[ -f "$student_net" ]] || { echo "学生网络不存在: $student_net" >&2; exit 2; }
[[ -x "$pika_exe" ]] || { echo "Pikafish 不可执行: $pika_exe" >&2; exit 2; }
for shard in 0 1 2 3; do
  [[ -f "$train_src/shard-$train_seed-$shard.bin" ]] || {
    echo "缺少固定用时训练分片 $shard" >&2; exit 2;
  }
  [[ -f "$val_src/shard-$val_seed-$shard.bin" ]] || {
    echo "缺少固定用时验证分片 $shard" >&2; exit 2;
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
  local source=$5
  local -a resume_arg=()
  if [[ -f "$out.config.json" ]]; then
    resume_arg=(--resume)
  elif [[ -e "$out" ]]; then
    echo "配对文件存在但缺少配置，拒绝不安全续跑: $out" >&2
    return 2
  fi
  python3 "$repo_dir/nnue/mine_regret_pairs.py" \
    --data "$source" \
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
    "${resume_arg[@]}" >>"$log" 2>&1
}

wait_wave() {
  local status=0
  local pid
  for pid in "$@"; do
    wait "$pid" || status=1
  done
  return "$status"
}

train_pids=()
for shard in 0 1 2 3; do
  run_job \
    "$train_out/pair-train-$shard.bin" \
    "$positions_per_shard" \
    "$((2026080351 + shard))" \
    "$log_dir/regret_fixed100_onpolicy_train_v1_job$shard.log" \
    "$train_src/shard-$train_seed-$shard.bin" &
  train_pids+=("$!")
done
if ! wait_wave "${train_pids[@]}"; then
  echo "固定用时 on-policy 训练配对采集失败；查看对应 job 日志" >&2
  exit 1
fi

val_pids=()
for shard in 0 1 2 3; do
  run_job \
    "$val_out/pair-val-$shard.bin" \
    "$positions_per_shard" \
    "$((2026080355 + shard))" \
    "$log_dir/regret_fixed100_onpolicy_val_v1_job$shard.log" \
    "$val_src/shard-$val_seed-$shard.bin" &
  val_pids+=("$!")
done
if ! wait_wave "${val_pids[@]}"; then
  echo "固定用时 on-policy 验证配对采集失败；查看对应 job 日志" >&2
  exit 1
fi

echo "固定 100ms on-policy 后悔值数据完成: train=$train_out val=$val_out"
