#!/usr/bin/env bash
set -euo pipefail

# 从当前 DAgger blend checkpoint 微调。默认 4 份新 D10 学生轨迹 + 1 份旧 D8
# 回放；可以用 TRAIN_REPEAT/REPLAY_REPEAT 做配比消融。验证只使用独立的新 D10
# 数据，避免把回放集误当作泛化提升。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
train_dir=${TRAIN_DIR:-/data/nnue/dagger-train-blend-d10-v1}
replay_dir=${REPLAY_DIR:-/data/nnue/dagger-train-net328m-d8-v1}
val_dir=${VAL_DIR:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_d10_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_d10_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-1e-4}
# NumPy RandomState 只接受 32 位无符号种子；数据生成可用长时间码，训练这里
# 必须折叠到该范围内，仍显式记录以保证本实验可复现。
seed=${SEED:-26080205}
train_repeat=${TRAIN_REPEAT:-4}
replay_repeat=${REPLAY_REPEAT:-1}
select_metric=${SELECT_METRIC:-loss}

[[ -d "$train_dir" ]] || { echo "训练集不存在: $train_dir" >&2; exit 2; }
[[ -d "$replay_dir" ]] || { echo "回放集不存在: $replay_dir" >&2; exit 2; }
[[ -d "$val_dir" ]] || { echo "验证集不存在: $val_dir" >&2; exit 2; }
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 nnue/train.py \
  --data "$train_dir" "$replay_dir" \
  --data-repeat "$train_repeat" "$replay_repeat" \
  --val-data "$val_dir" \
  --init "$init_pt" \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric "$select_metric" \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"
