# ADR-0003: Messaging backbone - Azure Service Bus, one shared queue and one event topic

- **Status:** Proposed
- **Date:** 2026-11-18

## Context

A notification is accepted when we return `2xx`, and nothing accepted may be lost (NFR3). So Intake must store the event durably **before** answering, within the provider timeout (5-10 s). Finance and Notifications subscribe on their own (FR6). A new country must not change the shared flow (NFR9). Peak is 40 msg/s. We need dead-lettering, retries with a delivery count, and least-privilege access with managed identities (NFR6).

In v1 (INC-0412) there was one queue per country, and only Mexico had an alert.

## Options considered

### Option 1 - Service Bus Premium: one shared queue + one topic *(chosen for prod)*
- Pros: durable; retries, delivery count and DLQ built in; one subscription per consumer with its own permissions; private network; a new country needs no new queue.
- Cons: fixed cost (~USD 680/month); all countries share one DLQ (we report DLQ counts per country).

### Option 2 - Service Bus Standard *(chosen for dev/test)*
- Pros: very cheap; same queue, topic and DLQ features.
- Cons: no private network; less predictable latency. Fine for dev/test, weaker for production payment data.

### Option 3 - Event Hubs
- Pros: very high throughput.
- Cons: no per-message DLQ or delivery count; overkill for 40 msg/s.

### Option 4 - Event Grid
- Pros: cheap, push delivery.
- Cons: limited retries; dead letters go to blob storage.

### Option 5 - One queue per country (v1)
- Pros: a failing country does not affect the others.
- Cons: each new country needs new infrastructure and new alerts (NFR9, INC-0412).

## Decision

**Service Bus Premium in prod, Standard in dev/test.**

- Queue `payment-notifications`: max 10 deliveries, then DLQ.
- Topic `premium-payment-events`: one subscription each for `finance` and `notifications`, each with its own DLQ and read-only access to its own subscription.
- `country` travels as a message property, so we get per-country metrics without separate queues.
- Duplicate detection is **off** on the queue (the State worker is the real dedup and must see every Andina retry id for FR7) and **on** for the topic, as a second guard for consumers.

## Consequences

- Positive: nothing accepted is lost; a new country needs no new messaging resources; private network and managed identities in prod.
- Negative: Premium is the biggest cost of the design. Dev/test networking differs from prod. Consumers may receive events more than once and out of order, so they must apply the same status precedence rule.
- Risks: a Service Bus outage → Intake returns `503` and providers retry, so nothing accepted is lost.