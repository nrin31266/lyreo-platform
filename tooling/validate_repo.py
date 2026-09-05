#!/usr/bin/env python3
"""Offline repository guardrails for Lyreo.

This script is intentionally dependency-light and runs even when Maven Central,
Docker or pnpm are unavailable. It does not replace compilation/tests; it catches
repository drift and architecture violations early enough for humans/agents.
"""
from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
warnings: list[str] = []

REQUIRED = [
    "README.md",
    "AGENTS.md",
    "docs/LYREO_PLATFORM_SPEC.md",
    "docs/ARCHITECTURE.md",
    "docs/TECH_CHOICES.md",
    "docs/DATA_PIPELINES.md",
    "docs/DECISIONS.md",
    "docs/CONFIGURATION.md",
    "docs/DEVELOPMENT.md",
    "docs/OPERATIONS.md",
    "pom.xml",
    "compose.dev.yml",
    "compose.prod.yml",
    ".dockerignore",
    "infra/docker/README.md",
    "scripts/doctor.sh",
    "scripts/verify-prod-env.sh",
    "platform/config/pom.xml",
    "apps/core-service/.env.example",
    "apps/admin-web/.env.example",
    "apps/mobile/.env.example",
    "services/ai-service/.env.example",
    "infra/keycloak/import/lyreo-realm.json",
    "infra/postgres/init/01-create-keycloak-db.sh",
    "tools/data-import/import_grammar.py",
    "tools/data-import/import_toeic.py",
    "tools/data-import/import_lexicon.py",
]

for rel in REQUIRED:
    if not (ROOT / rel).exists():
        errors.append(f"missing required file: {rel}")


# Real secret env files must never be present in an artifact/commit.
for path in ROOT.rglob(".env"):
    errors.append(f"real .env file must not be committed: {path.relative_to(ROOT)}")

# ZIP/repository hygiene: generated environments, caches and build outputs must not ship.
GENERATED_DIR_NAMES = {
    ".venv", "__pycache__", ".pytest_cache", "node_modules", "target", ".expo", ".data"
}
for path in ROOT.rglob("*"):
    if path.is_dir() and path.name in GENERATED_DIR_NAMES:
        errors.append(f"generated directory must not ship: {path.relative_to(ROOT)}")
    elif path.is_file() and (path.suffix == ".pyc" or path.name.endswith(".log")):
        errors.append(f"generated file must not ship: {path.relative_to(ROOT)}")

# TESTING_NOTES is an artifact handoff only. The repository must remain valid after team deletes it.
testing_notes = ROOT / "TESTING_NOTES.md"
if testing_notes.exists():
    head = testing_notes.read_text(encoding="utf-8")[:800].upper()
    if "TEMPORARY" not in head:
        errors.append("TESTING_NOTES.md must clearly identify itself as temporary handoff material")

# Manifests/config syntax -------------------------------------------------------
for path in ROOT.rglob("pom.xml"):
    try:
        ET.parse(path)
    except Exception as exc:
        errors.append(f"bad XML {path.relative_to(ROOT)}: {exc}")

for path in ROOT.rglob("*.json"):
    if any(part in {"node_modules", "target", ".expo"} for part in path.parts):
        continue
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"bad JSON {path.relative_to(ROOT)}: {exc}")

for rel in ("compose.dev.yml", "compose.prod.yml", "compose.gpu.yml"):
    path = ROOT / rel
    if not path.exists():
        continue
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or "services" not in data:
            errors.append(f"compose file has no services map: {rel}")
    except Exception as exc:
        errors.append(f"bad YAML {rel}: {exc}")

# Hard technology decisions ---------------------------------------------------
for path in {ROOT / "pom.xml", *ROOT.glob("**/pom.xml")}:
    text = path.read_text(encoding="utf-8").lower()
    forbidden = {
        "spring-kafka": "Kafka",
        "kafka-clients": "Kafka",
        "<artifactid>jedis": "Redis/Jedis",
        "<artifactid>lettuce": "Redis/Lettuce",
        "spring-boot-starter-data-redis": "Spring Data Redis",
    }
    for token, label in forbidden.items():
        if token in text:
            errors.append(f"forbidden {label} dependency in {path.relative_to(ROOT)}")

