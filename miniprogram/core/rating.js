/**
 * Glicko-2 评分 + 段位, 逐条对应 Android 的 RatingSystem.kt / UserProfile.kt。
 *
 * 之前小程序是"赢 +12 / 输 -8"的固定加减分, 和 App 显示的完全不是一个量表:
 * 同一个人在两端下同样多的棋会看到两个不同的分和段位。这里换成同一套
 * Glicko-2, 起始 1500 / RD 350 / σ 0.06, 段位切分点也读同一份共享配置。
 *
 * 参考: http://www.glicko.net/glicko/glicko2.pdf
 */
const { userRanks } = require('../generated/product-config');

const SCALE = 173.7178; // 评分缩放因子
const TAU = 0.5; // 系统常量(控制波动率变化)
const PI_SQUARED = Math.PI * Math.PI;

const DEFAULT_RATING = 1500;
const DEFAULT_RD = 350;
const DEFAULT_SIGMA = 0.06;

const RESULT = { WIN: 1, DRAW: 0.5, LOSS: 0 };

const g = (phi) => 1 / Math.sqrt(1 + (3 * phi * phi) / PI_SQUARED);

const E = (mu, muJ, phiJ) => 1 / (1 + Math.exp(-g(phiJ) * (mu - muJ)));

/**
 * Glicko-2 波动率方程:
 * f(x) = eˣ(Δ² − φ² − v − eˣ) / (2(φ² + v + eˣ)²) − (x − a) / τ²
 *
 * 其中 a = ln(σ²) —— 是玩家当前波动率的对数, 不是 ln(τ²)。
 */
function f(x, deltaSq, v, phi, tau, a) {
  const ex = Math.exp(x);
  const denom = phi * phi + v + ex;
  return (ex * (deltaSq - phi * phi - v - ex)) / (2 * denom * denom) - (x - a) / (tau * tau);
}

/**
 * 用一批对局更新评分, 原地改 `player`。
 *
 * @param player  `{ rating, rd, sigma }`
 * @param matches `[{ opponentRating, opponentRd, score }]`
 */
function update(player, matches, systemConstant = TAU) {
  if (!matches.length) {
    // 没有新对局, RD 缓慢增大
    player.rd = Math.sqrt(player.rd * player.rd + player.sigma * player.sigma);
    return player;
  }

  // 1. 转换为内部评分尺度
  const mu = (player.rating - 1500) / SCALE;
  const phi = player.rd / SCALE;

  // 2. 计算 v 和 Δ
  let vInv = 0;
  let sumG = 0;
  for (const m of matches) {
    const muJ = (m.opponentRating - 1500) / SCALE;
    const phiJ = m.opponentRd / SCALE;
    const gPhiJ = g(phiJ);
    const eVal = E(mu, muJ, phiJ);
    vInv += gPhiJ * gPhiJ * eVal * (1 - eVal);
    sumG += gPhiJ * (m.score - eVal);
  }
  if (vInv <= 0) return player; // 期望胜率为 0 或 1, 无信息量

  const v = 1 / vInv;
  const delta = v * sumG;
  const deltaSq = delta * delta;

  // 3. 新波动率: 解 f(x)=0
  const a = Math.log(player.sigma * player.sigma);
  const phiSq = phi * phi;

  let aLow = a;
  let aHigh;
  if (deltaSq > phiSq + v) {
    aHigh = Math.log(deltaSq - phiSq - v);
  } else {
    let k = 1;
    while (f(a - k * systemConstant, deltaSq, v, phi, systemConstant, a) < 0 && k < 100) k += 1;
    aHigh = a;
    aLow = a - k * systemConstant;
  }
  if (aLow > aHigh) {
    const t = aLow;
    aLow = aHigh;
    aHigh = t;
  }

  let fLow = f(aLow, deltaSq, v, phi, systemConstant, a);
  let aNew = (aLow + aHigh) / 2;
  let iter = 0;
  while (iter < 100 && Math.abs(aHigh - aLow) > 1e-6) {
    aNew = (aLow + aHigh) / 2;
    const fNew = f(aNew, deltaSq, v, phi, systemConstant, a);
    if (fNew * fLow <= 0) {
      aHigh = aNew;
    } else {
      aLow = aNew;
      fLow = fNew;
    }
    iter += 1;
  }
  const newSigma = Math.exp(aNew / 2);

  // 4-6. 新 φ*, φ', μ' 并转换回常规尺度
  const phiStar = Math.sqrt(phiSq + newSigma * newSigma);
  const newPhi = 1 / Math.sqrt(1 / (phiStar * phiStar) + 1 / v);
  const newMu = mu + newPhi * newPhi * sumG;

  player.rating = newMu * SCALE + 1500;
  player.rd = newPhi * SCALE;
  player.sigma = newSigma;

  // 限制范围
  if (player.rd < 30) player.rd = 30;
  if (player.rd > 350) player.rd = 350;
  if (player.rating < 0) player.rating = 0;
  if (player.sigma < 0.01) player.sigma = 0.01;
  if (player.sigma > 0.5) player.sigma = 0.5;
  return player;
}

/**
 * 段位。切分点与色调来自 shared/config/user-ranks.json, 与 UserRank.RANKS 同源。
 *
 * 超出最高档的分数落到最高档而不是最低档 —— Android 的 UserRank.fromRating
 * 兜底写的是 `?: RANKS.first()`, 一个 10000 分的人在那边会显示"初学者"。
 * Glicko-2 的分实际到不了那么高, 但这里没有理由复制一个反直觉的兜底。
 */
function rankOf(rating) {
  const value = Math.max(0, Math.round(rating));
  const matched = userRanks.find((rank) => value >= rank.minRating && value <= rank.maxRating);
  if (matched) return matched;
  return value > userRanks[userRanks.length - 1].maxRating ? userRanks[userRanks.length - 1] : userRanks[0];
}

module.exports = {
  DEFAULT_RATING, DEFAULT_RD, DEFAULT_SIGMA, RESULT,
  update, rankOf, ranks: userRanks,
};
