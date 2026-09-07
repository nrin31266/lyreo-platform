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
    "scripts/fetch-data.sh",
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
]

for rel in REQUIRED:
    if not (ROOT / rel).exists():
        errors.append(f"missing required file: {rel}")

# Dataset acquisition must remain reproducible without committing the multi-GB source tree.
data_env = ROOT / "tools/data-import/.env.example"
if data_env.exists():
    data_env_text = data_env.read_text(encoding="utf-8")
    for required_key in ("DAUTOEIC_DATA_DIR=", "DAUTOEIC_DATA_URL=", "DAUTOEIC_DATA_SHA256="):
        if required_key not in data_env_text:
            errors.append(f"data importer env example missing {required_key[:-1]}")
    data_url_match = re.search(r"^DAUTOEIC_DATA_URL=(.+)$", data_env_text, re.MULTILINE)
    if not data_url_match or not data_url_match.group(1).strip():
        errors.append("data importer env example must provide a non-empty DAUTOEIC_DATA_URL")

gitignore = ROOT / ".gitignore"
if gitignore.exists() and ".data/" not in gitignore.read_text(encoding="utf-8"):
    errors.append(".gitignore must exclude repo-local .data/ datasets/artifacts")


# Real secret env files must never be present in an artifact/commit.
for path in ROOT.rglob(".env"):
    errors.append(f"real .env file must not be committed: {path.relative_to(ROOT)}")

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

# Artifact cleanliness -----------------------------------------------------------
GENERATED_DIR_NAMES = {
    ".pytest_cache", "__pycache__", "node_modules", "target", ".expo", "dist", ".vite",
    ".venv", ".data", ".turbo",
}
for path in ROOT.rglob("*"):
    if path.is_dir() and path.name in GENERATED_DIR_NAMES:
        errors.append(f"generated/cache directory must not be shipped: {path.relative_to(ROOT)}")
    elif path.is_file() and path.suffix == ".pyc":
        errors.append(f"generated Python bytecode must not be shipped: {path.relative_to(ROOT)}")
    elif path.is_file() and path.name.endswith(".log"):
        errors.append(f"generated log file must not be shipped: {path.relative_to(ROOT)}")

# Frontend foundation guardrails ------------------------------------------------
# Feature UI must consume semantic variables/classes instead of reintroducing raw colors.
RAW_COLOR_RE = re.compile(r"#[0-9a-fA-F]{3,8}\b|rgba?\(")
for base in (ROOT / "apps/admin-web/src", ROOT / "apps/mobile/app", ROOT / "apps/mobile/src"):
    if not base.exists():
        continue
    for path in base.rglob("*"):
        if not path.is_file() or path.suffix not in {".ts", ".tsx", ".css"}:
            continue
        if RAW_COLOR_RE.search(path.read_text(encoding="utf-8")):
            errors.append(f"raw frontend color literal outside design system: {path.relative_to(ROOT)}")

# Screen-level rule is intentionally explicit even though the broader guard above is stronger:
# raw hex/RGB literals must never creep into Admin/Mobile TSX feature screens. This catches the
# exact dark-mode regression class we care about and keeps the policy obvious to future maintainers.
for base in (ROOT / "apps/admin-web/src/ui", ROOT / "apps/mobile/app"):
    if not base.exists():
        continue
    for path in base.rglob("*.tsx"):
        if RAW_COLOR_RE.search(path.read_text(encoding="utf-8")):
            errors.append(f"raw color literal forbidden in frontend screen: {path.relative_to(ROOT)}")

# Feature/platform code may use semantic contracts and brand metadata, but must not reach through
# the design-system abstraction to primitive palette names.
for base in (ROOT / "apps/admin-web/src", ROOT / "apps/mobile/app", ROOT / "apps/mobile/src"):
    if not base.exists():
        continue
    for path in (*base.rglob("*.ts"), *base.rglob("*.tsx")):
        if "primitiveColors" in path.read_text(encoding="utf-8"):
            errors.append(f"frontend code must not consume primitiveColors directly: {path.relative_to(ROOT)}")