# FastAPI is an AI execution boundary, not a shadow business backend.
for path in (ROOT / "services/ai-service").rglob("*.py"):
    text = path.read_text(encoding="utf-8").lower()
    for forbidden in (
        "curriculum_progress",
        "diamond_wallet",
        "lesson_build_job",
        "mission_progress",
        "vocabulary_card",
    ):
        if forbidden in text:
            errors.append(
                f"FastAPI contains business persistence token `{forbidden}` in {path.relative_to(ROOT)}"
            )

# Frontend public env files must never look like secret stores.
for rel in ("apps/admin-web/.env.example", "apps/mobile/.env.example"):
    path = ROOT / rel
    if not path.exists():
        continue
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key = stripped.split("=", 1)[0].upper()
        if any(token in key for token in ("SECRET", "API_KEY", "PASSWORD", "PRIVATE_KEY")):
            errors.append(f"frontend secret-looking env key in {rel}: {key}")

# One canonical agent contract.
for rel in ("CLAUDE.md", "GEMINI.md", "AGENT.md"):
    path = ROOT / rel
    if not path.is_symlink() or os.readlink(path) != "AGENTS.md":
        errors.append(f"{rel} must symlink to AGENTS.md")

# Pragmatic Clean Architecture ------------------------------------------------
MODULE_PACKAGE = {
    "identity": "identity",
    "learner": "learner",
    "ai": "ai",
    "lesson": "lesson",
    "speech-assessment": "speechassessment",
    "lexicon": "lexicon",
    "vocabulary": "vocabulary",
    "grammar": "grammar",
    "toeic": "toeic",
    "curriculum": "curriculum",
    "gamification": "gamification",
    "analytics": "analytics",
    "notification": "notification",
    "chat": "chat",
}
PACKAGE_TO_MODULE = {package: module for module, package in MODULE_PACKAGE.items()}
PUBLIC_CROSS_MODULE_PREFIXES = (
    "com.lyreo.ai.application.",
    "com.lyreo.ai.domain.",
    "com.lyreo.identity.application.",
)
IMPORT_RE = re.compile(r"^import\s+([\w.]+);", re.MULTILINE)

for module_name, package_name in MODULE_PACKAGE.items():
    module_root = ROOT / "modules" / module_name
    if not module_root.exists():
        errors.append(f"missing business module directory: modules/{module_name}")
        continue

    for path in module_root.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT)
        relative_parts = set(path.relative_to(module_root).parts)
        is_domain_or_application_or_api = bool(relative_parts & {"domain", "application", "api"})

        if is_domain_or_application_or_api:
            if f"com.lyreo.{package_name}.infrastructure." in text:
                errors.append(f"clean architecture violation (inner -> infrastructure): {relative}")
            if "org.springframework.data." in text or "jakarta.persistence." in text:
                errors.append(f"persistence framework leaked into inner layer: {relative}")

        for imported in IMPORT_RE.findall(text):
            # No business module may couple to another module's infrastructure.
            match = re.match(r"com\.lyreo\.([a-z][a-z0-9]*)\.infrastructure\.", imported)
            if match and match.group(1) != package_name:
                errors.append(f"cross-module infrastructure import in {relative}: {imported}")

            business = re.match(r"com\.lyreo\.([a-z][a-z0-9]*)\.", imported)
            if not business:
                continue
            imported_package = business.group(1)
            if imported_package not in PACKAGE_TO_MODULE or imported_package == package_name:
                continue
            if not imported.startswith(PUBLIC_CROSS_MODULE_PREFIXES):
                errors.append(
                    f"cross-module import must use a named/public interface or event: {relative}: {imported}"
                )

# Every business module has an explicit root package contract for humans/Modulith discovery.
for module_name, package_name in MODULE_PACKAGE.items():
    package_info = (
        ROOT / "modules" / module_name / "src/main/java/com/lyreo" / package_name / "package-info.java"
    )
    if not package_info.exists():
        errors.append(f"business module missing root package-info.java: modules/{module_name}")

# FastAPI must stay a thin capability runtime: no ORM/business database dependency.
ai_pyproject = ROOT / "services/ai-service/pyproject.toml"
if ai_pyproject.exists():
    ai_manifest = ai_pyproject.read_text(encoding="utf-8").lower()
    for token in ("sqlalchemy", "psycopg", "asyncpg", "alembic"):
        if token in ai_manifest:
            errors.append(f"FastAPI must not own business persistence dependency: {token}")

