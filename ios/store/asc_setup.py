#!/usr/bin/env python3
"""One-time App Store Connect setup for 棋眼 (app 6823980563), idempotent where the API allows.

  python3 asc_setup.py [step ...]     steps: rights categories age version review price territories eula
Credentials come from ASC_KEY_PATH / AC_API_KEY_ID / AC_API_ISSUER_ID (see asc.py).
App Privacy ("Data Not Collected") has no public API and is set in the web UI.
"""
import json
import os
import sys
import time

from asc import api

APP = "6823980563"
APP_INFO = "b1b22729-a111-47bf-9db6-06685577b79e"
VERSION = "ff97d336-ed53-483a-85ff-3cfc1e365f15"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")


def show(label, code, body):
    ok = code in (200, 201, 204)
    detail = "" if ok else "; ".join(e.get("detail") or e.get("title", "") for e in body.get("errors", []))[:500]
    print(f"{label}: {code} {detail}", flush=True)
    return ok


def rights():
    # 引擎/模型/字体都是开源许可授权的第三方内容, 照实声明 "使用第三方内容 (有权使用)"
    show("contentRightsDeclaration", *api("PATCH", f"/v1/apps/{APP}", {"data": {
        "type": "apps", "id": APP, "attributes": {"contentRightsDeclaration": "USES_THIRD_PARTY_CONTENT"}}}))


def categories():
    rel = lambda i: {"data": {"type": "appCategories", "id": i}}
    show("categories", *api("PATCH", f"/v1/appInfos/{APP_INFO}", {"data": {
        "type": "appInfos", "id": APP_INFO, "relationships": {
            "primaryCategory": rel("GAMES"), "primarySubcategoryOne": rel("GAMES_BOARD"),
            "primarySubcategoryTwo": rel("GAMES_STRATEGY")}}}))


def age():
    code, j = api("GET", f"/v1/appInfos/{APP_INFO}/ageRatingDeclaration")
    rid = j["data"]["id"]
    none = ["alcoholTobaccoOrDrugUseOrReferences", "contests", "gamblingSimulated", "gunsOrOtherWeapons",
            "horrorOrFearThemes", "matureOrSuggestiveThemes", "medicalOrTreatmentInformation", "profanityOrCrudeHumor",
            "sexualContentGraphicAndNudity", "sexualContentOrNudity", "violenceCartoonOrFantasy", "violenceRealistic",
            "violenceRealisticProlongedGraphicOrSadistic"]
    false = ["advertising", "gambling", "healthOrWellnessTopics", "lootBox", "messagingAndChat", "parentalControls",
             "ageAssurance", "socialMedia", "unrestrictedWebAccess", "userGeneratedContent"]
    attrs = {k: "NONE" for k in none} | {k: False for k in false}
    show("ageRating", *api("PATCH", f"/v1/ageRatingDeclarations/{rid}", {"data": {
        "type": "ageRatingDeclarations", "id": rid, "attributes": attrs}}))


def version():
    show("version", *api("PATCH", f"/v1/appStoreVersions/{VERSION}", {"data": {
        "type": "appStoreVersions", "id": VERSION,
        "attributes": {"copyright": "2026 Feiyang Zhang", "releaseType": "AFTER_APPROVAL"}}}))


def review():
    notes = open(os.path.join(HERE, "review_notes.txt"), encoding="utf-8").read().strip()
    # 审核联系人不进仓库: ASC_CONTACT_FIRST / ASC_CONTACT_LAST / ASC_CONTACT_EMAIL / ASC_CONTACT_PHONE
    env = os.environ
    attrs = {"contactFirstName": env["ASC_CONTACT_FIRST"], "contactLastName": env["ASC_CONTACT_LAST"],
             "contactEmail": env["ASC_CONTACT_EMAIL"], "contactPhone": env["ASC_CONTACT_PHONE"],
             "demoAccountRequired": False, "notes": notes}
    code, j = api("GET", f"/v1/appStoreVersions/{VERSION}/appStoreReviewDetail")
    if code == 200 and j.get("data"):
        rid = j["data"]["id"]
        show("reviewDetail PATCH", *api("PATCH", f"/v1/appStoreReviewDetails/{rid}", {"data": {
            "type": "appStoreReviewDetails", "id": rid, "attributes": attrs}}))
    else:
        show("reviewDetail POST", *api("POST", "/v1/appStoreReviewDetails", {"data": {
            "type": "appStoreReviewDetails", "attributes": attrs,
            "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": VERSION}}}}}))


