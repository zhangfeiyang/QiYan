/**
 * 装载冒烟测试。
 *
 * 在 Node 里用一套最小的 wx / Page / Component / Behavior 桩把每个页面和组件
 * require 一遍, 再手动跑一遍它们的生命周期与关键交互。
 *
 * 目标不是替代真机, 而是把"打开就白屏"这类问题挡在上传之前 —— 这类错误
 * (拼错的 require、少写的 data 字段、生命周期里读了还没赋值的东西) 占了
 * 小程序返工的绝大多数, 而它们全都不需要真正的微信环境就能暴露出来。
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

// ====== 桩 ======

const storage = new Map();
const registered = { pages: [], components: [] };
let toasts = [];
let modals = [];

globalThis.wx = {
  getStorageSync: (key) => (storage.has(key) ? storage.get(key) : ''),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: (key) => storage.delete(key),
  getSystemInfoSync: () => ({ theme: 'dark', pixelRatio: 2 }),
  setNavigationBarColor: () => {},
  setNavigationBarTitle: () => {},
  setKeepScreenOn: () => {},
  onThemeChange: () => {},
  showToast: (options) => toasts.push(options),
  showModal: (options) => modals.push(options),
  navigateTo: () => {},
  navigateBack: () => {},
  vibrateShort: () => {},
  request: () => {},
  createInnerAudioContext: () => ({ play() {}, pause() {}, stop() {}, destroy() {} }),
  createSelectorQuery: () => ({ in: () => ({ select: () => ({ fields: () => ({ exec: () => {} }) }) }) }),
};

globalThis.Behavior = (options) => options;
globalThis.Page = (options) => registered.pages.push(options);
globalThis.Component = (options) => registered.components.push(options);

const app = { globalData: { theme: 'dark', locale: 'zh' }, refreshTheme: () => 'dark' };
globalThis.getApp = () => app;

/** 把 Page/Component 的 options 组装成一个能跑的实例 */
function instantiate(options) {
  const behaviors = options.behaviors || [];
  const instance = {
    data: {},
    setData(patch) { Object.assign(this.data, patch); },
    selectComponent: () => null,
    triggerEvent: () => {},
  };
  for (const behavior of behaviors) {
    Object.assign(instance.data, behavior.data || {});
    Object.assign(instance, behavior.methods || {});
  }
  Object.assign(instance.data, options.data || {});
  for (const [key, value] of Object.entries(options)) {
    if (key === 'data' || key === 'behaviors') continue;
    instance[key] = typeof value === 'function' ? value.bind(instance) : value;
  }
  // 组件的方法挂在 methods 下
  for (const [key, value] of Object.entries(options.methods || {})) {
    instance[key] = value.bind(instance);
  }
  for (const [key, prop] of Object.entries(options.properties || {})) {
    instance.data[key] = prop && typeof prop === 'object' ? prop.value : undefined;
  }
  return instance;
}

function load(path) {
  registered.pages.length = 0;
  registered.components.length = 0;
  delete require.cache[require.resolve(path)];
  require(path);
  const options = registered.pages[0] || registered.components[0];
  assert.ok(options, `${path} 没有注册 Page/Component`);
  return instantiate(options);
}

// ====== 首页 ======

const index = load('../pages/index/index.js');
index.onShow();
assert.ok(index.data.nickname, '首页必须显示昵称');
assert.ok(index.data.rankName, '首页主角是段位名, 不是裸分数');
assert.match(index.data.playText, /Lv\.\d+/, '"开始对弈"要带上会用哪个档位开局');
assert.equal(index.data.themeClass, '', '深色是默认主题');
index.toggleLanguage();
assert.ok(index.data.T.menu_play, '切换语言后文案表要重建');
index.toggleLanguage();

// ====== 设置页 ======

const settings = load('../pages/settings/settings.js');
settings.onShow();
assert.ok(settings.data.featureRows.length >= 15, '能力矩阵要把所有功能列出来');
assert.ok(
  settings.data.featureRows.some((row) => !row.ready && row.roadmap),
  '平台受限的功能必须带 roadmap, 不能只标一个"迁移中"'
);
settings.onToggle({ currentTarget: { dataset: { key: 'showWinRate' } }, detail: { value: false } });
assert.equal(settings.data.settings.showWinRate, false);
settings.onPickTheme({ currentTarget: { dataset: { value: 2 } } });
assert.equal(settings.data.settings.theme, 2, '主题选择要落盘');

// 恢复默认, 免得影响后面的用例
settings.onToggle({ currentTarget: { dataset: { key: 'showWinRate' } }, detail: { value: true } });
settings.onPickTheme({ currentTarget: { dataset: { value: 1 } } });

