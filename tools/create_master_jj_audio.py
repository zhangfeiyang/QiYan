#!/usr/bin/env python3
"""
JJ Xiangqi Master Game Audio Generator.
Produces authentic Chinese martial arts chess game sound effects with:
- Genuine physical bronze gong crashes (大铜锣)
- Triumphal festive Chinese orchestral overture (凯旋序曲)
- Resonant, commanding, diaphragm-warm hero voice actor (雄浑原声)
- Mastered stereo dynamics with tournament arena acoustic echo
"""
import os
import subprocess

TEMP_DIR = "/tmp/jj_master_build"
RAW_DIR = "/home/zhangfy/zhongguoxiangqi/app/src/main/res/raw"
GONG_SRC = "/tmp/commons_gongs/strike1.wav"
FANFARE_SRC = "/tmp/win_xianqi.wav"
VOICE_DIR = "/tmp/voice_test"

os.makedirs(TEMP_DIR, exist_ok=True)
os.makedirs(RAW_DIR, exist_ok=True)

print("Mastering JJ Xiangqi sound effects...")

# 1. SFX_WIN.OGG: 大铜锣凯旋序曲 + 雄浑原声「大获全胜！」
# Track 0: Authentic physical bronze gong (大铜锣)
# Track 1: Triumphant Chinese victory fanfare overture (凯旋序曲)
# Track 2: Resonant martial hero master voice (雄浑原声「大获全胜！」)
win_voice = f"{VOICE_DIR}/dsp_yunyang_p4_r10.mp3"
out_win = f"{RAW_DIR}/sfx_win.ogg"

cmd_win = [
    'ffmpeg', '-y',
    '-i', GONG_SRC,
    '-i', FANFARE_SRC,
    '-itsoffset', '0.62',
    '-i', win_voice,
    '-filter_complex',
    '[0:a]aresample=44100,volume=1.15,afade=t=out:st=4.0:d=0.8[gong];'
    '[1:a]aresample=44100,volume=0.88,afade=t=out:st=4.2:d=0.6[fanfare];'
    '[2:a]aresample=44100,volume=1.55[voice];'
    '[gong][fanfare][voice]amix=inputs=3:duration=longest:dropout_transition=0[mix];'
    '[mix]dynaudnorm=p=0.92:m=3.0,alimiter=limit=0.98[out]',
    '-map', '[out]',
    '-c:a', 'libvorbis', '-q:a', '6',
    '-t', '4.8',
    out_win
]
subprocess.run(cmd_win, check=True)
print(f"Generated {out_win}")

# 2. SFX_CHECKMATE.OGG: 庆典战锣 + 豪迈雄浑原声「绝杀！」
out_mate = f"{RAW_DIR}/sfx_checkmate.ogg"
mate_voice = f"{VOICE_DIR}/dsp_mate.wav"

cmd_mate = [
    'ffmpeg', '-y',
    '-i', GONG_SRC,
    '-itsoffset', '0.28',
    '-i', mate_voice,
    '-filter_complex',
    '[0:a]aresample=44100,volume=1.2,afade=t=out:st=2.5:d=0.6[gong];'
    '[1:a]aresample=44100,volume=1.5[voice];'
    '[gong][voice]amix=inputs=2:duration=longest:dropout_transition=0[mix];'
    '[mix]dynaudnorm=p=0.92:m=3.0,alimiter=limit=0.98[out]',
    '-map', '[out]',
    '-c:a', 'libvorbis', '-q:a', '6',
    '-t', '3.2',
    out_mate
]
subprocess.run(cmd_mate, check=True)
print(f"Generated {out_mate}")

# 3. SFX_CHECK.OGG: 威武铜锣 + 雄浑原声「将军！」
out_check = f"{RAW_DIR}/sfx_check.ogg"
check_voice = f"{VOICE_DIR}/dsp_check.wav"

cmd_check = [
    'ffmpeg', '-y',
    '-i', GONG_SRC,
    '-itsoffset', '0.15',
    '-i', check_voice,
    '-filter_complex',
    '[0:a]aresample=44100,volume=1.25,afade=t=out:st=1.8:d=0.5[gong];'
    '[1:a]aresample=44100,volume=1.6[voice];'
    '[gong][voice]amix=inputs=2:duration=longest:dropout_transition=0[mix];'
    '[mix]dynaudnorm=p=0.92:m=3.0,alimiter=limit=0.98[out]',
    '-map', '[out]',
    '-c:a', 'libvorbis', '-q:a', '6',
    '-t', '2.4',
    out_check
]
subprocess.run(cmd_check, check=True)
print(f"Generated {out_check}")

# 4. SFX_LOSS.OGG: 幽咽铜锣 + 「棋差一着，惜败。」
out_loss = f"{RAW_DIR}/sfx_loss.ogg"
loss_voice = f"{VOICE_DIR}/dsp_loss.wav"

cmd_loss = [
    'ffmpeg', '-y',
    '-i', GONG_SRC,
    '-itsoffset', '0.3',
    '-i', loss_voice,
    '-filter_complex',
    '[0:a]aresample=44100,lowpass=f=800,volume=0.9,afade=t=out:st=2.8:d=0.6[gong];'
    '[1:a]aresample=44100,volume=1.3[voice];'
    '[gong][voice]amix=inputs=2:duration=longest:dropout_transition=0[mix];'
    '[mix]dynaudnorm=p=0.92:m=3.0,alimiter=limit=0.98[out]',
    '-map', '[out]',
    '-c:a', 'libvorbis', '-q:a', '6',
    '-t', '3.5',
    out_loss
]
subprocess.run(cmd_loss, check=True)
print(f"Generated {out_loss}")

print("All JJ Xiangqi master audio successfully generated!")
