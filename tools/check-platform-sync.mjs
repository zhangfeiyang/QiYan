import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import vm from 'node:vm';

const difficulties = JSON.parse(await readFile('shared/config/difficulty-levels.json', 'utf8'));
const features = JSON.parse(await readFile('shared/config/features.json', 'utf8'));
const engineProfile = JSON.parse(await readFile('shared/config/engine-profile.json', 'utf8'));
const levelNotes = JSON.parse(await readFile('shared/config/level-notes.json', 'utf8'));
const androidMenu = await readFile('app/src/main/java/com/zhongguoxiangqi/ui/menu/MainMenuActivity.kt', 'utf8');
const androidEngine = await readFile('app/src/main/java/com/zhongguoxiangqi/engine/EngineManager.kt', 'utf8');
const androidStrings = await readFile('app/src/main/res/values/strings.xml', 'utf8');
const payload = JSON.stringify({ difficulties, features, engineProfile, levelNotes });
const expected = createHash('sha256').update(payload).digest('hex').slice(0, 16);
const generated = await readFile('miniprogram/generated/product-config.js', 'utf8');
const sandbox = { module: { exports: {} } };
vm.runInNewContext(generated, sandbox);
if (sandbox.module.exports.fingerprint !== expected) throw new Error('小程序共享配置已过期，请运行 node tools/sync-shared-config.mjs');
if (difficulties.length !== 15 || difficulties.some((item, index) => item.level !== index + 1)) throw new Error('难度必须是连续的 1-15 档');
const missing = features.features.filter((feature) => feature.android && !feature.miniprogram && !feature.roadmap);
if (missing.length) throw new Error(`未声明小程序同步计划: ${missing.map((item) => item.id).join(', ')}`);
if (!androidMenu.includes('LEGENDARY(15,') || !androidEngine.includes('MAX_LEVEL = 15')) throw new Error('Android 端难度映射未同步到 15 档');
if (!androidStrings.includes('difficulty_segment_legendary')) throw new Error('Android 字符串表未同步 15 档段位文案');
if (engineProfile.miniprogramMaxLevel > 9) throw new Error('端侧 JS 引擎物理上只能稳定支持到 9 档，请收敛 miniprogramMaxLevel');
for (let level = 1; level <= engineProfile.miniprogramMaxLevel; level += 1) {
  const key = String(level);
  if (!engineProfile.miniprogramSearchDepths[key]) throw new Error(`共享引擎缺少 ${level} 档配置`);
  if (!levelNotes[key]) throw new Error(`共享段位说明缺少 ${level} 档`);
  if (!engineProfile.searchTimeBudgetMs?.[key]) throw new Error(`共享时间预算缺少 ${level} 档`);
}
for (let level = engineProfile.miniprogramMaxLevel + 1; level <= 15; level += 1) {
  if (engineProfile.miniprogramSearchDepths[String(level)]) throw new Error(`${level} 档超出端侧能力却仍然给了搜索参数`);
}
const notes = await readFile('miniprogram/pages/index/index.wxml', 'utf8');
for (let level = engineProfile.miniprogramMaxLevel + 1; level <= 15; level += 1) {
  if (!notes.includes(`Lv.${level}`)) throw new Error(`小程序首页需展示 Lv.${level} 灰态`);
}
const gameWxml = await readFile('miniprogram/pages/game/game.wxml', 'utf8');
if (!gameWxml.includes('appOnlyHint')) throw new Error('对局页缺少“需在 App 端体验”提示槽位');
console.log(`platform sync check passed: ${expected}`);