// ====== 对局页 ======

const gameCore = require('../core/xiangqi.js');
const gamePage = load('../pages/game/game.js');
gamePage.onLoad({ level: '3', side: '1' });
gamePage.onShow();

assert.equal(gamePage.data.board.length, 10, '开局要摆好棋盘');
assert.equal(gamePage.data.sideToMove, gameCore.RED);
assert.equal(gamePage.data.appOnly, false, 'Lv.3 是端侧档位');
assert.equal(gamePage.data.playerRowAlpha, 1, '轮到我走, 我这一行要亮着');
assert.equal(gamePage.data.opponentRowAlpha, 0.45, '不该谁走的那一行压暗');
assert.ok(gamePage.data.difficultyLabel.startsWith('Lv.3'), '档位 chip 要显示当前难度');

// 选中一个子 → 出可走点
gamePage.onCellTap({ detail: { row: 6, col: 4 } });
assert.deepEqual(gamePage.data.selected, { row: 6, col: 4 });
assert.equal(gamePage.data.targets.length, 1, '中兵开局只有一个可走点');

// 落子 → 记谱、上一步高亮、轮到引擎
toasts = [];
gamePage.onCellTap({ detail: { row: 5, col: 4 } });
assert.equal(gamePage.data.moveCount, 1);
assert.deepEqual(gamePage.data.lastMove, { from: { row: 6, col: 4 }, to: { row: 5, col: 4 } });
assert.equal(gamePage.data.sideToMove, gameCore.BLACK, '走完轮到黑方');
assert.equal(gamePage.data.selected, null, '落子后要清掉选中态');

// 提示: 轮到引擎时不给提示
gamePage.onHint();
assert.equal(gamePage.data.hintMove, null, '不是我的回合不该给提示');

// 引擎回一手
gamePage.playEngineLocal();
assert.equal(gamePage.data.moveCount, 2);
assert.equal(gamePage.data.sideToMove, gameCore.RED);

// 提示: 轮到我时画箭头
gamePage.onHint();
assert.ok(gamePage.data.hintMove && gamePage.data.hintMove.from, '轮到我时提示要给出一条着法');

// 悔棋: 轮到玩家 ⇒ 退两步回到开局
gamePage.undoMove();
assert.equal(gamePage.data.moveCount, 0, '轮到玩家时悔棋要退掉引擎和自己各一步');
assert.equal(gamePage.data.sideToMove, gameCore.RED);
assert.equal(gamePage.data.hintMove, null, '悔棋要清掉提示箭头');

// 换边: 我方改执黑, 视角翻转, 引擎接手红方
gamePage.onFlip();
assert.equal(gamePage.data.playerSide, gameCore.BLACK, '翻转是真换边');
assert.equal(gamePage.data.flipped, true, '换边后视角要跟着走');

// 难度: 对局中改档位立即生效, 不重开局
const movesBefore = gamePage.data.moveCount;
gamePage.onPickLevel({ detail: { level: 2 } });
assert.equal(gamePage.data.difficulty.level, 2);
assert.equal(gamePage.data.moveCount, movesBefore, '改难度不该重开局');

// Lv.4 以上进来给说明卡, 而不是一盘下不动的棋
const appOnlyGame = load('../pages/game/game.js');
appOnlyGame.onLoad({ level: '5', side: '1' });
assert.equal(appOnlyGame.data.appOnly, true);
assert.ok(appOnlyGame.data.appOnlyTitle.includes('Lv.5'));
assert.ok(appOnlyGame.data.appOnlyBody.includes('3'), '说明卡要写清端侧到哪一档');

// ====== 终局与复盘 ======

storage.delete('records');
const finishing = load('../pages/game/game.js');
finishing.onLoad({ level: '3', side: '1' });
modals = [];
finishing.onResign();
assert.equal(modals.length, 1, '认输要二次确认');
modals[0].success({ confirm: true });
assert.equal(finishing.data.isGameOver, true);

const replay = load('../pages/replay/replay.js');
replay.onShow();
assert.equal(replay.data.records.length, 1, '认输的那一局要进复盘列表');
assert.equal(replay.data.records[0].resultClass, 'loss', '认输记为负');
assert.ok(replay.data.records[0].ratingText, '列表要显示段位与评分变化');

const viewerId = replay.data.records[0].id;
const viewer = load('../pages/replay-viewer/replay-viewer.js');
viewer.onLoad({ id: viewerId });
viewer.onShow();
assert.equal(viewer.data.board.length, 10, '回放要能摆出起始局面');
assert.equal(viewer.data.step, 0);

// ====== 组件 ======

