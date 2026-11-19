"""Source pronunciation labels, without guessing links between separate records."""
from __future__ import annotations

import hashlib
import json
import re
import unicodedata

REGIONS = {
    "us": "US", "general american": "US", "american": "US", "united states": "US",
    "uk": "UK", "received pronunciation": "UK", "british": "UK", "england": "UK",
    "anh quốc": "UK", "anh mỹ": "US", "mỹ": "US",
    "australia": "AU", "australian": "AU", "úc": "AU", "new zealand": "NZ",
    "southern england": "Southern-England", "miền nam nước anh": "Southern-England",
    "northern england": "Northern-England", "scotland": "Scotland", "wales": "Wales", "california": "California",
    "canada": "CA", "canadian": "CA", "ireland": "IE", "irish": "IE",
}


def region_label(label: str) -> str:
    return " ".join(re.split(r"[-_\s]+", label.casefold().strip()))


def pronunciation_metadata(sound: dict, *, form: str, source: str) -> dict:
    labels = list(dict.fromkeys(str(t).strip() for key in ("tags", "raw_tags") for t in sound.get(key) or [] if str(t).strip()))
    regions = list(dict.fromkeys(REGIONS[region_label(label)] for label in labels if region_label(label) in REGIONS))
    notation = str(sound.get("ipa") or "").strip() or None
    # Legacy respellings use breve/acute/prime notation despite the source field name.
    respelling = notation and (any(c in notation for c in "ĕĭŏŭēāīōūʹ") or any(unicodedata.combining(c) == 230 and c == "\u0306" for c in unicodedata.normalize("NFD", notation)))
    return {
        "regions": regions, "labels": labels, "notation": notation,
        "notation_system": "SOURCE" if respelling else "IPA" if notation else None,
        "form": sound.get("form") or form,
        "usage": sound.get("note") or sound.get("text"), "source": source,
        "scope_status": "EXPLICIT" if sound.get("note") or sound.get("text") or sound.get("form") else "UNSPECIFIED",
        "source_record_key": hashlib.sha256(json.dumps(sound,sort_keys=True,ensure_ascii=False).encode()).hexdigest(), "unknown_labels": [label for label in labels if region_label(label) not in REGIONS],
    }


def accent(sound: dict) -> str:
    regions = pronunciation_metadata(sound, form="", source="")["regions"]
    return "/".join(regions) if regions else "unspecified"
