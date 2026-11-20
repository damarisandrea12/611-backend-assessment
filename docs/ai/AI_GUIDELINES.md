# AI assistant guidelines: Payments Integration squad (proposal)

Goal: use AI assistants to go faster without losing control of correctness, security or personal data. In this domain, one wrong payment status changes a customer's insurance coverage.

## 1. What is allowed

| Allowed (with the controls below) | Not allowed |
|---|---|
| Company-approved assistants only (enterprise licence, no training on our data, retention agreed with Security) | Personal accounts or free tiers for work material |
| Drafting code, tests, IaC, pipelines, ADRs and runbooks from **provider documentation** | Letting an agent **merge, deploy or run commands** against shared or production environments |
| Explaining code, proposing test cases, reviewing a diff as a **second** reviewer | AI as the **only** review of a PR, or as the source of truth for a provider spec or Salesforce behavior |
| Agents in a local or dev sandbox with least-privilege credentials | Standing credentials for test/prod, Key Vault or the Salesforce org |

**Never share with an AI tool:**
- **Secrets:** webhook and Salesforce secrets, tokens, connection strings, keys, `.env` files. Only published fictional sandbox values are allowed.
- **Personal data:** payer names, national IDs (CC, RUT, CURP), emails, phones, addresses. Also **production payloads, logs, DLQ messages or database extracts** that may contain them. Use synthetic data, not hand-masked data.
- **Payment data and tokens.**
- **Confidential business data:** unreleased pricing, regulator correspondence, unfixed security findings.
- When in doubt, **don't paste it** and ask the squad lead or Security.

## 2. Validating AI-generated code and tests before merge

The author owns every line. "The AI wrote it" is never an explanation in a review or an incident.

1. **Understand before committing.** The author must explain every changed line. Code the author cannot explain is not merged.
2. **Specs come from the source.** Signatures, timeouts, states and Salesforce limits are checked against `reference/`, not the model's memory. Tests use the **official test vectors and samples**.
3. **Tests must prove behavior** (see PR-214):
   - assert outcomes, not "no exception" or "not null";
   - duplicates assert `never()`, not `atMost(1)`;
   - error paths assert retry or dead-lettering, not swallowing;
   - at least one test **fails without the change**; for signatures, idempotency and ordering, break the code and see the test fail.
4. **Same CI gates as human code:** tests, coverage, CodeQL, secret scanning, dependency and image scanning, IaC lint. No exceptions for generated code.
5. **Extra scrutiny:** security and crypto, money (`BigDecimal`, minor units), concurrency and transactions, logging (no PII), IaC permissions, and new dependencies (check they exist and are maintained; watch for hallucinated packages).
6. **Proposal:** a second human reviewer for signature verification, idempotency and ordering, Salesforce writes, `infra/` and pipelines.

## 3. Disclosing AI use in pull requests

Every PR template gets a mandatory **AI assistance** section ("none" is a valid answer):

```markdown
### AI assistance
- Tool(s) and scope: <tool> / <files or parts generated or changed>
- How I validated it: <tests, docs checked, manual review>
- What I changed or rejected: <short list>
- Data shared: <confirm no secrets, no personal data, no production payloads>
```

Commits may carry an `Assisted-by: <tool>` trailer. Disclosure is **not a negative signal**; undisclosed use found later is a topic for the retrospective.

## 4. When one of our services calls an LLM: minimum controls

Example: operations wants an LLM to **summarize dead-lettered payment notifications**.

1. **Decision support only.** The output is advisory text for a human. It **never** triggers replays, resyncs, Salesforce writes or policy changes. Actions stay in the Ops API with RBAC and audit.
2. **Data minimization.** Send only what the summary needs (error codes, counts, providers, states, timestamps, ids), using an **allow-list** of fields. Build prompts from our canonical events and store, which have no PII, never from raw DLQ bodies.
3. **Approved endpoint inside our boundary:** enterprise endpoint (e.g. Azure OpenAI over private networking), no training on our data, agreed retention, data residency compatible with Mexican, Colombian and Chilean law, managed identity (no API keys). Privacy review before go-live.
4. **Untrusted input (prompt injection).** DLQ content includes payer-typed text (e.g. `referencia`). Delimit it as data, give the model no tools or credentials, and validate the output (structure, length, no URLs or commands).
5. **Checkable output.** Counts are computed by our code, not the model. Summaries cite message ids. The UI labels the text as AI-generated.
6. **Limits and fallback.** Rate, cost and timeout limits. The Ops API works without the LLM.
7. **Observability without leakage.** Log metadata (who, when, records, tokens, latency, cost), not full prompts or responses.
8. **Evaluation and change control.** Synthetic DLQ test set in CI, including injection attempts and PII canaries that must never appear in outputs. Prompt and model changes go through PR review; the model version is recorded in an ADR.
9. **Accountability.** A named owner, a runbook entry and a kill switch (feature flag).