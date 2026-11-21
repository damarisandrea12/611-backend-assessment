# AI usage disclosure

> **Mandatory.** AI assistants are allowed. What we value is how you governed their use.
> If you did not use any AI tool, tick the box below and leave the rest empty.

- [ ] I did not use any AI tool in this assessment.

## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| **Claude Code** v2.1.325 (model Claude Opus 5.5, Claude Pro plan), used inside **Cursor** (free plan) as the editor | All parts | Implementing quickly what I designed and asked for: Java methods and tests, the OpenAPI contract, Markdown documents organized from my ideas, and the Bicep and GitHub Actions workflows. Also ran the Maven build. |
| **Claude** (claude.ai chat, Claude Pro plan, Claude Opus 5.5) | All parts, during my review | A second reviewer and tutor: explaining technologies that were new to me (Bicep, Container Apps, OIDC), helping me shorten texts, and pointing out claims that I then checked myself against the code and `reference/`. |

## How I worked

I orchestrated the development: I decided what to build and why, directed the implementation, and validated the result. AI wrote quickly what I asked for. With limited time, this let me spend my effort on decisions and verification. Nothing went into the repository without my review, and I did not accept a line until I could explain it and check it against the code, the tests or the reference files.

**Technical ownership (decisions I made):**
- **Messaging:** NFR3 requires that nothing accepted is lost. I decided to meet it with a durable queue before answering `2xx`. I first thought of AWS SQS, which I know, and adapted the solution to Azure Service Bus because Meridian's cloud is Azure. I defined how the components connect and how events flow (one queue for intake, a topic for Finance and Notifications).
- **Stack:** between the options allowed (Mule 4 as reference, or Java 21), I chose Java 21 and Spring Boot.
- **Structure of the code:** layers with clear responsibilities, provider adapters behind one interface (so Chile is one more adapter, for NFR9), and Spring beans for configuration.
- **Money:** always `BigDecimal`; extra decimals are rejected, never rounded.
- **Rules I gave the agent:** my own rule to follow only the repository files and never invent data, plus the assessment's rules: record every assumption, English only, stub every external system, no deployment, no real secrets.

**Technical direction (how I guided the implementation):**
- I designed each method (what it does, its rules and why) and asked the agent to write it, then corrected it.
- Readable code for people: English names, small named variables and helpers, comments only where the "why" is not obvious.
- Cost awareness in hot paths: I questioned per-call object creation in the HMAC code.
- CI on every pull request (as TASKS asks) without a push trigger, and a CD that cannot deploy anything (`CD_ENABLED` guard), to respect "do not deploy".
- One commit per part, so the history shows how the work was built and reviewed.

**Technical validation (how I checked the result):**
- **Part B:** I ran `mvn -B clean verify` myself (JDK 21.0.13, Maven 3.9.9) after every refactor and at the end: **128 tests, 0 failures**, JaCoCo coverage gate met.
- **Bicep:** I installed Azure CLI and validated it locally with Bicep CLI 0.49.2 (`build`, `lint`, `build-params` for dev, test and prod). The first build gave **6 warnings that I fixed** (BCP318, BCP037). Now 0 errors, 0 warnings.
- **Every document against its source:** operations and pipeline docs against `alerts.bicep` and the workflows; the INC-0412 response against the incident and Andina's `CHANGELOG.md`; all 19 PR-214 findings against the diff, the Austral spec and the Salesforce reference.
- **Arithmetic:** INC-0412 numbers (4,312 x 10 = 43,120; about 29% of the 150,000 budget; 32,880 / 37,215 = 88%) and the capacity numbers in the design.
- **No deployment:** the CD `guard` job ran in GitHub with every deploy job skipped.
- Reported by the agent and not repeated by me: an `openssl` check of the first Quetzal vector, YAML and JSON parsing, and a local gitleaks scan.

## What I knew and what I learned

| Technology or concept | My part |
|---|---|
| Message queues, retries, idempotency | **Used professionally** (including in an AI feature I implemented). |
| Git, pull requests, deployments to test environments | **Used professionally.** |
| Layered backend design, webhooks | **Used in my work.** In this project I decided the layers, the provider adapters behind one interface, and the webhook flow. |
| HMAC signatures | Required by the provider specs. I understood the mechanism and applied it on the raw body. |
| Java 21 / Spring Boot (beans, sealed interfaces, Maven) | I directed the implementation and reviewed every class. Some Java and Spring specifics I reinforced during this work. |
| JUnit tests | I reviewed, fixed and ran them. |
| OpenAPI | AI wrote most of the contract. I reviewed it in Swagger Editor against the provider specs, learned the structure step by step, and fixed errors (field types, descriptions and YAML syntax). |
| **Azure Service Bus** | **New to me as a product.** I started from AWS SQS, which I know, and chose Service Bus to fit Meridian's Azure cloud, mapping the same ideas (queue, retries, dead-letter queue) and adding a topic for Finance and Notifications. |
| **Azure Bicep** | **New to me.** AI generated the modules; I learned their purpose, validated them locally and fixed the warnings. |
| **Azure Container Apps** | **New to me.** I learned its role in the infrastructure (running the containers, scaling on load). |
| **OIDC** | **New to me.** I learned how it works and why it removes stored secrets from the pipeline. |
| **GitHub Actions workflows** | AI built them; I reviewed their configuration against their README and changed the CI trigger. |

## What I discarded or corrected

**Claims that were not true, found and fixed by me:**
- An alert described as active only from 06:00 to 23:00; the code runs it all day.
- Flyway migrations described as part of CD; they do not exist yet. Now a documented next step.
- A CODEOWNERS rule and "every alert links to a runbook"; neither exists. Reworded.
- A "circuit breaker" alert in the docs that is not defined in `alerts.bicep`. Removed.
- Alert routing by hour (page vs ticket) that the IaC does not implement. Reworded as a next step.
- The gitleaks allow-list description did not match `.gitleaks.toml`. Corrected.
- INC-0412: an unsupported "Salesforce requires upper case" claim and a "99% before" ratio with no source. Replaced with facts from the incident data and the changelog wording.
- PR-214 review: a wrong section reference and approximate Salesforce latency and Austral retry figures. Replaced with exact values from the reference files.

**Changes I made:**
- Part B: renamed everything to English; refactored `AndinaAdapter`, `QuetzalAdapter`, `QuetzalSignatureVerifier` and `WebhookController` for readability; removed comments that did not explain a "why"; fixed a test broken during a rename.
- Design and ADRs: shortened them, and restored key decisions lost while shortening (upsert by external id, never writing the policy status, duplicate detection off on the queue and on for the topic).
- CI: removed the push trigger.
- Removed special characters from my documents for consistency.

## Data I shared with AI tools

- The contents of this assessment repository (fictional scenario, specs, samples, the published **sandbox** test secrets and the fictional PR-214), my instructions, and terminal output from building and validating.
- **No** real secrets, credentials or tokens, **no** real personal data, and **no** material from any current or former employer.
- Nothing was deployed. Maven dependencies came from Maven Central.