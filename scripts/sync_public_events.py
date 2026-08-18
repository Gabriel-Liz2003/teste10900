#!/usr/bin/env python3
import hashlib
import html
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from difflib import SequenceMatcher
from html.parser import HTMLParser
from pathlib import Path
from zoneinfo import ZoneInfo

OUT = Path("data/events-feed.json")
IGDB_FEED = Path("data/events-feed-igdb.json")
UA = "GameDrop-EventSync/1.1 (+https://github.com/Gabriel-Liz2003/teste10900)"

MONTHS = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
    "july": 7, "august": 8, "september": 9, "october": 10, "november": 11, "december": 12,
}
KEYWORDS = re.compile(
    r"state of play|playstation showcase|nintendo direct|xbox.*showcase|developer_direct|developer direct|"
    r"gamescom|opening night live|future games show|pc gaming show|summer game fest|the game awards|"
    r"capcom showcase|ubisoft forward|indie world|partner showcase",
    re.I,
)


def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "text/html,application/rss+xml,application/xml;q=0.9,*/*;q=0.8"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", errors="replace")


def strip_html(value):
    value = re.sub(r"<script\b[^>]*>.*?</script>", " ", value or "", flags=re.I | re.S)
    value = re.sub(r"<style\b[^>]*>.*?</style>", " ", value, flags=re.I | re.S)
    value = re.sub(r"<[^>]+>", " ", value)
    return re.sub(r"\s+", " ", html.unescape(value)).strip()


def stable_id(name, start):
    raw = f"{name.lower().strip()}|{start[:10]}".encode()
    return int(hashlib.sha1(raw).hexdigest()[:12], 16)


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def event(name, start, description=None, live=None, end=None, tz=None, source_url=None):
    return {
        "id": stable_id(name, start),
        "name": name,
        "description": description,
        "startTime": start,
        "endTime": end,
        "timeZone": tz,
        "liveStreamUrl": live or source_url,
        "logoUrl": None,
        "games": [],
        "videos": [],
    }


def normalize_name(name):
    value = (name or "").lower()
    value = re.sub(r"\b20\d{2}\b", " ", value)
    value = value.replace("@", " at ")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def parse_instant(raw):
    try:
        return datetime.fromisoformat((raw or "").replace("Z", "+00:00"))
    except Exception:
        return None


def same_event(a, b):
    an, bn = normalize_name(a.get("name")), normalize_name(b.get("name"))
    if not an or not bn:
        return False
    at, bt = parse_instant(a.get("startTime")), parse_instant(b.get("startTime"))
    if not at or not bt:
        return False
    hours = abs((at - bt).total_seconds()) / 3600
    if hours > 24:
        return False
    ratio = SequenceMatcher(None, an, bn).ratio()
    contained = min(len(an), len(bn)) >= 8 and (an in bn or bn in an)
    a_tokens, b_tokens = set(an.split()), set(bn.split())
    overlap = len(a_tokens & b_tokens) / max(1, min(len(a_tokens), len(b_tokens)))
    return contained or ratio >= 0.72 or overlap >= 0.75


def merge_list_by_key(left, right, key_fn):
    out = []
    seen = set()
    for item in (left or []) + (right or []):
        key = key_fn(item)
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out


def merge_record(base, incoming):
    out = dict(base)
    # Keep the existing ID stable so notifications/deep links do not churn.
    for field in ("description", "endTime", "timeZone", "liveStreamUrl", "logoUrl"):
        if not out.get(field) and incoming.get(field):
            out[field] = incoming[field]
    # Prefer a slightly more descriptive name, but do not replace a good existing one with a shorter alias.
    if len((incoming.get("name") or "").strip()) > len((out.get("name") or "").strip()):
        out["name"] = incoming["name"]
    out["games"] = merge_list_by_key(
        out.get("games"), incoming.get("games"),
        lambda g: g.get("igdbId") or normalize_name(g.get("name"))
    )
    out["videos"] = merge_list_by_key(
        out.get("videos"), incoming.get("videos"),
        lambda v: v.get("youtubeVideoId") or v.get("id") or normalize_name(v.get("name"))
    )
    return out


def merge_events(*groups):
    merged = []
    for group in groups:
        for candidate in group or []:
            start = candidate.get("startTime")
            name = (candidate.get("name") or "").strip()
            if not start or not name:
                continue
            idx = next((i for i, current in enumerate(merged) if same_event(current, candidate)), None)
            if idx is None:
                merged.append(candidate)
            else:
                merged[idx] = merge_record(merged[idx], candidate)
    return sorted(merged, key=lambda x: x["startTime"])


class LinkParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.links = []
    def handle_starttag(self, tag, attrs):
        if tag.lower() == "a":
            href = dict(attrs).get("href")
            if href: self.links.append(href)


def parse_time(text, default_hour=12, default_minute=0):
    m = re.search(r"\b(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*(a\.?m\.?|p\.?m\.?)\s*(PT|PST|PDT|ET|EST|EDT)\b", text, re.I)
    if not m:
        return default_hour, default_minute, None
    hour = int(m.group(1)); minute = int(m.group(2) or 0); ap = m.group(3).lower(); z = m.group(4).upper()
    if ap.startswith("p") and hour != 12: hour += 12
    if ap.startswith("a") and hour == 12: hour = 0
    zone = "America/Los_Angeles" if z.startswith("P") else "America/New_York"
    return hour, minute, zone


