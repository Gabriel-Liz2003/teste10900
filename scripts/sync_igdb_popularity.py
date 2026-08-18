#!/usr/bin/env python3
import json
import math
import os
import time
import urllib.parse
import urllib.request
from pathlib import Path

CLIENT_ID = os.environ.get("IGDB_CLIENT_ID", "").strip()
CLIENT_SECRET = os.environ.get("IGDB_CLIENT_SECRET", "").strip()
INDEX = Path("data/releases/index.json")
OUT = Path("data/releases/popularity.json")
RATE_DELAY = 0.28


def post_form(url, values):
    data = urllib.parse.urlencode(values).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read().decode())


def igdb(token, query):
    req = urllib.request.Request(
        "https://api.igdb.com/v4/games",
        data=query.encode(),
        method="POST",
        headers={
            "Client-ID": CLIENT_ID,
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "Content-Type": "text/plain",
        },
    )
    with urllib.request.urlopen(req, timeout=45) as response:
        rows = json.loads(response.read().decode())
    time.sleep(RATE_DELAY)
    return rows


def chunks(values, size=350):
    values = list(dict.fromkeys(int(v) for v in values))
    for i in range(0, len(values), size):
        yield values[i:i + size]


def popularity_score(row):
    # Hype keeps unreleased/high-interest games competitive, while rating volume
    # makes established popular games rank naturally. Log scaling avoids a single
    # huge catalog title flattening the rest of the month.
    hypes = max(0, int(row.get("hypes") or 0))
    total_count = max(0, int(row.get("total_rating_count") or 0))
    rating_count = max(0, int(row.get("rating_count") or 0))
    return round(math.log1p(total_count + rating_count) * 100 + math.log1p(hypes) * 260, 3)


def main():
    if not CLIENT_ID or not CLIENT_SECRET:
        raise SystemExit("IGDB_CLIENT_ID and IGDB_CLIENT_SECRET are required")
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    game_ids = [int(value) for value in (index.get("gameMonths") or {}).keys()]
    if not game_ids:
        raise SystemExit("Release index has no game IDs")

    oauth = post_form(
        "https://id.twitch.tv/oauth2/token",
        {"client_id": CLIENT_ID, "client_secret": CLIENT_SECRET, "grant_type": "client_credentials"},
    )
    token = oauth["access_token"]
    scores = {}
    for batch in chunks(game_ids):
        joined = ",".join(str(v) for v in batch)
        rows = igdb(token, f"fields id,hypes,total_rating_count,rating_count; where id = ({joined}); limit 500;")
        for row in rows:
            score = popularity_score(row)
            scores[str(row["id"])] = {
                "score": score,
                "hypes": int(row.get("hypes") or 0),
                "ratingCount": int(row.get("total_rating_count") or row.get("rating_count") or 0),
            }

    payload = {
        "schemaVersion": 1,
        "gameCount": len(scores),
        "games": scores,
    }
    if not scores:
        raise SystemExit("Refusing to publish empty popularity feed")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Popularity feed: games={len(scores)} bytes={OUT.stat().st_size}")


if __name__ == "__main__":
    main()
