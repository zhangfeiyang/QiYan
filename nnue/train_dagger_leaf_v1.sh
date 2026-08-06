#!/usr/bin/env bash
set -euo pipefail

# 用当前 DAgger 网络在搜索中真正访问到的叶节点做 Pikafish 蒸馏。
# 叶节点约占每轮训练样本 24%；普通 D10 根局面作为独立防退化门禁。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
d10_v1=${D10_V1:-/data/nnue/dagger-train-blend-d10-v1}
d10_v2=${D10_V2:-/data/nnue/dagger-train-blend-d10-v2}
replay_dir=${REPLAY_DIR:-/data/nnue/dagger-train-net328m-d8-v1}
leaf_train=${LEAF_TRAIN:-/data/nnue/eval-leaves-d6-train-v1}
leaf_val=${LEAF_VAL:-/data/nnue/eval-leaves-d6-val-v1}
root_val=${ROOT_VAL:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_leaf_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_leaf_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-1e-5}
seed=${SEED:-26080224}

for d in "$d10_v1" "$d10_v2" "$replay_dir" "$leaf_train" "$leaf_val" "$root_val"; do
  [[ -d "$d" ]] || { echo "数据目录不存在: $d" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$d10_v1" "$d10_v2" "$replay_dir" "$leaf_train" \
  --data-repeat 1 1 3 8 \
  --val-data "$leaf_val" \
  --guard-val-data "$root_val" \
  --guard-max-loss-regression 0.005 \
  --guard-max-mae-regression 0.01 \
  --init "$init_pt" \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric loss \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"
