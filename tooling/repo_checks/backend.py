from __future__ import annotations

import os
import re
from pathlib import Path

from .repository import repo_files

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
JACKSON2_DATABIND_RE = re.compile(r"^import\s+com\.fasterxml\.jackson\.databind\.", re.MULTILINE)
PLATFORM_INFRASTRUCTURE_RE = re.compile(r"^com\.lyreo\.platform\.[a-z0-9_]+\.infrastructure\b")


def check_forbidden_pom_dependencies(root: Path, errors: list[str]) -> None:
    """Ensure no forbidden Kafka or Redis dependencies are declared in POMs."""
    forbidden = {
        "spring-kafka": "Kafka",
        "kafka-clients": "Kafka",
        "<artifactid>jedis": "Redis/Jedis",
        "<artifactid>lettuce": "Redis/Lettuce",
        "spring-boot-starter-data-redis": "Spring Data Redis",
    }
    for path in repo_files(root, "pom.xml", root):
        text = path.read_text(encoding="utf-8").lower()
        for token, label in forbidden.items():
            if token in text:
                errors.append(f"forbidden {label} dependency in {path.relative_to(root)}")


def check_fastapi_boundaries(root: Path, errors: list[str]) -> None:
    """Ensure FastAPI remains an AI capability runtime and does not duplicate business domain or SQL persistence."""
    ai_service_dir = root / "apps/ai-service"
    if ai_service_dir.exists():
        for path in repo_files(ai_service_dir, "*.py", root):
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
                        f"FastAPI contains business persistence token `{forbidden}` in {path.relative_to(root)}"
                    )

    ai_pyproject = ai_service_dir / "pyproject.toml"
    if ai_pyproject.exists():
        ai_manifest = ai_pyproject.read_text(encoding="utf-8").lower()
        for token in ("sqlalchemy", "psycopg", "asyncpg", "alembic"):
            if token in ai_manifest:
                errors.append(f"FastAPI must not own business persistence dependency: {token}")


def check_clean_architecture_and_boundaries(root: Path, errors: list[str]) -> None:
    """Verify Clean/Hexagonal boundaries inside and between business modules."""
    for module_name, package_name in MODULE_PACKAGE.items():
        module_root = root / "modules" / module_name
        if not module_root.exists():
            continue

        package_info = (
            module_root / "src/main/java/com/lyreo" / package_name / "package-info.java"
        )
        if not package_info.exists():
            errors.append(f"business module missing root package-info.java: modules/{module_name}")

        for path in repo_files(module_root, "*.java", root):
            text = path.read_text(encoding="utf-8")
            relative = path.relative_to(root)
            relative_parts = set(path.relative_to(module_root).parts)
            is_domain_or_application_or_api = bool(relative_parts & {"domain", "application", "api"})
            is_main_java = "/src/main/java/" in path.as_posix()

            if is_domain_or_application_or_api:
                if f"com.lyreo.{package_name}.infrastructure." in text:
                    errors.append(f"clean architecture violation (inner -> infrastructure): {relative}")
                if "org.springframework.data." in text or "jakarta.persistence." in text:
                    errors.append(f"persistence framework leaked into inner layer: {relative}")

            for imported in IMPORT_RE.findall(text):
                # Guard business code from platform infrastructure internals (WS5).
                if is_main_java and PLATFORM_INFRASTRUCTURE_RE.match(imported):
                    errors.append(
                        f"business module must not import platform infrastructure internals: {relative}: {imported}"
                    )

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


def check_platform_dependencies(root: Path, errors: list[str]) -> None:
    """Ensure platform building blocks do not depend on business modules."""
    platform_dir = root / "platform"
    if not platform_dir.exists():
        return
    for path in repo_files(platform_dir, "*.java", root):
        text = path.read_text(encoding="utf-8")
        for package_name in PACKAGE_TO_MODULE:
            if f"import com.lyreo.{package_name}." in text:
                errors.append(f"platform depends on business module: {path.relative_to(root)}")


def check_transactional_targets(root: Path, errors: list[str]) -> None:
    """Ensure classes with @Transactional are non-final to prevent subclass proxy failures."""
    for path in repo_files(root, "*.java", root):
        text = path.read_text(encoding="utf-8")
        if "@Transactional" in text and re.search(r"public\s+final\s+class\s+", text):
            errors.append(f"transactional target must not be final: {path.relative_to(root)}")


def check_flyway_and_modulith_migrations(root: Path, errors: list[str]) -> None:
    """Verify Flyway migrations sorting, uniqueness, and Spring Modulith table schema."""
    modulith_migration = (
        root / "apps/core-service/src/main/resources/db/migration/V001__platform_identity_learner.sql"
    )
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

    migration_dir = root / "apps/core-service/src/main/resources/db/migration"
    if migration_dir.exists():
        migrations = sorted(migration_dir.glob("V*__*.sql"))
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


def check_security_and_database_policies(root: Path, errors: list[str]) -> None:
    """Verify Hibernate schema validation, compose service constraints, and canonical symlinks."""
    for path in repo_files(root, "*.java", root):
        text = path.read_text(encoding="utf-8").lower()
        if any(token in text for token in (
            "ddl-auto=update", "ddl-auto: update",
            "ddl-auto=create", "ddl-auto: create",
            "ddl-auto=create-drop", "ddl-auto: create-drop",
        )):
            errors.append(f"Hibernate schema mutation setting found in {path.relative_to(root)}")

    app_yml = root / "apps/core-service/src/main/resources/application.yml"
    if app_yml.exists():
        app_text = app_yml.read_text(encoding="utf-8")
        if "ddl-auto: validate" not in app_text:
            errors.append("Core application.yml must keep Hibernate ddl-auto=validate")

    for rel in ("compose.dev.yml", "compose.prod.yml", "compose.gpu.yml"):
        path = root / rel
        if path.exists():
            lowered = path.read_text(encoding="utf-8").lower()
            if re.search(r"(^|\n)\s*(redis|kafka|zookeeper):", lowered):
                errors.append(f"forbidden broker/cache service in {rel}")

    for rel in ("CLAUDE.md", "GEMINI.md", "AGENT.md"):
        path = root / rel
        if path.exists() and (not path.is_symlink() or os.readlink(path) != "AGENTS.md"):
            errors.append(f"{rel} must symlink to AGENTS.md")


def check_jackson2_databind(root: Path, errors: list[str]) -> None:
    """Ensure production Java source uses Spring Boot 4 Jackson 3 instead of legacy Jackson 2."""
    for path in repo_files(root, "*.java", root):
        if "/src/main/java/" in path.as_posix():
            text = path.read_text(encoding="utf-8")
            if JACKSON2_DATABIND_RE.search(text):
                errors.append(f"production Java code must not import com.fasterxml.jackson.databind: {path.relative_to(root)}")


def check_backend(root: Path, errors: list[str], warnings: list[str]) -> None:
    """Run all Java, Spring Boot, architecture, and FastAPI backend boundary checks."""
    check_forbidden_pom_dependencies(root, errors)
    check_fastapi_boundaries(root, errors)
    check_clean_architecture_and_boundaries(root, errors)
    check_platform_dependencies(root, errors)
    check_transactional_targets(root, errors)
    check_flyway_and_modulith_migrations(root, errors)
    check_security_and_database_policies(root, errors)
    check_jackson2_databind(root, errors)
