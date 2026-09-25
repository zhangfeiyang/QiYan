#!/usr/bin/env python3
"""
Polish-pass audio assets (2026-09).

为"配音补齐"一轮生成的新音效, 与 create_jj_game_audio.py 同一套路:
numpy 合成器乐底床 → ffmpeg 与 edge-tts 神经语音 (tools/raw_voices/) 混音
→ libvorbis ogg 落入 res/raw/.

不复用 create_master_jj_audio.py: 它的锣/号角采样源在 /tmp, 不可再生.
本脚本所有底床自合成, 语音缺失时报错提示先跑 generate_game_voices.py.

产物 (10 个):
  sfx_game_start     柔锣 + 「红方先行。」
  sfx_puzzle_solved  明亮五声 chime + 「妙手！」
  sfx_puzzle_fail    闷低锣 + 「再来一次。」
  sfx_campaign_clear 锣 + 短号角 + 「过关！」
  sfx_undo           下滑 whoosh + 木鱼 tick
  sfx_shutter        相机快门双噪声爆
  sfx_chime          识别成功 D5→A5 拨弦
  sfx_wrong          闷低双音 (答错/识别失败)
  sfx_lock           实时锁定 880Hz blip
  sfx_move_opponent  由现有 sfx_move 变调派生 (对手走子音色区分)
"""
import os
import subprocess
import sys
import wave

import numpy as np

SAMPLE_RATE = 44100
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(REPO, "app/src/main/res/raw")
VOICES_DIR = os.path.join(REPO, "tools/raw_voices")
TEMP_DIR = "/tmp/jj_polish_audio"
os.makedirs(TEMP_DIR, exist_ok=True)

PEAK = 0.9


def save_wav(filename, signal):
    sig = np.clip(signal, -1.0, 1.0)
    samples = (sig * 32767).astype(np.int16)
    with wave.open(filename, "w") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(samples.tobytes())


def norm(sig):
    peak = np.max(np.abs(sig))
    return sig / peak * PEAK if peak > 0 else sig


def t_axis(dur):
    return np.linspace(0, dur, int(SAMPLE_RATE * dur), endpoint=False)


