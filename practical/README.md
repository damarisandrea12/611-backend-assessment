# Practical part - Payment notification intake (MX, CO)

Intake of premium payment notifications from **Quetzal Pagos (Mexico)** and **Andina Pay (Colombia)**: from the webhook request to the **canonical payment event** published on a queue. This is the *Intake* component of [`SOLUTION_DESIGN.md`](../docs/design/SOLUTION_DESIGN.md). Writing to Salesforce is out of scope.

| Requirement | Where it is met | Proven by |
|---|---|---|
| **FR1** authenticity | `QuetzalSignatureVerifier`, `AndinaSignatureVerifier`: HMAC over the raw bytes, constant-time compare, timestamp tolerance, secret rotation, fail-closed without a secret | `*SignatureVerifierTest` with the 7 reference test vectors |
| **FR2** canonical event | `QuetzalAdapter`, `AndinaAdapter` → `CanonicalPaymentEvent` | `*AdapterTest`, `CanonicalEventSchemaTest` |
| **FR4** exactly once | Idempotency key per provider, used as `messageId`; first-line dedup in Intake (fail-open) | `IntakeServiceTest.Duplicates` |
| **NFR3** no loss | `2xx` only after the broker confirms; broker failure → `503` | `IntakeServiceTest.Durability` |
| **NFR4** provider timeouts | Publish has a 3 s budget; slow broker → `503` | `slowBrokerAnswersWithinBudget` |
| **NFR6** privacy and secrets | Payer data dropped in the adapters; logs carry ids only; secrets from the environment | `personalDataIsNeverLogged` |
| **NFR9** extensibility | `ProviderAdapter` interface; `IntakeService` has no provider-specific code | Austral = one new adapter |

## Stack and versions

- Java 21, Spring Boot 3.3.5, Maven 3.9+
- Tests: JUnit 5, AssertJ, Spring Boot Test, `json-schema-validator` 1.5.1
- JaCoCo coverage gate >= 80% of lines

The reason for Java instead of MuleSoft is in [ADR-0002](../docs/adr/0002-implementation-stack-and-data-store.md).

## How to build and run the tests

Requirements: JDK 21 and Maven 3.9. No credentials, no network, no Docker.

```bash
cd practical
mvn -B verify      # compile, run the 128 tests, check coverage
```

Run from `practical/`: the tests read the provider samples directly from `../reference/providers/` and the schema from `../docs/api/events/`.

## Tests and what they prove

| Tests | Proves |
|---|---|
| `QuetzalSignatureVerifierTest`, `AndinaSignatureVerifierTest` | Reference vectors verify. Tampered body, wrong secret, old timestamp and missing headers are rejected. Secret rotation works. |
| `QuetzalAdapterTest`, `AndinaAdapterTest` | Status and amount mapping. An Andina retry has the same idempotency key. INC-0412 references are normalized or parked. Unknown types are ignored, unknown states rejected. Payer data is dropped. |
| `CanonicalEventSchemaTest` | Every mapped sample is valid against the JSON Schema. |
| `PolicyNumberNormalizerTest`, `AmountsTest` | Policy number rules and currency units (CLP has no decimals). |
| `IntakeServiceTest` | Duplicates published once, broker down → `503`, slow broker → `503`, oversized body → `413`. |
| `WebhookIntakeIntegrationTest` | The full HTTP flow, including that no personal data or secrets appear in the logs. |

## Project structure

```
practical/src/main/java/com/meridian/payments/intake/
├── web/          # WebhookController: POST /webhooks/{provider}
├── core/         # IntakeService: verify → map → dedup → publish → 2xx
├── provider/     # ProviderAdapter + Quetzal and Andina (signature + mapping)
├── canonical/    # canonical event, normalizer, amounts
├── crypto/       # HMAC-SHA256 + constant-time compare
├── messaging/    # EventPublisher (+ in-memory stub)
├── idempotency/  # IdempotencyStore (+ in-memory stub)
└── secrets/      # SecretProvider (environment variables)
```

## What is stubbed

| Dependency | Stub here | In production (designed, not built) |
|---|---|---|
| Service Bus queue | `InMemoryEventPublisher` | Service Bus client with managed identity |
| Idempotency store | `InMemoryIdempotencyStore` | Azure SQL with managed identity |
| Key Vault | `EnvironmentSecretProvider` (env vars) | Same class, with Key Vault references |

No real provider, Azure service or Salesforce is called.

## Known gaps

- Austral (Chile) is not implemented (out of Part B scope).
- The in-memory dedup is per instance; the real dedup is designed in the State worker.
- The State worker, Salesforce Sync worker and Ops API are designed only.
- The M2M token check is left to the API Manager (A-04).
- A publish timeout can cause a duplicate (never a loss) when the provider retries.
- No load test and no OpenAPI contract test yet.

## Setup note

The HMAC test vectors only match the exact bytes of the reference samples. `.gitattributes` stops Windows from changing their line endings; otherwise the signature tests fail.