# SecureStore is reserved for authentication/session credentials.
for path in (ROOT / "apps/mobile").rglob("*.ts*"):
    if not path.is_file():
        continue
    if "SecureStore" in path.read_text(encoding="utf-8") and path.relative_to(ROOT).as_posix() != "apps/mobile/src/session.ts":
        errors.append(f"SecureStore usage outside auth/session storage: {path.relative_to(ROOT)}")

# Shared frontend contracts must stay wired to both platform adapters. ---------------------------
def flatten_translation_keys(value: object, prefix: str = "") -> set[str]:
    if not isinstance(value, dict):
        return {prefix}
    keys: set[str] = set()
    for key, nested in value.items():
        next_prefix = f"{prefix}.{key}" if prefix else str(key)
        keys.update(flatten_translation_keys(nested, next_prefix))
    return keys

for namespace in ("common", "admin", "mobile"):
    locale_keys: dict[str, set[str]] = {}
    for locale in ("en", "vi"):
        path = ROOT / f"packages/i18n/src/locales/{locale}/{namespace}.json"
        if path.exists():
            locale_keys[locale] = flatten_translation_keys(json.loads(path.read_text(encoding="utf-8")))
    if len(locale_keys) == 2 and locale_keys["en"] != locale_keys["vi"]:
        missing_vi = sorted(locale_keys["en"] - locale_keys["vi"])
        missing_en = sorted(locale_keys["vi"] - locale_keys["en"])
        errors.append(
            f"i18n key drift in {namespace}: missing vi={missing_vi or '[]'}, missing en={missing_en or '[]'}"
        )

# Catch literal translation-key typos even when EN/VI resource shapes still match each other.
translation_index: dict[str, set[str]] = {}
for namespace in ("common", "admin", "mobile"):
    path = ROOT / f"packages/i18n/src/locales/en/{namespace}.json"
    if path.exists():
        translation_index[namespace] = flatten_translation_keys(json.loads(path.read_text(encoding="utf-8")))
for app, default_namespace in (("apps/admin-web", "admin"), ("apps/mobile", "mobile")):
    base = ROOT / app
    if not base.exists():
        continue
    for path in base.rglob("*.tsx"):
        if "components/ui" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r"\bt\(\s*(['\"])([^'\"`{}]+)\1", text):
            raw_key = match.group(2)
            if ":" in raw_key:
                namespace, key = raw_key.split(":", 1)
            else:
                namespace, key = default_namespace, raw_key
            if namespace in translation_index and key not in translation_index[namespace]:
                errors.append(f"missing literal i18n key in {path.relative_to(ROOT)}: {raw_key}")

