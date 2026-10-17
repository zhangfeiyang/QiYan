#!/usr/bin/env python3
"""Push metadata.json to App Store Connect: app-info localizations (name, subtitle, privacy URL) and
version localizations (description, keywords, support URL) for every locale. Re-runnable."""
import json
import os
import sys

from asc import api

APP_INFO = "b1b22729-a111-47bf-9db6-06685577b79e"
VERSION = "ff97d336-ed53-483a-85ff-3cfc1e365f15"
PRIVACY = "https://zhangfeiyang.github.io/qiyan-site/privacy-policy-ios.html"
SUPPORT = "https://zhangfeiyang.github.io/qiyan-site/"
MARKETING = "https://github.com/zhangfeiyang/QiYan"
HERE = os.path.dirname(os.path.abspath(__file__))
M = json.load(open(os.path.join(HERE, "metadata.json"), encoding="utf-8"))


def existing(path):
    out, url = {}, path
    while url:
        code, j = api("GET", url)
        assert code == 200, (code, j)
        for d in j["data"]:
            out[d["attributes"]["locale"]] = d["id"]
        url = j.get("links", {}).get("next")
    return out


def upsert(kind, rel, parent, have, loc, attrs):
    if loc in have:
        code, j = api("PATCH", f"/v1/{kind}/{have[loc]}", {"data": {"type": kind, "id": have[loc], "attributes": attrs}})
    else:
        code, j = api("POST", f"/v1/{kind}", {"data": {"type": kind, "attributes": dict(attrs, locale=loc),
                                                      "relationships": {rel: {"data": {"type": rel + "s", "id": parent}}}}})
    if code not in (200, 201):
        return f"{code} " + "; ".join(e.get("detail", e.get("title", "")) for e in j.get("errors", []))
    return None


def main():
    only = set(sys.argv[1:])
    info_have = existing(f"/v1/appInfos/{APP_INFO}/appInfoLocalizations?limit=200")
    ver_have = existing(f"/v1/appStoreVersions/{VERSION}/appStoreVersionLocalizations?limit=200")
    fails = []
    for loc, v in M.items():
        if only and loc not in only:
            continue
        e1 = upsert("appInfoLocalizations", "appInfo", APP_INFO, info_have, loc,
                    {"name": v["name"], "subtitle": v["subtitle"], "privacyPolicyUrl": PRIVACY})
        e2 = upsert("appStoreVersionLocalizations", "appStoreVersion", VERSION, ver_have, loc,
                    {"description": v["desc"], "keywords": v["keywords"], "supportUrl": SUPPORT, "marketingUrl": MARKETING})
        print(f"{loc:8} info={'ok' if not e1 else e1}  version={'ok' if not e2 else e2}", flush=True)
        if e1 or e2:
            fails.append(loc)
    print("failed:", fails)


if __name__ == "__main__":
    main()
