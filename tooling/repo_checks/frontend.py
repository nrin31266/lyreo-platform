from __future__ import annotations

import json
import re
from pathlib import Path

from .repository import is_generated_path, repo_files

RAW_COLOR_RE = re.compile(r"#[0-9a-fA-F]{3,8}\b|rgba?\(")


def flatten_translation_keys(value: object, prefix: str = "") -> set[str]:
    """Recursively collect dotted translation keys from a nested dictionary."""
    if not isinstance(value, dict):
        return {prefix}
    keys: set[str] = set()
    for key, nested in value.items():
        next_prefix = f"{prefix}.{key}" if prefix else str(key)
        keys.update(flatten_translation_keys(nested, next_prefix))
    return keys


def check_raw_colors_and_primitives(root: Path, errors: list[str]) -> None:
    """Ensure feature UI does not embed raw color literals and does not directly access primitiveColors."""
    for base in (root / "apps/admin-web/src", root / "apps/mobile/app", root / "apps/mobile/src"):
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or path.suffix not in {".ts", ".tsx", ".css"}:
                continue
            if is_generated_path(path, root):
                continue
            text = path.read_text(encoding="utf-8")
            if RAW_COLOR_RE.search(text):
                errors.append(f"raw frontend color literal outside design system: {path.relative_to(root)}")
            if path.suffix in {".ts", ".tsx"} and "primitiveColors" in text:
                errors.append(f"frontend code must not consume primitiveColors directly: {path.relative_to(root)}")


def check_secure_store_usage(root: Path, errors: list[str]) -> None:
    """Ensure SecureStore is restricted to authentication and session management."""
    mobile_dir = root / "apps/mobile"
    if not mobile_dir.exists():
        return
    for pattern in ("*.ts", "*.tsx"):
        for path in repo_files(mobile_dir, pattern, root):
            if "SecureStore" in path.read_text(encoding="utf-8") and "auth" not in path.relative_to(mobile_dir).parts:
                errors.append(f"SecureStore usage outside auth/session storage: {path.relative_to(root)}")


def check_i18n_parity_and_keys(root: Path, errors: list[str]) -> None:
    """Ensure exact key parity between EN and VI resources, and catch translation key typos."""
    translation_index: dict[str, set[str]] = {}
    for namespace in ("common", "admin", "mobile"):
        locale_keys: dict[str, set[str]] = {}
        for locale in ("en", "vi"):
            path = root / f"packages/i18n/src/locales/{locale}/{namespace}.json"
            if path.exists():
                locale_keys[locale] = flatten_translation_keys(json.loads(path.read_text(encoding="utf-8")))
        if len(locale_keys) == 2 and locale_keys["en"] != locale_keys["vi"]:
            missing_vi = sorted(locale_keys["en"] - locale_keys["vi"])
            missing_en = sorted(locale_keys["vi"] - locale_keys["en"])
            errors.append(
                f"i18n key drift in {namespace}: missing vi={missing_vi or '[]'}, missing en={missing_en or '[]'}"
            )
        if "en" in locale_keys:
            translation_index[namespace] = locale_keys["en"]

    for app, default_namespace in (("apps/admin-web", "admin"), ("apps/mobile", "mobile")):
        base = root / app
        if not base.exists():
            continue
        for path in repo_files(base, "*.tsx", root):
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
                    errors.append(f"missing literal i18n key in {path.relative_to(root)}: {raw_key}")


def check_mobile_dependencies(root: Path, errors: list[str]) -> None:
    """Verify presence of Mobile baseline dependencies without duplicating exact version pins."""
    mobile_manifest = root / "apps/mobile/package.json"
    if not mobile_manifest.exists():
        return
    try:
        manifest = json.loads(mobile_manifest.read_text(encoding="utf-8"))
    except Exception:
        return

    runtime_dependencies = manifest.get("dependencies", {})
    merged_dependencies = {**manifest.get("dependencies", {}), **manifest.get("devDependencies", {})}

    for package in ("nativewind", "tailwindcss", "tailwindcss-animate", "@rn-primitives/portal"):
        if package not in merged_dependencies:
            errors.append(f"Mobile NativeWind/RNR baseline dependency missing: {package}")

    expo_router_direct = (
        "expo-router",
        "expo-constants",
        "expo-linking",
        "expo-status-bar",
        "react-native-safe-area-context",
        "react-native-screens",
    )
    for package in expo_router_direct:
        if package not in runtime_dependencies:
            errors.append(f"Mobile Expo Router direct dependency missing: {package}")

    oauth_sdk_pins = (
        "expo-auth-session",
        "expo-crypto",
    )
    for package in oauth_sdk_pins:
        if package not in runtime_dependencies:
            errors.append(f"Mobile OAuth dependency missing: {package}")


def check_admin_docker_packaging(root: Path, errors: list[str]) -> None:
    """Ensure Admin Dockerfile copies every local workspace dependency."""
    admin_manifest_path = root / "apps/admin-web/package.json"
    admin_dockerfile = root / "apps/admin-web/Dockerfile"
    if not (admin_manifest_path.exists() and admin_dockerfile.exists()):
        return

    try:
        admin_manifest = json.loads(admin_manifest_path.read_text(encoding="utf-8"))
    except Exception:
        return

    docker_text = admin_dockerfile.read_text(encoding="utf-8")
    workspace_dependencies = {
        **admin_manifest.get("dependencies", {}),
        **admin_manifest.get("devDependencies", {}),
    }
    workspace_package_paths: dict[str, str] = {}
    packages_root = root / "packages"
    if packages_root.exists():
        for package_dir in packages_root.iterdir():
            manifest_path = package_dir / "package.json"
            if not manifest_path.exists():
                continue
            try:
                package_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                name = package_manifest.get("name")
                if isinstance(name, str):
                    workspace_package_paths[name] = package_dir.relative_to(root).as_posix()
            except Exception:
                pass

    for dependency, version in workspace_dependencies.items():
        if not isinstance(version, str) or not version.startswith("workspace:"):
            continue
        package_path = workspace_package_paths.get(dependency)
        if not package_path:
            errors.append(f"Admin workspace dependency has no local package: {dependency}")
            continue
        if not re.search(rf"^COPY\s+{re.escape(package_path)}(?:\s+|/)", docker_text, re.MULTILINE):
            errors.append(f"Admin Dockerfile does not copy workspace dependency {dependency} ({package_path})")


def check_ui_primitive_consumers(root: Path, warnings: list[str]) -> None:
    """Warn if repository-owned UI primitives have no screen consumers."""
    for app in ("apps/admin-web", "apps/mobile"):
        ui_dir = root / app / "src/components/ui"
        if not ui_dir.exists():
            continue
        app_tsx = [
            path
            for path in repo_files(root / app, "*.tsx", root)
            if ui_dir not in path.parents
        ]
        combined = "\n".join(path.read_text(encoding="utf-8") for path in app_tsx)
        for component_file in ui_dir.glob("*.tsx"):
            if f"components/ui/{component_file.stem}" not in combined:
                warnings.append(f"repo-owned UI primitive currently has no screen consumer: {component_file.relative_to(root)}")


def check_frontend(root: Path, errors: list[str], warnings: list[str]) -> None:
    """Run all frontend design-system, i18n, Tailwind, and theme adapter checks."""
    check_raw_colors_and_primitives(root, errors)
    check_secure_store_usage(root, errors)
    check_i18n_parity_and_keys(root, errors)
    check_mobile_dependencies(root, errors)
    check_admin_docker_packaging(root, errors)
    check_ui_primitive_consumers(root, warnings)
