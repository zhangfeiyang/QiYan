#!/usr/bin/env bash
set -euo pipefail

# 双职责输出头实验：
#   head0 = DAgger 根/常规搜索评估，加载后永久冻结；
#   head1 = 固定 100ms 搜索叶节点上的 Pikafish 静态评估，只训练这一头。
#
# 四头 v4 文件格式在这里被复用为多职责容器；运行时只有显式打开 leaf-head
# 开关才会在 qsearch 使用 head1，默认的按子力阶段选择语义不受影响。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
train_data=${TRAIN_DATA:-/data/nnue/eval-leaves-fixed100-static-train-v1}
val_data=${VAL_DATA:-/data/nnue/eval-leaves-fixed100-static-val-v1}
guard_val=${GUARD_VAL:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_leaf_head_fixed100_v1.pt"}
out_nnue=${OUT_NNUE:-/home/zhangfy/nnue-nets/net_dagger_leaf_head_fixed100_v1.nnue}
epochs=${EPOCHS:-20}
lr=${LR:-5e-4}
seed=${SEED:-26080367}

for dir in "$train_data" "$val_data" "$guard_val"; do
  [[ -d "$dir" ]] || { echo "数据目录不存在: $dir" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$train_data" \
  --val-data "$val_data" \
  --guard-val-data "$guard_val" \
  --guard-max-loss-regression 0 \
  --guard-max-mae-regression 0 \
  --init "$init_pt" \
  --out "$out_pt" \
  --phase-buckets 4 \
  --train-head 1 \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric mae_cp \
  --loss-mode hybrid_mae \
  --hybrid-mae-weight 0.10 \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"
