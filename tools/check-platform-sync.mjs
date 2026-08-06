import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import vm from 'node:vm';

const difficulties = JSON.parse(await readFile('shared/config/difficulty-levels.json', 'utf8'));
const features = JSON.parse(await readFile('shared/config/features.json', 'utf8'));
const payload = JSON.stringify({ difficulties, features });
const expected = createHash('sha256').update(payload).digest('hex').slice(0, 16);
const generated = await readFile('miniprogram/generated/product-config.js', 'utf8');
const androidMenu = await readFile('app/src/main/java/com/zhongguoxiangqi/ui/menu/MainMenuActivity.kt', 'utf8');
const androidEngine = await readFile('app/src/main/java/com/zhongguoxiangqi/engine/EngineManager.kt', 'utf8');
const sandbox = { module: { exports: {} } };
vm.runInNewContext(generated, sandbox);
if (sandbox.module.exports.fingerprint !== expected) throw new Error('小程序共享配置已过期，请运行 node tools/sync-shared-config.mjs');
if (difficulties.length !== 15 || difficulties.some((item, index) => item.level !== index + 1)) throw new Error('难度必须是连续的 1-15 档');
if (!androidMenu.includes('LEGENDARY(15,') || !androidEngine.includes('MAX_LEVEL = 15')) throw new Error('Android 端难度映射未同步到 15 档');
const missing = features.features.filter((feature) => feature.android && !feature.miniprogram && !feature.roadmap);
if (missing.length) throw new Error(`未声明小程序同步计划: ${missing.map((item) => item.id).join(', ')}`);
console.log(`platform sync check passed: ${expected}`);
