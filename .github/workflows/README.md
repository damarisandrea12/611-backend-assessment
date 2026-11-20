# CI/CD pipelines (GitHub Actions)

| Workflow | Trigger | Purpose |
|---|---|---|
| [`ci.yml`](ci.yml) | every pull request to `main`, manual | Quality and security gates. Uses no Azure credentials. |
| [`cd.yml`](cd.yml) | push to `main`, manual | Build once, then deploy dev, test, prod. **Defined, not executed**: it runs only when the repository variable `CD_ENABLED` is `true`. |
| [`deploy-environment.yml`](deploy-environment.yml) | called by `cd.yml` | Same steps for each environment: what-if, approval, infrastructure, image promotion, DB grants, rollout, verification. |

## CI gates

| Gate | Tool | Fails the PR when | Why it matters here |
|---|---|---|---|
| Build + **tests** | Maven, JUnit 5 | any test fails | Tests cover the provider test vectors, duplicates, durability and PII rules. A regression means a lost or wrong payment. |
| **Coverage** | JaCoCo | line coverage < 80% | A floor, not a goal. Stops untested code (including AI-generated code) from being merged. |
| **Contract** | `CanonicalEventSchemaTest`, Redocly lint | event does not match the schema, or OpenAPI is invalid | Finance, Notifications and the providers depend on the contracts (FR6). |
| **SAST** | CodeQL `security-extended` | new security alert | Internet-facing webhooks with payment data. |
| **Secret scan** | gitleaks (full history) | a secret is found | NFR6. PR-214 shows how easily a live secret ends up in `application.yml`. Only the fictional sandbox secrets, the PR-214 review diff and public Azure role GUIDs are allow-listed ([`.gitleaks.toml`](../../.gitleaks.toml)). |
| **Dependencies** | Trivy, dependency-review | HIGH/CRITICAL with a fix available | Supply-chain risk in a payment service. |
| **Image scan** | Trivy | HIGH/CRITICAL in the image | The base image runs in production. |
| **IaC** | `az bicep build/lint`, Checkov | template does not compile, or a misconfiguration | Infrastructure gets the same review as code. |

Branch protection on `main` (configured in GitHub settings, not in this repository): all CI jobs required, 1 approving review, no direct pushes.

## CD controls

- **No stored secrets for Azure.** `azure/login` uses **OIDC**. Each GitHub Environment has its own Entra ID app with a federated credential. Client, tenant and subscription ids are environment variables (not secrets). The `prod` identity only has rights on `rg-paynotif-prod`.
- **Build once, promote the same image.** Test and prod import the exact image from the previous environment (`az acr import`). They never rebuild it.
- **Infrastructure:** `what-if` runs first and is shown in the job summary. The job fails if it would **delete** Service Bus, SQL or Key Vault resources. Then it waits for approval.
- **Approvals (GitHub Environment protection rules):** `test` needs 1 reviewer. `prod` needs 2 reviewers (not the author), `main` only, and a 15-minute wait.
- **Concurrency:** one deployment at a time, never cancelled half-way.
- **Database:** grants use an Entra ID token (no SQL password). Flyway migrations are a next step.
- **Verification and rollback:** every Container App revision must be `Healthy`. Rollback = redeploy the previous `IMAGE_TAG` (images are immutable).
- **Change window (environment protection rule):** no `prod` deployments during the month-end peak (last 2 and first 2 days of the month), except hotfixes.

## Not done

- Pin third-party actions to commit SHAs (version tags are used here for readability).
- A signed synthetic webhook against test after each deployment.