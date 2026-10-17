#!/usr/bin/env python3
"""Android strings (values*/*.xml) + ios/tools/ios_strings.json -> iOS .lproj tables.

Keys stay the Android resource names, so Swift says L("game_title") and shows the same
text as the Android app. Format specifiers are converted (%1$s -> %1$@, %d -> %ld,
%c -> %@). iOS-only strings live in ios_strings.json as {key: {lang: text}} with "en"
required; languages missing there fall back to English.

Writes ios/QiYan/Resources/<lproj>/Localizable.strings and InfoPlist.strings.
"""
import json
import os
import re
import xml.etree.ElementTree as ET

IOS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(IOS, "..", "app", "src", "main", "res")
OUT = os.path.join(IOS, "QiYan", "Resources")

# Android values folder -> iOS lproj
LANGS = {
    "values": "en", "values-zh": "zh-Hans", "values-zh-rTW": "zh-Hant", "values-ja": "ja", "values-ko": "ko",
    "values-es": "es", "values-pt-rBR": "pt-BR", "values-fr": "fr", "values-de": "de", "values-it": "it",
    "values-ru": "ru", "values-tr": "tr", "values-ar": "ar", "values-id": "id", "values-th": "th", "values-vi": "vi",
}

# The iOS app has no AI assistant (it called a cloud LLM); its strings are not shipped.
SKIP = re.compile(r"^(advisor_|analysis_summary_prompt$)")


def android_text(el):
    s = "".join(el.itertext()).strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    out, i = [], 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s):
            nxt = s[i + 1]
            if nxt == "u" and i + 5 < len(s):
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


FMT = re.compile(r"%(\d+\$)?([-#+ 0,(]*\d*)(\.\d+)?([sdfxc%])")


def convert_format(s):
    def rep(m):
        pos, flags, prec, conv = m.group(1) or "", m.group(2) or "", m.group(3) or "", m.group(4)
        if conv == "%":
            return "%%"
        if conv in "sc":
            return f"%{pos}@"
        if conv == "d":
            return f"%{pos}{flags}ld"
        return f"%{pos}{flags}{prec}{conv}"
    return FMT.sub(rep, s)


def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def load(folder):
    table = {}
    path = os.path.join(RES, folder)
    for name in sorted(os.listdir(path)):
        if not name.endswith(".xml"):
            continue
        root = ET.parse(os.path.join(path, name)).getroot()
        for el in root:
            key = el.get("name")
            if el.tag != "string" or SKIP.match(key or ""):
                continue
            table[key] = convert_format(android_text(el))
    return table


def main():
    extra = json.load(open(os.path.join(IOS, "tools", "ios_strings.json"), encoding="utf-8"))
    base = load("values")
    for folder, lproj in LANGS.items():
        table = dict(base)
        table.update(load(folder))
        for key, per_lang in extra.items():
            text = per_lang.get(lproj) or per_lang.get(lproj.split("-")[0])
            if text is None and key in table:
                continue    # 只覆盖个别语言的安卓串, 其余语言沿用安卓
            table[key] = text or per_lang["en"]
        d = os.path.join(OUT, f"{lproj}.lproj")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "Localizable.strings"), "w", encoding="utf-8") as f:
            for k in sorted(table):
                f.write(f'"{esc(k)}" = "{esc(table[k])}";\n')
        info = {
            "CFBundleDisplayName": table["app_name"],
            "NSCameraUsageDescription": table["ios_camera_usage"],
            "NSMicrophoneUsageDescription": table["ios_microphone_usage"],
            "NSSpeechRecognitionUsageDescription": table["ios_speech_usage"],
            "NSPhotoLibraryUsageDescription": table["ios_photos_usage"],
        }
        with open(os.path.join(d, "InfoPlist.strings"), "w", encoding="utf-8") as f:
            for k, v in info.items():
                f.write(f'"{k}" = "{esc(v)}";\n')
    print(f"wrote {len(LANGS)} localizations; {len(base)} Android keys + {len(extra)} iOS keys")


if __name__ == "__main__":
    main()
