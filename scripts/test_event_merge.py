#!/usr/bin/env python3
from sync_public_events import merge_events

public = {
    "id": 100,
    "name": "Nintendo Direct",
    "description": "Agenda oficial.",
    "startTime": "2026-06-09T14:00:00Z",
    "endTime": None,
    "timeZone": "America/New_York",
    "liveStreamUrl": "https://www.nintendo.com/",
    "logoUrl": None,
    "games": [],
    "videos": [],
}
igdb = {
    "id": 9001,
    "name": "Nintendo Direct 2026",
    "description": None,
    "startTime": "2026-06-09T14:05:00Z",
    "endTime": None,
    "timeZone": None,
    "liveStreamUrl": None,
    "logoUrl": "https://images.igdb.com/logo.jpg",
    "games": [
        {
            "igdbId": 42,
            "name": "Example Game",
            "coverUrl": "https://images.igdb.com/cover.jpg",
            "summary": "Example",
            "highlightType": "NEW_ANNOUNCEMENT",
            "videos": [],
        }
    ],
    "videos": [
        {"id": 7, "gameId": 42, "name": "Reveal Trailer", "youtubeVideoId": "abc123"}
    ],
}

merged = merge_events([public], [igdb])
assert len(merged) == 1, merged
item = merged[0]
assert item["id"] == 100, item
assert item["liveStreamUrl"] == public["liveStreamUrl"], item
assert item["logoUrl"] == igdb["logoUrl"], item
assert len(item["games"]) == 1, item
assert item["games"][0]["igdbId"] == 42, item
assert len(item["videos"]) == 1, item
print("IGDB enrichment merge: OK")