def price():
    code, j = api("GET", f"/v1/apps/{APP}/appPricePoints?filter[territory]=USA&limit=200")
    free = next(p for p in j["data"] if float(p["attributes"]["customerPrice"]) == 0)
    show("priceSchedule (free)", *api("POST", "/v1/appPriceSchedules", {
        "data": {"type": "appPriceSchedules", "relationships": {
            "app": {"data": {"type": "apps", "id": APP}},
            "baseTerritory": {"data": {"type": "territories", "id": "USA"}},
            "manualPrices": {"data": [{"type": "appPrices", "id": "${free}"}]}}},
        "included": [{"type": "appPrices", "id": "${free}", "attributes": {"startDate": None},
                      "relationships": {"appPricePoint": {"data": {"type": "appPricePoints", "id": free["id"]}}}}]}))


def all_territories():
    out, url = [], "/v1/territories?limit=200"
    while url:
        code, j = api("GET", url)
        out += [t["id"] for t in j["data"]]
        url = j.get("links", {}).get("next")
    return out


def territories():
    ids = all_territories()
    print("territories:", len(ids))
    show("availability", *api("POST", "/v2/appAvailabilities", {
        "data": {"type": "appAvailabilities", "attributes": {"availableInNewTerritories": True},
                 "relationships": {"app": {"data": {"type": "apps", "id": APP}},
                                   "territoryAvailabilities": {"data": [{"type": "territoryAvailabilities", "id": f"${{{t}}}"} for t in ids]}}},
        "included": [{"type": "territoryAvailabilities", "id": f"${{{t}}}", "attributes": {"available": True},
                      "relationships": {"territory": {"data": {"type": "territories", "id": t}}}} for t in ids]}))


def eula():
    gpl = open(os.path.join(ROOT, "LICENSE"), encoding="utf-8").read()
    text = ("棋眼 (QiYan) 以 GNU 通用公共许可证第 3 版 (GPL-3.0) 授权。你可以按 GPL-3.0 的条款自由使用、研究、修改和再分发本软件；"
            "本应用 (含 iOS 版) 的完整源代码见 https://github.com/zhangfeiyang/QiYan 。本许可取代 Apple 标准最终用户许可协议中与 GPL-3.0 冲突的限制。\n\n"
            "QiYan is licensed under the GNU General Public License, version 3 (GPL-3.0). You may use, study, modify and "
            "redistribute it under the terms of the GPL-3.0. The complete source code of this app, including the iOS version, "
            "is available at https://github.com/zhangfeiyang/QiYan . Third-party components and their licenses are listed in "
            "the app under Settings → Open-source licenses.\n\n" + gpl)
    ids = all_territories()
    code, j = api("GET", f"/v1/apps/{APP}/endUserLicenseAgreement")
    terr = {"data": [{"type": "territories", "id": t} for t in ids]}
    if code == 200 and j.get("data"):
        eid = j["data"]["id"]
        show("eula PATCH", *api("PATCH", f"/v1/endUserLicenseAgreements/{eid}", {"data": {
            "type": "endUserLicenseAgreements", "id": eid, "attributes": {"agreementText": text},
            "relationships": {"territories": terr}}}))
    else:
        show("eula POST", *api("POST", "/v1/endUserLicenseAgreements", {"data": {
            "type": "endUserLicenseAgreements", "attributes": {"agreementText": text},
            "relationships": {"app": {"data": {"type": "apps", "id": APP}}, "territories": terr}}}))


STEPS = {"rights": rights, "categories": categories, "age": age, "version": version, "review": review,
         "price": price, "territories": territories, "eula": eula}

if __name__ == "__main__":
    for name in sys.argv[1:] or list(STEPS):
        STEPS[name]()
