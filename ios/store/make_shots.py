#!/usr/bin/env python3
"""UI-test screenshots (tools/ui_test.sh testStoreShots, 1320x2868 PNG per app language)
-> out/<ASC locale>/<n>.jpg for asc_upload_shots.py.   python3 make_shots.py <shots dir>"""
import os
import sys

from PIL import Image

LOCALES = {"zh-Hans": "zh-Hans", "zh-Hant": "zh-Hant", "en": "en-US", "ja": "ja", "ko": "ko", "es": "es-ES",
           "pt-BR": "pt-BR", "fr": "fr-FR", "de": "de-DE", "it": "it", "ru": "ru", "tr": "tr", "ar": "ar-SA",
           "id": "id", "th": "th", "vi": "vi"}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def main():
    src = sys.argv[1]
    for lang, loc in LOCALES.items():
        d = os.path.join(OUT, loc)
        os.makedirs(d, exist_ok=True)
        files = sorted(f for f in os.listdir(os.path.join(src, lang)) if f.endswith(".png"))
        for f in files:
            n = f.split("_")[-2]          # "<lang>_<n>_<name>.png"
            im = Image.open(os.path.join(src, lang, f)).convert("RGB")
            assert im.size == (1320, 2868), (f, im.size)
            im.save(os.path.join(d, f"{n}.jpg"), quality=92)
        print(loc, len(files))


if __name__ == "__main__":
    main()
