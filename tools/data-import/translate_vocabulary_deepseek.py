#!/usr/bin/env python3
"""Translate and enrich Vocabulary base entries through DeepSeek in compact batches.

The API key stays in the ignored importer .env. Compact indexed batches minimize input and
output tokens, per-batch atomic caching makes interrupted runs free to resume, and automatic
batch splitting isolates rare schema hiccups without losing completed work.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-flash")
BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/chat/completions")
PROMPT_VERSION = "v3"
CJK_RE = re.compile(r"[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]")
REPEAT_RE = re.compile(r"(\b[\wÀ-ỹ]+\b)(?:\s+\1){2,}", re.IGNORECASE)
REL_RE = re.compile(r"^[A-Za-z][A-Za-z' -]{0,38}$")
FAM_RE = re.compile(r"^[A-Za-z][A-Za-z' -]{0,32} \((?:n|v|adj|adv|prep|conj|pron|interj)\.\)$")
POS_PREFIX_RE = re.compile(
    r"^(?:\(?(?:n|v|adj|adv|prep|conj|pron|interj|det|phr(?:\.\s*v)?|modal|num|aux)\.?\)?\s*[:\-–]?\s+)+",
    re.IGNORECASE,
)

SYSTEM = (
    "You localize an English-Vietnamese vocabulary study catalog. Return JSON only: "
    '{"items":[{"k":0,"s":["..."],"e":["..."],"c":["..."],"y":["..."],"n":["..."],"f":["..."]}]}. '
    "Rules:\n"
    "1. Match every input k in order; keep exact array lengths for s, e, c.\n"
    "2. s: concise, natural Vietnamese dictionary gloss for word w in each POS+definition (ideal for flashcards/quizzes, not a wordy literal translation of the English definition; separate nuances with '; ' if helpful).\n"
    "3. e: natural, idiomatic Vietnamese sentence translation; never repeat words unnaturally or insert the English headword.\n"
    "4. c: natural Vietnamese translation of each English collocation.\n"
    "5. y (English synonyms) & n (English antonyms): When input lacks y (or len(y)<2), include 1-2 common same-POS English synonyms in y if natural ones exist. When input lacks n (or len(n)<2), include 1-2 genuine same-POS English antonyms in n if a real opposite exists (do not force opposites for concrete nouns). Omit y/n when none apply.\n"
    "6. f (word family): ONLY when input lacks f and standard derivatives exist, include 1-2 items formatted 'word (pos.)' like 'admiration (n.)'; otherwise omit f."
)


def norm(text: str) -> str:
    return " ".join(unicodedata.normalize("NFC", text).split())


def strip_pos_prefix(text: str) -> str:
    stripped = POS_PREFIX_RE.sub("", text).strip()
    return stripped or text


def valid_vi(text: object, source: str, *, strip_pos: bool = False) -> str | None:
    if not isinstance(text, str):
        return None
    cleaned = norm(text)
    if strip_pos:
        cleaned = strip_pos_prefix(cleaned)
    if not cleaned or len(cleaned) > 500 or CJK_RE.search(cleaned) or REPEAT_RE.search(cleaned):
        return None
    if any(unicodedata.category(ch).startswith("C") for ch in cleaned):
        return None
    if len(source.split()) >= 3 and cleaned.casefold() == norm(source).casefold():
        return None
    return cleaned


def clean_relations(values: object, term: str, existing: list[str], pattern: re.Pattern[str], limit: int = 3) -> list[str]:
    if not isinstance(values, list):
        return []
    seen = {term.casefold(), *(item.split(" (", 1)[0].strip().casefold() for item in existing)}
    accepted: list[str] = []
    for raw in values:
        if not isinstance(raw, str):
            continue
        cleaned = norm(raw)
        head = cleaned.split(" (", 1)[0].strip()
        if not pattern.fullmatch(cleaned) or head.count(" ") > 2 or head.casefold() in seen:
            continue
        seen.add(head.casefold())
        accepted.append(cleaned)
        if len(accepted) >= limit:
            break
    return accepted


def make_prompt_item(index: int, row: dict) -> dict:
    content = row["content"]
    item: dict[str, object] = {
        "k": index,
        "w": row["term"],
        "s": [f"{sense['pos']} {sense['definitionEn']}" for sense in content["senses"]],
        "e": content["examples"],
        "c": content["collocations"],
    }
    if content["synonyms"]:
        item["y"] = content["synonyms"]
    if content["antonyms"]:
        item["n"] = content["antonyms"]
    if content["family"]:
        item["f"] = content["family"]
    return item


def batch_hash(rows: list[dict], model: str) -> str:
    canonical = [
        {
            "id": row["id"],
            "term": row["term"],
            "senses": [(s["pos"], s["definitionEn"]) for s in row["content"]["senses"]],
            "examples": row["content"]["examples"],
            "collocations": row["content"]["collocations"],
            "synonyms": row["content"]["synonyms"],
            "antonyms": row["content"]["antonyms"],
            "family": row["content"]["family"],
        }
        for row in rows
    ]
    payload = json.dumps({"v": PROMPT_VERSION, "model": model, "rows": canonical}, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def parse_batch_response(payload: object, rows: list[dict]) -> dict[str, dict]:
    if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
        raise ValueError("Response must contain an items array")
    by_k = {item.get("k"): item for item in payload["items"] if isinstance(item, dict) and isinstance(item.get("k"), int)}
    if set(by_k) != set(range(len(rows))):
        raise ValueError(f"Expected {len(rows)} indexed items, got {len(by_k)}")
    decoded: dict[str, dict] = {}
    for index, row in enumerate(rows):
        item = by_k[index]
        content = row["content"]
        senses_src = [s["definitionEn"] for s in content["senses"]]
        senses_vi = item.get("s")
        examples_vi = item.get("e")
        collocations_vi = item.get("c")
        if (
            not isinstance(senses_vi, list) or len(senses_vi) != len(senses_src)
            or not isinstance(examples_vi, list) or len(examples_vi) != len(content["examples"])
            or not isinstance(collocations_vi, list) or len(collocations_vi) != len(content["collocations"])
        ):
            raise ValueError(f"Array length mismatch for {row['source_id']}")
        clean_s = [valid_vi(val, src, strip_pos=True) for val, src in zip(senses_vi, senses_src, strict=True)]
        clean_e = [valid_vi(val, src) for val, src in zip(examples_vi, content["examples"], strict=True)]
        clean_c = [valid_vi(val, src) for val, src in zip(collocations_vi, content["collocations"], strict=True)]
        if any(val is None for val in clean_s) or any(val is None for val in clean_e) or any(val is None for val in clean_c):
            raise ValueError(f"Invalid or untranslated Vietnamese field for {row['source_id']}")
        decoded[row["id"]] = {
            "id": row["id"],
            "senses": clean_s,
            "examples": clean_e,
            "collocations": clean_c,
            "synonyms": clean_relations(item.get("y", []), row["term"], content["synonyms"], REL_RE),
            "antonyms": clean_relations(item.get("n", []), row["term"], content["antonyms"], REL_RE),
            "family": clean_relations(item.get("f", []), row["term"], content["family"], FAM_RE),
        }
    return decoded


def valid_cached_entry(item: object, row: dict) -> bool:
    if not isinstance(item, dict) or item.get("id") != row["id"]:
        return False
    content = row["content"]
    for key, src_len in (("senses", len(content["senses"])), ("examples", len(content["examples"])), ("collocations", len(content["collocations"]))):
        vals = item.get(key)
        if not isinstance(vals, list) or len(vals) != src_len or any(not isinstance(v, str) or not v or CJK_RE.search(v) for v in vals):
            return False
    for key in ("synonyms", "antonyms", "family"):
        vals = item.get(key, [])
        if not isinstance(vals, list) or any(not isinstance(v, str) for v in vals):
            return False
    return True


def call_api(rows: list[dict], api_key: str, model: str, retries: int) -> tuple[dict[str, dict], int, int]:
    prompt_items = [make_prompt_item(i, row) for i, row in enumerate(rows)]
    estimated = sum(len(item["s"]) * 16 + len(item["e"]) * 28 + len(item["c"]) * 12 + 24 for item in prompt_items)
    body = json.dumps({
        "model": model,
        "thinking": {"type": "disabled"},
        "temperature": 0.1,
        "max_tokens": min(8192, max(2048, 3 * estimated)),
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": json.dumps(prompt_items, ensure_ascii=False, separators=(",", ":"))},
        ],
    }).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            request = Request(
                BASE_URL,
                data=body,
                headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            )
            with urlopen(request, timeout=90) as response:
                payload = json.loads(response.read())
            usage = payload.get("usage") or {}
            content = json.loads(payload["choices"][0]["message"]["content"])
            return (
                parse_batch_response(content, rows),
                int(usage.get("prompt_tokens", 0)),
                int(usage.get("completion_tokens", 0)),
            )
        except (HTTPError, URLError, TimeoutError, KeyError, IndexError, json.JSONDecodeError, ValueError) as exc:
            last_error = exc
            if attempt < retries:
                time.sleep(min(8, 1.5 * (2 ** attempt)))
    if len(rows) > 1:
        mid = len(rows) // 2
        left, lp, lc = call_api(rows[:mid], api_key, model, retries)
        right, rp, rc = call_api(rows[mid:], api_key, model, retries)
        return left | right, lp + rp, lc + rc
    raise RuntimeError(f"translation failed for {rows[0]['source_id']}: {type(last_error).__name__}: {last_error}") from last_error


def write_atomic(path: Path, content: str) -> None:
    tmp = path.with_suffix(f".{os.getpid()}.{threading.get_ident()}.tmp")
    tmp.write_text(content, encoding="utf-8")
    tmp.replace(path)


def run(input_path: Path, output_path: Path, cache_dir: Path, batch_size: int, workers: int, retries: int) -> None:
    api_key = (os.getenv("DEEPSEEK_API_KEY") or os.getenv("DEEPSEEK_KEY") or "").strip()
    if not api_key:
        raise SystemExit("DEEPSEEK_API_KEY is required. Put it in the ignored importer .env, never in the release or logs.")
    model = os.getenv("DEEPSEEK_MODEL", MODEL)
    all_rows = [json.loads(line) for line in input_path.open(encoding="utf-8") if line.strip()]
    rows = [row for row in all_rows if not str(row.get("source_id", "")).startswith("toeic:")]
    cache_dir.mkdir(parents=True, exist_ok=True)

    # Also index any per-entry cached records already in cache_dir so resizing batches still reuses completed entries.
    entry_cache_dir = cache_dir / "entries"
    entry_cache_dir.mkdir(parents=True, exist_ok=True)
    results: dict[str, dict] = {}
    remaining: list[dict] = []
    for row in rows:
        epath = entry_cache_dir / f"{row['id']}.json"
        if epath.is_file():
            try:
                cached_item = json.loads(epath.read_text(encoding="utf-8"))
                if valid_cached_entry(cached_item, row):
                    results[row["id"]] = cached_item
                    continue
            except (OSError, json.JSONDecodeError):
                pass
        remaining.append(row)

    size = max(1, batch_size)
    pending: list[tuple[list[dict], Path]] = []
    for start in range(0, len(remaining), size):
        batch = remaining[start:start + size]
        bpath = cache_dir / f"{batch_hash(batch, model)}.json"
        if bpath.is_file():
            try:
                raw = json.loads(bpath.read_text(encoding="utf-8"))
                items = {item["id"]: item for item in raw.get("items", []) if isinstance(item, dict) and "id" in item}
                if set(items) == {r["id"] for r in batch} and all(valid_cached_entry(items[r["id"]], r) for r in batch):
                    results.update(items)
                    for r in batch:
                        write_atomic(entry_cache_dir / f"{r['id']}.json", json.dumps(items[r["id"]], ensure_ascii=False, separators=(",", ":")) + "\n")
                    continue
            except (OSError, json.JSONDecodeError):
                pass
        pending.append((batch, bpath))

    print(f"loaded {len(results)}/{len(rows)} cached entries; {len(pending)} batches to translate with {model}", flush=True)
    prompt_tokens = completion_tokens = 0
    lock = threading.Lock()
    done = 0

    def worker(batch: list[dict], bpath: Path) -> dict[str, dict]:
        nonlocal prompt_tokens, completion_tokens, done
        decoded, p_tok, c_tok = call_api(batch, api_key, model, retries)
        write_atomic(bpath, json.dumps({"items": list(decoded.values())}, ensure_ascii=False, separators=(",", ":")) + "\n")
        for ident, item in decoded.items():
            write_atomic(entry_cache_dir / f"{ident}.json", json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n")
        with lock:
            prompt_tokens += p_tok
            completion_tokens += c_tok
            done += 1
            if done == 1 or done % 10 == 0 or done == len(pending):
                print(
                    f"translated {done}/{len(pending)} batches "
                    f"(prompt={prompt_tokens} tok, completion={completion_tokens} tok)",
                    flush=True,
                )
        return decoded

    if pending:
        pool = ThreadPoolExecutor(max_workers=max(1, min(workers, 24)))
        try:
            futures = [pool.submit(worker, batch, bpath) for batch, bpath in pending]
            for future in as_completed(futures):
                results.update(future.result())
        except Exception:
            pool.shutdown(wait=False, cancel_futures=True)
            raise
        else:
            pool.shutdown(wait=True)

    if set(results) != {row["id"] for row in rows}:
        raise RuntimeError("Translation output does not cover all base entries")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as stream:
        for row in rows:
            item = results[row["id"]]
            stream.write(json.dumps({
                "id": row["id"],
                "senses": item["senses"],
                "examples": item["examples"],
                "collocations": item["collocations"],
                "synonyms": item.get("synonyms", []),
                "antonyms": item.get("antonyms", []),
                "family": item.get("family", []),
            }, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"completed {len(rows)} entries -> {output_path} (prompt={prompt_tokens}, completion={completion_tokens})", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=25)
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--retries", type=int, default=2)
    args = parser.parse_args()
    run(args.input, args.output, args.cache_dir, args.batch_size, args.workers, args.retries)
