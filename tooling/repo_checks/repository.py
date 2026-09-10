from __future__ import annotations

import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

GENERATED_DIR_NAMES = {
    ".pytest_cache",
    "__pycache__",
    "node_modules",
    "target",
    ".expo",
    "dist",
    ".vite",
    ".venv",
    ".data",
    ".turbo",
}


def is_generated_path(path: Path, root: Path | None = None) -> bool:
    """Return True if any component of the path is a generated/cache directory."""
    if root is not None:
        try:
            parts = path.relative_to(root).parts
        except ValueError:
            parts = path.parts
    else:
        parts = path.parts
    return any(part in GENERATED_DIR_NAMES for part in parts)


def repo_files(base: Path, pattern: str, root: Path | None = None):
    """Yield source files under *base* matching *pattern*, skipping generated dirs."""
    if not base.exists():
        return
    effective_root = root if root is not None else base
    for path in base.rglob(pattern):
        if path.is_file() and not is_generated_path(path, effective_root):
            yield path


REQUIRED_FILES = (
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
    "scripts/fetch-data.sh",
    "scripts/verify-prod-env.sh",
    "platform/config/pom.xml",
    "apps/core-service/.env.example",
    "apps/admin-web/.env.example",
    "apps/mobile/.env.example",
    "apps/ai-service/.env.example",
    "infra/keycloak/import/lyreo-realm.json",
    "infra/postgres/init/01-create-keycloak-db.sh",
    "tools/data-import/import_grammar.py",
    "tools/data-import/import_toeic.py",
    "tools/data-import/import_lexicon.py",
    "tools/data-import/.env.example",
    "packages/design-system/package.json",
    "packages/design-system/src/semantic.ts",
    "packages/i18n/package.json",
    "packages/i18n/src/index.ts",
    "packages/i18n/src/locales/en/common.json",
    "packages/i18n/src/locales/en/admin.json",
    "packages/i18n/src/locales/en/mobile.json",
    "packages/i18n/src/locales/vi/common.json",
    "packages/i18n/src/locales/vi/admin.json",
    "packages/i18n/src/locales/vi/mobile.json",
    "apps/admin-web/components.json",
    "apps/admin-web/src/providers/AppThemeProvider.tsx",
    "apps/mobile/components.json",
    "apps/mobile/global.css",
    "apps/mobile/babel.config.js",
    "apps/mobile/metro.config.js",
    "apps/mobile/src/providers/AppThemeProvider.tsx",
    "apps/mobile/src/providers/LocaleProvider.tsx",
)

REQUIRED_LOCKFILES = (
    "pnpm-lock.yaml",
    "apps/ai-service/uv.lock",
    "tools/data-import/uv.lock",
)

ENV_OWNER_SCOPES = {
    "apps/core-service/.env.example": ("apps/core-service",),
    "apps/ai-service/.env.example": ("apps/ai-service/app",),
    "infra/docker/.env.example": ("compose.dev.yml", "compose.prod.yml", "compose.gpu.yml", "scripts/init-dev-env.sh"),
    "infra/keycloak/.env.example": ("infra/keycloak/scripts", "scripts/init-dev-env.sh"),
    "tools/data-import/.env.example": ("tools/data-import", "scripts/fetch-data.sh", "scripts/init-dev-env.sh", "scripts/doctor.sh"),
}

IMPLICIT_ENV_KEYS = {
    "infra/docker/.env.example": {"COMPOSE_PROJECT_NAME"},
    "apps/core-service/.env.example": {"SPRING_PROFILES_ACTIVE"},
}

ALLOWED_CROSS_OWNER_ENV_KEYS = {
    "AI_SERVICE_INTERNAL_TOKEN",
    "DATABASE_URL",
    "DEV_BOOTSTRAP_TOKEN",
    "KEYCLOAK_ADMIN",
    "KEYCLOAK_ADMIN_PASSWORD",
    "LYREO_CORE_CLIENT_SECRET",
    "R2_ENDPOINT",
    "R2_ACCESS_KEY_ID",
    "R2_SECRET_ACCESS_KEY",
    "R2_BUCKET",
}


def check_git_cleanliness(root: Path, errors: list[str], warnings: list[str]) -> None:
    """Ensure git does not track generated, bytecode, log, or .env files."""
    git_dir = root / ".git"
    if not git_dir.exists():
        warnings.append("Git metadata unavailable; tracked generated/cache-file and .env checks skipped")
        return

    git_result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        capture_output=True,
        text=True,
    )
    if git_result.returncode == 0:
        tracked_files = {item for item in git_result.stdout.split("\0") if item}
        for rel in sorted(tracked_files):
            path = Path(rel)
            if any(part in GENERATED_DIR_NAMES for part in path.parts):
                errors.append(f"generated/cache file must not be tracked: {rel}")
            if path.suffix == ".pyc":
                errors.append(f"generated Python bytecode must not be tracked: {rel}")
            if path.name.endswith(".log"):
                errors.append(f"generated log file must not be tracked: {rel}")
            if path.name == ".env":
                errors.append(f"real .env file must not be committed: {rel}")
    else:
        warnings.append("Git metadata unavailable; tracked generated/cache-file and .env checks skipped")


def check_required_files(root: Path, errors: list[str]) -> None:
    """Verify presence of core repository structural and configuration files."""
    for rel in REQUIRED_FILES:
        if not (root / rel).exists():
            errors.append(f"missing required file: {rel}")


