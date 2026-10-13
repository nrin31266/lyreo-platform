"""Selected internal provenance and occurrence identity for auxiliary VI data.

Physical ordinals locate evidence in a checksum-pinned input. They never select
an English lexical item. Unknown raw keys require review, rather than a raw blob
fallback or silent data loss.
"""
from __future__ import annotations

import gzip
import json
import math
import uuid
from collections import Counter
from pathlib import Path
from typing import Iterator

RECORD_CONTEXT_FIELDS = frozenset({
    "pos", "pos_title", "tags", "raw_tags", "etymology_texts", "etymology_links", "notes",
})
SENSE_CONTEXT_FIELDS = frozenset({
    "glosses", "tags", "raw_tags", "topics", "categories", "form_of", "alt_of", "examples",
})
EXAMPLE_CONTEXT_FIELDS = frozenset({"text", "translation", "roman", "ref"})
KNOWN_OMITTED_RECORD_FIELDS = frozenset({
    "word", "lang", "lang_code", "categories", "forms", "sounds", "related", "synonyms",
    "antonyms", "derived", "anagrams", "hyphenations", "translations", "coordinate_terms",
    "descendants", "hypernyms", "hyponyms", "meronyms", "holonyms", "redirects",
})
KNOWN_OMITTED_EXAMPLE_FIELDS = frozenset({
    "bold_text_offsets", "bold_translation_offsets", "bold_roman_offsets",
})


def reject_json_constant(value: str) -> None:
    raise ValueError(f"Non-finite JSON number: {value}")


def finite_json_float(value: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"Non-finite JSON number: {value}")
    return result


def occurrence_id(source_sha256: str, record_line: int, sense_ordinal: int, gloss_ordinal: int) -> str:
    return str(uuid.uuid5(
        uuid.NAMESPACE_URL,
        f"lyreo:lexicon:trans:viwiktionary:occurrence:{source_sha256}:"
        f"{record_line}:{sense_ordinal}:{gloss_ordinal}",
    ))


def normalized_source_pos(value: object) -> str | None:
    pos = str(value or "").strip().lower()
    return pos if pos and pos != "unknown" else None


def stream_physical_jsonl(path: Path) -> Iterator[tuple[int, dict]]:
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if line.strip():
                record = json.loads(line, parse_constant=reject_json_constant, parse_float=finite_json_float)
                if not isinstance(record, dict):
                    raise ValueError(f"VI raw record must be an object at line {line_number}")
                yield line_number, record


def new_field_inventory() -> dict:
    return {
        level: {kind: Counter() for kind in ("preserved", "omitted", "unknown")}
        for level in ("record", "sense", "example")
    }


def _nullable_array(value: object, field: str, location: str) -> list:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ValueError(f"Invalid VI {field} at {location}: expected nullable array")
    return value


def _nullable_text(value: object, field: str, location: str) -> None:
    if value is not None and not isinstance(value, str):
        raise ValueError(f"Invalid VI {field} at {location}: expected nullable text")


def _string_array(value: object, field: str, location: str) -> None:
    if any(not isinstance(item, str) for item in _nullable_array(value, field, location)):
        raise ValueError(f"Invalid VI {field} at {location}: expected nullable string array")


def inventory_raw_record(record: dict, line: int, inventory: dict) -> None:
    def inspect(value: dict, level: str, kept: frozenset, omitted: frozenset, location: str):
        unknown = set(value) - kept - omitted
        for key in value:
            bucket = "preserved" if key in kept else "omitted" if key in omitted else "unknown"
            inventory[level][bucket][key] += 1
        if unknown:
            raise ValueError(f"Unclassified VI {level} fields at {location}: {', '.join(sorted(unknown))}")

    location = f"line {line}"
    inspect(record, "record", RECORD_CONTEXT_FIELDS | {"senses"}, KNOWN_OMITTED_RECORD_FIELDS, location)
    for key in ("pos", "pos_title"):
        _nullable_text(record.get(key), f"record_context.{key}", location)
    for key in ("tags", "raw_tags", "notes", "etymology_texts"):
        _string_array(record.get(key), f"record_context.{key}", location)
    links = _nullable_array(record.get("etymology_links"), "record_context.etymology_links", location)
    if any(not isinstance(link, list) or len(link) != 2
           or any(not isinstance(value, str) for value in link) for link in links):
        raise ValueError(f"Invalid VI record_context.etymology_links at {location}: expected pairs of text")
    senses = _nullable_array(record.get("senses"), "senses", location)
    for ordinal, sense in enumerate(senses, start=1):
        if not isinstance(sense, dict):
            raise ValueError(f"VI sense must be an object at line {line}, sense {ordinal}")
        location = f"line {line}, sense {ordinal}"
        inspect(sense, "sense", SENSE_CONTEXT_FIELDS, frozenset(), location)
        for key in ("glosses", "tags", "raw_tags", "topics", "categories"):
            _string_array(sense.get(key), f"sense_context.{key}", location)
        for key in ("form_of", "alt_of"):
            links = _nullable_array(sense.get(key), f"sense_context.{key}", location)
            if any(not isinstance(link, dict) or not set(link) <= {"word", "roman"}
                   or not isinstance(link.get("word"), str)
                   or (link.get("roman") is not None and not isinstance(link["roman"], str))
                   for link in links):
                raise ValueError(f"Invalid VI sense_context.{key} at {location}: expected lexical references")
        examples = _nullable_array(sense.get("examples"), "sense_context.examples", location)
        for example in examples:
            if not isinstance(example, dict):
                raise ValueError(f"VI example must be an object at {location}")
            inspect(example, "example", EXAMPLE_CONTEXT_FIELDS, KNOWN_OMITTED_EXAMPLE_FIELDS, location)
            _nullable_text(example.get("text"), "sense_context.examples.text", location)
            for key in ("translation", "roman", "ref"):
                value = example.get(key)
                if value is not None and not isinstance(value, (str, dict, list)):
                    raise ValueError(f"Invalid VI sense_context.examples.{key} at {location}: "
                                     "expected nullable text or nested JSON")


def project_source_context(record: dict, line: int, sense: dict, sense_ordinal: int, gloss_ordinal: int) -> dict:
    sense_context = {key: value for key, value in sense.items() if key in SENSE_CONTEXT_FIELDS}
    if isinstance(sense.get("examples"), list):
        sense_context["examples"] = [
            {key: value for key, value in example.items() if key in EXAMPLE_CONTEXT_FIELDS}
            for example in sense["examples"]
        ]
    return {
        "record_line": line,
        "record_context": {key: value for key, value in record.items() if key in RECORD_CONTEXT_FIELDS},
        "sense_ordinal": sense_ordinal,
        "sense_context": sense_context,
        "gloss_ordinal": gloss_ordinal,
    }
