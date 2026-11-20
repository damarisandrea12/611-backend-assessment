# API contracts

| Contract | File | Audience |
|---|---|---|
| Webhook endpoints (inbound) | [`webhooks.openapi.yaml`](webhooks.openapi.yaml) | Payment providers, API Manager |
| Canonical payment event (outbound) | [`events/premium-payment.schema.json`](events/premium-payment.schema.json) | State worker, Finance, Notifications |

The contracts were written before the code. The Part B tests validate every published event against the JSON Schema.

## Webhooks: evolving without breaking providers

- Providers own the payload. We describe only the fields we use and ignore new ones (`additionalProperties: true`).
- The signature is checked on the raw body before parsing, so payload changes cannot weaken authentication.
- Response codes never change meaning: `2xx` accepted, `400` unprocessable, `401` bad signature, `503` retry.
- A breaking provider change gets a new path (for example `/webhooks/quetzal/v2`) next to the old one.
- A new provider gets a new path and adapter. Existing paths are not touched (NFR9).

## Canonical event: evolving without breaking consumers

- The version is in `schemaVersion` (`MAJOR.MINOR`) and in the Service Bus message property.
- **Minor (`1.x`):** only new optional fields. Consumers must ignore unknown fields. No field is removed, renamed or changes type.
- **Major (`2.0`):** published on a new topic, in parallel with v1, until every consumer has migrated. A new `status` value is a major change.
- Delivery is at least once and unordered. Consumers deduplicate by `idempotencyKey` and order by status rank, then `statusChangedAt`.