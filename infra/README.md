# Infrastructure as code

Azure resources for the design in [`docs/design/SOLUTION_DESIGN.md`](../docs/design/SOLUTION_DESIGN.md). Service choices, SKUs and costs are in [`docs/infra/AZURE_SERVICES.md`](../docs/infra/AZURE_SERVICES.md). **Nothing has been deployed.**

## Tool and version

- **Bicep**, validated with Bicep CLI **0.49.2** (Azure CLI). Uses `.bicepparam` files.
- Deployed at **resource-group scope**, one resource group per environment (`rg-paynotif-dev|test|prod`), only by the CD pipeline ([`.github/workflows/cd.yml`](../.github/workflows/cd.yml)).

## Structure

```
infra/
├── main.bicep                     # composition for one environment
├── environments/
│   ├── dev.bicepparam             # Standard SB, public endpoints, scale to zero, SQL auto-pause
│   ├── test.bicepparam            # private networking like prod, Standard SB
│   └── prod.bicepparam            # Premium SB, private endpoints, zone redundant, 2 warm intake replicas
├── modules/
│   ├── network.bicep              # VNet: Container Apps subnet + private endpoints subnet
│   ├── identities.bicep           # 4 managed identities (intake, state-worker, sync-worker, ops-api)
│   ├── monitoring.bicep           # Application Insights on the existing platform Log Analytics workspace
│   ├── servicebus.bicep           # queue (DLQ, max delivery 10) + topic + subscriptions
│   ├── keyvault.bicep             # RBAC-only Key Vault, purge protection, audit logs
│   ├── sql.bicep                  # Azure SQL serverless, Entra ID-only auth
│   ├── registry.bicep             # container registry, admin user disabled
│   ├── private-endpoints.bicep    # private endpoints + private DNS zones
│   ├── rbac.bicep                 # least-privilege role assignments
│   ├── containerapps.bicep        # Container Apps environment + the 4 apps
│   └── alerts.bicep               # broker metric alerts + per-country log alerts
└── sql/grant-database-roles.sql   # database roles for the managed identities (run by CD)
```

## Per environment and per country

| Parameter | dev | test | prod |
|---|---|---|---|
| `serviceBusSku` | Standard | Standard | **Premium** (1 MU) |
| `enablePrivateNetworking` | false | true | true |
| `zoneRedundant` | false | false | true |
| SQL vCores (min to max), auto-pause | 0.5 to 1, 60 min | 0.5 to 2, 60 min | 1 to 4, off |
| Intake replicas (min to max) | 0 to 2 | 1 to 4 | **2** to 10 |
| Workers min replicas | 0 | 0 | 1 |
| Alerts page on-call | no | yes | yes |

**Per country:** the `providers` parameter (`quetzal/MX`, `andina/CO`, `austral/CL`) creates one webhook secret reference per provider in the Intake app and one set of per-country alerts. Adding a country is one more array entry, with no new queues (ADR-0003, NFR9).

## What the IaC does not create

- **Secret values.** Key Vault holds the webhook secrets and the Salesforce client credentials. The secret owners set the values with `az keyvault secret set` under just-in-time access. They never pass through the repository or the pipeline.
- **Existing platform resources:** Log Analytics workspace, on-call action group, API Manager and IdP. They are passed in by resource id.
- **API Manager routes:** owned by the integration platform team.
- **SQL schema:** Flyway migrations, planned as a CD step before the grants (not written yet).

## Security choices (NFR6)

- No SAS keys or connection strings: local auth disabled on Service Bus, Entra ID-only SQL, RBAC-only Key Vault, ACR admin user disabled.
- One managed identity per component, with the narrowest scope (queue/topic level). See [`modules/rbac.bicep`](modules/rbac.bicep).
- In test/prod: private endpoints and an internal Container Apps environment. Only the API Manager reaches Intake and the Ops API (A-04).
- Diagnostic logs, including Key Vault audit events, go to the platform Log Analytics workspace.

## Validation

**Validated locally without deploying** (no Azure subscription needed) on Windows with Azure CLI and Bicep CLI 0.49.2:

- `az bicep build` and `az bicep lint` on `main.bicep`: 0 errors, 0 warnings.
- `az bicep build-params` on `dev`, `test` and `prod`: all OK.

The first build gave 6 warnings, which I fixed: non-null access (`!`) to the outputs of the conditional `network` and `containerApps` modules (BCP318), and an unsupported `anonymousPullEnabled` property in `registry.bicep` (BCP037).

To repeat it (PowerShell, from the repository root; the values are placeholders, not secrets):

```powershell
az bicep build --file infra/main.bicep --stdout | Out-Null
az bicep lint  --file infra/main.bicep
$env:IMAGE_TAG="local"; $env:LOG_ANALYTICS_WORKSPACE_ID="x"; $env:ONCALL_ACTION_GROUP_ID="x"; $env:SQL_ADMIN_GROUP_OBJECT_ID="00000000-0000-0000-0000-000000000000"
foreach ($e in "dev","test","prod") { az bicep build-params --file "infra/environments/$e.bicepparam" --stdout | Out-Null }
```

The same checks run in CI on every pull request ([`ci.yml`](../.github/workflows/ci.yml), job `iac`), and CD runs `az deployment group what-if` before each environment (prod needs approval).

Known point: some resources use preview API versions (`scheduledQueryRules`, Service Bus `2022-10-01-preview`). Move them to GA versions when available.