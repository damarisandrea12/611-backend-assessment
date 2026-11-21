# Submission

## Candidate

| Field | Value |
|---|---|
| Candidate ID (provided by your recruiter) | damarisaranda3@gmail.com |
| Final commit SHA | `b7586e6d924a2795a43dcf6640fc75e7a07bf4ec` (last content commit; the next commit only completes this file) |
| Date and time of submission (with time zone) |  2026-11-20 20:55, UTC-05:00 |

## Time spent

| Part | Approximate time |
|---|---|
| A. Integration and data design | 5 h |
| B. Integration component | 7.5 h |
| C. Azure IaC and CI/CD | 2.5 h |
| D. Operations | 1 h |
| E. Code review | 0.5 h |
| F. Responsible AI | 1 h |
| **Total** | **17.5 h** |

This includes reading the scenario and references, learning the Azure services and Bicep I had not used before, and reviewing and validating every file until I could explain it.

## Stack

- Practical part (Part B): **Java 21 + Spring Boot 3.3.5**, Maven 3.9, JUnit 5 (AssertJ, Spring Boot Test, networknt json-schema-validator), JaCoCo. Justified in [ADR-0002](docs/adr/0002-implementation-stack-and-data-store.md).
- Infrastructure as code: **Bicep**, validated locally with Bicep CLI 0.49.2 (`build`, `lint`, `build-params`), not deployed. See [`infra/README.md`](infra/README.md#validation).
- Pipeline: **GitHub Actions**: [`ci.yml`](.github/workflows/ci.yml), [`cd.yml`](.github/workflows/cd.yml) and [`deploy-environment.yml`](.github/workflows/deploy-environment.yml). CD is defined, not executed.
- CI run link (optional): not included.

## Where to start reading

1. [`docs/design/SOLUTION_DESIGN.md`](docs/design/SOLUTION_DESIGN.md). The core idea: **Salesforce is fed from a desired-state store**, so duplicates, out-of-order events, Salesforce outages and the shared API budget are handled in one place. Includes the capacity numbers (about 4% of the API budget at peak).
2. [`practical/README.md`](practical/README.md) and the tests: reference signature vectors, retries with new ids, broker failure returns `503`, no PII in logs.
3. [`docs/operations/INC-0412-RESPONSE.md`](docs/operations/INC-0412-RESPONSE.md) and [`pr-review/REVIEW.md`](pr-review/REVIEW.md): the same principles applied to real failures.

## Known gaps and what I would do next

In order of priority:

1. **Build the Payment State worker and Salesforce Sync worker** (FR3, FR5, FR7): precedence update, batched composite upsert with the budget guard, and the Ops API. They are designed only; Part B stops at the queue, as TASKS.md asks.
2. **Back the idempotency store with Azure SQL.** It is in-memory per instance today, so dedup across replicas is best effort until then.
3. **Flyway migrations** for the SQL schema, run by CD before the grants.
4. **Austral (Chile) adapter.** Designed; with the shared flow it is one more adapter class.
5. **Load test** the composite upsert with the Salesforce flow active, and agree the freshness SLO accounting for Salesforce outages with the Salesforce team (A-10).
6. **Runbooks** for each alert, `what-if` against a sandbox subscription, OpenAPI contract tests, and pinning GitHub Actions to commit SHAs.