for (const path of [
  '../components/x-icon/x-icon.js',
  '../components/win-rate-bar/win-rate-bar.js',
  '../components/xiangqi-board/xiangqi-board.js',
  '../components/difficulty-picker/difficulty-picker.js',
]) {
  const component = load(path);
  assert.ok(component, `${path} 应能装载`);
}

const icon = load('../components/x-icon/x-icon.js');
icon.data.name = 'play';
icon.data.theme = 'dark';
icon.data.size = 48;
const style = icon.buildStyle();
assert.match(style, /data:image\/svg\+xml/, '图标要内联成 data URI');
assert.match(style, /%23/, '色值里的 # 必须编码, 否则会被当成 URL 片段截断');

// 棋盘几何: 坐标数字必须落在"棋子外圈"和"木纹边缘"之间的空档里。
// 之前用写死的半径倍数, 结果上下两排的列号被第一排/最后一排棋子整个盖住,
// 左右的行号被木头边裁掉一半 —— 屏幕上看起来就是"没有坐标"。
const boardComponent = load('../components/xiangqi-board/xiangqi-board.js');
for (const [w, h] of [[304, 400], [304, 560], [560, 400], [750, 1200], [200, 200]]) {
  boardComponent._width = w;
  boardComponent._height = h;
  boardComponent.calculateDimensions();
  const g = boardComponent._geom;
  const label = g.radius * 0.65; // drawCoordinates 用的字号
  const where = `${w}x${h}`;

  assert.ok(g.pad > g.radius + label, `${where}: 木纹要装得下坐标数字`);
  assert.ok(g.coord - label / 2 > g.radius, `${where}: 坐标数字不能压在棋子上`);
  assert.ok(g.coord + label / 2 < g.pad, `${where}: 坐标数字不能掉到木纹外`);
  // 木纹本身也不能被画布裁掉
  assert.ok(g.top - g.pad >= -0.5, `${where}: 木纹上边被画布裁了`);
  assert.ok(g.left - g.pad >= -0.5, `${where}: 木纹左边被画布裁了`);
  assert.ok(g.bottom + g.pad <= h + 0.5, `${where}: 木纹下边被画布裁了`);
  assert.ok(g.right + g.pad <= w + 0.5, `${where}: 木纹右边被画布裁了`);
}

const picker = load('../components/difficulty-picker/difficulty-picker.js');
picker.rebuild();
assert.equal(picker.data.levels.length, 8, '难度选择器要列出全部 8 档');
assert.equal(picker.data.levels[0].appOnly, false);
assert.equal(picker.data.levels[4].appOnly, true, 'Lv.5 要标成 App 端');
assert.equal(picker.data.levels[7].appOnly, true, 'Lv.8 要标成 App 端');
assert.ok(picker.data.levels[0].color.startsWith('#'), '每一档要有段位色条');

// Practice assistance always uses the strongest local analysis, not random opponent policy.
const gentleHint = load('../pages/game/game.js');
gentleHint.onLoad({ level: '1', side: '1' });
const originalBestMove = gameCore.localBestMove;
let analysisLevel;
gameCore.localBestMove = (board, side, level) => {
  analysisLevel = level;
  return gameCore.legalMoves(board, side)[0];
};
gentleHint.onHint();
assert.equal(analysisLevel, gameCore.engineProfile.miniprogramMaxLevel);
gameCore.localBestMove = originalBestMove;

// Configuring a cloud engine must never turn the first two practice levels into strong opponents.
const store = require('../core/store');
store.updateSettings({ engineApiBaseUrl: 'https://example.invalid' });
let localCalls = 0;
gentleHint.playEngineLocal = () => { localCalls++; };
gentleHint.setData({ sideToMove: gameCore.BLACK, isThinking: true });
gentleHint.requestEngineMove();
assert.equal(localCalls, 1);
store.updateSettings({ engineApiBaseUrl: '' });

// A gentle practice game stays unrated even after selecting a harder opponent.
const rankedProfileBefore = { ...store.getProfile() };
gentleHint.onPickLevel({ detail: { level: 3 } });
gentleHint.endGame({ type: gameCore.GAME_OVER.CHECKMATE, winner: gameCore.BLACK });
assert.deepEqual(store.getProfile(), rankedProfileBefore, 'gentle games leave the entire ranked profile unchanged');
assert.equal(store.getRecords()[0].practice, true);

// App-only pages can be changed back to an available local tier.
appOnlyGame.onPickLevel({ detail: { level: 1 } });
assert.equal(appOnlyGame.data.appOnly, false);
assert.equal(appOnlyGame.data.difficulty.level, 1);

console.log('smoke tests passed');
