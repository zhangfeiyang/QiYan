import os
import subprocess
import numpy as np
import wave

SAMPLE_RATE = 44100
RAW_DIR = "/home/zhangfy/zhongguoxiangqi/app/src/main/res/raw"
VOICES_DIR = "/home/zhangfy/zhongguoxiangqi/tools/raw_voices"
TEMP_DIR = "/tmp/jj_audio"
os.makedirs(RAW_DIR, exist_ok=True)
os.makedirs(TEMP_DIR, exist_ok=True)

def save_wav(filename, signal):
    # Normalize and clip
    sig = np.clip(signal, -1.0, 1.0)
    samples = (sig * 32767).astype(np.int16)
    with wave.open(filename, 'w') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(samples.tobytes())

def convert_to_ogg(in_wav, out_ogg):
    subprocess.run([
        'ffmpeg', '-y', '-i', in_wav,
        '-c:a', 'libvorbis', '-q:a', '5',
        out_ogg
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def mix_sfx_and_voice(sfx_wav, voice_mp3, out_ogg, delay_voice_ms=200):
    # Mix sfx and voice using ffmpeg
    subprocess.run([
        'ffmpeg', '-y',
        '-i', sfx_wav,
        '-itsoffset', str(delay_voice_ms / 1000.0),
        '-i', voice_mp3,
        '-filter_complex',
        '[0:a]volume=1.0[a0];[1:a]volume=1.2[a1];[a0][a1]amix=inputs=2:duration=longest[out]',
        '-map', '[out]',
        '-c:a', 'libvorbis', '-q:a', '5',
        out_ogg
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

# 1. Authentic wooden chess piece click (清脆实木落子音)
def make_move_click():
    dur = 0.16
    t = np.linspace(0, dur, int(SAMPLE_RATE * dur), endpoint=False)
    # Impact transient + wooden cavity resonance
    impact = np.sin(2 * np.pi * 1400 * t) * np.exp(-120 * t)
    body = np.sin(2 * np.pi * 420 * t) * np.exp(-45 * t)
    sub = np.sin(2 * np.pi * 210 * t) * np.exp(-35 * t)
    noise = np.random.normal(0, 0.15, len(t)) * np.exp(-180 * t)
    sig = 0.55 * impact + 0.35 * body + 0.25 * sub + 0.1 * noise
    return sig / np.max(np.abs(sig))

# 2. Heavy capture strike (沉重吃子碰击音)
def make_capture_strike():
    dur = 0.35
    t = np.linspace(0, dur, int(SAMPLE_RATE * dur), endpoint=False)
    # Double knock
    strike1 = np.sin(2 * np.pi * 750 * t) * np.exp(-55 * t)
    strike2_t = np.maximum(0, t - 0.025)
    strike2 = np.sin(2 * np.pi * 520 * strike2_t) * np.exp(-40 * strike2_t) * (t > 0.025)
    thump = np.sin(2 * np.pi * 160 * t) * np.exp(-25 * t)
    crack = np.random.normal(0, 0.3, len(t)) * np.exp(-90 * t)
    sig = 0.4 * strike1 + 0.5 * strike2 + 0.35 * thump + 0.2 * crack
    return sig / np.max(np.abs(sig))

# 3. Chinese martial gong / strike (威武战锣/拔剑将军音)
def make_check_gong():
    dur = 1.4
    t = np.linspace(0, dur, int(SAMPLE_RATE * dur), endpoint=False)
    # Gong frequencies with slight pitch bend
    f0 = 360 - 30 * t
    gong = (
        np.sin(2 * np.pi * f0 * t) * 1.0 +
        np.sin(2 * np.pi * f0 * 1.47 * t) * 0.55 +
        np.sin(2 * np.pi * f0 * 2.05 * t) * 0.35 +
        np.sin(2 * np.pi * f0 * 2.82 * t) * 0.2 +
        np.sin(2 * np.pi * 1800 * t) * 0.25 * np.exp(-35 * t) # clang
    ) * np.exp(-3.2 * t)
    drum = np.sin(2 * np.pi * (110 - 40 * t) * t) * np.exp(-6.0 * t)
    sig = gong * 0.75 + drum * 0.45
    return sig / np.max(np.abs(sig))

# 4. Victory fanfare (JJ象棋 欢庆胜局大铜锣与凯旋号角)
def make_victory_fanfare():
    dur = 2.8
    t = np.linspace(0, dur, int(SAMPLE_RATE * dur), endpoint=False)
    sig = np.zeros_like(t)
    # Big celebratory ceremonial gong at t=0
    f0 = 280 - 25 * t
    big_gong = (
        np.sin(2 * np.pi * f0 * t) +
        0.6 * np.sin(2 * np.pi * f0 * 1.52 * t) +
        0.4 * np.sin(2 * np.pi * f0 * 2.1 * t) +
        0.3 * np.sin(2 * np.pi * 2200 * t) * np.exp(-25 * t)
    ) * np.exp(-2.0 * t)
    sig += big_gong * 0.65

    # Triumphant Chinese pentatonic chord chime (D-F#-A-D, Gong & Shang notes)
    notes = [
        (0.08, 587.33), # D5
        (0.25, 739.99), # F#5
        (0.42, 880.00), # A5
        (0.60, 1174.66) # D6 (peak triumphant note!)
    ]
    for start, freq in notes:
        idx = (t >= start)
        t_note = t[idx] - start
        chime = (
            np.sin(2 * np.pi * freq * t_note) +
            0.4 * np.sin(2 * np.pi * freq * 2 * t_note) +
            0.2 * np.sin(2 * np.pi * freq * 3 * t_note)
        ) * np.exp(-4.5 * t_note)
        sig[idx] += chime * 0.45

    return sig / np.max(np.abs(sig))

# 5. Defeat tone (低沉古筝/幽咽余音)
def make_defeat_tone():
    dur = 2.0
    t = np.linspace(0, dur, int(SAMPLE_RATE * dur), endpoint=False)
    # Descending minor tone
    f = 330 - 90 * (t / dur)
    tone = (
        np.sin(2 * np.pi * f * t) +
        0.4 * np.sin(2 * np.pi * f * 2 * t) +
        0.2 * np.sin(2 * np.pi * f * 3 * t)
    ) * np.exp(-2.5 * t)
    drum = np.sin(2 * np.pi * (90 - 30 * t) * t) * np.exp(-5.0 * t)
    sig = tone * 0.65 + drum * 0.35
    return sig / np.max(np.abs(sig))

print("Building sound effects...")
sfx_move_wav = f"{TEMP_DIR}/move.wav"
sfx_capture_wav = f"{TEMP_DIR}/capture.wav"
sfx_check_wav = f"{TEMP_DIR}/check_gong.wav"
sfx_victory_wav = f"{TEMP_DIR}/victory_fanfare.wav"
sfx_defeat_wav = f"{TEMP_DIR}/defeat_tone.wav"

save_wav(sfx_move_wav, make_move_click())
save_wav(sfx_capture_wav, make_capture_strike())
save_wav(sfx_check_wav, make_check_gong())
save_wav(sfx_victory_wav, make_victory_fanfare())
save_wav(sfx_defeat_wav, make_defeat_tone())

# Convert basic sound effects to OGG
convert_to_ogg(sfx_move_wav, f"{RAW_DIR}/sfx_move.ogg")
convert_to_ogg(sfx_capture_wav, f"{RAW_DIR}/sfx_capture.ogg")

# Mix check gong with neural voice "将军！"
mix_sfx_and_voice(sfx_check_wav, f"{VOICES_DIR}/voice_check.mp3", f"{RAW_DIR}/sfx_check.ogg", delay_voice_ms=180)

# Mix victory fanfare with neural voice "绝杀！"
mix_sfx_and_voice(sfx_victory_wav, f"{VOICES_DIR}/voice_checkmate.mp3", f"{RAW_DIR}/sfx_checkmate.ogg", delay_voice_ms=300)

# Mix victory fanfare with neural voice "大获全胜！"
mix_sfx_and_voice(sfx_victory_wav, f"{VOICES_DIR}/voice_win.mp3", f"{RAW_DIR}/sfx_win.ogg", delay_voice_ms=350)

# Mix defeat tone with neural voice "棋差一着，惜败。"
mix_sfx_and_voice(sfx_defeat_wav, f"{VOICES_DIR}/voice_loss.mp3", f"{RAW_DIR}/sfx_loss.ogg", delay_voice_ms=250)

# Mix defeat tone with neural voice "握手言和。"
mix_sfx_and_voice(sfx_defeat_wav, f"{VOICES_DIR}/voice_draw.mp3", f"{RAW_DIR}/sfx_draw.ogg", delay_voice_ms=200)

# Tactical checks
mix_sfx_and_voice(sfx_check_wav, f"{VOICES_DIR}/voice_tactic_mahoupao.mp3", f"{RAW_DIR}/sfx_tactic_mahoupao.ogg", delay_voice_ms=150)
mix_sfx_and_voice(sfx_check_wav, f"{VOICES_DIR}/voice_tactic_tiemenshuan.mp3", f"{RAW_DIR}/sfx_tactic_tiemenshuan.ogg", delay_voice_ms=150)
mix_sfx_and_voice(sfx_check_wav, f"{VOICES_DIR}/voice_tactic_wochaoma.mp3", f"{RAW_DIR}/sfx_tactic_wochaoma.ogg", delay_voice_ms=150)
mix_sfx_and_voice(sfx_check_wav, f"{VOICES_DIR}/voice_tactic_shuangche.mp3", f"{RAW_DIR}/sfx_tactic_shuangche.ogg", delay_voice_ms=150)
mix_sfx_and_voice(sfx_check_wav, f"{VOICES_DIR}/voice_tactic_zhongpao.mp3", f"{RAW_DIR}/sfx_tactic_zhongpao.ogg", delay_voice_ms=150)

print("All JJ Xiangqi audio assets created successfully in res/raw/!")
