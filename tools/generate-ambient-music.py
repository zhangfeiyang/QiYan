#!/usr/bin/env python3
"""Create the original score 松风入局 / Pine Breeze for the Xiangqi app.

No samples, model outputs or third-party compositions. All notes, oscillator
partials and reverb below are original project source, under the project license.
Requires Python 3 + numpy + ffmpeg. Run from any directory. Deterministic output.
"""
from pathlib import Path
import subprocess
import tempfile
import wave
import numpy as np

RATE = 22050
DURATION = 64
FRAMES = RATE * DURATION
score = np.zeros((FRAMES, 2), dtype=np.float64)


def hz(midi):
    return 440 * 2 ** ((midi - 69) / 12)


def place(signal, start, pan=0.0):
    """Wrap note tails through the loop boundary, preserving reverb continuity."""
    idx = (np.arange(len(signal)) + round(start * RATE)) % FRAMES
    score[idx, 0] += signal * np.sqrt((1 - pan) / 2)
    score[idx, 1] += signal * np.sqrt((1 + pan) / 2)


def pluck(midi, start, level=0.15, pan=0.0):
    t = np.arange(RATE * 5) / RATE
    # The higher overtones decay faster, giving a soft wooden-string attack.
    tone = np.zeros_like(t)
    for partial, weight in [(1, 1), (2, .35), (3, .16), (4, .065), (6, .025)]:
        tone += weight * np.sin(2 * np.pi * hz(midi) * partial * t) * np.exp(-t * (1.6 + partial * .32))
    envelope = (1 - np.exp(-t * 150)) * np.minimum((5 - t) / .2, 1)
    place(tone * envelope * level, start, pan)


def pad(notes, start):
    t = np.arange(RATE * 12) / RATE
    envelope = np.sin(np.pi * np.minimum(t / 2.5, 1) / 2) ** 2
    envelope *= np.sin(np.pi * np.minimum((12 - t) / 3.5, 1) / 2) ** 2
    for i, note in enumerate(notes):
        # Slow beating is sparse enough to leave the board feedback clear.
        fundamental = np.sin(2 * np.pi * hz(note) * t)
        air = .24 * np.sin(2 * np.pi * hz(note) * 1.0013 * t + .4)
        place((fundamental + air) * envelope * .018, start, (i - 1) * .22)


# Eight 8-beat phrases, D major pentatonic, calm 60 BPM. Spacious and unhurried.
chords = [(38, 57, 64), (35, 54, 62), (43, 57, 64), (45, 59, 64),
          (38, 57, 66), (35, 54, 62), (43, 57, 64), (45, 57, 64)]
melodies = [(69, 66, 64, 62), (66, 69, 71, 66), (64, 69, 66, 64), (71, 69, 64, 62),
            (74, 71, 69, 66), (69, 66, 64, 62), (66, 64, 69, 71), (69, 66, 64, 62)]
for phrase, (chord, melody) in enumerate(zip(chords, melodies)):
    base = phrase * 8
    pad(chord, base - 1.0)
    pluck(chord[0] + 12, base + .12, .085, -.12)
    for beat, note in zip((.6, 2.25, 4.4, 6.0), melody):
        pluck(note, base + beat, .145 if beat == .6 else .115, -.28 if beat < 4 else .28)
    # A quiet answering note, not a percussion loop.
    pluck(chord[1] + 12, base + 5.25, .055, -.35)

# Stereo room reflections with periodic delay, so the loop has no dry seam.
dry = score.copy()
for seconds, gain in [(.19, .18), (.31, .13), (.47, .095), (.73, .07), (1.07, .035)]:
    reflection = np.roll(dry, round(seconds * RATE), axis=0)
    score += reflection[:, ::-1] * gain
score = np.tanh(score * 1.15)
score *= .72 / np.max(np.abs(score))
assert np.max(np.abs(score[-1] - score[0])) < .02, 'Unexpected loop discontinuity'
assert np.isfinite(score).all()

root = Path(__file__).resolve().parents[1]
destination = root / 'app/src/main/res/raw/bgm_ambient.ogg'
with tempfile.TemporaryDirectory() as tmp:
    pcm = Path(tmp) / 'pine-breeze.wav'
    with wave.open(str(pcm), 'wb') as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes((score * 32767).astype('<i2').tobytes())
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(pcm),
                    '-c:a', 'libvorbis', '-q:a', '3', '-metadata', 'title=Pine Breeze / 松风入局',
                    '-metadata', 'artist=Qiyan original procedural score', str(destination)], check=True)
print(f'{destination}: {DURATION}s stereo, {destination.stat().st_size // 1024} KiB')
print(f'Peak {np.max(np.abs(score)):.3f}; RMS {np.sqrt(np.mean(score ** 2)):.3f}; boundary step {np.max(np.abs(score[-1] - score[0])):.6f}')
