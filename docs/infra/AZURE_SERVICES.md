# Azure services

IaC: [`infra/`](../../infra/). Decisions: [ADR-0001](../adr/0001-salesforce-integration.md), [ADR-0002](../adr/0002-implementation-stack-and-data-store.md), [ADR-0003](../adr/0003-messaging-backbone.md).
Prices are rough pay-as-you-go list prices in USD (A-17). They exclude platform services that already exist (Log Analytics workspace, API Manager, IdP).

## Azure Container Apps - compute (Intake, State worker, Sync worker, Ops API)

| | |
|---|---|
| **Why** | Runs the Java containers (ADR-0002) without a cluster to manage. Scales on HTTP load (Intake) and queue length (State worker). Managed identities, private network, Key Vault references, health probes. |
| **Discarded** | Azure Functions (Java cold starts risk Andina's 5 s timeout). AKS (too much to operate for 4 small services). App Service (no scale on queue length). |
| **Tier** | Consumption profile. Intake 0.5 vCPU / 1 GiB, min 2 replicas in prod. Workers scale to zero in dev/test. |
| **Reliability** | 2 Intake replicas, zone redundant in prod, health probes, rollback to the previous image. |
| **Security** | Private environment in test/prod. One managed identity per app. No secrets in config. |
| **Cost** | Pay per use. Low cost at idle. |

## Azure Service Bus - messaging

| | |
|---|---|
| **Why** | Stores the event before we answer `2xx` (NFR3). Retries, delivery count and DLQ built in. Topic for Finance and Notifications (FR6). See ADR-0003. |
| **Discarded** | Event Hubs (no per-message DLQ), Event Grid (limited retries), Storage Queues (no topics), one queue per country (NFR9). |
| **Tier** | **Premium, 1 messaging unit** in prod. **Standard** in dev/test. Peak load (40 msg/s) is a small part of 1 unit. |
| **Reliability** | Zone redundant in prod. Max 10 deliveries, then DLQ. Messages kept 14 days. |
| **Security** | No access keys (Entra ID only). Private endpoint in prod. Send and listen roles scoped per queue or topic. |
| **Cost** | Biggest fixed cost (~USD 680/month). Standard would save ~USD 670/month but uses a public endpoint. |

## Azure SQL Database - state store

| | |
|---|---|
| **Why** | One transaction for "dedup + state update" (FR4, FR5). Safe row claims for the Salesforce batches. Indexes for FR7 lookups. |
| **Discarded** | Cosmos DB (cross-partition queries for FR7 and the sync), Table Storage / Redis (no transactions; Redis is not durable). |
| **Tier** | General Purpose serverless. Prod 1-4 vCores, zone redundant, no auto-pause. Dev/test auto-pause. |
| **Reliability** | Automatic failover in prod, 7-day point-in-time restore. If SQL is down, messages wait in Service Bus. |
| **Security** | Entra ID only (no SQL passwords), minimal roles per identity, private endpoint, auditing. No personal data stored. |
| **Cost** | Pay per vCore used. Small load (max ~40 transactions/s). |

## Azure Key Vault - secrets

| | |
|---|---|
| **Why** | NFR6: provider and Salesforce secrets stay out of code. Container Apps reads them with the managed identity. |
| **Discarded** | Secrets stored directly in Container Apps or GitHub (copied values, no rotation audit). |
| **Tier** | Standard. RBAC, soft delete and purge protection. |
| **Reliability, security, cost** | Apps cache secrets, so a short outage does not stop Intake. Every read is audited. Private endpoint in test/prod. A few USD/month. Rotation: add the new secret next to the old one, then remove the old one. |

## Application Insights (on the existing Log Analytics workspace) - observability

| | |
|---|---|
| **Why** | Traces with the correlation id (NFR7), metrics per country, alerts. Reuses the platform workspace. |
| **Discarded** | A separate workspace (extra cost), a third-party APM (not in the ecosystem). |
| **Tier** | Pay per GB ingested, 90-day retention. |
| **Security** | Entra ID ingestion only. Logs carry identifiers only, never personal data (tested). |
| **Cost** | ~10-15 GB/month. |

## Supporting services

| Service | Why / tier |
|---|---|
| **Container Registry** | Stores the images. Basic (dev/test), Standard (prod). No admin user, pull with managed identity. |
| **VNet, private endpoints, private DNS** | Private access to Service Bus, SQL and Key Vault in test/prod. |
| **Managed identities** | One per component, least privilege (`infra/modules/rbac.bicep`). Free. |
| **Azure Monitor alerts** | DLQ, backlog and per-country alerts. A few USD/month. |

## Well-Architected summary

| Pillar | How the design supports it |
|---|---|
| **Reliability** | Event stored before `2xx`; Salesforce outages absorbed by our store; zone redundancy in prod; DLQ and safe replay; per-country alerts. |
| **Security** | No secrets in code or pipelines; managed identities; private network; no personal data outside Salesforce; secret and code scanning in CI. |
| **Cost** | Batches cut Salesforce calls by ~95%; serverless SQL; pay-per-use compute; Standard tier outside prod. |
| **Operational excellence** | Everything as code, approvals before prod, Ops API instead of searching raw logs (FR7). |
| **Performance** | Scales on real demand; 40/s bursts with more than 10x headroom. |

## Monthly cost estimate

Assumptions: ~870,000 notifications/month (26 normal days x 15,000 + 4 peak days x 120,000), US East list prices, 730 hours/month.

| Item | Prod | Test | Dev |
|---|---|---|---|
| Service Bus | 680 | 10 | 10 |
| Container Apps | 150 | 30 | 10 |
| Azure SQL serverless | 250 | 40 | 20 |
| Log Analytics / App Insights | 35 | 10 | 5 |
| Private endpoints + DNS | 25 | 25 | 0 |
| Container Registry | 20 | 5 | 5 |
| Key Vault, alerts, identities | 10 | 5 | 3 |
| **Total (USD/month)** | **~1,170** | **~125** | **~55** |

- **Total for all environments:** ~USD 1,350/month. About USD 0.0013 per notification in prod.
- **Biggest lever:** Service Bus Premium (58% of prod). Standard would bring prod to ~USD 500/month, but without private endpoints. I recommend Premium for payment data, to be confirmed with the security team.