semantic_path = ROOT / "packages/design-system/src/semantic.ts"
semantic_roles: tuple[str, ...] = ()
if semantic_path.exists():
    semantic_source = semantic_path.read_text(encoding="utf-8")
    theme_type = re.search(r"export type ThemeColors\s*=\s*\{(.*?)\};", semantic_source, re.DOTALL)
    if theme_type:
        semantic_roles = tuple(re.findall(r"^\s*([A-Za-z][A-Za-z0-9]*)\s*:", theme_type.group(1), re.MULTILINE))
    if not semantic_roles:
        errors.append("could not derive semantic ThemeColors roles for frontend adapter validation")

    # Static 100% light/dark parity: every ThemeColors role must exist exactly once in both maps,
    # neither map may introduce an extra role, and no semantic value may be undefined. TypeScript
    # also enforces this at compile time; keeping the check here protects offline artifact review.
    semantic_maps = re.search(
        r"export const semanticThemes[^=]*=\s*\{\s*light:\s*\{(?P<light>.*?)\}\s*,\s*dark:\s*\{(?P<dark>.*?)\}\s*,?\s*\};",
        semantic_source,
        re.DOTALL,
    )
    if not semantic_maps:
        errors.append("could not parse semanticThemes.light/dark for parity validation")
    else:
        def theme_map_keys(block: str) -> tuple[str, ...]:
            return tuple(re.findall(r"^\s*([A-Za-z][A-Za-z0-9]*)\s*:", block, re.MULTILINE))

        light_roles = theme_map_keys(semantic_maps.group("light"))
        dark_roles = theme_map_keys(semantic_maps.group("dark"))
        expected = set(semantic_roles)
        for mode, roles in (("light", light_roles), ("dark", dark_roles)):
            role_set = set(roles)
            missing = sorted(expected - role_set)
            extra = sorted(role_set - expected)
            duplicate = sorted({role for role in roles if roles.count(role) > 1})
            if missing or extra or duplicate:
                errors.append(
                    f"semanticThemes.{mode} role parity failed: "
                    f"missing={missing or '[]'}, extra={extra or '[]'}, duplicate={duplicate or '[]'}"
                )
            if re.search(r":\s*(?:undefined|null)\b", semantic_maps.group(mode)):
                errors.append(f"semanticThemes.{mode} contains undefined/null semantic color value")
        if set(light_roles) != set(dark_roles):
            errors.append("semanticThemes.light and semanticThemes.dark do not have identical role sets")

        # Every semantic assignment must resolve to an existing primitive key. This catches the
        # static equivalent of `primitiveColors.typo === undefined` even when dependencies cannot
        # be installed and TypeScript cannot run dependency-aware in the review sandbox.
        primitive_path = ROOT / "packages/design-system/src/primitives.ts"
        primitive_keys: set[str] = set()
        if primitive_path.exists():
            primitive_source = primitive_path.read_text(encoding="utf-8")
            primitive_block = re.search(r"export const primitiveColors\s*=\s*\{(.*?)\} as const;", primitive_source, re.DOTALL)
            if primitive_block:
                primitive_keys = set(re.findall(r"^\s*([A-Za-z][A-Za-z0-9]*)\s*:", primitive_block.group(1), re.MULTILINE))
        if not primitive_keys:
            errors.append("could not derive primitiveColors keys for semantic theme validation")
        else:
            for mode in ("light", "dark"):
                block = semantic_maps.group(mode)
                assignments = re.findall(
                    r"^\s*([A-Za-z][A-Za-z0-9]*)\s*:\s*primitiveColors\.([A-Za-z][A-Za-z0-9]*)\s*,?",
                    block,
                    re.MULTILINE,
                )
                if len(assignments) != len(semantic_roles):
                    errors.append(f"semanticThemes.{mode} must map every role directly to primitiveColors")
                for role, primitive in assignments:
                    if primitive not in primitive_keys:
                        errors.append(f"semanticThemes.{mode}.{role} references missing primitiveColors.{primitive}")

admin_styles = ROOT / "apps/admin-web/src/ui/styles.css"
admin_theme_provider = ROOT / "apps/admin-web/src/providers/AppThemeProvider.tsx"
mobile_provider = ROOT / "apps/mobile/src/providers/AppThemeProvider.tsx"
mobile_tailwind = ROOT / "apps/mobile/tailwind.config.js"
mobile_app_config = ROOT / "apps/mobile/app.json"
mobile_layout = ROOT / "apps/mobile/app/_layout.tsx"
if admin_styles.exists() and mobile_provider.exists() and mobile_tailwind.exists():
    admin_text = admin_styles.read_text(encoding="utf-8")
    mobile_provider_text = mobile_provider.read_text(encoding="utf-8")
    mobile_tailwind_text = mobile_tailwind.read_text(encoding="utf-8")
    tailwind_alias = {"focusRing": "ring"}
    for role in semantic_roles:
        css_name = re.sub(r"([A-Z])", lambda match: "-" + match.group(1).lower(), role)
        class_name = tailwind_alias.get(role, css_name)

        # Admin Tailwind alias must point to the matching runtime semantic variable.
        if not re.search(
            rf"--color-{re.escape(class_name)}\s*:\s*var\(--{re.escape(css_name)}\)",
            admin_text,
        ):
            errors.append(f"Admin Tailwind adapter miswired/missing semantic role: {role}")

        # Mobile vars() must expose every role from the matching ThemeColors property.
        if not re.search(
            rf"['\"]--{re.escape(css_name)}['\"]\s*:\s*colors\.{re.escape(role)}\b",
            mobile_provider_text,
        ):
            errors.append(f"Mobile theme provider miswired/missing semantic role: {role}")

        if not re.search(
            rf"['\"]?{re.escape(class_name)}['\"]?\s*:\s*['\"]var\(--{re.escape(css_name)}\)",
            mobile_tailwind_text,
        ):
            errors.append(f"Mobile Tailwind adapter miswired/missing semantic role: {role}")

