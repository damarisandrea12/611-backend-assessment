# Assumptions

> The scenario is intentionally incomplete. Record every assumption you needed to make, and the part it affects.

| # | Assumption | Why I needed it | Part(s) affected |
|---|---|---|---|
| A-01 | Notifications carry no card data, only tokens (stated). We drop the token because nothing downstream needs it. | To decide what sensitive data the canonical event may carry. | A, B |
| A-02 | One Azure region serves the three countries (stated), with no multi-region or geo-DR. Production uses zone redundancy inside the region. | Sizing, availability and IaC. | A, C |
| A-03 | The API Manager forwards the body byte for byte, with all provider headers. | The HMAC must be computed over the raw body. | A, B |
| A-04 | The API Manager calls Intake with a machine-to-machine IdP token, and Intake is private. Providers cannot get IdP tokens, so webhook authenticity relies only on their HMAC. | To define the trust boundary between the API Manager and Azure. | A, B, C |
| A-05 | Providers do not publish fixed IP ranges, so an IP allow-list is optional and not relied on. | Webhook endpoint hardening. | A |
| A-06 | Quetzal sends `Quetzal-Signature` exactly as documented. Header names are case-insensitive. | Signature parsing. | B |
| A-07 | Quetzal `charge.refunded` maps to `REFUNDED` for total and partial refunds, with the amount in `refundedAmount`. Whether a partial refund re-suspends a policy is decided in Salesforce. | Salesforce has no "partially refunded" status. | A, B |
| A-08 | A payment with a policy reference we cannot normalize is still accepted, published with `policyNumber = null` and parked for operations. Normalization: trim, upper case, and add `POL-<CC>-` when the value is exactly 9 digits. | Andina 2.3 sends the reference as typed, and rejecting it would lose a real payment. | A, B, D |
| A-09 | `CAPTURED` and `FAILED` never happen for the same transaction. If both arrive, the first one wins and we flag an anomaly. | Status precedence for FR5. | A |
| A-10 | Salesforce outages are spread across the month and last 5 to 90 minutes. If most last over 60 minutes, no design can meet the 1 hour target, so we also report freshness without those outages. | Capacity and NFR2 calculation. | A, D |
| A-11 | We keep notifications for 180 days and payment state for 24 months after the last change. No legal requirement was given and we store no personal data. | Data design. | A, C |
| A-12 | Finance and Notifications accept at-least-once, unordered delivery. They use `idempotencyKey`, `status` and `statusChangedAt` to dedup and order. | Contract for FR6. | A, B |
| A-13 | Unknown event types get `200` and are ignored with a metric. Unknown states inside known types get `400` and an alert. | To avoid writing a wrong status without causing endless retries. | A, B |
| A-14 | Timestamp tolerance is 10 minutes for Andina and 300 seconds for Quetzal. Our server clocks are in sync (NTP). | Replay protection. | B |
| A-16 | Quetzal `statusChangedAt` comes from `refunded_at` for refunds, `paid_at` for captures, and `created` when those are null. | Quetzal has no single state change time field. | A, B |
| A-17 | Cost estimates use Azure public pay-as-you-go prices in USD. They exclude platform services that already exist (Log Analytics, API Manager, IdP). | Cost estimate in `AZURE_SERVICES.md`. | C |
| A-18 | The repository is on GitHub. CI/CD uses GitHub Actions with OIDC to Entra ID, and GitHub Environments with reviewers for test and prod. | Pipeline design. | C |
| A-19 | The squad's on-call is paged through Azure Monitor action groups. The Salesforce team has its own channel for Salesforce issues. | Alert routing. | D |
| A-20 | In INC-0412, "Payments upserted in Salesforce" counts every successful upsert, including updates. So it is not the number of unique payments. | To read the incident numbers correctly. | D |
| A-21 | Webhook bodies are at most 64 KB. The API Manager and Intake reject larger bodies with `413`. | Basic abuse protection. | B, C |
| A-22 | An Andina `REVERSADA` reverses the full amount, so `refundedAmount = valor`. The spec has no partial reversal field. | Canonical `refundedAmount` for Andina. | B |
| A-24 | IaC creates one container registry per environment. If the platform has a shared registry, we reference it instead. | IaC completeness. | C |
| A-25 | The API Manager can reach a private Azure VNet. The Container Apps environment in test and prod has no public IP. | Trust boundary and IaC networking. | A, C |
