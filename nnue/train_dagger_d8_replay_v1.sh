#!/usr/bin/env bash
set -euo pipefail

# 候选 B：DAgger + 旧数据 replay。
# replay：约 2546 万旧样本（srv-d10b 前 4 分片，train-subset-25m）。
# DAgger 通过 --data-repeat 提升到约 1/3 批次权重：
#   458,523 × 28 ≈ 12.84M，总 = 25.46M + 12.84M，DAgger 占约 33.5%。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
replay_dir=${REPLAY_DIR:-/data/nnue/train-subset-25m}
dagger_dir=${DAGGER_DIR:-/data/nnue/dagger-train-net328m-d8-v1}
val_dir=${VAL_DIR:-/data/nnue/dagger-val-net328m-d8-v1}
guard_dir=${GUARD_DIR:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_328m.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_d8_replay_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_d8_replay_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-1e-5}
seed=${SEED:-26080212}
replay_repeat=${REPLAY_REPEAT:-1}
dagger_repeat=${DAGGER_REPEAT:-28}

for d in "$replay_dir" "$dagger_dir" "$val_dir" "$guard_dir"; do
  [[ -d "$d" ]] || { echo "数据目录不存在: $d" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$replay_dir" "$dagger_dir" \
  --data-repeat "$replay_repeat" "$dagger_repeat" \
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