# Web theme flow: `system` tracks prefers-color-scheme, the resolved mode toggles `.dark`, and
# semantic CSS variables are exposed to Tailwind. The class remains the DOM dark-mode contract.
if admin_theme_provider.exists():
    admin_theme_text = admin_theme_provider.read_text(encoding="utf-8")
    for marker, message in (
        ("(prefers-color-scheme: dark)", "Admin theme provider must observe prefers-color-scheme"),
        ("addEventListener('change'", "Admin system theme must react to OS color-scheme changes"),
        ("preference === 'system'", "Admin theme provider must resolve system preference"),
        ("classList.toggle('dark'", "Admin dark mode must toggle the .dark class on documentElement"),
        ("semanticThemes[mode]", "Admin theme provider must source values from semanticThemes"),
    ):
        if marker not in admin_theme_text:
            errors.append(message)
if admin_styles.exists() and "@custom-variant dark" not in admin_styles.read_text(encoding="utf-8"):
    errors.append("Admin Tailwind CSS must keep a .dark class custom variant")

# Mobile theme flow: system appearance comes from React Native, NativeWind receives semantic CSS
# vars on the root View, and Expo must allow the native app to follow system appearance changes.
if mobile_provider.exists():
    mobile_theme_text = mobile_provider.read_text(encoding="utf-8")
    for marker, message in (
        ("useColorScheme()", "Mobile theme provider must observe device color scheme"),
        ("preference === 'system'", "Mobile theme provider must resolve system preference"),
        ("semanticThemes[mode]", "Mobile theme provider must source values from semanticThemes"),
        ("vars({", "Mobile theme provider must expose semantic variables through NativeWind vars()"),
        ('className="flex-1 bg-background"', "Mobile theme root must consume a semantic NativeWind class"),
    ):
        if marker not in mobile_theme_text:
            errors.append(message)
if mobile_app_config.exists():
    mobile_app = json.loads(mobile_app_config.read_text(encoding="utf-8"))
    if mobile_app.get("expo", {}).get("userInterfaceStyle") != "automatic":
        errors.append("Mobile app.json must keep expo.userInterfaceStyle=automatic for system dark/light changes")
if mobile_layout.exists():
    layout_text = mobile_layout.read_text(encoding="utf-8")
    if "mode === 'dark' ? 'light' : 'dark'" not in layout_text:
        errors.append("Mobile StatusBar must follow resolved theme mode")
    if "backgroundColor: colors.background" not in layout_text:
        errors.append("Mobile navigation content background must follow semantic theme colors")

if semantic_path.exists() and RAW_COLOR_RE.search(semantic_path.read_text(encoding="utf-8")):
    errors.append("semantic theme must map to design-system primitives instead of embedding raw color literals")

# NativeWind/RNR foundation should follow the standard baseline instead of custom Metro resolution hacks.
mobile_manifest = ROOT / "apps/mobile/package.json"
mobile_metro = ROOT / "apps/mobile/metro.config.js"
app_providers = ROOT / "apps/mobile/src/providers/AppProviders.tsx"
if mobile_manifest.exists():
    manifest = json.loads(mobile_manifest.read_text(encoding="utf-8"))
    runtime_dependencies = manifest.get("dependencies", {})
    merged_dependencies = {**manifest.get("dependencies", {}), **manifest.get("devDependencies", {})}
    for package in ("nativewind", "tailwindcss", "tailwindcss-animate", "@rn-primitives/portal"):
        if package not in merged_dependencies:
            errors.append(f"Mobile NativeWind/RNR baseline dependency missing: {package}")

    # Expo Router documents this subset as direct application dependencies. Do not rely on
    # transitive installs because a fresh workspace must not depend on package-manager hoisting.
    expo_router_direct = {
        "expo-router": "~57.0.19",
        "expo-constants": "~57.0.17",
        "expo-linking": "~57.0.9",
        "expo-status-bar": "~57.0.1",
        "react-native-safe-area-context": "~5.7.0",
        "react-native-screens": "~4.26.0",
    }
    for package, expected in expo_router_direct.items():
        actual = runtime_dependencies.get(package)
        if actual is None:
            errors.append(f"Mobile Expo Router direct dependency missing: {package}")
        elif actual != expected:
            errors.append(f"Mobile Expo SDK 57 dependency drift: {package}={actual}, expected {expected}")

    # AuthSession is an application dependency rather than an Expo Router dependency, but pin its
    # SDK 57 baseline here as well so OAuth setup cannot silently drift from the reviewed stack.
    oauth_sdk_pins = {
        "expo-auth-session": "~57.0.11",
        "expo-crypto": "~57.0.2",
    }
    for package, expected in oauth_sdk_pins.items():
        actual = runtime_dependencies.get(package)
        if actual is None:
            errors.append(f"Mobile OAuth dependency missing: {package}")
        elif actual != expected:
            errors.append(f"Mobile Expo SDK 57 OAuth dependency drift: {package}={actual}, expected {expected}")
