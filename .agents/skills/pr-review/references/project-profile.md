# Project Review Profile

> This is the ONLY file to edit when copying this skill to another project.
> Core skill files (SKILL.md, subagents, scripts, other references) are project-agnostic.
> Read this file once in `pr-context-collector`, before proposing dimensions.

## Default Language

Natural Vietnamese. Use if `LANGUAGE_STYLE` is not explicitly provided.

## Context Entry Points

Read these files as routing indexes to find owner documents:

1. `AGENTS.md` — engineering contract and mandatory prohibitions (project root)
2. `docs/README.md` — routing index to domain owner documents

If an entry point does not exist in the current project, skip gracefully.

## Review Posture

- Production-minded project: prioritize correctness, regressions, contracts, security when relevant, and maintainability.
- Avoid unnecessary enterprise over-engineering in findings.
- Useful verification: discover commands from CI config and build metadata — do not hardcode.
