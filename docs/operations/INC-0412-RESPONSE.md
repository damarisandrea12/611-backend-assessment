# INC-0412: Response

Incident: [`reference/incident/INC-0412.md`](../../reference/incident/INC-0412.md). I am the on-call engineer. It is Monday 2026-11-30 08:15 (Colombia), the **last day of the month: the month-end peak has started**.

## 1. First 30 minutes

| Min | What I check / do | Why |
|---|---|---|
| 0-5 | Acknowledge, open the incident channel, **propose raising to SEV-1**. | Customers who paid may be **without coverage**. The real scope is thousands, not 37 (4,198 approved payments in the DLQ). Peak traffic and a high API budget. |
| 5-10 | Snapshot by country: received vs upserted, DLQ depth, Salesforce `400`s. | Only Colombia is affected: 4,312 in the DLQ, 43,120 `400`s. Signatures and volume are normal, `5xx` is low. Not an intake, auth or Salesforce-wide problem. |
| 10-15 | **Protect the shared API budget** (66% vs 38%). If it passes 80% before the fix, **pause the `payments-co` consumer**: messages wait safely in the queue. Inform the Salesforce team. | An org-wide limit would also stop the contact center and partners. Biggest impact first. |
| 15-20 | Peek (not receive) DLQ samples and group by `lastError`: `400 INVALID_FIELD: Foreign key external ID ... not found for field Policy_Number__c`, with references like `000215560` and `pol-co-000198004`. | Salesforce rejects the policy lookup, not the payment. |
| 20-25 | **What changed?** Our deploy (Fri 18:05), Salesforce release (Sat 22:00), **Andina 2.3 (effective Sat 23:00)**. First dead letter: Sat 23:41. | Timeline correlation. |
| 25-30 | Contact center: keep logging cases with the transaction id; **nobody reinstates policies by hand**. Inform Andina support. Start a DLQ inventory (`transaccion.id`, `estado`, `referencia`, no payer data). | Communication and preparing a safe replay. |

## 2. Most likely root cause

**Andina 2.3 (effective Sat 2026-11-28 23:00) sends `transaccion.referencia` exactly as entered or confirmed by the payer; before, Andina normalized it, and the changelog asks merchants to normalize it themselves. v1 uses it unchanged as the Salesforce policy external id, so any reference not in the `POL-CO-<9 digits>` format fails the lookup with a permanent `400`. v1 retries it as if it were transient: 10 immediate redeliveries, then the DLQ. There was no DLQ alert for `payments-co`.**

Evidence:
- **Timing:** Andina 2.3 at 23:00, first dead letter at 23:41, none before.
- **Error content:** `000215560` (digits only) and `pol-co-000198004` (lower case) are not in the expected format.
- **Numbers:** 4,312 messages x 10 deliveries = **43,120** `400`s, exactly. That is about 29% of the 150,000 budget: 38% to **66%**.
- **Partial failure:** 32,880 Colombian payments still succeed (correct references). Mexico and Chile use other providers.
- **Why nobody saw it:** DLQ alert for Mexico only; v1.14.0 removed payloads from `INFO` logs (right for PII) without logging the reference validation result; the consumer logs `sfErrorCode` without the field.

Ruled out:
- **Salesforce release:** its rule is about cancelling policies; it would give `FIELD_CUSTOM_VALIDATION_EXCEPTION`, not a foreign-key `INVALID_FIELD`.
- **Our Friday deploy:** errors start 29 h later, and Mexico and Chile run the same release.

How I confirm it:
1. Query Salesforce for `POL-CO-000215560` and `POL-CO-000198004`: if they exist, normalization fixes them.
2. Check that every DLQ `referencia` fails `^POL-CO-\d{9}$` and every upsert since Sat 23:00 matches it.
3. Reconcile Colombia: 37,215 received vs 32,880 upserted + 4,312 dead-lettered (23 left to explain: duplicates or in flight).

## 3. Remediation

**A. Stop the damage (same-day hotfix through the normal pipeline, expedited approval)**
1. Normalize the Colombian reference: trim, upper-case, add `POL-CO-` when it is exactly 9 digits (same rules and tests as Part B). What still does not resolve is **parked** for operations, not retried.
2. Salesforce `400` (`INVALID_FIELD`, validation) is **not retried**: dead-letter at once with the reason. Retry only `5xx`, `UNABLE_TO_LOCK_ROW` and timeouts, with backoff.
3. Add DLQ, `400` rate and API budget alerts for **every** country queue.

**B. Reprocess the 4,312 dead letters safely**
1. **Quarantine first:** copy the DLQ messages (`messageId`, `transaccion.id`, `estado`, `fecha_transaccion`, `referencia`). Delete nothing until reconciliation.
2. **Fix the data** with the hotfix normalization. Unresolvable references go to operations to identify the policy with Andina's receipt. **No guessing.**
3. **One state per transaction:** some have both `APROBADA` and `REVERSADA` (for example `TRX-CO-5603318`). Replaying in DLQ order could leave **Captured** for money that was returned (FR5). Per `transaccion.id`: keep the highest state (`REVERSADA` > `APROBADA`/`RECHAZADA` > `PENDIENTE`), read the Salesforce record by `Payment_Key__c`, and write only if Salesforce is lower or missing.
4. **The 61 `REVERSADA`:** if the `APROBADA` was recorded before Sat 23:00, Salesforce shows Captured for refunded money. Moving to Refunded may re-suspend policies: coordinate with the contact center first.
5. **10 messages, verify, then the rest**, rate-limited, only after the budget recovers (about 4,300 calls, 3% of the budget). Upsert by `Payment_Key__c` makes a repeated replay harmless.
6. **Reconcile** (DLQ count = recorded + superseded + parked; Andina approvals since Sat 23:00 = Salesforce records; the 37 cases one by one). Only then purge the DLQ.
7. **Customers:** the contact center calls each customer once the policy is reinstated. Backdating coverage for the gap is a business decision for product and legal.

## 4. Prevention

| What | Would have caught or avoided it |
|---|---|
| DLQ, `400` rate and API budget alerts for **every** country, generated from IaC | Page at about 23:45 Saturday, not a customer report 32 h later |
| Received vs recorded ratio per country (S3) | Colombia at 88% while Mexico was at 99% |
| Never retry permanent `400`s | No retry storm, no 29% budget burn |
| Normalize and validate the reference at intake; park what cannot be resolved | The root cause, handled in the target design |
| Provider changelog ownership: contract tests and a sandbox run **before** each effective date | Andina 2.3 was announced with a date |
| Log the validation result (`policyResolved=false`), never the payload | Visible in minutes, without PII |
| Status precedence and Ops API replay/resync (target design) | Safe, repeatable recovery |
| Blameless review within 5 working days, with owners and dates | Shared learning with Salesforce and the contact center |