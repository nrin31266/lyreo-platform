"""Validate and apply the authored starter path to matching source subtopics."""
from __future__ import annotations

from collections import Counter

from source_repairs import digest

PATH = "editorial/grammar_theory.json"


def apply(bundle: dict, subtopics: list[dict], memberships: list[dict]) -> dict[str, dict]:
    if bundle.get("format") != "lyreo.grammar-theory.v1" or not bundle.get("author"):
        raise ValueError("Unrecognized grammar theory source")
    available = {row["id"]: row for row in subtopics}
    counts = Counter(row["subtopic_id"] for row in memberships)
    lessons = {}
    orders = set()
    for row in bundle["lessons"]:
        identifier, theory = row["subtopic_id"], row["theory"]
        source = available.get(identifier)
        if source is None or row["source_sha256"] != digest(source) or not counts[identifier]:
            raise ValueError(f"Grammar theory target changed or has no practice: {identifier}")
        order = theory["order"]
        if identifier in lessons or type(order) is not int or order < 1 or order > 9999 or order in orders:
            raise ValueError("Grammar theory subtopics and lesson orders must be unique")
        orders.add(order)
        for field, size in (("objectiveVi", 500), ("objectiveEn", 500), ("ruleVi", 5000), ("ruleEn", 5000)):
            text(theory.get(field), size)
        examples = theory.get("examples")
        if not isinstance(examples, list) or not 1 <= len(examples) <= 8:
            raise ValueError("Grammar theory needs one to eight examples")
        for example in examples:
            text(example.get("en"), 500)
            text(example.get("vi"), 500)
        lessons[identifier] = theory
    return lessons


def text(value: str, limit: int) -> None:
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise ValueError("Grammar theory requires bounded bilingual text")
