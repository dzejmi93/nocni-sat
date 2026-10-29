#!/usr/bin/env python3
"""Builds the list of Serbian radio stations that actually play.

Takes Serbian stations from the Radio Browser directory, opens every stream,
keeps only those that answer with real audio, and writes stations.json.
Optionally injects the list into the app page (--inject path/to/index.html).
"""
import concurrent.futures as cf
import json
import re
import sys
import urllib.parse
import urllib.request

UA = "NocniSat/1.0 (+https://github.com/dzejmi93/nocni-sat)"
MIRRORS = ["https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info",
           "https://fi1.api.radio-browser.info", "https://all.api.radio-browser.info"]

# shown first, in this order, when they work
PINNED = [
    ("naxi radio", "Pop i hitovi, Beograd"), ("tdi", "Domaći i strani hitovi"), ("play radio", "Pop, dance"),
    ("s1", "Domaća muzika"), ("hit fm", "Hitovi"), ("lola", "Hitovi"), ("lollipop", "Pop i hitovi"),
    ("lolipop", "Pop i hitovi"), ("radio beograd 1", "RTS, vesti i program"),
    ("radio beograd 202", "RTS, muzika i zabava"), ("radio beograd 2", "RTS, kultura i klasika"),
    ("studio b", "Beograd, vesti i muzika"), ("karolina", "Narodna muzika"), ("index", "Pop i rok"),
    ("rock radio", "Rok"), ("novosti", "Domaća muzika"), ("red radio", "Pop i dance"), ("as fm", "Hitovi"),
    ("jat", "Pop i rok"), ("nostalgija", "Stari hitovi"), ("super fm", "Hitovi"), ("laguna", "Domaći hitovi"),
    ("naxi chill", "Opuštena muzika"), ("naxi jazz", "Džez"),
]
MAX_STATIONS = 90
AUDIO_TYPES = ("audio/", "application/ogg", "application/octet-stream", "video/mp2t")
BAD_TYPES = ("mpegurl", "scpls", "x-scpls", "text/", "html", "xml", "json")


def api(path):
    for m in MIRRORS:
        try:
            req = urllib.request.Request(m + "/json/" + path, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # try the next mirror
            print(f"mirror failed {m}: {e}", file=sys.stderr)
    return []


def norm(s):
    return re.sub(r"[^a-z0-9]+", "", (s or "").lower())


def plays(url):
    """True when the stream answers 200 with an audio type and real data."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Icy-MetaData": "0", "Accept": "*/*"})
        with urllib.request.urlopen(req, timeout=10) as r:
            if r.status != 200:
                return False
            ct = (r.headers.get("Content-Type") or "").lower()
            if any(b in ct for b in BAD_TYPES) or not ct.startswith(AUDIO_TYPES):
                return False
            data = b""
            while len(data) < 16384:
                chunk = r.read(4096)
                if not chunk:
                    break
                data += chunk
            return len(data) >= 8192
    except Exception:
        return False


def tag_for(s):
    tags = [t.strip() for t in (s.get("tags") or "").split(",") if t.strip()][:2]
    parts = [s.get("state") or ""] + [", ".join(tags)]
    return " · ".join(p for p in parts if p)


def main():
    raw = api("stations/bycountrycodeexact/RS?hidebroken=true&order=clickcount&reverse=true&limit=500")
    for name, _ in PINNED:
        raw += api("stations/search?" + urllib.parse.urlencode(
            {"name": name, "countrycode": "RS", "hidebroken": "true", "order": "clickcount", "reverse": "true", "limit": 10}))

    by_url = {}
    for s in raw:
        u = (s.get("url_resolved") or "").strip()
        if not u.startswith(("http://", "https://")) or u.lower().endswith((".m3u8", ".m3u", ".pls")):
            continue
        if u not in by_url or s.get("clickcount", 0) > by_url[u].get("clickcount", 0):
            by_url[u] = s
    cands = list(by_url.values())
    print(f"candidates: {len(cands)}", file=sys.stderr)

    with cf.ThreadPoolExecutor(max_workers=24) as ex:
        ok = dict(zip([c["url_resolved"] for c in cands], ex.map(lambda c: plays(c["url_resolved"]), cands)))
    working = [c for c in cands if ok.get(c["url_resolved"])]
    print(f"working: {len(working)}", file=sys.stderr)

    # one entry per station name: the most listened working stream, https preferred
    best = {}
    for s in working:
        k = norm(s.get("name"))
        if not k:
            continue
        cur = best.get(k)
        score = (s["url_resolved"].startswith("https"), s.get("clickcount", 0))
        if cur is None or score > (cur["url_resolved"].startswith("https"), cur.get("clickcount", 0)):
            best[k] = s

    out, used = [], set()
    for name, tag in PINNED:
        nk = norm(name)
        match = [s for k, s in best.items() if k not in used and (k == nk or k.startswith(nk) or k.startswith("radio" + nk))]
        match.sort(key=lambda s: (norm(s["name"]) not in (nk, "radio" + nk), -s.get("clickcount", 0)))
        if match:
            s = match[0]
            used.add(norm(s["name"]))
            out.append({"name": " ".join(s["name"].split()), "tag": tag, "url": s["url_resolved"]})
    rest = sorted((s for k, s in best.items() if k not in used), key=lambda s: -s.get("clickcount", 0))
    for s in rest:
        if len(out) >= MAX_STATIONS:
            break
        out.append({"name": " ".join(s["name"].split()), "tag": tag_for(s), "url": s["url_resolved"]})

    if len(out) < 5:
        print("too few working stations, keeping the previous list", file=sys.stderr)
        sys.exit(1)

    with open("stations.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"stations.json: {len(out)} stations", file=sys.stderr)
    for s in out[:30]:
        print("  " + s["name"], file=sys.stderr)

    if len(sys.argv) == 3 and sys.argv[1] == "--inject":
        page = open(sys.argv[2], encoding="utf-8").read()
        marker = "/*STATIONS*/[]"
        if marker not in page:
            print("marker missing in page", file=sys.stderr)
            sys.exit(1)
        page = page.replace(marker, "/*STATIONS*/" + json.dumps(out, ensure_ascii=False), 1)
        open(sys.argv[2], "w", encoding="utf-8").write(page)
        print("injected into " + sys.argv[2], file=sys.stderr)


if __name__ == "__main__":
    main()
