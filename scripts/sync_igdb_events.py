#!/usr/bin/env python3
import json
import os
import re
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path

CLIENT_ID = os.environ.get("IGDB_CLIENT_ID", "").strip()
CLIENT_SECRET = os.environ.get("IGDB_CLIENT_SECRET", "").strip()
OUT = Path(os.environ.get("EVENT_FEED_OUT", "data/events-feed.json"))


def post_form(url, values):
    data = urllib.parse.urlencode(values).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def igdb(token, endpoint, query):
    req = urllib.request.Request(
        f"https://api.igdb.com/v4/{endpoint}",
        data=query.encode(),
        method="POST",
        headers={
            "Client-ID": CLIENT_ID,
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "Content-Type": "text/plain",
        },
    )
    with urllib.request.urlopen(req, timeout=45) as r:
        result = json.loads(r.read().decode())
    time.sleep(0.28)
    return result


def chunks(values, size=400):
    values = list(dict.fromkeys(values))
    for i in range(0, len(values), size):
        yield values[i:i + size]


def query_ids(token, endpoint, ids, fields):
    rows = []
    for batch in chunks(ids):
        if not batch:
            continue
        joined = ",".join(str(x) for x in batch)
        rows.extend(igdb(token, endpoint, f"fields {fields}; where id = ({joined}); limit 500;"))
    return rows


def image_url(image_id, size):
    return f"https://images.igdb.com/igdb/image/upload/t_{size}/{image_id}.jpg" if image_id else None


def iso(ts):
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat().replace("+00:00", "Z") if ts else None


def classify(video_names):
    text = " ".join(video_names).lower()
    if re.search(r"announcement|announce|reveal|world premiere|debut|first look|teaser", text):
        return "NEW_ANNOUNCEMENT"
    if "gameplay" in text:
        return "GAMEPLAY"
    if "trailer" in text:
        return "TRAILER"
    if video_names:
        return "UPDATE"
    return "FEATURED"


def main():
    if not CLIENT_ID or not CLIENT_SECRET:
        raise SystemExit("IGDB_CLIENT_ID and IGDB_CLIENT_SECRET are required")

    oauth = post_form(
        "https://id.twitch.tv/oauth2/token",
        {"client_id": CLIENT_ID, "client_secret": CLIENT_SECRET, "grant_type": "client_credentials"},
    )
    token = oauth["access_token"]
    now = datetime.now(timezone.utc)
    start = int((now - timedelta(days=730)).timestamp())
    end = int((now + timedelta(days=730)).timestamp())

    events = igdb(
        token,
        "events",
        f"fields id,name,slug,description,start_time,end_time,time_zone,live_stream_url,event_logo,games,videos,updated_at; "
        f"where start_time >= {start} & start_time <= {end}; sort start_time asc; limit 500;",
    )

    game_ids = [gid for e in events for gid in e.get("games", [])]
    video_ids = [vid for e in events for vid in e.get("videos", [])]
    logo_ids = [e.get("event_logo") for e in events if e.get("event_logo")]

    games = query_ids(token, "games", game_ids, "id,name,slug,summary,cover,created_at,first_release_date")
    videos = query_ids(token, "game_videos", video_ids, "id,game,name,video_id")
    logos = query_ids(token, "event_logos", logo_ids, "id,event,image_id")
    cover_ids = [g.get("cover") for g in games if g.get("cover")]
    covers = query_ids(token, "covers", cover_ids, "id,image_id")

    game_by_id = {x["id"]: x for x in games}
    video_by_id = {x["id"]: x for x in videos}
    logo_by_id = {x["id"]: x for x in logos}
    cover_by_id = {x["id"]: x for x in covers}

    output_events = []
    for e in events:
        event_videos = [video_by_id[v] for v in e.get("videos", []) if v in video_by_id]
        videos_by_game = {}
        for v in event_videos:
            videos_by_game.setdefault(v.get("game"), []).append(v)

        out_games = []
        for gid in e.get("games", []):
            g = game_by_id.get(gid)
            if not g:
                continue
            gv = videos_by_game.get(gid, [])
            out_videos = [
                {"id": v["id"], "gameId": v.get("game"), "name": v.get("name") or "Video", "youtubeVideoId": v.get("video_id") or ""}
                for v in gv if v.get("video_id")
            ]
            cover = cover_by_id.get(g.get("cover"), {})
            out_games.append({
                "igdbId": gid,
                "name": g.get("name") or "Unknown",
                "coverUrl": image_url(cover.get("image_id"), "cover_big"),
                "summary": g.get("summary"),
                "highlightType": classify([v["name"] for v in out_videos]),
                "videos": out_videos,
            })

        logo = logo_by_id.get(e.get("event_logo"), {})
        output_events.append({
            "id": e["id"],
            "name": e.get("name") or "Gaming event",
            "description": e.get("description"),
            "startTime": iso(e.get("start_time")),
            "endTime": iso(e.get("end_time")),
            "timeZone": e.get("time_zone"),
            "liveStreamUrl": e.get("live_stream_url"),
            "logoUrl": image_url(logo.get("image_id"), "logo_med"),
            "games": out_games,
            "videos": [
                {"id": v["id"], "gameId": v.get("game"), "name": v.get("name") or "Video", "youtubeVideoId": v.get("video_id") or ""}
                for v in event_videos if v.get("video_id")
            ],
        })

    feed = {
        "schemaVersion": 1,
        "generatedAt": now.isoformat().replace("+00:00", "Z"),
        "source": "IGDB",
        "events": output_events,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(feed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(output_events)} events to {OUT}")


if __name__ == "__main__":
    main()
