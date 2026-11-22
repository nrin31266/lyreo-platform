"""Local reference IPA capability. No lesson policy or language-learning prompts."""
from __future__ import annotations

import asyncio
from functools import lru_cache
import re
import subprocess
import unicodedata
from datetime import date

from fastapi import HTTPException

from ..schemas import ExecuteRequest, ExecuteResponse

# These forms need context or a proper-name check; reference IPA is never audio-derived.
AMBIGUOUS = re.compile(r"\b(read|lead|live|record|present|wind|tear|bow|close|minute|object|invalid|use|used)\b", re.I)
WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")
TITLES = {"mr": "mister", "mrs": "missus", "ms": "miz", "dr": "doctor", "prof": "professor"}
MONTHS = ("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
CURRENCIES = {"$": ("dollar", "cent"), "£": ("pound", "penny"), "€": ("euro", "cent")}
DETAIL_MARKS = str.maketrans({"ɹ": "r", "ɻ": "r", "ɫ": "l", "ⁿ": "n", "ˡ": "l", "ᵐ": "m",
                              "ʰ": "", "ʲ": "", "ʷ": ""})
KNOWN_PAIRS = {("with", "the"): (r"wɪ[ðθ]", r"ð[əiɪ]"), ("in", "the"): ("ɪn", r"ð[əiɪ]"),
               ("of", "the"): (r"[ʌə]v", r"ð[əiɪ]"), ("i", "shall"): ("aɪ", r"ʃ[əæɐ]l"),
               ("was", "a"): ("wʌz", r"[əɐ]"), ("out", "of"): (r"[ˈˌ]?aʊ[tɾ]", r"[əʌ]v")}
SPOKEN_FORMS = re.compile(
    r"(?P<url>(?:https?://|www\.)[^\s<>]+|\b[\w-]+\.(?:com|org|net|edu)\b)|"
    r"(?P<date>\b(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}/[0-9]{1,2}/[0-9]{4})\b)|"
    r"(?P<money>[$£€][0-9]+(?:,[0-9]{3})*(?:\.[0-9]{1,2}(?![0-9]))?)|"
    r"(?P<time>\b(?:[01]?[0-9]|2[0-3]):[0-5][0-9]\b)|"
    r"(?P<percent>\b[0-9]+(?:,[0-9]{3})*(?:\.[0-9]+)?%)|"
    r"(?P<grouped>\b[0-9]{1,3}(?:,[0-9]{3})+\b)|"
    r"(?P<initialism>\b(?:[A-Z]\.){2,})|"
    r"(?P<title>\b(?:Mr|Mrs|Ms|Dr|Prof)\.(?=\s+[A-Z]))|"
    r"(?P<code>\b[A-Z][A-Z0-9-]*[0-9][A-Z0-9-]*\b)|"
    r"(?P<number>\b[0-9]+(?:\.[0-9]+)?\b)"
)


def _year(value: str) -> str:
    century, rest = int(value[:2]), int(value[2:])
    if 10 <= century <= 20 and not (century == 20 and rest < 10):
        return f"{century} hundred" if not rest else f"{century} {'oh ' if rest < 10 else ''}{rest}"
    return value


def _issue(text: str, start: int, end: int, code: str) -> dict:
    # The browser and Core use UTF-16 offsets into the original sentence.
    return {
        "start": len(text[:start].encode("utf-16-le")) // 2,
        "end": len(text[:end].encode("utf-16-le")) // 2,
        "surface": text[start:end],
        "code": code,
    }


def _prepare(text: str) -> tuple[str, list[dict]]:
    issues = []
    covered = set()

    def spoken(match: re.Match) -> str:
        value, kind = match.group(), match.lastgroup
        if kind in {"title", "initialism", "url", "code"}:
            covered.update(range(*match.span()))
        if kind == "date":
            parts = [int(part) for part in re.split(r"[-/]", value)]
            if "-" in value:
                year, month, day = parts
            else:
                first, second, year = parts
                if first <= 12 and second <= 12 and first != second:
                    issues.append(_issue(text, *match.span(), "DATE_FORMAT"))
                    return " ".join(re.split(r"[-/]", value))
                month, day = (second, first) if first > 12 else (first, second)
            try:
                date(year, month, day)
            except ValueError:
                issues.append(_issue(text, *match.span(), "DATE_FORMAT"))
                return " ".join(re.split(r"[-/]", value))
            suffix = "th" if 11 <= day <= 13 else {1: "st", 2: "nd", 3: "rd"}.get(day % 10, "th")
            return f"{MONTHS[month-1]} {day}{suffix} {_year(str(year))}"
        if kind == "url":
            issues.append(_issue(text, *match.span(), "URL"))
        elif kind == "money":
            major, _, fraction = value[1:].replace(",", "").partition(".")
            unit, subunit = CURRENCIES[value[0]]
            minor = int(fraction.ljust(2, "0")) if fraction else 0
            main = f"{major} {unit}{'' if major.lstrip('0') == '1' else 's'}"
            if minor:
                plural = "pence" if subunit == "penny" else "cents"
                return f"{main} and {minor} {subunit if minor == 1 else plural}"
            return main
        elif kind == "time":
            hours, minutes = map(int, value.split(":"))
            if not minutes:
                return f"{hours} o'clock"
            return f"{hours} {'oh ' if minutes < 10 else ''}{minutes}"
        elif kind == "percent":
            return value[:-1].replace(",", "") + " percent"
        elif kind == "grouped":
            return value.replace(",", "")
        elif kind == "initialism":
            return " ".join(value.replace(".", ""))
        elif kind == "title":
            return TITLES[value[:-1].lower()]
        elif kind == "code":
            return " ".join(value.replace("-", ""))
        elif kind == "number" and len(value) == 4 and value.isascii() and value.isdecimal():
            before = text[max(0, match.start() - 12):match.start()]
            after = text[match.end():]
            cardinal_noun = re.match(r"\s+(?:people|years?|dollars?|students?|words?|items?)\b", after, re.I)
            if not cardinal_noun and re.search(r"\b(?:in|since|by|year(?: of)?|around|from)\s+$", before, re.I):
                return _year(value)
        return value

    prepared = SPOKEN_FORMS.sub(spoken, text)
    for match in AMBIGUOUS.finditer(text):
        issues.append(_issue(text, *match.span(), "MULTIPLE_READINGS"))
    for index, match in enumerate(WORD.finditer(text)):
        word = match.group()
        if match.start() not in covered:
            if word in {"sk", "sh", "pf"} or (word in {"p", "d", "f", "t"} and re.search(r"\b(?:sound|letters?|shift|starting with)\b", text, re.I)):
                issues.append(_issue(text, *match.span(), "LETTER_OR_SOUND"))
            elif (word in {"Alice", "Chaucer", "Beowulf", "Nguyen"} or
                  (index > 0 and word != "I" and word.istitle() and not re.search(r"[.!?]\W*$", text[:match.start()]))):
                issues.append(_issue(text, *match.span(), "NAME"))
    prepared = unicodedata.normalize("NFC", prepared)
    prepared = re.sub(r"(?<=\w)[‘’](?=\w)", "'", prepared)
    prepared = re.sub(r"[“”‘’\u00ab\u00bb\"]|(?<!\w)'|'(?!\w)", " ", prepared)
    return " ".join(prepared.split()), issues


def _display(raw: str, prepared: str, source: str, accent: str, issues: list[dict]) -> str:
    words = [m.group().lower() for m in WORD.finditer(prepared)]
    tokens = raw.split()
    if len(tokens) < len(words):
        for index, (left, right) in enumerate(zip(words, words[1:])):
            pair = KNOWN_PAIRS.get((left, right))
            if not pair or index >= len(tokens):
                continue
            first, second = pair
            joined = re.fullmatch(rf"({first})({second})", tokens[index])
            if joined:
                tokens[index:index+1] = list(joined.groups())

    source_words = list(WORD.finditer(source))
    aligned = len(tokens) == len(words)
    source_aligned = aligned and words == [m.group().lower().replace("’", "'") for m in source_words]
    display = []
    uncertain = False
    for i, token in enumerate(tokens):
        word = words[i] if aligned else ""
        ipa = token.translate(DETAIL_MARKS)
        # The English eSpeak tables use ɐ for an unstressed reduced vowel (a#).
        ipa = re.sub(r"(?<![ˈˌ])ɐ", "ə", ipa)
        if accent == "US":
            ipa = re.sub(r"(?:ɚ|ə˞)r?", "ər", ipa)
            ipa = re.sub(r"(?:ɝ|ɜ˞)r?", "ɜr", ipa)
            if re.search(r"[aeiou]r", word):
                ipa = re.sub(r"ɜːr?", "ɜr", ipa)
        if "ɾ" in ipa and ipa.count("ɾ") == 1:
            # The same flap may represent /t/ or /d/. A unique intervocalic spelling is evidence.
            candidates = re.findall(r"[aeiouyr](tt?|dd?)(?=[aeiouy])", word)
            if len(candidates) == 1:
                ipa = ipa.replace("ɾ", candidates[0][0])
            elif word in {"it", "at", "but", "that", "what", "not", "out", "had", "did"} and ipa.endswith("ɾ"):
                ipa = ipa[:-1] + word[-1]
        if word in {"written", "button", "cotton", "certain", "kitten"}:
            ipa = ipa.replace("ʔn̩", "tən").replace("tn̩", "tən")
        if re.search(r"(?:t|d)ᵻd$|(?:s|z|ʃ|ʒ)ᵻz$", ipa):
            ipa = ipa.replace("ᵻ", "ɪ")
        if word.endswith(("ity", "ities")):
            ipa = re.sub(r"ᵻ(?=[tɾ]i(?:z)?$)", "ə", ipa)
        if re.search(r"[ɐɾᵻʔʁ˞̩]", ipa):
            if source_aligned:
                match = source_words[i]
                issues.append(_issue(source, *match.span(), "IPA_VARIANT"))
            else:
                uncertain = True
        display.append(ipa)
    if uncertain:
        issues.append(_issue(source, 0, len(source), "WORD_BOUNDARIES"))
    return " ".join(display)


@lru_cache(maxsize=2048)
def _transcribe(text: str, accent: str, version: str) -> str:
    try:
        result = subprocess.run(
            ["espeak-ng", "-q", "--ipa", "-v", "en-us" if accent == "US" else "en-gb", "--stdin"],
            input=text + "\n", encoding="utf-8", capture_output=True, check=True, timeout=10,
        )
    except FileNotFoundError as error:
        raise HTTPException(503, "Local IPA requires espeak-ng") from error
    except (subprocess.SubprocessError, OSError) as error:
        raise HTTPException(503, "Local IPA execution failed") from error
    ipa = result.stdout.strip()
    if not ipa or len(ipa) > 30000:
        raise ValueError("Local IPA returned empty or oversized output")
    return ipa


@lru_cache(maxsize=1)
def _version() -> str:
    try:
        result = subprocess.run(["espeak-ng", "--version"], capture_output=True, text=True, check=True, timeout=5)
    except (OSError, subprocess.SubprocessError) as error:
        raise HTTPException(503, "Local IPA requires espeak-ng") from error
    version = re.search(r"\b\d+\.\d+(?:\.\d+)?\b", result.stdout)
    return "espeak-ng:" + (version.group(0) if version else "unknown")


class IpaRuntime:
    async def analyze(self, request: ExecuteRequest) -> ExecuteResponse:
        sentences = request.input.get("sentences")
        accent = request.options.get("accent", "US")
        if accent not in {"US", "UK"} or not isinstance(sentences, list) or not 1 <= len(sentences) <= 1000:
            raise ValueError("Provide sentences and a supported IPA accent")
        if any(not isinstance(text, str) or not text.strip() or len(text) > 10000 for text in sentences):
            raise ValueError("Invalid IPA sentence")
        if sum(map(len, sentences)) > 200000:
            raise ValueError("IPA input is too large")
        version = await asyncio.to_thread(_version)
        items = []
        for index, text in enumerate(sentences):
            prepared, issues = _prepare(text)
            raw = await asyncio.to_thread(_transcribe, prepared, accent, version)
            ipa = _display(raw, prepared, text, accent, issues)
            issues.sort(key=lambda issue: (issue["start"], issue["end"], issue["code"]))
            items.append({"i": index + 1, "ipa_raw": raw, "display_version": "learner-ipa-v2",
                          "ipa": ipa, "needs_review": bool(issues), "review_issues": issues[:100]})
        return ExecuteResponse(output={"items": items, "accent": accent, "version": version}, metadata={"runtime": "local-espeak-ng", "reference_only": True})
