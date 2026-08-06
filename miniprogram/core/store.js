/**
 * 本地持久化 —— 对应 Android 的 AppSettings(SharedPreferences)、
 * UserProfile(SharedPreferences) 和 GameRecordDao(Room)。
 *
 * 小程序侧统一走 wx.getStorageSync/setStorageSync, 分三个 key:
 *   settings  界面/引擎选项
 *   profile   棋力档案 (Glicko-2)
 *   records   对局记录 (最多留 100 局, 与 ReplayActivity 的 getRecent(100) 对齐)
 *
 * 读取一律"默认值 + 存量覆盖": 加新选项时老用户不会读到 undefined,
 * 也不必写迁移代码。
 */
const rating = require('./rating');

const KEY_SETTINGS = 'settings';
const KEY_PROFILE = 'profile';
const KEY_RECORDS = 'records';
const KEY_LAST_SETUP = 'lastGameSetup';

const MAX_RECORDS = 100;

/** 主题: 0=跟随系统 1=深色 2=浅色。默认 1, 与 AppSettings.themeMode 一致 */
const THEME = { SYSTEM: 0, DARK: 1, LIGHT: 2 };

const DEFAULT_SETTINGS = {
  theme: THEME.DARK,
  sound: true,
  vibrate: true,
  bgm: true,
  showCoords: true,
  showWinRate: true,
  animation: true,
  flipped: false,
  /** 小程序特有: 云端 Pikafish / AI 分析服务地址。空 = 用端侧引擎与本地建议 */
  engineApiBaseUrl: '',
  advisorApiBaseUrl: '',
};

const DEFAULT_PROFILE = {
  nickname: '',
  rating: rating.DEFAULT_RATING,
  rd: rating.DEFAULT_RD,
  sigma: rating.DEFAULT_SIGMA,
  totalGames: 0,
  wins: 0,
  losses: 0,
  draws: 0,
};

const DEFAULT_SETUP = { difficulty: 4, playerSide: 1 };

function read(key, defaults) {
  const stored = wx.getStorageSync(key);
  if (!stored || typeof stored !== 'object') return { ...defaults };
  return { ...defaults, ...stored };
}

// ====== 设置 ======

function getSettings() {
  return read(KEY_SETTINGS, DEFAULT_SETTINGS);
}

function updateSettings(patch) {
  const next = { ...getSettings(), ...patch };
  wx.setStorageSync(KEY_SETTINGS, next);
  return next;
}

// ====== 棋力档案 ======

function getProfile() {
  return read(KEY_PROFILE, DEFAULT_PROFILE);
}

function saveProfile(profile) {
  wx.setStorageSync(KEY_PROFILE, profile);
  return profile;
}

/** 显示评分: 取整且不为负, 与 UserProfile.displayedRating 一致 */
function displayedRating(profile) {
  return Math.max(0, Math.round(profile.rating));
}

/**
 * 记录一局结果并更新 Glicko-2。
 *
 * @param result 1=胜 0.5=和 0=负 (rating.RESULT)
 * @param opponentRating 对手实际棋力, 用档位的 elo
 * @returns 更新后的档案
 */
function recordResult(result, opponentRating) {
  const profile = getProfile();
  const glicko = { rating: profile.rating, rd: profile.rd, sigma: profile.sigma };
  rating.update(glicko, [{ opponentRating, opponentRd: 200, score: result }]);

  profile.rating = glicko.rating;
  profile.rd = glicko.rd;
  profile.sigma = glicko.sigma;
  profile.totalGames += 1;
  if (result === rating.RESULT.WIN) profile.wins += 1;
  else if (result === rating.RESULT.LOSS) profile.losses += 1;
  else profile.draws += 1;

  return saveProfile(profile);
}

// ====== 对局记录 ======

function getRecords() {
  const stored = wx.getStorageSync(KEY_RECORDS);
  return Array.isArray(stored) ? stored : [];
}

function getRecord(id) {
  return getRecords().find((record) => record.id === id) || null;
}

/**
 * 存一局。字段与 Android 的 GameRecord 实体一一对应, 便于以后做云同步。
 * 走法存 UCI 字符串, 起始局面存 FEN —— 回放时重放即可, 不必存每一步的棋盘。
 */
function addRecord(record) {
  const records = getRecords();
  records.unshift({ id: `${record.startTime}-${records.length}`, ...record });
  wx.setStorageSync(KEY_RECORDS, records.slice(0, MAX_RECORDS));
  return records[0];
}

// ====== 上次对局设定 ======
// 首页的"开始对弈"直接开局, 用的就是这里 —— 开出来的是你上次在下的那盘的
// 设定, 而不是每次都从默认值重来 (同 GameActivity.PREFS_GAME)。

function getLastSetup() {
  return read(KEY_LAST_SETUP, DEFAULT_SETUP);
}

function saveLastSetup(setup) {
  const next = { ...getLastSetup(), ...setup };
  wx.setStorageSync(KEY_LAST_SETUP, next);
  return next;
}

// ====== 清除数据 ======

function clearAll() {
  wx.removeStorageSync(KEY_PROFILE);
  wx.removeStorageSync(KEY_RECORDS);
  wx.removeStorageSync(KEY_SETTINGS);
  wx.removeStorageSync(KEY_LAST_SETUP);
}

module.exports = {
  THEME, MAX_RECORDS,
  DEFAULT_SETTINGS, DEFAULT_PROFILE,
  getSettings, updateSettings,
  getProfile, saveProfile, displayedRating, recordResult,
  getRecords, getRecord, addRecord,
  getLastSetup, saveLastSetup,
  clearAll,
};
