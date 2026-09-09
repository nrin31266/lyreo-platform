from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tooling.validate_docs import validate_markdown_tree


class ValidateDocsTest(unittest.TestCase):
    def fixture(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            return validate_markdown_tree(root)

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


if __name__ == "__main__":
    unittest.main()