if mobile_tailwind.exists():
    mobile_tailwind_source = mobile_tailwind.read_text(encoding="utf-8")
    if "require('tailwindcss-animate')" not in mobile_tailwind_source:
        errors.append("Mobile Tailwind config must register tailwindcss-animate for RNR-style primitives")
    if "@lyreo/design-system/foundation" not in mobile_tailwind_source or "foundation.radius" not in mobile_tailwind_source:
        errors.append("Mobile Tailwind radius config must consume the shared design-system foundation source")
if admin_theme_provider.exists():
    admin_theme_source = admin_theme_provider.read_text(encoding="utf-8")
    if "Object.entries(radius)" not in admin_theme_source or "--lyreo-radius-" not in admin_theme_source:
        errors.append("Admin theme adapter must publish shared design-system radius tokens")
if mobile_metro.exists():
    metro_text = mobile_metro.read_text(encoding="utf-8")
    if "inlineRem: 16" not in metro_text:
        errors.append("Mobile NativeWind Metro config must keep inlineRem=16")
    if "resolveRequest" in metro_text:
        errors.append("Mobile Metro config must not carry a custom package resolver hack")
if app_providers.exists() and "<PortalHost" not in app_providers.read_text(encoding="utf-8"):
    errors.append("Mobile AppProviders must mount PortalHost for portal-based native primitives")

# Admin container build must include every workspace package that Admin declares with workspace:*.
# A source checkout can typecheck while a clean Docker build still fails if one shared package is
# omitted from COPY instructions, so keep this packaging boundary explicit.
admin_manifest_path = ROOT / "apps/admin-web/package.json"
admin_dockerfile = ROOT / "apps/admin-web/Dockerfile"
if admin_manifest_path.exists() and admin_dockerfile.exists():
    admin_manifest = json.loads(admin_manifest_path.read_text(encoding="utf-8"))
    docker_text = admin_dockerfile.read_text(encoding="utf-8")
    workspace_dependencies = {
        **admin_manifest.get("dependencies", {}),
        **admin_manifest.get("devDependencies", {}),
    }
    workspace_package_paths: dict[str, str] = {}
    packages_root = ROOT / "packages"
    if packages_root.exists():
        for package_dir in packages_root.iterdir():
            manifest_path = package_dir / "package.json"
            if not manifest_path.exists():
                continue
            package_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            name = package_manifest.get("name")
            if isinstance(name, str):
                workspace_package_paths[name] = package_dir.relative_to(ROOT).as_posix()
    for dependency, version in workspace_dependencies.items():
        if not isinstance(version, str) or not version.startswith("workspace:"):
            continue
        package_path = workspace_package_paths.get(dependency)
        if not package_path:
            errors.append(f"Admin workspace dependency has no local package: {dependency}")
            continue
        if not re.search(rf"^COPY\s+{re.escape(package_path)}(?:\s+|/)", docker_text, re.MULTILINE):
            errors.append(f"Admin Dockerfile does not copy workspace dependency {dependency} ({package_path})")

# Public frontend env templates should have one explicit typed/runtime adapter consumer.
for rel, adapter in (
    ("apps/admin-web/.env.example", "apps/admin-web/src/env.ts"),
    ("apps/mobile/.env.example", "apps/mobile/src/env.ts"),
):
    env_path, adapter_path = ROOT / rel, ROOT / adapter
    if env_path.exists() and adapter_path.exists():
        adapter_text = adapter_path.read_text(encoding="utf-8")
        for line in env_path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key = stripped.split("=", 1)[0]
            if key not in adapter_text:
                errors.append(f"frontend env key has no adapter consumer: {rel}:{key}")

