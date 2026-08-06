// 把 miniprogram/ 打成符合微信小程序后台"版本管理→开发版本→上传代码包"规范的 zip。
// 规则：
//   - zip 根目录必须可直接被微信开发者工具识别：app.js / app.json / app.wxss / pages/ / ...
//   - project.config.json 的 appid 必须是 wx... 形式的正式 AppID，禁止 touristappid
//   - 排除 dev-only：tests/、.git/、node_modules/、*.md
import { readFile, mkdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = path.join(ROOT, 'miniprogram');
const DIST = path.join(ROOT, 'dist');

const cfg = JSON.parse(await readFile(path.join(SRC, 'project.config.json'), 'utf8'));
if (!/^wx[0-9a-f]{16}$/i.test(cfg.appid)) {
  throw new Error(`project.config.json 的 appid 看起来不是正式 AppID：${cfg.appid}。请先把 AppID 替换为 wx 开头的 18 位字符串。`);
}
if (cfg.appid === 'touristappid') {
  throw new Error('appid 仍是 touristappid，请先替换为正式 AppID。');
}

await mkdir(DIST, { recursive: true });
const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
const zipName = `qiyan-miniprogram-${cfg.appid}-${stamp}.zip`;
const zipPath = path.join(DIST, zipName);

// 排除规则：tests、.git、node_modules、README.md
const args = [
  '-r', zipPath, '.',
  '-x', 'tests/*', 'tests/**',
  '-x', '*.md',
  '-x', '.git/*', '.git/**',
  '-x', 'node_modules/*', 'node_modules/**',
];

await new Promise((resolve, reject) => {
  const proc = spawn('zip', args, { cwd: SRC, stdio: 'inherit' });
  proc.on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`zip exited with code ${code}`))));
});

console.log(`packaged: ${path.relative(ROOT, zipPath)}`);
