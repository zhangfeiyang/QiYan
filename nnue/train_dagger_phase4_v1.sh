#!/usr/bin/env bash
set -euo pipefail

# 第一轮结构消融：只把单输出栈改成 4 个阶段输出栈，数据、配比、学习率、
# seed 全部沿用 dagger_d10_v1，确保固定用时对战中的差异只来自阶段专门化。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
train_dir=${TRAIN_DIR:-/data/nnue/dagger-train-blend-d10-v1}
replay_dir=${REPLAY_DIR:-/data/nnue/dagger-train-net328m-d8-v1}
val_dir=${VAL_DIR:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_phase4_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_phase4_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-1e-4}
seed=${SEED:-26080205}

for d in "$train_dir" "$replay_dir" "$val_dir"; do
  [[ -d "$d" ]] || { echo "数据目录不存在: $d" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$train_dir" "$replay_dir" \
  --data-repeat 4 1 \
  --val-data "$val_dir" \
  --init "$init_pt" \
  --phase-buckets 4 \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric loss \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"
