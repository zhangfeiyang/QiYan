/**
 * 落子的听觉/触觉反馈 + 背景音乐 —— 对应 Android 的 GameFeedback.kt / BgmPlayer.kt。
 *
 * 音效同样是**代码合成**的一声木质敲击, 不带音频资源: 基频加一个八度泛音,
 * 指数衰减包络, 参数与 Android 那边逐个对齐 (落子 900Hz/decay 55/gain 0.55,
 * 吃子 520Hz/decay 32/gain 0.85, 时长 0.12s)。
 *
 * BGM 是随项目一起生成的原创五声音阶环境音 (Android 侧 res/raw/bgm_ambient.ogg,
 * 这里转成 mp3 —— iOS 的 innerAudioContext 对 ogg 支持不稳)。音量压到 0.22:
 * 再高就会盖住落子声, 再低基本等于没有。
 */
const store = require('./store');

const SAMPLE_RATE = 44100;
const CLICK_SECONDS = 0.12;
const MOVE_VIBRATION = 'light';
const BG_VOLUME = 0.22;

let audioCtx = null;
let buffers = null;
let bgm = null;

/** WebAudio 在部分基础库上取不到, 取不到就静音降级, 不能让它把落子流程带崩 */
function context() {
  if (audioCtx !== null) return audioCtx;
  audioCtx = (typeof wx.createWebAudioContext === 'function')
    ? (() => { try { return wx.createWebAudioContext(); } catch (e) { return false; } })()
    : false;
  return audioCtx;
}

/** 合成一声敲击: 基频 + 八度泛音, 指数衰减包络。衰减越快听感越"硬" */
function buildClick(ctx, baseHz, decay, gain) {
  const frames = Math.floor(SAMPLE_RATE * CLICK_SECONDS);
  const buffer = ctx.createBuffer(1, frames, SAMPLE_RATE);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < frames; i += 1) {
    const t = i / SAMPLE_RATE;
    const envelope = Math.exp(-decay * t);
    const wave = Math.sin(2 * Math.PI * baseHz * t) + 0.5 * Math.sin(2 * Math.PI * baseHz * 2 * t);
    data[i] = Math.max(-1, Math.min(1, (envelope * wave * gain) / 1.5));
  }
  return buffer;
}

function ensureBuffers() {
  const ctx = context();
  if (!ctx) return null;
  if (!buffers) {
    buffers = {
      move: buildClick(ctx, 900, 55, 0.55),
      capture: buildClick(ctx, 520, 32, 0.85),
    };
  }
  return buffers;
}

function playClick(kind) {
  const ctx = context();
  const bank = ensureBuffers();
  if (!ctx || !bank) return;
  try {
    const source = ctx.createBufferSource();
    source.buffer = bank[kind];
    source.connect(ctx.destination);
    source.start(0);
  } catch (e) {
    // 播不出来就算了, 反馈缺一次不影响下棋
  }
}

function vibrate(type) {
  try {
    if (type === 'heavy') wx.vibrateShort({ type: 'medium' });
    else wx.vibrateShort({ type: MOVE_VIBRATION });
  } catch (e) {
    // 部分机型/模拟器没有马达
  }
}

// ====== 对外 ======

/** 走子。吃子用更重的音效与震动 */
function onMove(captured) {
  const settings = store.getSettings();
  if (settings.sound) playClick(captured ? 'capture' : 'move');
  if (settings.vibrate) vibrate(captured ? 'heavy' : 'light');
}

/** 被将军: 两下短震 + 吃子音 */
function onCheck() {
  const settings = store.getSettings();
  if (settings.sound) playClick('capture');
  if (!settings.vibrate) return;
  vibrate('heavy');
  setTimeout(() => vibrate('heavy'), 110);
}

/** 绝杀: 比被将军更重更久, 长-短-长三段, 表达"完了" */
function onCheckmate() {
  const settings = store.getSettings();
  if (settings.sound) playClick('capture');
  if (!settings.vibrate) return;
  [0, 140, 280].forEach((delay) => setTimeout(() => vibrate('heavy'), delay));
}

// ====== 背景音乐 ======
// 跟着界面走: 切后台就停, 免得用户在别的地方还听见。

function bgmResume() {
  if (!store.getSettings().bgm) {
    bgmStop();
    return;
  }
  if (!bgm) {
    bgm = wx.createInnerAudioContext();
    bgm.src = '/assets/bgm_ambient.mp3';
    bgm.loop = true;
    bgm.volume = BG_VOLUME;
  }
  bgm.play();
}

function bgmPause() {
  if (bgm) bgm.pause();
}

function bgmStop() {
  if (!bgm) return;
  bgm.stop();
  bgm.destroy();
  bgm = null;
}

module.exports = { onMove, onCheck, onCheckmate, bgmResume, bgmPause, bgmStop };
