#!/usr/bin/env python3
"""Build the public GameDrop release-calendar feed from IGDB.

The Twitch/IGDB credentials stay in GitHub Actions. The Android app only reads
`data/releases-feed.json`, so release-date/trailer fixes do not require a new APK.
"""
import json
import os
import re
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

CLIENT_ID = os.environ.get("IGDB_CLIENT_ID", "").strip()
CLIENT_SECRET = os.environ.get("IGDB_CLIENT_SECRET", "").strip()
OUT = Path(os.environ.get("RELEASE_FEED_OUT", "data/releases-feed.json"))
RATE_DELAY = 0.28


def post_form(url, values):
    data = urllib.parse.urlencode(values).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read().decode())


def igdb(token, endpoint, query):
    request = urllib.request.Request(
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
    with urllib.request.urlopen(request, timeout=45) as response:
        result = json.loads(response.read().decode())
    time.sleep(RATE_DELAY)
    return result


def chunks(values, size=350):
    values = list(dict.fromkeys(int(v) for v in values if v))
    for index in range(0, len(values), size):
        yield values[index:index + size]


def query_ids(token, endpoint, ids, fields):
    rows = []
    for batch in chunks(ids):
        joined = ",".join(str(value) for value in batch)
        rows.extend(igdb(token, endpoint, f"fields {fields}; where id = ({joined}); limit 500;"))
    return rows


def query_all(token, endpoint, fields):
    return igdb(token, endpoint, f"fields {fields}; limit 500;")


def image_url(image_id, size="cover_big"):
    if not image_id:
        return None
    return f"https://images.igdb.com/igdb/image/upload/t_{size}/{image_id}.jpg"


def iso(timestamp):
    if not timestamp:
        return None
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def classify_video(name):
    label = (name or "").strip()
    text = label.lower()
    if re.search(r"launch|release date|available now", text):
        kind, rank = "LAUNCH", 100
    elif "gameplay" in text or "game play" in text:
        kind, rank = "GAMEPLAY", 95
    elif re.search(r"story|cinematic", text):
        kind, rank = "STORY", 90
    elif re.search(r"announcement|announce|reveal|world premiere|debut|first look", text):
        kind, rank = "ANNOUNCEMENT", 88
    elif "trailer" in text:
        kind, rank = "TRAILER", 75
    elif "teaser" in text:
        kind, rank = "TEASER", 65
    elif re.search(r"developer|dev diary|behind the scenes|interview", text):
        kind, rank = "DEV_DIARY", 45
    else:
        kind, rank = "OTHER", 20
    return kind, rank


def fetch_release_dates(token, start_timestamp, end_timestamp):
    """Query in 45-day windows so busy months cannot silently hit the API limit."""
    rows = {}
    cursor = datetime.fromtimestamp(start_timestamp, tz=timezone.utc)
    end_dt = datetime.fromtimestamp(end_timestamp, tz=timezone.utc)
    while cursor < end_dt:
        window_end = min(cursor + timedelta(days=45), end_dt)
        left, right = int(cursor.timestamp()), int(window_end.timestamp())
        offset = 0
        while True:
            batch = igdb(
                token,
                "release_dates",
                "fields id,date,date_format,game,human,platform,release_region,status,updated_at,y,m,d; "
                f"where date >= {left} & date < {right}; sort date asc; limit 500; offset {offset};",
            )
            for row in batch:
                rows[row["id"]] = row
            if len(batch) < 500:
                break
            offset += 500
            if offset >= 5000:
                raise RuntimeError(f"release_dates window exceeded safe pagination limit: {cursor.date()}")
        cursor = window_end
    return sorted(rows.values(), key=lambda row: (row.get("date") or 0, row.get("id") or 0))


def normalize_name(value):
    return re.sub(r"\s+", " ", (value or "").strip())


def primary_release(releases, region_by_id):
    if not releases:
        return None

    def region_rank(row):
        region = (region_by_id.get(row.get("release_region"), "") or "").lower()
        if "brazil" in region:
            return 0
        if "worldwide" in region:
            return 1
        if not region:
            return 2
        if "north" in region and "america" in region:
            return 3
        return 4

    return min(releases, key=lambda row: (region_rank(row), row.get("date") or 2**62))


def main():
    if not CLIENT_ID or not CLIENT_SECRET:
        raise SystemExit("IGDB_CLIENT_ID and IGDB_CLIENT_SECRET are required")

    oauth = post_form(
        "https://id.twitch.tv/oauth2/token",
        {"client_id": CLIENT_ID, "client_secret": CLIENT_SECRET, "grant_type": "client_credentials"},
    )
    token = oauth["access_token"]
    now = datetime.now(timezone.utc)
    start = int((now - timedelta(days=180)).timestamp())
    end = int((now + timedelta(days=730)).timestamp())

    release_dates = fetch_release_dates(token, start, end)
    game_ids = [row.get("game") for row in release_dates]
    games = query_ids(
        token,
        "games",
        game_ids,
        "id,name,slug,summary,storyline,cover,first_release_date,platforms,videos,game_type,game_status,updated_at,url,parent_game,version_parent",
    )

    # Avoid collector/special-edition duplicates. Regular ports/remakes remain useful calendar entries.
    games = [game for game in games if not game.get("version_parent")]
    game_by_id = {game["id"]: game for game in games}
    valid_game_ids = set(game_by_id)
    release_dates = [row for row in release_dates if row.get("game") in valid_game_ids]

    cover_ids = [game.get("cover") for game in games]
    video_ids = [video_id for game in games for video_id in game.get("videos", [])]
    platform_ids = [row.get("platform") for row in release_dates]
    game_type_ids = [game.get("game_type") for game in games]
    game_status_ids = [game.get("game_status") for game in games]
    release_region_ids = [row.get("release_region") for row in release_dates]
    release_status_ids = [row.get("status") for row in release_dates]

    covers = query_ids(token, "covers", cover_ids, "id,image_id")
    videos = query_ids(token, "game_videos", video_ids, "id,game,name,video_id")
    platforms = query_ids(token, "platforms", platform_ids, "id,name,abbreviation")
    game_types = query_ids(token, "game_types", game_type_ids, "id,type")
    game_statuses = query_ids(token, "game_statuses", game_status_ids, "id,status")
    release_regions = query_ids(token, "release_date_regions", release_region_ids, "id,region")
    release_statuses = query_ids(token, "release_date_statuses", release_status_ids, "id,name,description")

    cover_by_id = {row["id"]: row for row in covers}
    platform_by_id = {row["id"]: row for row in platforms}
    game_type_by_id = {row["id"]: row.get("type") for row in game_types}
    game_status_by_id = {row["id"]: row.get("status") for row in game_statuses}
    region_by_id = {row["id"]: row.get("region") for row in release_regions}
    release_status_by_id = {row["id"]: row.get("name") for row in release_statuses}

    videos_by_game = defaultdict(list)
    for video in videos:
        if not video.get("video_id"):
            continue
        kind, rank = classify_video(video.get("name"))
        videos_by_game[video.get("game")].append({
            "id": video["id"],
            "name": normalize_name(video.get("name")) or "Trailer",
            "youtubeVideoId": video["video_id"],
            "type": kind,
            "rank": rank,
        })
    for game_videos in videos_by_game.values():
        game_videos.sort(key=lambda item: (-item["rank"], item["name"].lower()))

    dates_by_game = defaultdict(list)
    for row in release_dates:
        dates_by_game[row["game"]].append(row)

    allowed_types = {
        None,
        "Main Game",
        "DLC Addon",
        "Expansion",
        "Standalone Expansion",
        "Remake",
        "Remaster",
        "Expanded Game",
        "Port",
    }

    output_games = []
    for game in games:
        game_type = game_type_by_id.get(game.get("game_type"))
        if game_type not in allowed_types:
            continue
        raw_dates = dates_by_game.get(game["id"], [])
        if not raw_dates:
            continue
        primary = primary_release(raw_dates, region_by_id)
        game_videos = videos_by_game.get(game["id"], [])
        cover = cover_by_id.get(game.get("cover"), {})
        releases = []
        seen_release_keys = set()
        for row in sorted(raw_dates, key=lambda item: item.get("date") or 0):
            platform = platform_by_id.get(row.get("platform"), {})
            key = (row.get("date"), row.get("platform"), row.get("release_region"))
            if key in seen_release_keys:
                continue
            seen_release_keys.add(key)
            releases.append({
                "date": iso(row.get("date")),
                "human": row.get("human"),
                "platformId": row.get("platform"),
                "platform": platform.get("abbreviation") or platform.get("name"),
                "platformName": platform.get("name"),
                "region": region_by_id.get(row.get("release_region")),
                "status": release_status_by_id.get(row.get("status")),
                "dateFormatId": row.get("date_format"),
            })

        output_games.append({
            "igdbId": game["id"],
            "name": normalize_name(game.get("name")) or "Unknown",
            "slug": game.get("slug"),
            "summary": game.get("summary") or game.get("storyline"),
            "summaryPtBr": None,
            "coverUrl": image_url(cover.get("image_id")),
            "primaryReleaseDate": iso(primary.get("date")) if primary else iso(game.get("first_release_date")),
            "firstReleaseDate": iso(game.get("first_release_date")),
            "gameType": game_type,
            "gameStatus": game_status_by_id.get(game.get("game_status")),
            "parentGameId": game.get("parent_game"),
            "igdbUrl": game.get("url"),
            "releaseDates": releases,
            "bestTrailer": game_videos[0] if game_videos else None,
            "trailers": game_videos,
        })

    output_games.sort(key=lambda game: (game.get("primaryReleaseDate") or "9999", game["name"].lower()))
    feed = {
        "schemaVersion": 1,
        "generatedAt": now.isoformat().replace("+00:00", "Z"),
        "source": "IGDB with RAWG client fallback",
        "windowStart": iso(start),
        "windowEnd": iso(end),
        "gameCount": len(output_games),
        "gamesWithTrailers": sum(1 for game in output_games if game.get("trailers")),
        "games": output_games,
    }
    if not output_games:
        raise SystemExit("Refusing to publish an empty release feed")
    if not feed["gamesWithTrailers"]:
        raise SystemExit("IGDB release feed contains no trailers")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(
        "Release feed:",
        f"games={feed['gameCount']}",
        f"withTrailers={feed['gamesWithTrailers']}",
        f"bytes={OUT.stat().st_size}",
    )


if __name__ == "__main__":
    main()
