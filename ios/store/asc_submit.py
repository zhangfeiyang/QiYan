#!/usr/bin/env python3
"""Attach the processed build to version 1.0 and submit it for App Review, then confirm the queue state.

Usage: asc_submit.py <build-number>
"""
import os
import sys
import time


from asc import api  # noqa: E402

APP = "6823980563"
VERSION = "ff97d336-ed53-483a-85ff-3cfc1e365f15"
FINAL = {"WAITING_FOR_REVIEW", "IN_REVIEW", "PENDING_DEVELOPER_RELEASE", "READY_FOR_SALE"}


def must(code, j, what):
    if code not in (200, 201, 204):
        raise SystemExit(f"{what}: {code} {j}")
    return j.get("data") if isinstance(j, dict) else None


def main():
    number = sys.argv[1]
    c, j = api("GET", f"/v1/builds?filter[app]={APP}&filter[version]={number}&filter[preReleaseVersion.version]=1.0&limit=5")
    builds = must(c, j, "builds")
    assert len(builds) == 1, builds
    b = builds[0]
    print("build", b["id"], b["attributes"]["processingState"], "usesNonExemptEncryption=", b["attributes"].get("usesNonExemptEncryption"))
    assert b["attributes"]["processingState"] == "VALID"
    must(*api("PATCH", f"/v1/appStoreVersions/{VERSION}/relationships/build", {"data": {"type": "builds", "id": b["id"]}}), "attach build")
    c, j = api("GET", f"/v1/apps/{APP}/reviewSubmissions?filter[platform]=IOS&limit=50")
    open_subs = [s for s in must(c, j, "list submissions") if s["attributes"]["state"] in ("READY_FOR_REVIEW", "UNRESOLVED_ISSUES")]
    if open_subs:
        sid = open_subs[0]["id"]
    else:
        sid = must(*api("POST", "/v1/reviewSubmissions", {"data": {"type": "reviewSubmissions", "attributes": {"platform": "IOS"},
                   "relationships": {"app": {"data": {"type": "apps", "id": APP}}}}}), "create submission")["id"]
    c, j = api("GET", f"/v1/reviewSubmissions/{sid}/items?include=appStoreVersion")
    items = must(c, j, "items")
    if not any((i.get("relationships", {}).get("appStoreVersion", {}).get("data") or {}).get("id") == VERSION for i in items):
        must(*api("POST", "/v1/reviewSubmissionItems", {"data": {"type": "reviewSubmissionItems", "relationships": {
            "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": sid}},
            "appStoreVersion": {"data": {"type": "appStoreVersions", "id": VERSION}}}}}), "add item")
    must(*api("PATCH", f"/v1/reviewSubmissions/{sid}", {"data": {"type": "reviewSubmissions", "id": sid, "attributes": {"submitted": True}}}), "submit")
    for _ in range(20):
        v = must(*api("GET", f"/v1/appStoreVersions/{VERSION}"), "version")["attributes"]["appStoreState"]
        s = must(*api("GET", f"/v1/reviewSubmissions/{sid}"), "submission")["attributes"]["state"]
        print("version:", v, " submission:", s, flush=True)
        if v in FINAL and s in ("WAITING_FOR_REVIEW", "IN_REVIEW", "COMPLETE"):
            print("FINAL", v)
            return
        time.sleep(20)
    raise SystemExit("not in review queue yet")


if __name__ == "__main__":
    main()
