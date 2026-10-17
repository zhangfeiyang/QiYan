"""App Store Connect API helper (key file stays outside the repo)."""
import base64, json, os, sys, time, urllib.error, urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
# 凭据只从环境变量读 (ASC_KEY_PATH / AC_API_KEY_ID / AC_API_ISSUER_ID), 不进仓库
KEY_PATH = os.environ.get("ASC_KEY_PATH", ""); KEY_ID = os.environ.get("AC_API_KEY_ID", ""); ISSUER = os.environ.get("AC_API_ISSUER_ID", "")
def b64url(d): return base64.urlsafe_b64encode(d).rstrip(b"=").decode()
_tok = [None, 0]
def token():
    if _tok[0] and time.time() - _tok[1] < 900: return _tok[0]
    key = serialization.load_pem_private_key(open(KEY_PATH, "rb").read(), password=None)
    now = int(time.time())
    si = b64url(json.dumps({"alg": "ES256", "kid": KEY_ID, "typ": "JWT"}).encode()) + "." + b64url(json.dumps({"iss": ISSUER, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}).encode())
    r, s = decode_dss_signature(key.sign(si.encode(), ec.ECDSA(hashes.SHA256())))
    _tok[0] = si + "." + b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big")); _tok[1] = time.time()
    return _tok[0]
def api(method, path, body=None, ok=False):
    url = path if path.startswith("http") else "https://api.appstoreconnect.apple.com" + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, method=method, data=data, headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                raw = resp.read(); return resp.status, (json.loads(raw) if raw.strip() else {})
        except urllib.error.HTTPError as e:
            raw = e.read()
            if e.code in (429, 500, 502, 503) and attempt < 3: time.sleep(5 * (attempt + 1)); continue
            return e.code, (json.loads(raw) if raw.strip() else {})
        except Exception as e:
            if attempt < 3: time.sleep(5); continue
            raise
if __name__ == "__main__":
    m, p = sys.argv[1], sys.argv[2]
    b = json.loads(sys.argv[3]) if len(sys.argv) > 3 else None
    c, r = api(m, p, b); print(c); print(json.dumps(r, ensure_ascii=False, indent=1)[:int(os.environ.get("LIM", "6000"))])
