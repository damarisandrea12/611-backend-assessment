# ADR-0002: Implementation stack and data store - Java 21 + Spring Boot 3 on Azure Container Apps, Azure SQL Database

- **Status:** Proposed
- **Date:** 2026-11-18

## Context

MuleSoft is Meridian's reference stack; Java 21 is accepted with justification. Every API goes through the API Manager (no point-to-point).

The hardest parts are correctness logic: signature checks on the **exact raw bytes**, idempotency, ordering, and safely claiming rows for the Salesforce sync. We need a store for dedup keys, the latest state per payment and FR7 lookups, with atomic updates. Managed identities are mandatory (NFR6). Reviewers read the code but do not run it. The squad already has a Java / Spring Boot prototype on Azure SQL.

## Options considered

### Stack A - Mule 4 on the integration platform
- Pros: reference stack; DataWeave is great for mapping; native API Manager policies.
- Cons: HMAC and ordering logic would need custom code anyway; managed identity to Azure is not native (needs stored secrets, against NFR6); licence cost; Enterprise dependencies need credentials a reviewer may not have.

### Stack B - Java 21 on Azure Functions
- Pros: serverless, pay per use, native Service Bus triggers.
- Cons: cold starts risk Andina's 5 s timeout; the long-running sync loop does not fit the function model.

### Stack C - Java 21 + Spring Boot 3 on Azure Container Apps *(chosen)*
- Pros: same stack as the prototype; full control of raw bytes and response time; each component scales on its own; managed identity and private network are native; JUnit tests run without credentials.
- Cons: one more runtime next to Mule; more mapping code than DataWeave; we patch our own container images.

### Store 1 - Azure SQL Database *(chosen)*
- Pros: one transaction for "insert notification + update state"; conditional updates make ordering safe; proven pattern to claim rows for the sync; indexes for FR7 lookups.
- Cons: fixed monthly cost; schema migrations to manage.

### Store 2 - Cosmos DB (serverless)
- Pros: cheap at this volume; built-in TTL.
- Cons: transactions only inside one partition; FR7 lookups and the sync claim are cross-partition.

### Store 3 - Table Storage / Redis
- Pros: cheapest and fastest.
- Cons: no secondary indexes or transactions; Redis is not durable. Fails FR7.

## Decision

**Java 21 + Spring Boot 3** in one container image (intake, state worker, sync worker, Ops API) on **Azure Container Apps**, internal only, behind the API Manager. **Azure SQL Database** for state, accessed with managed identity.

Java wins because the critical logic is easier to write, test and review in Java, and the squad already has the skills and a prototype. The design does not depend on the stack: it could be built in Mule.

## Consequences

- Positive: Part B builds and tests with plain Maven + JDK 21. Each component scales on its own; Intake keeps at least 2 warm replicas for the provider timeouts (NFR4). SQL transactions make FR4/FR5 simple.
- Negative: the squad runs Java next to Mule. More mapping code; a new provider needs a Java adapter, but no change to the shared flow (NFR9). SQL has a fixed monthly cost.
- Risks: the platform team may require Mule → the contracts and design stay the same, only the runtime changes. SQL load under burst → small transactions (max ~40/s), confirm with a load test.