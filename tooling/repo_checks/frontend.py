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
            if "SecureStore" in path.read_text(encoding="utf-8") and path.relative_to(root).as_posix() != "apps/mobile/src/session.ts":
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


def check_semantic_theme_contract(root: Path, errors: list[str]) -> tuple[str, ...]:
    """Verify ThemeColors role parity and valid primitive mappings in design-system."""
    semantic_path = root / "packages/design-system/src/semantic.ts"
    if not semantic_path.exists():
        return ()

    semantic_source = semantic_path.read_text(encoding="utf-8")
    if RAW_COLOR_RE.search(semantic_source):
        errors.append("semantic theme must map to design-system primitives instead of embedding raw color literals")

    semantic_roles: tuple[str, ...] = ()
    theme_type = re.search(r"export type ThemeColors\s*=\s*\{(.*?)\};", semantic_source, re.DOTALL)
    if theme_type:
        semantic_roles = tuple(re.findall(r"^\s*([A-Za-z][A-Za-z0-9]*)\s*:", theme_type.group(1), re.MULTILINE))
    if not semantic_roles:
        errors.append("could not derive semantic ThemeColors roles for frontend adapter validation")
        return ()

    semantic_maps = re.search(
        r"export const semanticThemes[^=]*=\s*\{\s*light:\s*\{(?P<light>.*?)\}\s*,\s*dark:\s*\{(?P<dark>.*?)\}\s*,?\s*\};",
        semantic_source,
        re.DOTALL,
    )
    if not semantic_maps:
        errors.append("could not parse semanticThemes.light/dark for parity validation")
        return semantic_roles

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

    primitive_path = root / "packages/design-system/src/primitives.ts"
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

    return semantic_roles


def check_theme_adapters_and_wiring(root: Path, semantic_roles: tuple[str, ...], errors: list[str]) -> None:
    """Verify Tailwind adapters and ThemeProviders connect properly to semantic theme tokens."""
    admin_styles = root / "apps/admin-web/src/ui/styles.css"
    admin_theme_provider = root / "apps/admin-web/src/providers/AppThemeProvider.tsx"
    mobile_provider = root / "apps/mobile/src/providers/AppThemeProvider.tsx"
    mobile_tailwind = root / "apps/mobile/tailwind.config.js"

    if admin_styles.exists() and mobile_provider.exists() and mobile_tailwind.exists():
        admin_text = admin_styles.read_text(encoding="utf-8")
        mobile_provider_text = mobile_provider.read_text(encoding="utf-8")
        mobile_tailwind_text = mobile_tailwind.read_text(encoding="utf-8")
        tailwind_alias = {"focusRing": "ring"}
        for role in semantic_roles:
            css_name = re.sub(r"([A-Z])", lambda match: "-" + match.group(1).lower(), role)
            class_name = tailwind_alias.get(role, css_name)

            if not re.search(
                rf"--color-{re.escape(class_name)}\s*:\s*var\(--{re.escape(css_name)}\)",
                admin_text,
            ):
                errors.append(f"Admin Tailwind adapter miswired/missing semantic role: {role}")

            if not re.search(
                rf"""['"]--{re.escape(css_name)}['"]\s*:\s*colors\.{re.escape(role)}\b""",
                mobile_provider_text,
            ):
                errors.append(f"Mobile theme provider miswired/missing semantic role: {role}")

            if not re.search(
                rf"""['"]?{re.escape(class_name)}['"]?\s*:\s*['"]var\(--{re.escape(css_name)}\)""",
                mobile_tailwind_text,
            ):
                errors.append(f"Mobile Tailwind adapter miswired/missing semantic role: {role}")

    if admin_theme_provider.exists():
        admin_theme_text = admin_theme_provider.read_text(encoding="utf-8")
        for marker, message in (
            ("(prefers-color-scheme: dark)", "Admin theme provider must observe prefers-color-scheme"),
            ("addEventListener('change'", "Admin system theme must react to OS color-scheme changes"),
            ("classList.toggle('dark'", "Admin dark mode must toggle the .dark class on documentElement"),
            ("semanticThemes[mode]", "Admin theme provider must source values from semanticThemes"),
            ("--lyreo-radius-", "Admin theme adapter must publish shared design-system radius tokens"),
        ):
            if marker not in admin_theme_text:
                errors.append(message)

    if admin_styles.exists() and "@custom-variant dark" not in admin_styles.read_text(encoding="utf-8"):
        errors.append("Admin Tailwind CSS must keep a .dark class custom variant")

    if mobile_provider.exists():
        mobile_theme_text = mobile_provider.read_text(encoding="utf-8")
        for marker, message in (
            ("useColorScheme()", "Mobile theme provider must observe device color scheme"),
            ("semanticThemes[mode]", "Mobile theme provider must source values from semanticThemes"),
            ("vars({", "Mobile theme provider must expose semantic variables through NativeWind vars()"),
            ("bg-background", "Mobile theme root must consume semantic background tokens"),
        ):
            if marker not in mobile_theme_text:
                errors.append(message)

    mobile_app_config = root / "apps/mobile/app.json"
    if mobile_app_config.exists():
        try:
            mobile_app = json.loads(mobile_app_config.read_text(encoding="utf-8"))
            if mobile_app.get("expo", {}).get("userInterfaceStyle") != "automatic":
                errors.append("Mobile app.json must keep expo.userInterfaceStyle=automatic for system dark/light changes")
        except Exception:
            pass

    if mobile_tailwind.exists():
        mobile_tailwind_source = mobile_tailwind.read_text(encoding="utf-8")
        if "tailwindcss-animate" not in mobile_tailwind_source:
            errors.append("Mobile Tailwind config must register tailwindcss-animate for RNR-style primitives")
        if "@lyreo/design-system/foundation" not in mobile_tailwind_source:
            errors.append("Mobile Tailwind radius config must consume the shared design-system foundation source")

    mobile_metro = root / "apps/mobile/metro.config.js"
    if mobile_metro.exists():
        metro_text = mobile_metro.read_text(encoding="utf-8")
        if "inlineRem: 16" not in metro_text:
            errors.append("Mobile NativeWind Metro config must keep inlineRem=16")
        if "resolveRequest" in metro_text:
            errors.append("Mobile Metro config must not carry a custom package resolver hack")

    app_providers = root / "apps/mobile/src/providers/AppProviders.tsx"
    if app_providers.exists() and "PortalHost" not in app_providers.read_text(encoding="utf-8"):
        errors.append("Mobile AppProviders must mount PortalHost for portal-based native primitives")


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
    semantic_roles = check_semantic_theme_contract(root, errors)
    check_theme_adapters_and_wiring(root, semantic_roles, errors)
    check_mobile_dependencies(root, errors)
    check_admin_docker_packaging(root, errors)
    check_ui_primitive_consumers(root, warnings)
