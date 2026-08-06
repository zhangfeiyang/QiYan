#!/usr/bin/env bash
set -euo pipefail

# 候选 A：纯 DAgger 微调。
# 数据：net328m 学生轨迹 + Pikafish depth=8 标签（458,523 条）。
# 起点：生成这批数据所用的 net328m 权重；验证只用独立留出集。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
train_dir=${TRAIN_DIR:-/data/nnue/dagger-train-net328m-d8-v1}
val_dir=${VAL_DIR:-/data/nnue/dagger-val-net328m-d8-v1}
guard_dir=${GUARD_DIR:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_328m.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_d8_pure_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_d8_pure_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-1e-5}
seed=${SEED:-26080211}

for d in "$train_dir" "$val_dir" "$guard_dir"; do
  [[ -d "$d" ]] || { echo "数据目录不存在: $d" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$train_dir" \
  --val-data "$val_dir" \
  --guard-val-data "$guard_dir" \
  --init "$init_pt" \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric loss \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"
