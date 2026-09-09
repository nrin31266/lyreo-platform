#!/usr/bin/env python3
"""Dependency-free checks for Lyreo project documentation.

Supported Markdown is intentionally limited to ATX headings, explicit HTML anchors, inline links,
and reference-style links. Fenced blocks are excluded. Semantic duplication still needs review.
"""
from __future__ import annotations

import os
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote

PROJECT_REQUIRED = (
    "AGENTS.md", "README.md", "docs/README.md", "docs/documentation.md",
    "docs/product/discovery.md", "docs/product/prd.md", "docs/requirements/analysis.md",
    "docs/requirements/non-functional.md", "docs/requirements/user-stories.md",
    "docs/requirements/traceability.md", "docs/requirements/gaps.md", "docs/ARCHITECTURE.md",
    "docs/requirements/identity-learner.md", "docs/requirements/lesson.md",
    "docs/requirements/ai.md", "docs/requirements/lexicon-vocabulary.md",
    "docs/requirements/grammar-toeic.md", "docs/requirements/curriculum-gamification.md",
    "docs/requirements/analytics-notification-chat.md",
    "docs/requirements/stories/identity-learner.md", "docs/requirements/stories/lesson.md",
    "docs/requirements/stories/ai.md", "docs/requirements/stories/lexicon-vocabulary.md",
    "docs/requirements/stories/grammar-toeic.md",
    "docs/requirements/stories/curriculum-gamification.md",
    "docs/requirements/stories/analytics-notification-chat.md",
    "docs/features/lesson-build.md", "docs/features/dictation.md", "docs/features/shadowing.md",
    "docs/features/ai-routing.md", "docs/features/auth-onboarding.md",
    "docs/features/vocabulary-srs.md", "docs/features/grammar-practice.md",
    "docs/features/toeic-attempts.md", "docs/features/curriculum-progress.md",
    "docs/features/rewards-analytics.md",
    "docs/architecture/background-jobs.md", "docs/architecture/ai-execution.md",
    "docs/architecture/frontend-conventions.md", "docs/CONFIGURATION.md",
    "docs/DATA_PIPELINES.md", "docs/DEVELOPMENT.md", "docs/OPERATIONS.md",
    "docs/TECH_CHOICES.md", "docs/DECISIONS.md", "docs/coursework/chapter-03.md",
    "docs/coursework/ai-usage-log.md", "docs/LYREO_PLATFORM_SPEC.md",
)
SKIP_PARTS = {
    ".git", ".agents", ".codex", ".venv", "node_modules", "build", "dist", "target",
    ".pytest_cache", "__pycache__", ".data",
}
ID_RE = re.compile(
    r"\b(?:FR|BR|NFR|US|AC)-[A-Z]{2,5}-\d{3}\b|\bFEAT-[A-Z0-9-]+\b|\bGAP-\d{3}\b|\bD-\d{3}\b"
)
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
EXPLICIT_ANCHOR_RE = re.compile(r"<a\s+(?:id|name)=[\"']([^\"']+)[\"']\s*></a>", re.I)
INLINE_LINK_RE = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")
REFERENCE_USE_RE = re.compile(r"!?\[([^\]]+)]\[([^\]]*)]")
REFERENCE_DEF_RE = re.compile(r"^\s*\[([^\]]+)]:\s*(\S+)", re.M)
CODE_TOKEN_RE = re.compile(r"`([^`\n]+)`")


def markdown_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*.md") if path.is_file()
                  and not any(part in SKIP_PARTS for part in path.relative_to(root).parts))


def without_fences(text: str) -> str:
    output: list[str] = []
    fence: str | None = None
    for line in text.splitlines():
        match = re.match(r"^\s*(```+|~~~+)", line)
        if match:
            marker = match.group(1)[0]
            fence = None if fence == marker else marker if fence is None else fence
            output.append("")
        else:
            output.append(line if fence is None else "")
    return "\n".join(output)


def slugify(value: str) -> str:
    value = re.sub(r"<[^>]+>", "", value).strip().lower()
    value = re.sub(r"\s*—\s*", "--", value)
    value = "".join(char for char in unicodedata.normalize("NFKD", value)
                    if not unicodedata.combining(char))
    value = value.replace("đ", "d")
    value = re.sub(r"[^\w\- ]", "", value, flags=re.UNICODE)
    return re.sub(r"\s+", "-", value).strip("-")


def anchors_for(text: str) -> set[str]:
    clean = without_fences(text)
    anchors = set(EXPLICIT_ANCHOR_RE.findall(clean))
    counts: dict[str, int] = defaultdict(int)
    for line in clean.splitlines():
        match = HEADING_RE.match(line)
        if match:
            base = slugify(match.group(2))
            count = counts[base]
            anchors.add(base if count == 0 else f"{base}-{count}")
            counts[base] += 1
    return anchors


def link_target(raw: str) -> str:
    target = raw.strip()
    if target.startswith("<") and ">" in target:
        return target[1:target.index(">")]
    return target.split()[0] if target else target