def extract_date_time(text, year_hint=None):
    clean = strip_html(text)
    m = re.search(r"\b(" + "|".join(MONTHS) + r")\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(20\d{2}))?\b", clean, re.I)
    if not m:
        return None
    month = MONTHS[m.group(1).lower()]
    day = int(m.group(2))
    year = int(m.group(3) or year_hint or datetime.now(timezone.utc).year)
    hour, minute, zone_name = parse_time(clean)
    zone = ZoneInfo(zone_name) if zone_name else timezone.utc
    try:
        return datetime(year, month, day, hour, minute, tzinfo=zone)
    except ValueError:
        return None


def sync_sgf():
    base = "https://www.summergamefest.com"
    page = fetch(base + "/events")
    p = LinkParser(); p.feed(page)
    links = sorted({x for x in p.links if x.startswith("/events/")})
    out = []
    for path in links[:80]:
        try:
            body = fetch(base + path)
            text = strip_html(body)
            title_m = re.search(r"<h1[^>]*>(.*?)</h1>", body, re.I | re.S)
            name = strip_html(title_m.group(1)) if title_m else path.rsplit("/", 1)[-1].replace("-", " ").title()
            if not KEYWORDS.search(name + " " + text):
                continue
            dt = extract_date_time(text, datetime.now(timezone.utc).year)
            if not dt:
                continue
            out.append(event(name, iso(dt), description="Evento listado na agenda oficial do Summer Game Fest.", source_url=base + path, tz=str(dt.tzinfo)))
        except Exception as exc:
            print(f"SGF skip {path}: {exc}")
    return out


def parse_rss(url, source_name):
    raw = fetch(url)
    root = ET.fromstring(raw)
    out = []
    for item in root.findall(".//item")[:80]:
        title = strip_html(item.findtext("title") or "")
        if not KEYWORDS.search(title):
            continue
        link = strip_html(item.findtext("link") or "")
        pub = item.findtext("pubDate") or ""
        ym = re.search(r"(20\d{2})", pub)
        year_hint = int(ym.group(1)) if ym else None
        content = " ".join(x.text or "" for x in item if x.tag.endswith("encoded"))
        desc = item.findtext("description") or ""
        article = content + " " + desc
        try:
            if link:
                article += " " + fetch(link)
        except Exception:
            pass
        dt = extract_date_time(article, year_hint)
        if dt:
            out.append(event(title, iso(dt), description=f"Descoberto automaticamente em {source_name}.", source_url=link or url, tz=str(dt.tzinfo)))
    return out


def sync_nintendo():
    url = "https://www.nintendo.com/us/whatsnew/"
    body = fetch(url)
    matches = re.findall(r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', body, re.I | re.S)
    out = []
    for href, label_html in matches:
        label = strip_html(label_html)
        if not re.search(r"nintendo direct|indie world|partner showcase", label, re.I):
            continue
        full = href if href.startswith("http") else "https://www.nintendo.com" + href
        try:
            article = fetch(full)
        except Exception:
            continue
        dt = extract_date_time(article, datetime.now(timezone.utc).year)
        if dt:
            out.append(event(label, iso(dt), description="Descoberto automaticamente no site oficial da Nintendo.", source_url=full, tz=str(dt.tzinfo)))
    return out


def sync_gamescom():
    return []


def sync_tga():
    return []


def load_feed(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {"events": []}


def main():
    existing = load_feed(OUT)
    igdb = load_feed(IGDB_FEED) if IGDB_FEED.exists() else {"events": []}
    discovered = []
    sources_ok = []
    for name, fn in [
        ("Summer Game Fest", sync_sgf),
        ("PlayStation Blog", lambda: parse_rss("https://blog.playstation.com/feed/", "PlayStation Blog")),
        ("Xbox Wire", lambda: parse_rss("https://news.xbox.com/en-us/feed/", "Xbox Wire")),
        ("Nintendo", sync_nintendo),
    ]:
        try:
            rows = fn(); discovered.extend(rows); sources_ok.append(f"{name}:{len(rows)}")
        except Exception as exc:
            print(f"SOURCE ERROR {name}: {exc}")

    # Public/official events establish stable IDs and livestream links; IGDB runs last to enrich them with games, covers and videos.
    merged = merge_events(existing.get("events", []), discovered, igdb.get("events", []))
    if not merged and existing.get("events"):
        merged = existing["events"]
    now = datetime.now(timezone.utc)
    cutoff = now.timestamp() - 730 * 86400
    kept = []
    for e in merged:
        try:
            ts = datetime.fromisoformat(e["startTime"].replace("Z", "+00:00")).timestamp()
        except Exception:
            ts = now.timestamp()
        if ts >= cutoff:
            kept.append(e)

    enriched_count = sum(1 for e in kept if e.get("games"))
    feed = {
        "schemaVersion": 3,
        "generatedAt": now.isoformat().replace("+00:00", "Z"),
        "source": "GameDrop aggregator: official public sources + IGDB enrichment",
        "sourcesStatus": sources_ok,
        "igdbEnrichedEvents": enriched_count,
        "events": kept,
    }
    OUT.write_text(json.dumps(feed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(kept)} events; IGDB-enriched events: {enriched_count}; sources: {', '.join(sources_ok)}")


if __name__ == "__main__":
    main()
