from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from tooling.validate_docs import validate_markdown_tree


class ValidateDocsTest(unittest.TestCase):
    def fixture(self, files: dict[str, str], *, project_guards: bool = False,
                symlinks: dict[str, str] | None = None) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            for link_name, target in (symlinks or {}).items():
                link_path = root / link_name
                os.symlink(target, link_path)
            return validate_markdown_tree(root, project_guards=project_guards)

    # ── original tests (preserved) ────────────────────────────────────────────

    def test_valid_links_reference_and_duplicate_heading_suffix(self) -> None:
        errors = self.fixture({
            "README.md": "# Home\n[owner](docs/owner.md#same-1)\n[ref][owner]\n[owner]: docs/owner.md#fr-tst-001--rule\n",
            "docs/owner.md": "# Owner\n## Same\n## Same\n### FR-TST-001 — Rule\n",
        })
        self.assertEqual([], errors)

    def test_missing_file_fails(self) -> None:
        errors = self.fixture({"README.md": "[missing](docs/missing.md)\n"})
        self.assertTrue(any("missing internal link target" in error for error in errors))

    def test_missing_fragment_fails(self) -> None:
        errors = self.fixture({"README.md": "[bad](docs/owner.md#missing)\n", "docs/owner.md": "# Owner\n"})
        self.assertTrue(any("missing anchor" in error for error in errors))

    def test_duplicate_id_definition_fails(self) -> None:
        errors = self.fixture({"one.md": "### FR-TST-001 — One\n", "two.md": "### FR-TST-001 — Two\n"})
        self.assertTrue(any("duplicate ID definition" in error for error in errors))

    def test_reference_is_not_a_definition(self) -> None:
        errors = self.fixture({"README.md": "Reference FR-TST-404 only.\n"})
        self.assertTrue(any("reference to undefined ID FR-TST-404" in error for error in errors))

    def test_external_and_fenced_content_are_excluded(self) -> None:
        errors = self.fixture({"README.md": "[web](https://example.com/x#y)\n```\n[bad](missing.md)\nFR-TST-999\n```\n"})
        self.assertEqual([], errors)

    # ── new tests: GitHub-style anchor algorithm ───────────────────────────────

    def test_vietnamese_heading_ascii_slug_fails(self) -> None:
        """A link using an old ASCII-stripped slug must fail under the GitHub-style algorithm.

        Heading 'Kiểm tra' produces GitHub slug 'kiểm-tra', NOT 'kiem-tra'.
        This was the root cause of 25 false-PASS links before the slugify() fix.
        """
        errors = self.fixture({
            "README.md": "[bad](target.md#kiem-tra)\n",   # ASCII slug — wrong
            "target.md": "## Kiểm tra\n",
        })
        self.assertTrue(
            any("missing anchor" in error for error in errors),
            f"Expected 'missing anchor' error for ASCII slug of Vietnamese heading; got: {errors}",
        )

    def test_vietnamese_heading_unicode_slug_passes(self) -> None:
        """A link using the correct GitHub-style Unicode slug must pass."""
        errors = self.fixture({
            "README.md": "[ok](target.md#kiểm-tra)\n",   # Unicode slug — correct
            "target.md": "## Kiểm tra\n",
        })
        self.assertEqual([], errors, f"Unexpected errors: {errors}")

    def test_explicit_custom_anchor_passes(self) -> None:
        """Explicit <a id> anchors resolve regardless of heading text."""
        errors = self.fixture({
            "README.md": "[ok](target.md#kiem-tra)\n[ok2](target.md#my-anchor)\n",
            "target.md": '<a id="kiem-tra"></a>\n## Kiểm tra\n\n<a id="my-anchor"></a>\n## Another section\n',
        })
        self.assertEqual([], errors, f"Explicit anchors must always resolve; got: {errors}")

    def test_explicit_anchor_bridges_ascii_link_and_vietnamese_heading(self) -> None:
        """Explicit ASCII anchor is the correct bridge between an inbound ASCII link and a Vietnamese heading."""
        errors = self.fixture({
            "a.md": "[link](b.md#scope-va-uu-tien)\n",
            "b.md": '<a id="scope-va-uu-tien"></a>\n## Scope và ưu tiên\n',
        })
        self.assertEqual([], errors, f"Explicit anchor should resolve ASCII fragment link; got: {errors}")

    # ── new tests: project-specific guards ────────────────────────────────────

    def test_concrete_evidence_path_missing_fails(self) -> None:
        """A code path cited in traceability.md must actually exist in the repository."""
        errors = self.fixture({
            "docs/requirements/traceability.md": "Status: `modules/nonexistent/Foo.java`\n",
            "docs/README.md": "",
        }, project_guards=True)
        self.assertTrue(
            any("does not exist" in error for error in errors),
            f"Expected missing evidence-path error; got: {errors}",
        )

    def test_legacy_master_used_as_owner_fails(self) -> None:
        """Referencing LYREO_PLATFORM_SPEC.md as owner outside the allowed set must fail."""
        errors = self.fixture({
            "docs/requirements/lesson.md": "See [spec](../LYREO_PLATFORM_SPEC.md) for the rule.\n",
            "docs/LYREO_PLATFORM_SPEC.md": "# Compatibility\n",
        }, project_guards=True)
        self.assertTrue(
            any("legacy master referenced as a current owner" in error for error in errors),
            f"Expected legacy master owner error; got: {errors}",
        )

    def test_alias_symlink_wrong_target_fails(self) -> None:
        """AGENT.md, CLAUDE.md, GEMINI.md must symlink to AGENTS.md; a plain file must fail."""
        errors = self.fixture({
            "AGENTS.md": "# Contract\n",
            "AGENT.md": "# Wrong direct content\n",   # regular file, not a symlink
        }, project_guards=True)
        self.assertTrue(
            any("must symlink to AGENTS.md" in error for error in errors),
            f"Expected symlink guard error; got: {errors}",
        )


if __name__ == "__main__":
    unittest.main()
