#!/usr/bin/env python3
"""Offline repository guardrails for Lyreo.

This script is intentionally dependency-light and runs even when Maven Central,
Docker or pnpm are unavailable. It does not replace compilation/tests; it catches
repository drift and architecture violations early enough for humans/agents.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tooling.repo_checks.backend import check_backend
from tooling.repo_checks.frontend import check_frontend
from tooling.repo_checks.repository import check_repository, repo_files


def run_all_checks(root: Path) -> tuple[list[str], list[str]]:
    """Execute repository, backend, and frontend validation checks."""
    errors: list[str] = []
    warnings: list[str] = []
    check_repository(root, errors, warnings)
    check_backend(root, errors, warnings)
    check_frontend(root, errors, warnings)
    return errors, warnings


def main() -> None:
    errors, warnings = run_all_checks(ROOT)

    if errors:
        print("VALIDATION FAILED")
        print("\n".join(f"- {item}" for item in errors))
        if warnings:
            print("WARNINGS")
            print("\n".join(f"- {item}" for item in warnings))
        sys.exit(1)

    java = sum(1 for _ in repo_files(ROOT, "*.java", ROOT))
    py = sum(1 for _ in repo_files(ROOT, "*.py", ROOT))
    ts = (
        sum(1 for _ in repo_files(ROOT, "*.ts", ROOT))
        + sum(1 for _ in repo_files(ROOT, "*.tsx", ROOT))
    )
    sql = sum(1 for _ in repo_files(ROOT, "*.sql", ROOT))
    print(f"VALIDATION OK | Java={java} Python={py} TS/TSX={ts} SQL={sql}")
    for warning in warnings:
        print(f"WARNING: {warning}")


if __name__ == "__main__":
    main()
