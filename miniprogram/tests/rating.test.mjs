import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

// store.js 走 wx.getStorageSync/setStorageSync, 在 Node 里用一个内存实现顶上 ——
// 测的是"赢了分要涨、段位切分点对不对", 不是微信 API 本身。
const memory = new Map();
globalThis.wx = {
  getStorageSync: (key) => (memory.has(key) ? memory.get(key) : ''),
  setStorageSync: (key, value) => memory.set(key, value),
  removeStorageSync: (key) => memory.delete(key),
};

const rating = require('../core/rating.js');
const store = require('../core/store.js');

// ====== 段位切分点 ======
// 必须与 Android 的 UserRank.RANKS 完全一致, 否则同一个分在两端显示成两个段位

assert.equal(rating.rankOf(0).key, 'rank_1');
assert.equal(rating.rankOf(999).key, 'rank_1');
assert.equal(rating.rankOf(1000).key, 'rank_2');
assert.equal(rating.rankOf(1500).key, 'rank_4', '起始分 1500 应落在业余三段');
assert.equal(rating.rankOf(2600).key, 'rank_10');
assert.equal(rating.rankOf(99999).key, 'rank_10', '超出上限也要有段位, 不能返回 undefined');

// ====== Glicko-2 ======

const player = { rating: 1500, rd: 350, sigma: 0.06 };
rating.update(player, [{ opponentRating: 1500, opponentRd: 200, score: rating.RESULT.WIN }]);
assert.ok(player.rating > 1500, '赢同分对手, 分要涨');
assert.ok(player.rd < 350, '下了棋, 评分偏差要收窄');

const loser = { rating: 1500, rd: 350, sigma: 0.06 };
rating.update(loser, [{ opponentRating: 1500, opponentRd: 200, score: rating.RESULT.LOSS }]);
assert.ok(loser.rating < 1500, '输给同分对手, 分要跌');

// 没有新对局时 RD 缓慢增大 —— 久不下棋, 系统对你水平的把握变差
const idle = { rating: 1500, rd: 100, sigma: 0.06 };
rating.update(idle, []);
assert.ok(idle.rd > 100 && idle.rating === 1500, '空场次只放大 RD, 不动评分');

// 赢弱旅涨得少, 赢强手涨得多
const vsWeak = { rating: 1500, rd: 100, sigma: 0.06 };
const vsStrong = { rating: 1500, rd: 100, sigma: 0.06 };
rating.update(vsWeak, [{ opponentRating: 700, opponentRd: 200, score: rating.RESULT.WIN }]);
rating.update(vsStrong, [{ opponentRating: 2500, opponentRd: 200, score: rating.RESULT.WIN }]);
assert.ok(vsStrong.rating - 1500 > vsWeak.rating - 1500, '赢强手应该比赢弱旅涨得多');

// ====== 档案 ======

const before = store.getProfile();
assert.equal(before.rating, 1500);
assert.equal(before.totalGames, 0);

store.recordResult(rating.RESULT.WIN, 1450);
const after = store.getProfile();
assert.equal(after.totalGames, 1);
assert.equal(after.wins, 1);
assert.ok(store.displayedRating(after) > 1500);

store.recordResult(rating.RESULT.DRAW, 1450);
assert.equal(store.getProfile().draws, 1);
store.recordResult(rating.RESULT.LOSS, 1450);
assert.equal(store.getProfile().losses, 1);
assert.equal(store.getProfile().totalGames, 3);

// ====== 设置默认值 ======
// 读取一律"默认值 + 存量覆盖": 加新选项时老用户不会读到 undefined

memory.set('settings', { sound: false });
const settings = store.getSettings();
assert.equal(settings.sound, false, '存量值要覆盖默认值');
assert.equal(settings.showWinRate, true, '存量里没有的项要落到默认值');
assert.equal(settings.theme, store.THEME.DARK, '默认深色, 与 AppSettings.themeMode 一致');

// ====== 对局记录 ======

store.addRecord({ startTime: 1, moves: [], result: 1, playerRating: 1500, ratingDelta: 8 });
store.addRecord({ startTime: 2, moves: [], result: -1, playerRating: 1490, ratingDelta: -10 });
const records = store.getRecords();
assert.equal(records.length, 2);
assert.equal(records[0].startTime, 2, '最新的一局排在最前');
assert.ok(store.getRecord(records[0].id), '按 id 应能取回记录');

store.clearAll();
assert.equal(store.getRecords().length, 0);
assert.equal(store.getProfile().totalGames, 0);

console.log('rating & store tests passed');