def convert_to_ogg(in_wav, out_ogg, dur=None):
    cmd = ["ffmpeg", "-y", "-i", in_wav]
    if dur:
        cmd += ["-t", str(dur)]
    cmd += ["-c:a", "libvorbis", "-q:a", "5", out_ogg]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def mix_voice(sfx_wav, voice_name, out_ogg, delay_ms, dur, voice_vol=1.3):
    voice = os.path.join(VOICES_DIR, f"{voice_name}.mp3")
    if not os.path.exists(voice):
        sys.exit(f"missing voice {voice}; run tools/generate_game_voices.py first")
    subprocess.run([
        "ffmpeg", "-y",
        "-i", sfx_wav,
        "-itsoffset", str(delay_ms / 1000.0),
        "-i", voice,
        "-filter_complex",
        f"[0:a]volume=1.0[a0];[1:a]volume={voice_vol}[a1];"
        "[a0][a1]amix=inputs=2:duration=longest:dropout_transition=0[mix];"
        "[mix]dynaudnorm=p=0.9:m=3.0,alimiter=limit=0.97[out]",
        "-map", "[out]",
        "-c:a", "libvorbis", "-q:a", "5",
        "-t", str(dur),
        out_ogg,
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


# ---- 合成底床 -------------------------------------------------------------

def gong(dur=2.4, f_base=340.0, decay=3.0, brightness=1.0):
    """中式锣: 基频下滑 + 非谐泛音 + 敲击 clang."""
    t = t_axis(dur)
    f0 = f_base - 28 * t
    sig = (
        np.sin(2 * np.pi * f0 * t)
        + 0.55 * np.sin(2 * np.pi * f0 * 1.47 * t)
        + 0.35 * np.sin(2 * np.pi * f0 * 2.05 * t)
        + brightness * 0.25 * np.sin(2 * np.pi * 1750 * t) * np.exp(-35 * t)
    ) * np.exp(-decay * t)
    drum = np.sin(2 * np.pi * (105 - 35 * t) * t) * np.exp(-6.0 * t)
    return norm(sig * 0.75 + drum * 0.4)


def chime_pentatonic(dur=1.8):
    """明亮五声琶音 D5-F#5-A5-D6 (与胜局 fanfare 同族, 但轻快)."""
    t = t_axis(dur)
    sig = np.zeros_like(t)
    for start, freq in [(0.0, 587.33), (0.11, 739.99), (0.22, 880.0), (0.36, 1174.66)]:
        idx = t >= start
        tn = t[idx] - start
        sig[idx] += (
            np.sin(2 * np.pi * freq * tn)
            + 0.4 * np.sin(2 * np.pi * freq * 2 * tn)
            + 0.18 * np.sin(2 * np.pi * freq * 3 * tn)
        ) * np.exp(-5.0 * tn) * 0.42
    return norm(sig)


def short_fanfare(dur=1.6):
    """凯旋号角的短版: 三个上行五声音."""
    t = t_axis(dur)
    sig = np.zeros_like(t)
    for start, freq in [(0.0, 440.0), (0.16, 587.33), (0.32, 880.0)]:
        idx = t >= start
        tn = t[idx] - start
        # 略带铜管感: 基频 + 渐强包络
        env = np.minimum(1.0, tn * 22) * np.exp(-2.2 * tn)
        sig[idx] += (
            np.sin(2 * np.pi * freq * tn)
            + 0.5 * np.sin(2 * np.pi * freq * 2 * tn)
            + 0.25 * np.sin(2 * np.pi * freq * 3 * tn)
        ) * env * 0.4
    return norm(sig)


def whoosh_down(dur=0.5):
    """悔棋: 下滑噪声扫 + 木鱼 tick 收尾."""
    t = t_axis(dur)
    # 带限噪声, 中心频率随时间下滑
    noise = np.random.normal(0, 1.0, len(t))
    freq = 2400 - 3200 * (t / dur)
    phase = 2 * np.pi * np.cumsum(np.clip(freq, 300, None)) / SAMPLE_RATE
    swept = noise * 0.35 * np.sin(phase) * np.exp(-5.5 * t)
    # 木鱼 tick @ 0.32s (借用落子音配方)
    tick = np.zeros_like(t)
    idx = t >= 0.32
    tt = t[idx] - 0.32
    tick[idx] = (
        0.55 * np.sin(2 * np.pi * 1300 * tt) * np.exp(-130 * tt)
        + 0.4 * np.sin(2 * np.pi * 430 * tt) * np.exp(-50 * tt)
    )
    return norm(swept + tick)


def shutter(dur=0.3):
    """相机快门: 两声极短机械噪声爆 (反光板起/落)."""
    t = t_axis(dur)
    sig = np.zeros_like(t)
    for start, amp, decay in [(0.0, 0.9, 260), (0.085, 0.65, 200)]:
        idx = t >= start
        tn = t[idx] - start
        burst = np.random.normal(0, 1.0, idx.sum()) * np.exp(-decay * tn)
        click = np.sin(2 * np.pi * 2600 * tn) * np.exp(-320 * tn) * 0.5
        sig[idx] += (burst * 0.5 + click) * amp
    return norm(sig)


def pluck_up(dur=0.9):
    """识别成功: D5→A5 两音拨弦."""
    t = t_axis(dur)
    sig = np.zeros_like(t)
    for start, freq in [(0.0, 587.33), (0.16, 880.0)]:
        idx = t >= start
        tn = t[idx] - start
        sig[idx] += (
            np.sin(2 * np.pi * freq * tn)
            + 0.45 * np.sin(2 * np.pi * freq * 2 * tn)
            + 0.15 * np.sin(2 * np.pi * freq * 3 * tn)
        ) * np.exp(-6.0 * tn) * 0.5
    return norm(sig)


def wrong_double(dur=0.6):
    """答错/失败: 闷低失谐双音 (不刺耳, 不羞辱)."""
    t = t_axis(dur)
    f = 196.0 - 30 * (t / dur)  # G3 下滑
    sig = (
        np.sin(2 * np.pi * f * t)
        + np.sin(2 * np.pi * f * 1.06 * t)  # 失谐拍频
        + 0.3 * np.sin(2 * np.pi * f * 2 * t)
    ) * np.exp(-4.5 * t)
    thump = np.sin(2 * np.pi * 85 * t) * np.exp(-7.0 * t) * 0.5
    return norm(sig * 0.7 + thump)


def blip(dur=0.25, freq=880.0):
    """实时锁定: 干净短 blip."""
    t = t_axis(dur)
    env = np.minimum(1.0, t * 400) * np.exp(-14 * t)
    sig = (np.sin(2 * np.pi * freq * t) + 0.3 * np.sin(2 * np.pi * freq * 2 * t)) * env
    return norm(sig)


# ---- 产出 -----------------------------------------------------------------

print("Building polish-pass sfx beds...")
beds = {
    "game_start": (gong(dur=2.4, f_base=320, decay=2.6, brightness=0.6), 2.4),
    "puzzle_solved": (chime_pentatonic(1.8), 1.8),
    "puzzle_fail": (gong(dur=2.2, f_base=210, decay=3.4, brightness=0.35), 2.2),
    "campaign_clear": (None, None),  # 锣+号角合成, 见下
    "undo": (whoosh_down(0.5), 0.5),
    "shutter": (shutter(0.3), 0.3),
    "chime": (pluck_up(0.9), 0.9),
    "wrong": (wrong_double(0.6), 0.6),
    "lock": (blip(0.25), 0.25),
}

# campaign: 锣垫底, 0.25s 后叠短号角
camp_gong = gong(dur=2.6, f_base=300, decay=2.2, brightness=0.8) * 0.6
fan = short_fanfare(1.6) * 0.55
offset = int(0.25 * SAMPLE_RATE)
camp = np.zeros(int(2.6 * SAMPLE_RATE))
camp[: len(camp_gong)] += camp_gong[: len(camp)]
camp[offset: offset + len(fan)] += fan[: len(camp) - offset]
beds["campaign_clear"] = (norm(camp), 2.6)

for name, (sig, dur) in beds.items():
    wav = os.path.join(TEMP_DIR, f"{name}.wav")
    save_wav(wav, sig)
    print(f"  bed {name}.wav ({dur}s)")

print("Mixing voice lines...")
mix_voice(f"{TEMP_DIR}/game_start.wav", "voice_game_start",
          f"{RAW_DIR}/sfx_game_start.ogg", delay_ms=300, dur=2.6)
mix_voice(f"{TEMP_DIR}/puzzle_solved.wav", "voice_puzzle_solved",
          f"{RAW_DIR}/sfx_puzzle_solved.ogg", delay_ms=180, dur=2.2)
mix_voice(f"{TEMP_DIR}/puzzle_fail.wav", "voice_puzzle_fail",
          f"{RAW_DIR}/sfx_puzzle_fail.ogg", delay_ms=250, dur=2.4)
mix_voice(f"{TEMP_DIR}/campaign_clear.wav", "voice_campaign_clear",
          f"{RAW_DIR}/sfx_campaign_clear.ogg", delay_ms=350, dur=3.0)

print("Encoding pure sfx...")
for name in ["undo", "shutter", "chime", "wrong", "lock"]:
    sig, dur = beds[name]
    convert_to_ogg(f"{TEMP_DIR}/{name}.wav", f"{RAW_DIR}/sfx_{name}.ogg", dur=dur)

print("Deriving opponent move timbre from sfx_move...")
subprocess.run([
    "ffmpeg", "-y", "-i", f"{RAW_DIR}/sfx_move.ogg",
    "-af", "asetrate=44100*0.88,aresample=44100,atempo=1.136,volume=0.85",
    "-c:a", "libvorbis", "-q:a", "5",
    f"{RAW_DIR}/sfx_move_opponent.ogg",
], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

print("Done. New files in res/raw/:")
for f in sorted(os.listdir(RAW_DIR)):
    if any(k in f for k in ["game_start", "puzzle_", "campaign_clear", "undo",
                            "shutter", "chime", "wrong", "lock", "opponent"]):
        size = os.path.getsize(os.path.join(RAW_DIR, f))
        print(f"  {f}  {size // 1024}KB")
