#!/usr/bin/env bash
set -euo pipefail

# 结构消融：旧 DAgger 主干完全冻结，只训练零初始化的线性 PSQT 残差。
# 数据配比沿用已验证的 d10-v1 + d10-v2 + 3x d8 replay，避免把轨迹分布变化
# 误判为结构收益。通过固定用时门禁后，才考虑主干与 PSQT 联合微调。
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
d10_v1=${D10_V1:-/data/nnue/dagger-train-blend-d10-v1}
d10_v2=${D10_V2:-/data/nnue/dagger-train-blend-d10-v2}
replay_dir=${REPLAY_DIR:-/data/nnue/dagger-train-net328m-d8-v1}
val_dir=${VAL_DIR:-/data/nnue/dagger-val-blend-d10-v1}
init_pt=${INIT_PT:-"$repo_dir/nnue/net_dagger_blend_v1.pt"}
out_pt=${OUT_PT:-"$repo_dir/nnue/net_dagger_psqt_v1.pt"}
out_nnue=${OUT_NNUE:-"${HOME}/nnue-nets/net_dagger_psqt_v1.nnue"}
epochs=${EPOCHS:-20}
lr=${LR:-3e-4}
seed=${SEED:-26080211}

for d in "$d10_v1" "$d10_v2" "$replay_dir" "$val_dir"; do
  [[ -d "$d" ]] || { echo "数据目录不存在: $d" >&2; exit 2; }
done
[[ -f "$init_pt" ]] || { echo "初始化 checkpoint 不存在: $init_pt" >&2; exit 2; }
[[ ! -e "$out_pt" ]] || { echo "拒绝覆盖已有 checkpoint: $out_pt" >&2; exit 2; }
[[ ! -e "$out_nnue" ]] || { echo "拒绝覆盖已有网络: $out_nnue" >&2; exit 2; }

cd "$repo_dir"
python3 -u nnue/train.py \
  --data "$d10_v1" "$d10_v2" "$replay_dir" \
  --data-repeat 1 1 3 \
  --val-data "$val_dir" \
  --init "$init_pt" \
  --psqt \
  --freeze-trunk \
  --out "$out_pt" \
  --epochs "$epochs" \
  --lr "$lr" \
  --batch 8192 \
  --workers 4 \
  --select-metric loss \
  --seed "$seed"
python3 nnue/export.py --ckpt "$out_pt" --out "$out_nnue"
