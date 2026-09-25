// 跨端一致性检查。提交前必跑。
//
// 检查的是"用户看得见的东西两端一不一样": 难度档名、段位名、能力矩阵、
// 文案表、图标。UI 和平台 API 可以各写各的, 但这些一旦漂了, 用户就会觉得
// App 和小程序是两个产品 —— 这正是这次返工要修的问题。
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import { readAndroidStrings } from './android-resources.mjs';

const fail = (msg) => { throw new Error(msg); };

// ====== 共享配置 ======

const difficulties = JSON.parse(await readFile('shared/config/difficulty-levels.json', 'utf8'));
const features = JSON.parse(await readFile('shared/config/features.json', 'utf8'));
const engineProfile = JSON.parse(await readFile('shared/config/engine-profile.json', 'utf8'));
const levelNotes = JSON.parse(await readFile('shared/config/level-notes.json', 'utf8'));
const userRanks = JSON.parse(await readFile('shared/config/user-ranks.json', 'utf8'));

const payload = JSON.stringify({ difficulties, features, engineProfile, levelNotes, userRanks });
const expected = createHash('sha256').update(payload).digest('hex').slice(0, 16);

function loadGenerated(path) {
  const sandbox = { module: { exports: {} } };
  vm.runInNewContext(readFileSync(path), sandbox);
  return sandbox.module.exports;
}
const { readFileSync } = await import('node:fs');

const generated = loadGenerated('miniprogram/generated/product-config.js');
if (generated.fingerprint !== expected) {
  fail('小程序共享配置已过期，请运行 node tools/sync-shared-config.mjs');
}

// ====== 难度档位 ======

if (difficulties.length !== 8 || difficulties.some((item, index) => item.level !== index + 1)) {
  fail('难度必须是连续的 1-8 档');
}
for (const item of difficulties) {
  for (const field of ['name', 'segment', 'desc', 'elo', 'tone', 'thinkTimeMs']) {
    if (item[field] === undefined) fail(`Lv.${item.level} 缺少字段 ${field}`);
  }
}

// ====== Android 侧 ======

const androidEngine = await readFile('app/src/main/java/com/zhongguoxiangqi/engine/EngineManager.kt', 'utf8');
const androidDifficulty = await readFile('app/src/main/java/com/zhongguoxiangqi/engine/DifficultySettings.kt', 'utf8');
if (!androidDifficulty.includes('MAX_LEVEL = 8') ||
    !androidEngine.includes('MAX_LEVEL = DifficultySettings.MAX_LEVEL')) {
  fail('Android 端难度映射未同步到 8 档');
}
const zhStrings = await readAndroidStrings('app/src/main/res/values-zh');

// Resource keys are shared as well as text, so both locales show the same tier.
for (const item of difficulties) {
  for (const field of ['name', 'segment', 'desc']) {
    const key = item[`${field}Key`];
    if (!key || zhStrings[key] === undefined) fail(`Android 字符串表缺少 ${key}`);
    if (item[field] !== zhStrings[key]) {
      fail(`Lv.${item.level} 的 ${field} 与 Android ${key} 不一致`);
    }
  }
}

// 段位 key 必须在 Android 字符串表里存在, 小程序按同一个 key 取文案
for (const rank of userRanks) {
  if (zhStrings[rank.key] === undefined) fail(`Android 字符串表缺少段位 ${rank.key}`);
}

// ====== 能力矩阵 ======

const missing = features.features.filter((f) => f.android && !f.miniprogram && !f.roadmap);
if (missing.length) fail(`未声明小程序同步计划: ${missing.map((f) => f.id).join(', ')}`);

// ====== 端侧引擎档位 ======

if (engineProfile.miniprogramMaxLevel !== 3) {
  fail('小程序本地难度应为 1-3；4-8 档需专业引擎');
}
for (let level = 1; level <= engineProfile.miniprogramMaxLevel; level += 1) {
  const key = String(level);
  if (!engineProfile.miniprogramSearchDepths[key]) fail(`共享引擎缺少 ${level} 档配置`);
  if (!levelNotes[key]) fail(`共享段位说明缺少 ${level} 档`);
  if (!engineProfile.searchTimeBudgetMs?.[key]) fail(`共享时间预算缺少 ${level} 档`);
}
for (let level = engineProfile.miniprogramMaxLevel + 1; level <= difficulties.length; level += 1) {
  if (engineProfile.miniprogramSearchDepths[String(level)]) {
    fail(`${level} 档超出端侧能力却仍然给了搜索参数`);
  }
}

// ====== 文案表 / 图标 ======

const strings = loadGenerated('miniprogram/generated/strings.js');
const androidEnStrings = await readAndroidStrings('app/src/main/res/values');
for (const key of Object.keys(androidEnStrings)) {
  if (strings.en[key] !== androidEnStrings[key] || strings.zh[key] !== zhStrings[key]) {
    fail(`文案表缺少 ${key}，请运行 node tools/sync-shared-config.mjs`);
  }
}
if (Object.keys(strings.zh).length !== Object.keys(strings.en).length) {
  fail('文案表中英两份 key 数量不一致');
}

const icons = loadGenerated('miniprogram/generated/icons.js');
for (const name of ['play', 'hint', 'undo', 'flip', 'ai', 'flag', 'history', 'camera', 'settings', 'info', 'language', 'live']) {
  if (!icons[name]) fail(`图标表缺少 ${name}，请运行 node tools/sync-shared-config.mjs`);
}

// ====== 小程序界面上的硬性槽位 ======

// 难度选择器必须把 Lv.4-5 也列出来 (灰态), 而不是直接藏掉:
// 让用户知道这些档位存在、在哪能用, 比装作没有更诚实。
const picker = await readFile('miniprogram/components/difficulty-picker/difficulty-picker.wxml', 'utf8');
if (!picker.includes('appOnly')) fail('难度选择器缺少"仅 App 可用"的灰态标记');
if (!picker.includes('wx:for="{{levels}}"')) fail('难度选择器必须遍历全部 5 档');

// 首页不该再摆难度网格 —— App 的首页没有它, 它属于对局页的选择器
const indexWxml = await readFile('miniprogram/pages/index/index.wxml', 'utf8');
if (/Lv\.\d+/.test(indexWxml)) fail('首页不应硬编码难度档位, 难度选择在对局页');

// 首页的入口必须与 MainMenuActivity 一一对应。
// 连 index.js 一起看: "开始对弈"的文案是把 menu_play 和当前档位拼出来的,
// 只扫 wxml 会漏掉它。
const indexSource = indexWxml + (await readFile('miniprogram/pages/index/index.js', 'utf8'));
for (const key of ['menu_play', 'live_entry', 'menu_replay', 'menu_recognize', 'menu_settings', 'menu_about', 'menu_language']) {
  if (!indexSource.includes(key)) fail(`首页缺少入口 ${key}`);
}

// 对局页的工具条必须与 activity_game.xml 的五个键一一对应
const gameWxml = await readFile('miniprogram/pages/game/game.wxml', 'utf8');
for (const key of ['action_hint', 'action_undo', 'action_flip', 'advisor_button', 'action_resign']) {
  if (!gameWxml.includes(`T.${key}`)) fail(`对局页工具条缺少 ${key}`);
}
if (!gameWxml.includes('appOnly')) fail('对局页缺少"需在 App 端体验"提示槽位');

console.log(`platform sync check passed: ${expected}`);