def check_data_env(root: Path, errors: list[str]) -> None:
    """Verify data import env template keys and gitignore exclusion of raw data."""
    data_env = root / "tools/data-import/.env.example"
    if data_env.exists():
        data_env_text = data_env.read_text(encoding="utf-8")
        for required_key in ("DAUTOEIC_DATA_DIR=", "DAUTOEIC_DATA_URL=", "DAUTOEIC_DATA_SHA256="):
            if required_key not in data_env_text:
                errors.append(f"data importer env example missing {required_key[:-1]}")
        data_url_match = re.search(r"^DAUTOEIC_DATA_URL=(.+)$", data_env_text, re.MULTILINE)
        if not data_url_match or not data_url_match.group(1).strip():
            errors.append("data importer env example must provide a non-empty DAUTOEIC_DATA_URL")

    gitignore = root / ".gitignore"
    if gitignore.exists() and ".data/" not in gitignore.read_text(encoding="utf-8"):
        errors.append(".gitignore must exclude repo-local .data/ datasets/artifacts")


def check_manifest_syntax(root: Path, errors: list[str]) -> None:
    """Verify XML and JSON files parse cleanly."""
    for path in repo_files(root, "pom.xml", root):
        try:
            ET.parse(path)
        except Exception as exc:
            errors.append(f"bad XML {path.relative_to(root)}: {exc}")

    for path in repo_files(root, "*.json", root):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"bad JSON {path.relative_to(root)}: {exc}")


def check_compose_syntax(root: Path, errors: list[str]) -> None:
    """Verify top-level compose files define services."""
    for rel in ("compose.dev.yml", "compose.prod.yml", "compose.gpu.yml"):
        path = root / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        if not re.search(r"(?m)^services:\s*$", text):
            errors.append(f"compose file has no top-level services map: {rel}")


def check_env_contracts(root: Path, errors: list[str]) -> None:
    """Verify frontend env secrets, consumers, and duplicate owners."""
    for rel in ("apps/admin-web/.env.example", "apps/mobile/.env.example"):
        path = root / rel
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            key = stripped.split("=", 1)[0].upper()
            if any(token in key for token in ("SECRET", "API_KEY", "PASSWORD", "PRIVATE_KEY")):
                errors.append(f"frontend secret-looking env key in {rel}: {key}")

    for rel, adapter in (
        ("apps/admin-web/.env.example", "apps/admin-web/src/env.ts"),
        ("apps/mobile/.env.example", "apps/mobile/src/env.ts"),
    ):
        env_path, adapter_path = root / rel, root / adapter
        if env_path.exists() and adapter_path.exists():
            adapter_text = adapter_path.read_text(encoding="utf-8")
            for line in env_path.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith("#") or "=" not in stripped:
                    continue
                key = stripped.split("=", 1)[0]
                if key not in adapter_text:
                    errors.append(f"frontend env key has no adapter consumer: {rel}:{key}")

    for rel, scopes in ENV_OWNER_SCOPES.items():
        env_path = root / rel
        if not env_path.exists():
            continue
        owner_texts: list[str] = []
        for scope in scopes:
            target = root / scope
            candidates = [target] if target.is_file() else list(target.rglob("*")) if target.exists() else []
            for candidate in candidates:
                if not candidate.is_file() or candidate.name == ".env.example":
                    continue
                if is_generated_path(candidate, root):
                    continue
                try:
                    owner_texts.append(candidate.read_text(encoding="utf-8"))
                except (UnicodeDecodeError, OSError):
                    pass
        owner_text = "\n".join(owner_texts)
        for line in env_path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key = stripped.split("=", 1)[0].strip()
            if key in IMPLICIT_ENV_KEYS.get(rel, set()):
                continue
            if key not in owner_text and key.lower() not in owner_text.lower():
                errors.append(f"env example key has no owner-scope consumer: {rel}:{key}")

    env_key_owners: dict[str, list[str]] = {}
    for rel in ENV_OWNER_SCOPES:
        path = root / rel
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key = stripped.split("=", 1)[0]
            env_key_owners.setdefault(key, []).append(rel)

    for key, owners in env_key_owners.items():
        if len(owners) > 1 and key not in ALLOWED_CROSS_OWNER_ENV_KEYS:
            errors.append(f"env key duplicated across owners without an explicit boundary allowlist: {key} -> {owners}")


def check_lockfiles(root: Path, errors: list[str]) -> None:
    """Verify existence of mandatory lockfiles and workspace importers."""
    for lock_rel in REQUIRED_LOCKFILES:
        lock_path = root / lock_rel
        if not lock_path.exists():
            errors.append(f"missing required lockfile: {lock_rel}")

    lockfile = root / "pnpm-lock.yaml"
    if lockfile.exists():
        lock_text = lockfile.read_text(encoding="utf-8")
        required_importers = (
            "apps/admin-web:",
            "apps/mobile:",
            "packages/design-system:",
            "packages/i18n:",
        )
        missing_importers = [
            item[:-1] for item in required_importers
            if not re.search(rf"^  {re.escape(item)}", lock_text, re.MULTILINE)
        ]
        if missing_importers:
            errors.append("pnpm-lock.yaml is stale/incomplete; missing workspace importers: " + ", ".join(missing_importers))


def check_repository(root: Path, errors: list[str], warnings: list[str]) -> None:
    """Run all repository cleanliness, required files, syntax, and lockfile checks."""
    check_git_cleanliness(root, errors, warnings)
    check_required_files(root, errors)
    check_data_env(root, errors)
    check_manifest_syntax(root, errors)
    check_compose_syntax(root, errors)
    check_env_contracts(root, errors)
    check_lockfiles(root, errors)