# Env templates are process/tool contracts, not wish lists. Every declared key must have a
# consumer in the owner scope (or be an explicitly documented tool-level implicit variable).
ENV_OWNER_SCOPES = {
    "apps/core-service/.env.example": ("apps/core-service",),
    "services/ai-service/.env.example": ("services/ai-service/app",),
    "infra/docker/.env.example": ("compose.dev.yml", "compose.prod.yml", "compose.gpu.yml", "scripts/init-dev-env.sh"),
    "infra/keycloak/.env.example": ("infra/keycloak/scripts", "scripts/init-dev-env.sh"),
    "tools/data-import/.env.example": ("tools/data-import", "scripts/fetch-data.sh", "scripts/init-dev-env.sh", "scripts/doctor.sh"),
}
IMPLICIT_ENV_KEYS = {
    "infra/docker/.env.example": {"COMPOSE_PROJECT_NAME"},
    # Spring Boot consumes this conventional process variable before application.yml binding.
    "apps/core-service/.env.example": {"SPRING_PROFILES_ACTIVE"},
}

for rel, scopes in ENV_OWNER_SCOPES.items():
    env_path = ROOT / rel
    if not env_path.exists():
        continue
    owner_texts: list[str] = []
    for scope in scopes:
        target = ROOT / scope
        candidates = [target] if target.is_file() else list(target.rglob("*")) if target.exists() else []
        for candidate in candidates:
            if not candidate.is_file() or candidate.name == ".env.example":
                continue
            if any(part in GENERATED_DIR_NAMES for part in candidate.parts):
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
        # Pydantic Settings maps QWEN_ASR_MODEL -> qwen_asr_model, while shell/YAML usually uses
        # uppercase names directly. Accept either representation inside the owning runtime.
        if key not in owner_text and key.lower() not in owner_text.lower():
            errors.append(f"env example key has no owner-scope consumer: {rel}:{key}")

# Cross-owner env duplication is allowed only for explicit process boundaries. A new duplicate key
# is usually a sign that one .env.example has started owning another executable's configuration.
env_key_owners: dict[str, list[str]] = {}
for rel in ENV_OWNER_SCOPES:
    path = ROOT / rel
    if not path.exists():
        continue
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key = stripped.split("=", 1)[0]
        env_key_owners.setdefault(key, []).append(rel)
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
for key, owners in env_key_owners.items():
    if len(owners) > 1 and key not in ALLOWED_CROSS_OWNER_ENV_KEYS:
        errors.append(f"env key duplicated across owners without an explicit boundary allowlist: {key} -> {owners}")

# Repo-owned starter primitives should normally be exercised by at least one feature screen.
for app in ("apps/admin-web", "apps/mobile"):
    ui_dir = ROOT / app / "src/components/ui"
    if not ui_dir.exists():
        continue
    app_tsx = [path for path in (ROOT / app).rglob("*.tsx") if ui_dir not in path.parents]
    combined = "\n".join(path.read_text(encoding="utf-8") for path in app_tsx)
    for component_file in ui_dir.glob("*.tsx"):
        if f"components/ui/{component_file.stem}" not in combined:
            warnings.append(f"repo-owned UI primitive currently has no screen consumer: {component_file.relative_to(ROOT)}")

# A lockfile is useful only if it actually represents the workspace. A root-only lockfile
# creates false reproducibility and is worse than an explicit first-install bootstrap.
lockfile = ROOT / "pnpm-lock.yaml"
if lockfile.exists():
    lock_text = lockfile.read_text(encoding="utf-8")
    required_importers = (
        "apps/admin-web:",
        "apps/mobile:",
        "packages/design-system:",
        "packages/i18n:",
    )
    missing_importers = [item[:-1] for item in required_importers if not re.search(rf"^  {re.escape(item)}", lock_text, re.MULTILINE)]
    if missing_importers:
        errors.append("pnpm-lock.yaml is stale/incomplete; missing workspace importers: " + ", ".join(missing_importers))
else:
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
