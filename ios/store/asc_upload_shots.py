#!/usr/bin/env python3
"""Replace the 6.9" iPhone screenshots of every version localization with out/<locale>/<n>.jpg (see make_shots.py)."""
import hashlib
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor


from asc import api  # noqa: E402

VERSION = "ff97d336-ed53-483a-85ff-3cfc1e365f15"
DISPLAY = "APP_IPHONE_67"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def put(op, data):
    for attempt in range(4):
        try:
            req = urllib.request.Request(op["url"], method=op["method"], data=data[op["offset"]:op["offset"] + op["length"]],
                                         headers={h["name"]: h["value"] for h in op["requestHeaders"]})
            with urllib.request.urlopen(req, timeout=180) as r:
                r.read()
            return
        except Exception:
            if attempt == 3:
                raise
            time.sleep(4)


def do_locale(loc, loc_id):
    files = sorted(f for f in os.listdir(os.path.join(OUT, loc)) if f.endswith(".jpg"))
    c, sets = api("GET", f"/v1/appStoreVersionLocalizations/{loc_id}/appScreenshotSets")
    set_id = next((s["id"] for s in sets.get("data", []) if s["attributes"]["screenshotDisplayType"] == DISPLAY), None)
    if not set_id:
        c, r = api("POST", "/v1/appScreenshotSets", {"data": {"type": "appScreenshotSets", "attributes": {"screenshotDisplayType": DISPLAY},
                   "relationships": {"appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": loc_id}}}}})
        if c != 201:
            return f"{loc}: set {c} {r}"
        set_id = r["data"]["id"]
    c, old = api("GET", f"/v1/appScreenshotSets/{set_id}/appScreenshots?limit=50")
    for s in old.get("data", []):
        api("DELETE", f"/v1/appScreenshots/{s['id']}")
    for fn in files:
        data = open(os.path.join(OUT, loc, fn), "rb").read()
        c, r = api("POST", "/v1/appScreenshots", {"data": {"type": "appScreenshots", "attributes": {"fileName": f"{loc}_{fn}", "fileSize": len(data)},
                   "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets", "id": set_id}}}}})
        if c != 201:
            return f"{loc}/{fn}: create {c} {r}"
        sid = r["data"]["id"]
        for op in r["data"]["attributes"]["uploadOperations"]:
            put(op, data)
        c, r = api("PATCH", f"/v1/appScreenshots/{sid}", {"data": {"type": "appScreenshots", "id": sid,
                   "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(data).hexdigest()}}})
        if c != 200:
            return f"{loc}/{fn}: commit {c} {r}"
    return None


def verify(loc, loc_id):
    c, sets = api("GET", f"/v1/appStoreVersionLocalizations/{loc_id}/appScreenshotSets?include=appScreenshots")
    states = [i["attributes"]["assetDeliveryState"]["state"] for i in sets.get("included", []) if i["type"] == "appScreenshots"]
    return states


def main():
    only = set(sys.argv[1:])
    locs, url = {}, f"/v1/appStoreVersions/{VERSION}/appStoreVersionLocalizations?limit=200"
    c, j = api("GET", url)
    for d in j["data"]:
        locs[d["attributes"]["locale"]] = d["id"]
    todo = [(l, i) for l, i in locs.items() if (not only or l in only) and os.path.isdir(os.path.join(OUT, l))]
    with ThreadPoolExecutor(6) as ex:
        for (loc, _), err in zip(todo, ex.map(lambda t: do_locale(*t), todo)):
            print(loc, "ok" if not err else err, flush=True)
    time.sleep(20)
    bad = {}
    for loc, i in todo:
        st = verify(loc, i)
        if len(st) != len(os.listdir(os.path.join(OUT, loc))) or any(s != "COMPLETE" for s in st):
            bad[loc] = st
    print("verify:", "all complete" if not bad else bad)


if __name__ == "__main__":
    main()
