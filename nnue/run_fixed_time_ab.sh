#!/usr/bin/env bash
set -euo pipefail

# 当前基线与下一候选网络分别在相同固定用时下对 Pikafish。每个开局四盘：
# 两个网络各执红、执黑一次。结果逐盘 checkpoint，同一命令可在重启后续跑。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
pika_exe=${PIKA_EXE:-"$repo_dir/third_party/Pikafish/src/pikafish"}
baseline_net=${BASELINE_NET:-"${HOME}/nnue-nets/net_dagger_blend_v1.nnue"}
candidate_net=${CANDIDATE_NET:?请显式指定下一候选网络：CANDIDATE_NET=/path/to/candidate.nnue}
output_tsv=${OUTPUT_TSV:-"${HOME}/nnue-logs/fixed_time_ab_2s.tsv"}
pairs=${PAIRS:-100}
movetime_ms=${MOVETIME_MS:-2000}
threads=${THREADS:-4}
opening_plies=${OPENING_PLIES:-6}
max_plies=${MAX_PLIES:-200}
seed=${SEED:-20260730}

[[ -x "$pika_exe" ]] || { echo "Pikafish 不可执行: $pika_exe" >&2; exit 2; }
[[ -f "$baseline_net" ]] || { echo "基线网络不存在: $baseline_net" >&2; exit 2; }
[[ -f "$candidate_net" ]] || { echo "候选网络不存在: $candidate_net" >&2; exit 2; }
mkdir -p "$(dirname "$output_tsv")"

cd "$repo_dir"
exec ./gradlew :app:testDebugUnitTest --tests "*PikafishMatchRunner*" \
  -Dpika.match=1 \
  -Dpika.exe="$pika_exe" \
  -Dpika.pairs="$pairs" \
  -Dpika.movetime="$movetime_ms" \
  -Dpika.threads="$threads" \
  -Dpika.openingPlies="$opening_plies" \
  -Dpika.maxPlies="$max_plies" \
  -Dpika.seed="$seed" \
  -Dpika.baselineName=dagger_blend_v1 \
  -Dpika.baselineNet="$baseline_net" \
  -Dpika.candidateName=candidate \
  -Dpika.candidateNet="$candidate_net" \
  -Dpika.dump="$output_tsv" \
  -Dpika.checkpoint="$output_tsv.checkpoint" \
  --no-daemon --offline --rerun-tasks -i