# Business package names must not masquerade as infrastructure in platform.
for path in (ROOT / "platform").rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    for package_name in PACKAGE_TO_MODULE:
        if f"import com.lyreo.{package_name}." in text:
            errors.append(f"platform depends on business module: {path.relative_to(ROOT)}")

# Spring proxy footgun: method-level @Transactional on a concrete final class cannot be
# subclass-proxied when the bean has no transactional interface proxy. Keep application services
# non-final unless transaction semantics are provided elsewhere explicitly.
for path in ROOT.rglob("*.java"):
    if any(part == "target" for part in path.parts):
        continue
    text = path.read_text(encoding="utf-8")
    if "@Transactional" in text and re.search(r"public\s+final\s+class\s+", text):
        # Infrastructure adapters implementing interfaces may still receive JDK proxies, but
        # allowing this exception in a static checker hides application-service mistakes. Prefer
        # non-final transactional targets consistently.
        errors.append(f"transactional target must not be final: {path.relative_to(ROOT)}")

# Lyreo disables Modulith runtime DDL, so Flyway must carry the current PostgreSQL registry shape.
modulith_migration = ROOT / "apps/core-service/src/main/resources/db/migration/V001__platform_identity_learner.sql"
if modulith_migration.exists():
    migration_text = modulith_migration.read_text(encoding="utf-8").lower()
    for required in (
        "serialized_event text not null",
        "event_publication_serialized_event_hash_idx",
        "completion_attempts int",
        "last_resubmission_date",
    ):
        if required not in migration_text:
            errors.append(f"Spring Modulith PostgreSQL registry schema marker missing: {required}")

# Basic migration hygiene ------------------------------------------------------
migrations = sorted((ROOT / "apps/core-service/src/main/resources/db/migration").glob("V*__*.sql"))
versions: list[int] = []
for path in migrations:
    match = re.match(r"V(\d+)__", path.name)
    if not match:
        errors.append(f"invalid Flyway migration name: {path.name}")
        continue
    versions.append(int(match.group(1)))
if len(versions) != len(set(versions)):
    errors.append("duplicate Flyway migration version")
if versions and versions != sorted(versions):
    errors.append("Flyway migrations are not sorted")

# Common security footguns -----------------------------------------------------
for path in ROOT.rglob("*.java"):
    if any(part in {"target"} for part in path.parts):
        continue
    text = path.read_text(encoding="utf-8")
    lowered = text.lower()
    if any(token in lowered for token in (
        "ddl-auto=update", "ddl-auto: update",
        "ddl-auto=create", "ddl-auto: create",
        "ddl-auto=create-drop", "ddl-auto: create-drop",
    )):
        errors.append(f"Hibernate schema mutation setting found in {path.relative_to(ROOT)}")

# Core schema policy must stay explicit.
app_yml = ROOT / "apps/core-service/src/main/resources/application.yml"
if app_yml.exists():
    app_text = app_yml.read_text(encoding="utf-8")
    if "ddl-auto: validate" not in app_text:
        errors.append("Core application.yml must keep Hibernate ddl-auto=validate")

# Compose starter should not silently reintroduce forbidden infrastructure.
for rel in ("compose.dev.yml", "compose.prod.yml", "compose.gpu.yml"):
    path = ROOT / rel
    if path.exists():
        lowered = path.read_text(encoding="utf-8").lower()
        if re.search(r"(^|\n)\s*(redis|kafka|zookeeper):", lowered):
            errors.append(f"forbidden broker/cache service in {rel}")

# Helpful non-fatal warnings.
if not (ROOT / "pnpm-lock.yaml").exists():
    warnings.append("pnpm-lock.yaml is absent; generate it after `pnpm install` on a networked dev machine")

if errors:
    print("VALIDATION FAILED")
    print("\n".join(f"- {item}" for item in errors))
    if warnings:
        print("WARNINGS")
        print("\n".join(f"- {item}" for item in warnings))
    sys.exit(1)

java = len(list(ROOT.rglob("*.java")))
py = len(list(ROOT.rglob("*.py")))
ts = len(list(ROOT.rglob("*.ts"))) + len(list(ROOT.rglob("*.tsx")))
sql = len(list(ROOT.rglob("*.sql")))
print(f"VALIDATION OK | Java={java} Python={py} TS/TSX={ts} SQL={sql}")
for warning in warnings:
    print(f"WARNING: {warning}")