def validate_markdown_tree(root: Path, *, required: tuple[str, ...] = (),
                           project_guards: bool = False) -> list[str]:
    root = root.resolve()
    errors: list[str] = []
    for relative in required:
        if not (root / relative).exists():
            errors.append(f"missing canonical doc: {relative}")

    files = markdown_files(root)
    texts = {path: path.read_text(encoding="utf-8") for path in files}
    anchors = {path: anchors_for(text) for path, text in texts.items()}
    definitions: dict[str, list[str]] = defaultdict(list)
    references: list[tuple[str, str]] = []

    for path, raw_text in texts.items():
        clean = without_fences(raw_text)
        rel = path.relative_to(root).as_posix()
        reference_defs = {key.lower(): link_target(target)
                          for key, target in REFERENCE_DEF_RE.findall(clean)}
        for line_no, line in enumerate(clean.splitlines(), 1):
            heading = HEADING_RE.match(line)
            if heading:
                defined = ID_RE.match(heading.group(2))
                if defined:
                    definitions[defined.group(0)].append(f"{rel}:{line_no}")
            for target in INLINE_LINK_RE.findall(line):
                _check_link(root, path, rel, line_no, link_target(target), anchors, errors)
            for label, key in REFERENCE_USE_RE.findall(line):
                lookup = (key or label).lower()
                target = reference_defs.get(lookup)
                if target is None:
                    errors.append(f"{rel}:{line_no}: undefined Markdown reference [{lookup}]")
                else:
                    _check_link(root, path, rel, line_no, target, anchors, errors)
        references.extend((identifier, rel) for identifier in ID_RE.findall(clean))

    for identifier, locations in sorted(definitions.items()):
        if len(locations) > 1:
            errors.append(f"duplicate ID definition {identifier}: {', '.join(locations)}")
    for identifier, rel in references:
        if identifier not in definitions:
            errors.append(f"{rel}: reference to undefined ID {identifier}")

    if project_guards:
        _check_concrete_evidence_paths(root, texts, errors)
        _check_legacy_owner_references(root, texts, errors)
        for alias in ("AGENT.md", "CLAUDE.md", "GEMINI.md"):
            path = root / alias
            if not path.is_symlink() or os.readlink(path) != "AGENTS.md":
                errors.append(f"{alias} must symlink to AGENTS.md")
    return sorted(set(errors))


def _check_link(root: Path, source: Path, rel: str, line_no: int, target: str,
                anchors: dict[Path, set[str]], errors: list[str]) -> None:
    if not target or target.startswith(("http://", "https://", "mailto:", "tel:", "data:")):
        return
    path_part, separator, fragment = unquote(target).partition("#")
    destination = source if not path_part else (source.parent / path_part).resolve()
    try:
        destination.relative_to(root)
    except ValueError:
        errors.append(f"{rel}:{line_no}: internal link escapes repository: {target}")
        return
    if not destination.exists():
        errors.append(f"{rel}:{line_no}: missing internal link target: {target}")
    elif separator and fragment:
        if destination.suffix.lower() != ".md":
            errors.append(f"{rel}:{line_no}: fragment used on non-Markdown target: {target}")
        elif fragment not in anchors.get(destination, set()):
            errors.append(f"{rel}:{line_no}: missing anchor: {target}")


def _check_concrete_evidence_paths(root: Path, texts: dict[Path, str], errors: list[str]) -> None:
    for path in (root / "docs/README.md", root / "docs/requirements/traceability.md"):
        text = texts.get(path)
        if text is None:
            continue
        rel = path.relative_to(root).as_posix()
        for line_no, line in enumerate(without_fences(text).splitlines(), 1):
            for token in CODE_TOKEN_RE.findall(line):
                candidate = token.strip().rstrip(".,;:")
                if ("/" not in candidate or candidate.startswith(("/api/", "http://", "https://"))
                        or any(mark in candidate for mark in ("*", "…", "{", "}", "<", ">", "|"))
                        or " " in candidate):
                    continue
                if not (root / candidate.split("#", 1)[0]).exists():
                    errors.append(f"{rel}:{line_no}: concrete route/evidence path does not exist: {candidate}")


def _check_legacy_owner_references(root: Path, texts: dict[Path, str], errors: list[str]) -> None:
    allowed = {root / "docs/LYREO_PLATFORM_SPEC.md", root / "README.md", root / "AGENTS.md",
               root / "AGENT.md", root / "CLAUDE.md", root / "GEMINI.md", root / "docs/README.md",
               root / "docs/documentation.md", root / "TESTING_NOTES.md"}
    for path, text in texts.items():
        if path not in allowed and "LYREO_PLATFORM_SPEC.md" in without_fences(text):
            errors.append(f"{path.relative_to(root).as_posix()}: legacy master referenced as a current owner")


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = validate_markdown_tree(root, required=PROJECT_REQUIRED, project_guards=True)
    if errors:
        for error in errors:
            print(f"DOCS ERROR: {error}", file=sys.stderr)
        print(f"DOCS VALIDATION FAILED | errors={len(errors)}", file=sys.stderr)
        return 1
    print(f"DOCS VALIDATION OK | Markdown={len(markdown_files(root))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
