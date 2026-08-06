// 跨端一致性检查。提交前必跑。
//
// 检查的是"用户看得见的东西两端一不一样": 难度档名、段位名、能力矩阵、
// 文案表、图标。UI 和平台 API 可以各写各的, 但这些一旦漂了, 用户就会觉得
// App 和小程序是两个产品 —— 这正是这次返工要修的问题。
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import vm from 'node:vm';

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

if (difficulties.length !== 15 || difficulties.some((item, index) => item.level !== index + 1)) {
  fail('难度必须是连续的 1-15 档');
}
for (const item of difficulties) {
  for (const field of ['name', 'segment', 'desc', 'elo', 'tone', 'thinkTimeMs']) {
    if (item[field] === undefined) fail(`Lv.${item.level} 缺少字段 ${field}`);
  }
}

// ====== Android 侧 ======

const androidMenu = await readFile('app/src/main/java/com/zhongguoxiangqi/ui/menu/MainMenuActivity.kt', 'utf8');
const androidEngine = await readFile('app/src/main/java/com/zhongguoxiangqi/engine/EngineManager.kt', 'utf8');
const androidStringsXml = await readFile('app/src/main/res/values-zh/strings.xml', 'utf8');

if (!androidMenu.includes('LEGENDARY(15,') || !androidEngine.includes('MAX_LEVEL = 15')) {
  fail('Android 端难度映射未同步到 15 档');
}

function parseStrings(xml) {
  const out = {};
  const re = /<string\s+name="([^"]+)"\s*(?:formatted="[^"]*"\s*)?>([\s\S]*?)<\/string>/g;
  let m;
  while ((m = re.exec(xml)) !== null) out[m[1]] = m[2];
  return out;
}
const zhStrings = parseStrings(androidStringsXml);

// 档名/段位/描述必须和 Android 的 strings.xml 逐字相同。
// 这一条是这次返工的核心: 之前共享配置写的是"启蒙/初学/入门…", Android 写的
// 是"入门/初级/中级下…", 同一档在两端叫两个名字。
const ANDROID_LEVEL_KEYS = [
  'easy', 'beginner', 'lower_intermediate', 'intermediate', 'upper_intermediate',
  'advanced', 'amateur', 'district', 'city', 'provincial',
  'local_master', 'national_master', 'grandmaster', 'international_master', 'legendary',
];
difficulties.forEach((item, index) => {
  const suffix = ANDROID_LEVEL_KEYS[index];
  const checks = [
    ['name', `difficulty_${suffix}`],
    ['segment', `difficulty_segment_${suffix}`],
    ['desc', `difficulty_desc_${suffix}`],
  ];
  for (const [field, key] of checks) {
    if (zhStrings[key] === undefined) fail(`Android 字符串表缺少 ${key}`);
    if (item[field] !== zhStrings[key]) {
      fail(`Lv.${item.level} 的 ${field} 与 Android ${key} 不一致: "${item[field]}" vs "${zhStrings[key]}"`);
    }
  }
});

// 段位 key 必须在 Android 字符串表里存在, 小程序按同一个 key 取文案
for (const rank of userRanks) {
  if (zhStrings[rank.key] === undefined) fail(`Android 字符串表缺少段位 ${rank.key}`);
}

// ====== 能力矩阵 ======

const missing = features.features.filter((f) => f.android && !f.miniprogram && !f.roadmap);
if (missing.length) fail(`未声明小程序同步计划: ${missing.map((f) => f.id).join(', ')}`);

// ====== 端侧引擎档位 ======

if (engineProfile.miniprogramMaxLevel > 9) {
  fail('端侧 JS 引擎物理上只能稳定支持到 9 档，请收敛 miniprogramMaxLevel');
}
for (let level = 1; level <= engineProfile.miniprogramMaxLevel; level += 1) {
  const key = String(level);
  if (!engineProfile.miniprogramSearchDepths[key]) fail(`共享引擎缺少 ${level} 档配置`);
  if (!levelNotes[key]) fail(`共享段位说明缺少 ${level} 档`);
  if (!engineProfile.searchTimeBudgetMs?.[key]) fail(`共享时间预算缺少 ${level} 档`);
}
for (let level = engineProfile.miniprogramMaxLevel + 1; level <= 15; level += 1) {
  if (engineProfile.miniprogramSearchDepths[String(level)]) {
    fail(`${level} 档超出端侧能力却仍然给了搜索参数`);
  }
}

// ====== 文案表 / 图标 ======

const strings = loadGenerated('miniprogram/generated/strings.js');
const androidEnStrings = parseStrings(await readFile('app/src/main/res/values/strings.xml', 'utf8'));
for (const key of Object.keys(androidEnStrings)) {
  if (!(key in strings.en) || !(key in strings.zh)) {
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

// 难度选择器必须把 Lv.10-15 也列出来 (灰态), 而不是直接藏掉:
// 让用户知道这些档位存在、在哪能用, 比装作没有更诚实。
const picker = await readFile('miniprogram/components/difficulty-picker/difficulty-picker.wxml', 'utf8');
if (!picker.includes('appOnly')) fail('难度选择器缺少"仅 App 可用"的灰态标记');
if (!picker.includes('wx:for="{{levels}}"')) fail('难度选择器必须遍历全部 15 档');

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
