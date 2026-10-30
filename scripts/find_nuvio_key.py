#!/usr/bin/env python3
"""Find the public server key and address inside an official Nuvio APK.

The key is Supabase's "publishable" (or older "anon") key, which is designed to ship
inside apps. Prints NUVIO_SERVER_KEY=... and NUVIO_SERVER_URLS=... lines suitable for
$GITHUB_ENV. Prints nothing if no key is found.
"""
import base64
import json
import re
import sys
import zipfile

PUBLISHABLE = re.compile(rb"sb_publishable_[A-Za-z0-9_-]{16,}")
JWT = re.compile(rb"eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}")
URLS = re.compile(rb"https://api\.nuvio\.tv|https://[a-z0-9]{20}\.supabase\.co")


def is_anon(token: bytes) -> bool:
    try:
        payload = token.split(b".")[1]
        payload += b"=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get("role") == "anon"
    except Exception:
        return False


def main(apk_path: str) -> None:
    keys, urls = [], []
    with zipfile.ZipFile(apk_path) as apk:
        for name in apk.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            data = apk.read(name)
            keys += [m.decode() for m in PUBLISHABLE.findall(data)]
            keys += [m.decode() for m in JWT.findall(data) if is_anon(m)]
            urls += [m.decode() for m in URLS.findall(data)]
    keys = list(dict.fromkeys(keys))
    urls = list(dict.fromkeys(["https://api.nuvio.tv"] + urls))
    if keys:
        print(f"NUVIO_SERVER_KEY={keys[0]}")
        print(f"NUVIO_SERVER_URLS={','.join(urls)}")
    print(f"Found {len(keys)} key(s) and {len(urls)} address(es)", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1])
