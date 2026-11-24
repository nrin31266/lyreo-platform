#!/usr/bin/env python3
"""Generate Vietnamese draft glosses and example translations offline for release preparation."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from build_vocabulary_release import clean
from clean_import_common import chunks, jsonl_rows


def acceptable(source: str, result: str | None) -> bool:
    if not result or len(result) > min(500, max(48, 3 * len(source) + 20)):
        return False
    if any(char in result for char in ("♪", "�")) or re.search(r"([a-z])\1{3,}|(\b\w+\b)(?:\s+\2){2,}", result, re.I):
        return False
    words = result.casefold().split()
    trigrams = list(zip(words, words[1:], words[2:]))
    return len(trigrams) == len(set(trigrams)) and not re.search(r"[\u3400-\u9fff]", result)


def sentence(text: str) -> str:
    return text if text.endswith((".", "?", "!")) else text + "."

def translate(release: Path, model: Path, output: Path, threads: int, device: str, reuse: Path | None) -> None:
    import ctranslate2
    import sentencepiece

    entries = list(jsonl_rows(release / "entries.jsonl"))
    source = sentencepiece.SentencePieceProcessor(model_file=str(model / "source.spm"))
    target = sentencepiece.SentencePieceProcessor(model_file=str(model / "target.spm"))
    translator = ctranslate2.Translator(str(model), device=device, compute_type="int8_float16" if device == "cuda" else "int8", intra_threads=threads)
    previous = {row["id"]: row for row in jsonl_rows(reuse)} if reuse else {}
    translated = {}
    if reuse:
        for row in entries:
            content = row["content"]
            earlier = previous[row["id"]]
            definitions = [sense["definitionEn"] for sense in content["senses"]]
            for key, english in (("senses", definitions), ("examples", content["examples"]),
                                 ("collocations", content["collocations"])):
                if key in earlier:
                    translated.update((text, result) for text, result in zip(english, earlier[key], strict=True)
                                      if acceptable(text, result))
    texts = sorted({
        text for row in entries
        for text in [*(sense["definitionEn"] for sense in row["content"]["senses"]),
                     *row["content"]["examples"], *row["content"]["collocations"]]
        if text not in translated
    }, key=lambda text: (len(text), text))
    checked = 0
    for batch in chunks(texts, 256):
        results = translator.translate_batch(
            [source.encode(sentence(text), out_type=str) + ["</s>"] for text in batch],
            beam_size=3, max_batch_size=64, max_decoding_length=min(100, max(14, len(batch[-1]) // 2 + 12)),
            repetition_penalty=1.15, no_repeat_ngram_size=3,
        )
        for text, result in zip(batch, results, strict=True):
            translation = target.decode(result.hypotheses[0]).strip()
            if not text.endswith((".", "?", "!")):
                translation = translation.removesuffix(".")
            translated[text] = clean(translation) if acceptable(text, translation) and translation.casefold() != text.casefold() else None
        checked += len(batch)
        if checked % 2048 == 0 or checked == len(texts):
            print(f"{checked}/{len(texts)} source strings translated", flush=True)

    with output.open("x", encoding="utf-8") as stream:
        for row in entries:
            values = row["content"]
            generated = {"id": row["id"],
                         "senses": [translated[sense["definitionEn"]] for sense in values["senses"]],
                         "examples": [translated[text] for text in values["examples"]],
                         "collocations": [translated[text] for text in values["collocations"]]}
            stream.write(json.dumps(generated, ensure_ascii=False, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=6)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--reuse", type=Path, help="Reuse draft gloss and example translations from the same base")
    args = parser.parse_args()
    translate(args.release, args.model, args.output, args.threads, args.device, args.reuse)
