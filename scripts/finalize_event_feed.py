#!/usr/bin/env python3
import json
import os
from datetime import datetime, timezone
from pathlib import Path

OUT = Path("data/events-feed.json")
IGDB = Path("data/events-feed-igdb.json")


def parse_time(value):
    try:
        return datetime.fromisoformat((value or "").replace("Z", "+00:00"))
    except Exception:
        return None


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    feed = load(OUT)
    igdb = load(IGDB) if IGDB.exists() else {"events": []}
    configured = os.environ.get("IGDB_CONFIGURED", "false").lower() == "true"

    rows = feed.get("events") or []
    raw_igdb = igdb.get("events") or []
    now = datetime.now(timezone.utc)
    cutoff = datetime(now.year, 1, 1, tzinfo=timezone.utc)

    # GameDrop is a current gaming calendar. Keeping the current year plus all
    # future events preserves useful history while avoiding multi-year payloads.
    kept = []
    for event in rows:
        when = parse_time(event.get("startTime"))
        if when is None or when >= cutoff:
            kept.append(event)

    kept.sort(key=lambda e: e.get("startTime") or "")
    enriched = sum(1 for e in kept if e.get("games"))
    raw_with_games = sum(1 for e in raw_igdb if e.get("games"))

    if not kept:
        raise SystemExit("Refusing to publish an empty event feed")
    if configured and not raw_igdb:
        raise SystemExit("IGDB is configured but returned no events")
    if configured and raw_with_games == 0:
        raise SystemExit("IGDB is configured but returned no events with games")

    feed["schemaVersion"] = 4
    feed["generatedAt"] = now.isoformat().replace("+00:00", "Z")
    feed["source"] = "GameDrop aggregator: official public sources + IGDB enrichment"
    feed["feedWindowStart"] = cutoff.date().isoformat()
    feed["igdbConfigured"] = configured
    feed["igdbRawEvents"] = len(raw_igdb)
    feed["igdbRawEventsWithGames"] = raw_with_games
    feed["igdbEnrichedEvents"] = enriched
    feed["eventCount"] = len(kept)
    feed["events"] = kept

    OUT.write_text(json.dumps(feed, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(
        "Final feed:",
        f"events={len(kept)}",
        f"enriched={enriched}",
        f"igdbRaw={len(raw_igdb)}",
        f"igdbWithGames={raw_with_games}",
        f"bytes={OUT.stat().st_size}",
    )


if __name__ == "__main__":
    main()
