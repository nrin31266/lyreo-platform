# Lyreo documentation routes

Task/domain routing table. Read [`AGENTS.md`](../AGENTS.md) first, find the matching intent below,
then open only the linked canonical context. Do not scan all documentation for a focused change.

## Route by intent

| Intent | Canonical context |
|---|---|
| Product scope, priorities, personas | [product/prd.md](product/prd.md) |
| Product discovery, evidence, assumptions | [product/discovery.md](product/discovery.md) |
| Identity, auth, OIDC, onboarding, learner profile | [requirements/identity-learner.md](requirements/identity-learner.md) |
| Lesson content, build, dictation, shadowing | [requirements/lesson.md](requirements/lesson.md), [features/lesson-build.md](features/lesson-build.md) |
| AI provider, routing, credentials, invocation | [requirements/ai.md](requirements/ai.md), [architecture/ai-execution.md](architecture/ai-execution.md) |
| Lexicon search, vocabulary SRS, review | [requirements/lexicon-vocabulary.md](requirements/lexicon-vocabulary.md) |
| Grammar/TOEIC practice, scoring, import | [requirements/grammar-toeic.md](requirements/grammar-toeic.md) |
| Curriculum paths, gamification, XP/Diamond/missions | [requirements/curriculum-gamification.md](requirements/curriculum-gamification.md) |
| Analytics, notification, chat | [requirements/analytics-notification-chat.md](requirements/analytics-notification-chat.md) |
| Non-functional: security, jobs, resilience | [requirements/non-functional.md](requirements/non-functional.md) |
| Unresolved decisions, open questions | [requirements/open-questions.md](requirements/open-questions.md) |
| System/module/client architecture | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Background jobs protocol | [architecture/background-jobs.md](architecture/background-jobs.md) |
| HTTP API conventions, errors, pagination | [architecture/http-api-contract.md](architecture/http-api-contract.md) |
| AI execution boundary (Core vs FastAPI) | [architecture/ai-execution.md](architecture/ai-execution.md) |
| Frontend foundation: tokens, translations, platform UI | [Design System](../packages/design-system/README.md), [i18n](../packages/i18n/README.md), [Admin Web](../apps/admin-web/README.md), [Mobile](../apps/mobile/README.md) |
| Data import implementation and operator setup | [DATA_PIPELINES.md](DATA_PIPELINES.md), [Data Import](../tools/data-import/README.md) |
| Local infrastructure and Keycloak setup | [Docker](../infra/docker/README.md), [Keycloak](../infra/keycloak/README.md), [CONFIGURATION.md](CONFIGURATION.md) for configuration; [operational questions](requirements/open-questions.md) when relevant |
| Architecture/product decisions | [DECISIONS.md](DECISIONS.md) |
| Development workflow, DB, local infra | [DEVELOPMENT.md](DEVELOPMENT.md) |
| Testing strategy, test types, CI mapping | [TESTING.md](TESTING.md) |
| Configuration model, env ownership | [CONFIGURATION.md](CONFIGURATION.md) |
| Data pipelines, dataset import | [DATA_PIPELINES.md](DATA_PIPELINES.md) |
| Documentation governance, drift rules | [DOCUMENTATION.md](DOCUMENTATION.md) |
| Coursework / academic evidence | [coursework/](coursework/) |

## Document type entrypoints

- **Product:** [product/prd.md](product/prd.md), [product/discovery.md](product/discovery.md)
- **Requirements:** [requirements/](requirements/) (one file per domain area, plus `open-questions.md`)
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md), [DECISIONS.md](DECISIONS.md), [architecture/](architecture/) (protocols and contracts)
- **Reference:** [DEVELOPMENT.md](DEVELOPMENT.md), [TESTING.md](TESTING.md), [CONFIGURATION.md](CONFIGURATION.md), [DATA_PIPELINES.md](DATA_PIPELINES.md), [DOCUMENTATION.md](DOCUMENTATION.md)

## Missing route?

If a route is missing, find the owner from code and existing headings, add the route here, then
continue. Do not create a second routing manifest.
