#!/usr/bin/env bash
set -euo pipefail

# 用当前 DAgger 基线走出它实际会到达的局面，再让 PikaFish depth 10 只做标签。
# SPLIT=train / val 采用不同目录和种子，保证验证集不泄漏到训练集中。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
split=${SPLIT:-train}
actor_net=${ACTOR_NET:-"${HOME}/nnue-nets/net_dagger_blend_v1.nnue"}
pika_exe=${PIKA_EXE:-"$repo_dir/third_party/Pikafish/src/pikafish"}
label_depth=${LABEL_DEPTH:-10}
actor_depth=${ACTOR_DEPTH:-3}
actor_movetime=${ACTOR_MOVETIME:-0}

case "$split" in
  train)
    output_dir=${OUTPUT_DIR:-/data/nnue/dagger-train-blend-d10-v1}
    games=${GAMES:-6000}
    threads=${THREADS:-6}
    seed=${SEED:-2026080203001}
    ;;
  val)
    output_dir=${OUTPUT_DIR:-/data/nnue/dagger-val-blend-d10-v1}
    games=${GAMES:-1000}
    threads=${THREADS:-2}
    seed=${SEED:-2026080204001}
    ;;
  *)
    echo "SPLIT 只能是 train 或 val: $split" >&2
    exit 2
    ;;
esac

[[ -f "$actor_net" ]] || { echo "演员网络不存在: $actor_net" >&2; exit 2; }
[[ -x "$pika_exe" ]] || { echo "Pikafish 不可执行: $pika_exe" >&2; exit 2; }
[[ ! -e "$output_dir" ]] || { echo "输出目录已存在，为防重复数据拒绝写入: $output_dir" >&2; exit 2; }

cd "$repo_dir"
exec ./gradlew :app:testDebugUnitTest --tests "*DataGenRunner*" \
  -Dnnue.run=1 \
  -Dnnue.out="$output_dir" \
  -Dnnue.games="$games" \
  -Dnnue.threads="$threads" \
  -Dnnue.seed="$seed" \
  -Dnnue.depth="$actor_depth" \
  -Dnnue.movetime="$actor_movetime" \
  -Dnnue.actor=student \
  -Dnnue.teacherNet="$actor_net" \
  -Dnnue.uciTeacher="$pika_exe" \
  -Dnnue.uciDepth="$label_depth" \
  -Dnnue.uciHash=32 \
  --no-daemon --offline --rerun-tasks -i
