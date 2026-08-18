#!/usr/bin/env python3
import hashlib
import html
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from zoneinfo import ZoneInfo

OUT = Path("data/events-feed.json")
IGDB_FEED = Path("data/events-feed-igdb.json")
UA = "GameDrop-EventSync/1.0 (+https://github.com/Gabriel-Liz2003/teste10900)"

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


def parse_time(text, default_hour=12, default_minute=0):
    # Prefer explicit PT/ET time because official US gaming announcements commonly publish one.
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
    # Month Day, Year / Month Day Year / Month Day
    m = re.search(
        r"\b(" + "|".join(MONTHS) + r")\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(20\d{2}))?\b",
        clean,
        re.I,
    )
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


def merge_events(*groups):
    merged = {}
    for group in groups:
        for e in group or []:
            start = e.get("startTime")
            name = (e.get("name") or "").strip()
            if not start or not name:
                continue
            key = (re.sub(r"\W+", "", name.lower()), start[:10])
            current = merged.get(key)
            if current is None:
                merged[key] = e
            else:
                # Prefer richer records (games, logo, description, livestream).
                score = lambda x: len(x.get("games", [])) * 10 + bool(x.get("logoUrl")) * 3 + bool(x.get("description")) * 2 + bool(x.get("liveStreamUrl"))
                if score(e) > score(current):
                    merged[key] = e
    return sorted(merged.values(), key=lambda x: x["startTime"])


class LinkParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.links = []
    def handle_starttag(self, tag, attrs):
        if tag.lower() == "a":
            href = dict(attrs).get("href")
            if href: self.links.append(href)


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
        year_hint = None
        ym = re.search(r"(20\d{2})", pub)
        if ym: year_hint = int(ym.group(1))
        content = " ".join(x.text or "" for x in item if x.tag.endswith("encoded"))
        desc = item.findtext("description") or ""
        article = content + " " + desc
        try:
            if link:
                article += " " + fetch(link)
        except Exception:
            pass
        dt = extract_date_time(article, year_hint)
        if not dt:
            continue
        out.append(event(title, iso(dt), description=f"Descoberto automaticamente em {source_name}.", source_url=link or url, tz=str(dt.tzinfo)))
    return out


def sync_nintendo():
    url = "https://www.nintendo.com/us/whatsnew/"
    body = fetch(url)
    # Pull article links whose visible anchor text mentions Direct/showcase.
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
    url = "https://www.gamescom.global/en/live/events"
    text = strip_html(fetch(url))
    out = []
    # Official page reliably carries the annual date range and ONL date.
    m = re.search(r"gamescom\s+(20\d{2}).{0,180}?from\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+to\s+(\d{1,2})(?:st|nd|rd|th)?\s+of\s+August", text, re.I)
    if not m:
        m = re.search(r"gamescom\s+(20\d{2}).{0,180}?August\s+(\d{1,2})\s+to\s+(\d{1,2})", text, re.I)
    if m:
        year, d1, d2 = map(int, m.groups())
        start = datetime(year, 8, d1, 10, 0, tzinfo=ZoneInfo("Europe/Berlin"))
        end = datetime(year, 8, d2, 20, 0, tzinfo=ZoneInfo("Europe/Berlin"))
        out.append(event(f"gamescom {year}", iso(start), "Maior feira anual de games em Colônia.", source_url=url, end=iso(end), tz="Europe/Berlin"))
    onl = re.search(r"Opening Night Live.{0,160}?August\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(20\d{2}))?", text, re.I)
    if onl:
        day = int(onl.group(1)); year = int(onl.group(2) or datetime.now(timezone.utc).year)
        # If the page does not expose a clock time, keep a conservative evening placeholder; richer RSS/IGDB data wins during merge.
        start = datetime(year, 8, day, 20, 0, tzinfo=ZoneInfo("Europe/Berlin"))
        out.append(event(f"gamescom Opening Night Live {year}", iso(start), "Show de abertura oficial da gamescom.", source_url=url, tz="Europe/Berlin"))
    return out


def sync_tga():
    url = "https://thegameawards.com/faq"
    text = strip_html(fetch(url))
    m = re.search(r"Thursday,\s+(December)\s+(\d{1,2}),\s+(20\d{2}).{0,240}?(\d{1,2}):(\d{2})\s*p\.?m\.?\s*ET", text, re.I)
    if not m:
        return []
    day, year, hour, minute = int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
    if hour != 12: hour += 12
    dt = datetime(year, 12, day, hour, minute, tzinfo=ZoneInfo("America/New_York"))
    return [event(f"The Game Awards {year}", iso(dt), "Premiação anual com anúncios e world premieres.", source_url=url, tz="America/New_York")]


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
        ("gamescom", sync_gamescom),
        ("The Game Awards", sync_tga),
    ]:
        try:
            rows = fn()
            discovered.extend(rows)
            sources_ok.append(f"{name}:{len(rows)}")
        except Exception as exc:
            print(f"SOURCE ERROR {name}: {exc}")

    merged = merge_events(existing.get("events", []), igdb.get("events", []), discovered)
    # Never destroy a healthy feed because upstream sources temporarily fail.
    if not merged and existing.get("events"):
        merged = existing["events"]
    now = datetime.now(timezone.utc)
    # Retain 2 years of history and all future events so past-event pages remain useful.
    cutoff = now.timestamp() - 730 * 86400
    kept = []
    for e in merged:
        try:
            ts = datetime.fromisoformat(e["startTime"].replace("Z", "+00:00")).timestamp()
        except Exception:
            ts = now.timestamp()
        if ts >= cutoff:
            kept.append(e)

    feed = {
        "schemaVersion": 2,
        "generatedAt": now.isoformat().replace("+00:00", "Z"),
        "source": "GameDrop aggregator: official public sources + IGDB when configured",
        "sourcesStatus": sources_ok,
        "events": kept,
    }
    OUT.write_text(json.dumps(feed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(kept)} events; sources: {', '.join(sources_ok)}")


if __name__ == "__main__":
    main()
