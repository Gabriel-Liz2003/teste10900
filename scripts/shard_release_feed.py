#!/usr/bin/env python3
"""Split the large IGDB release feed into mobile-friendly monthly shards."""
import json
from collections import defaultdict
from pathlib import Path

SOURCE = Path("data/releases-feed.json")
OUT_DIR = Path("data/releases")
INDEX = OUT_DIR / "index.json"


def month_for(game):
    value = game.get("primaryReleaseDate") or game.get("firstReleaseDate") or ""
    return value[:7] if len(value) >= 7 else None


def compact_dump(path, payload):
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


def main():
    feed = json.loads(SOURCE.read_text(encoding="utf-8"))
    games = feed.get("games") or []
    if not games:
        raise SystemExit("Cannot shard an empty release feed")

    grouped = defaultdict(list)
    game_months = {}
    for game in games:
        month = month_for(game)
        if not month:
            continue
        grouped[month].append(game)
        game_months[str(game["igdbId"])] = month

    if not grouped:
        raise SystemExit("Release feed contains no games with usable months")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    expected_files = {"index.json"}
    month_meta = []
    total_bytes = 0
    for month, rows in sorted(grouped.items()):
        rows.sort(key=lambda game: (game.get("primaryReleaseDate") or "9999", game.get("name") or ""))
        filename = f"{month}.json"
        expected_files.add(filename)
        target = OUT_DIR / filename
        payload = {
            "schemaVersion": 1,
            "generatedAt": feed.get("generatedAt"),
            "month": month,
            "source": feed.get("source"),
            "gameCount": len(rows),
            "games": rows,
        }
        compact_dump(target, payload)
        size = target.stat().st_size
        total_bytes += size
        month_meta.append({"month": month, "gameCount": len(rows), "bytes": size})

    # Remove shards that fell outside the rolling feed window.
    for path in OUT_DIR.glob("*.json"):
        if path.name not in expected_files:
            path.unlink()

    index_payload = {
        "schemaVersion": 1,
        "generatedAt": feed.get("generatedAt"),
        "source": feed.get("source"),
        "windowStart": feed.get("windowStart"),
        "windowEnd": feed.get("windowEnd"),
        "gameCount": sum(item["gameCount"] for item in month_meta),
        "gamesWithTrailers": feed.get("gamesWithTrailers", 0),
        "months": month_meta,
        "gameMonths": game_months,
    }
    compact_dump(INDEX, index_payload)

    # The monolithic file is an intermediate build artifact only.
    SOURCE.unlink()
    print(
        "Release shards:",
        f"months={len(month_meta)}",
        f"games={index_payload['gameCount']}",
        f"indexBytes={INDEX.stat().st_size}",
        f"shardsBytes={total_bytes}",
        f"largestShard={max(item['bytes'] for item in month_meta)}",
    )


if __name__ == "__main__":
    main()
