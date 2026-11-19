# ADR-0001: Integration with Salesforce - asynchronous, batched upserts from a desired-state store

- **Status:** Proposed
- **Date:** 2026-11-18

## Context

Payments must be recorded in Salesforce as `Premium_Payment__c` once (FR3, FR4) and never with an older status (FR5). A Salesforce flow reinstates or suspends the policy from that status, so a wrong write harms customers.

Salesforce limits (`API_CONSTRAINTS.md`): about 97% availability; **150,000 API calls per 24 h**, inside an org-wide limit shared with other teams; a **200-record** composite upsert counts as **one** call. Peak load is 120,000 notifications/day, and providers wait only 5-10 s.

In INC-0412, one call per message plus retrying permanent `400` errors used up the budget and dead-lettered real payments.

## Options considered

### Option 1 - Synchronous, inside the webhook
- Pros: simplest; the provider retries if Salesforce fails.
- Cons: Salesforce can be slower than the provider timeout; our webhook depends on Salesforce being up.

### Option 2 - Asynchronous, one record per message (v1)
- Pros: the provider is decoupled from Salesforce.
- Cons: one call per message; retries multiply calls; outages dead-letter good payments (INC-0412).

### Option 3 - Asynchronous, batches of 200 from our own store *(chosen)*
- Pros: few API calls; Salesforce always gets the latest state; outages only delay records.
- Cons: an extra store and worker to run; more code.

### Option 4 - Bulk API 2.0
- Pros: fewest calls for huge volumes.
- Cons: jobs take minutes; misses the 5-minute target (NFR2).

### Option 5 - Platform Events processed inside Salesforce
- Pros: the write happens next to the data.
- Cons: logic would live in an org we do not own; ordering and dedup rebuilt in Apex.

## Decision

**Option 3.** We store the latest state of each payment in Azure SQL. A sync worker sends changed payments to Salesforce in batches of up to 200, by external id `Payment_Key__c`. A bad record does not fail the rest of the batch.

- Bad data (`400`): not retried; fix and resend.
- Salesforce down: keep pending, retry later.
- API limit reached: pause and alert on-call.
- Upsert by external id, so a retry or replay cannot create a duplicate payment.
- We never write the policy status; Salesforce automation does the reinstatement.

## Consequences

- Positive: well under the API budget; outages delay records but lose nothing; Salesforce never gets an older status; permanent errors no longer burn the budget.
- Negative: more components to build and pay for. Salesforce may not see intermediate statuses (the history stays in our store). A bad batch affects more records (isolated per record).
- Risks: large batches may be slow in Salesforce → reduce batch size. Salesforce outages over 1 hour can miss the 1-hour target (A-10). If SQL is down, messages wait in Service Bus; nothing accepted is lost.