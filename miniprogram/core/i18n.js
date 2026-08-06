/**
 * 文案.
 *
 * key 与 Android 的 strings.xml 完全一致, 表由 tools/sync-shared-config.mjs
 * 从 values/strings.xml + values-zh/strings.xml 抽出来 —— 两端读同一句话,
 * 不存在"小程序这边的说法和 App 不一样"。
 *
 * 语言跟 App 一样存本地并可在首页一键切换; 默认中文。
 */
const TABLE = require('../generated/strings');

const KEY_LOCALE = 'locale';
let locale = 'zh';

/** 启动时读回上次选的语言 */
function init() {
  const saved = wx.getStorageSync(KEY_LOCALE);
  locale = saved === 'en' ? 'en' : 'zh';
  return locale;
}

function getLocale() {
  return locale;
}

function setLocale(next) {
  locale = next === 'en' ? 'en' : 'zh';
  wx.setStorageSync(KEY_LOCALE, locale);
  return locale;
}

/** 切到另一种语言, 返回切换后的值 */
function toggleLocale() {
  return setLocale(locale === 'zh' ? 'en' : 'zh');
}

/**
 * 取一条文案, 支持 Android 的位置参数格式 (%1$s / %2$d)。
 *
 * 找不到 key 时原样返回 key 而不是空串: 空串会让界面上凭空少一块, debug 时
 * 根本看不出是哪条文案没配。
 */
function t(key, ...args) {
  const table = TABLE[locale] || TABLE.zh;
  const raw = table[key];
  if (raw === undefined) return key;
  if (!args.length) return raw;
  return raw.replace(/%(\d+)\$[sd]/g, (whole, index) => {
    const value = args[Number(index) - 1];
    return value === undefined ? whole : String(value);
  });
}

module.exports = { init, t, getLocale, setLocale, toggleLocale };
