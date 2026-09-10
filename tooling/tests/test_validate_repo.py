from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tooling.repo_checks.backend import (
    check_clean_architecture_and_boundaries,
    check_jackson2_databind,
)
from tooling.repo_checks.frontend import (
    check_mobile_dependencies,
    check_theme_adapters_and_wiring,
)
from tooling.repo_checks.repository import check_lockfiles


class ValidateRepoTest(unittest.TestCase):
    def create_fixture(self, files: dict[str, str]) -> Path:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        root = Path(temp_dir.name)
        for rel_path, content in files.items():
            file_path = root / rel_path
            file_path.parent.mkdir(parents=True, exist_ok=True)
            file_path.write_text(content, encoding="utf-8")
        return root

    def test_missing_required_lockfile_fails(self) -> None:
        """Missing required lockfiles must produce explicit validation errors."""
        root = self.create_fixture({
            "pnpm-lock.yaml": "lockfileVersion: '9.0'\nimporters:\n  apps/admin-web:\n  apps/mobile:\n  packages/design-system:\n  packages/i18n:\n",
            # Missing apps/ai-service/uv.lock and tools/data-import/uv.lock
        })
        errors: list[str] = []
        check_lockfiles(root, errors)

        self.assertTrue(any("missing required lockfile: apps/ai-service/uv.lock" in err for err in errors))
        self.assertTrue(any("missing required lockfile: tools/data-import/uv.lock" in err for err in errors))

    def test_legacy_jackson2_production_import_fails(self) -> None:
        """Production Java code importing Jackson 2 databind must be rejected."""
        root = self.create_fixture({
            "modules/lesson/src/main/java/com/lyreo/lesson/Foo.java": (
                "package com.lyreo.lesson;\n"
                "import com.fasterxml.jackson.databind.ObjectMapper;\n"
                "public class Foo {}\n"
            ),
            "modules/lesson/src/test/java/com/lyreo/lesson/FooTest.java": (
                "package com.lyreo.lesson;\n"
                "import com.fasterxml.jackson.databind.ObjectMapper;\n"
                "public class FooTest {}\n"
            ),
        })
        errors: list[str] = []
        check_jackson2_databind(root, errors)

        self.assertEqual(len(errors), 1)
        self.assertIn("production Java code must not import com.fasterxml.jackson.databind", errors[0])
        self.assertIn("Foo.java", errors[0])

    def test_business_module_importing_platform_infrastructure_fails(self) -> None:
        """Business code in modules/**/src/main/java must not import platform infrastructure internals (WS5)."""
        root = self.create_fixture({
            "modules/lesson/src/main/java/com/lyreo/lesson/package-info.java": "package com.lyreo.lesson;\n",
            "modules/lesson/src/main/java/com/lyreo/lesson/infrastructure/LessonRepo.java": (
                "package com.lyreo.lesson.infrastructure;\n"
                "import com.lyreo.platform.config.infrastructure.RuntimeConfigRepository;\n"
                "public class LessonRepo {}\n"
            ),
        })
        errors: list[str] = []
        check_clean_architecture_and_boundaries(root, errors)

        self.assertTrue(
            any("business module must not import platform infrastructure internals" in err for err in errors),
            f"Expected platform infrastructure import failure; got: {errors}",
        )

    def test_valid_repository_fixture_passes(self) -> None:
        """A valid repository fixture with compliant imports, lockfiles, and dependencies must pass without errors."""
        root = self.create_fixture({
            "pnpm-lock.yaml": (
                "lockfileVersion: '9.0'\n"
                "importers:\n"
                "  apps/admin-web:\n"
                "  apps/mobile:\n"
                "  packages/design-system:\n"
                "  packages/i18n:\n"
            ),
            "apps/ai-service/uv.lock": "version = 1\n",
            "tools/data-import/uv.lock": "version = 1\n",
            "modules/lesson/src/main/java/com/lyreo/lesson/package-info.java": "package com.lyreo.lesson;\n",
            "modules/lesson/src/main/java/com/lyreo/lesson/application/LessonService.java": (
                "package com.lyreo.lesson.application;\n"
                "import com.lyreo.platform.config.application.RuntimeConfigService;\n"
                "import com.lyreo.platform.jobs.application.BackgroundJobService;\n"
                "import tools.jackson.databind.ObjectMapper;\n"
                "public class LessonService {}\n"
            ),
            "apps/mobile/package.json": json.dumps({
                "dependencies": {
                    "expo-router": "~57.0.99",
                    "expo-constants": "~57.0.17",
                    "expo-linking": "~57.0.9",
                    "expo-status-bar": "~57.0.1",
                    "react-native-safe-area-context": "~5.7.0",
                    "react-native-screens": "~4.26.0",
                    "expo-auth-session": "~57.0.11",
                    "expo-crypto": "~57.0.2",
                    "nativewind": "^4.1.23",
                    "tailwindcss": "^3.4.17",
                    "tailwindcss-animate": "^1.0.7",
                    "@rn-primitives/portal": "^1.1.0",
                }
            }),
        })

        errors: list[str] = []
        check_lockfiles(root, errors)
        check_jackson2_databind(root, errors)
        check_clean_architecture_and_boundaries(root, errors)
        check_mobile_dependencies(root, errors)

        self.assertEqual([], errors)

    def test_exact_dependency_patch_version_change_passes(self) -> None:
        """Patch or minor version changes in package.json dependencies must NOT fail validation."""
        root = self.create_fixture({
            "apps/mobile/package.json": json.dumps({
                "dependencies": {
                    "expo-router": "~57.0.99",  # Bumped patch version
                    "expo-constants": "~57.0.25",
                    "expo-linking": "~57.0.15",
                    "expo-status-bar": "~57.0.5",
                    "react-native-safe-area-context": "~5.8.0",
                    "react-native-screens": "~4.27.0",
                    "expo-auth-session": "~57.0.99",
                    "expo-crypto": "~57.0.99",
                    "nativewind": "^4.2.0",
                    "tailwindcss": "^3.4.18",
                    "tailwindcss-animate": "^1.0.8",
                    "@rn-primitives/portal": "^1.2.0",
                }
            })
        })
        errors: list[str] = []
        check_mobile_dependencies(root, errors)
        self.assertEqual([], errors)

        # Confirm that missing a required package DOES fail
        root_missing = self.create_fixture({
            "apps/mobile/package.json": json.dumps({
                "dependencies": {
                    "expo-constants": "~57.0.17",
                }
            })
        })
        missing_errors: list[str] = []
        check_mobile_dependencies(root_missing, missing_errors)
        self.assertTrue(any("Mobile Expo Router direct dependency missing: expo-router" in err for err in missing_errors))

    def test_implementation_equivalent_frontend_refactor_passes(self) -> None:
        """Behavior-equivalent frontend code must not fail due to removed brittle string guards."""
        root = self.create_fixture({
            "apps/admin-web/src/providers/AppThemeProvider.tsx": (
                "import { semanticThemes } from '@lyreo/design-system';\n"
                "// Published radius tokens without using exact Object.entries(radius) syntax\n"
                "document.documentElement.style.setProperty('--lyreo-radius-md', '8px');\n"
                "if (window.matchMedia('(prefers-color-scheme: dark)').matches) {}\n"
                "window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {});\n"
                "document.documentElement.classList.toggle('dark', isDark);\n"
                "const colors = semanticThemes[mode];\n"
            ),
            "apps/mobile/src/providers/AppThemeProvider.tsx": (
                "import { semanticThemes } from '@lyreo/design-system';\n"
                "import { useColorScheme, View } from 'react-native';\n"
                "import { vars } from 'nativewind';\n"
                "export function AppThemeProvider() {\n  const scheme = useColorScheme();\n"
                "  const colors = semanticThemes[mode];\n"
                "  const v = vars({ '--bg': colors.background });\n"
                "  // Refactored className attribute formatting\n"
                "  return <View className='bg-background p-4 flex-1' style={v} />;\n"
                "}\n"
            ),
        })
        errors: list[str] = []
        check_theme_adapters_and_wiring(root, (), errors)
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
