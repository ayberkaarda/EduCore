# Cost Guardrails

Security checklist item 22 (spending alerts and resource limits). Three layers: the cloud bill alerts,
the containers cannot grow beyond fixed limits, and Prometheus warns before a limit is hit.

## 1. Budget alerts (Terraform)

| Module | Resources | Alerts |
|---|---|---|
| `infra/cloud/aws` | `aws_budgets_budget` (monthly COST budget, optional cost-allocation-tag filter) | Email at 50 / 80 / 100 % of actual spend, plus 100 % forecasted |
| `infra/cloud/gcp` | `google_billing_budget` scoped to the project, email `google_monitoring_notification_channel`s, enables `billingbudgets` and `monitoring` APIs | Email at 50 / 80 / 100 % of current spend, plus 100 % forecasted |

Only the module of the cloud that hosts EduCore is applied. Usage:

```bash
cd infra/cloud/aws            # or infra/cloud/gcp
cp terraform.tfvars.example terraform.tfvars   # edit; terraform.tfvars is git-ignored
terraform init
terraform plan -out budget.tfplan
terraform apply budget.tfplan
```

Every variable is documented in `variables.tf` and in the module's `terraform.tfvars.example`. Required
permissions: AWS `budgets:ModifyBudget`/`budgets:ViewBudget`; GCP `roles/billing.costsManager` on the billing
account and `roles/monitoring.notificationChannelEditor` on the project. GCP sends a verification email to each
alert address; alerts are delivered only after it is confirmed. Budgets alert, they never stop resources.

Validation: `terraform fmt -check` and `terraform validate` pass for both modules (run with the
`hashicorp/terraform` image when Terraform is not installed:
`docker run --rm -e TF_DATA_DIR=/tmp/tf -v "$PWD/infra/cloud/aws:/w" -w /w --entrypoint sh hashicorp/terraform -c "terraform init -backend=false && terraform validate"`).
`.terraform.lock.hcl` pins provider checksums and is committed.

### Response runbook

| Alert | Action |
|---|---|
| 50 % before mid-month | Compare with last month in the billing console; look for new resources or traffic. |
| 80 % | Identify the top cost service; check for runaway logs, oversized instances, forgotten snapshots or test stacks; open an issue. |
| 100 % actual or forecast | Owner decides within one business day: accept and raise the budget (change `monthly_budget_amount`), or scale down. |

## 2. Container limits (docker-compose.yml)

| Service | CPU | Memory | Notes |
|---|---|---|---|
| `postgres-db` | 1.0 | 1 GB | |
| `educore-backend` | 1.0 | 1 GB | JVM started with `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`, so heap stays inside the limit and an OOM ends the JVM; `restart: unless-stopped` then restarts the container instead of leaving it degraded. |
| `educore-frontend` (nginx) | 0.25 | 128 MB | `restart: unless-stopped`; the only published port (3000). |
| `backup` (override file) | 0.5 | 256 MB | `restart: unless-stopped`; healthcheck on backup age. |

`postgres-db` uses `restart: always`. PostgreSQL and the backend publish no host ports (only through
`docker-compose.dev.yml`, bound to 127.0.0.1), so nothing but nginx is reachable from outside the host.

Logs: every service uses the `json-file` driver with `max-size=10m`, `max-file=3`, i.e. at most 30 MB of
logs per container on disk. The Actuator management port 9090 is not published.

Check the effective values with `docker compose config` and live usage with `docker stats`.

## 3. Metrics and alert rules

`infra/monitoring/alerts.example.yml` holds Prometheus rules for the metrics the backend exposes today
(`/actuator/prometheus` on the internal port 9090): availability (`up`), 5xx ratio, auth 401/429 spikes and
mean latency from `http_server_requests_seconds_*`; heap pressure, GC overhead and CPU from `jvm_*` and
`process_cpu_usage`; connection-pool saturation and timeouts from `hikaricp_*`. `promtool check rules`
reports 14 valid rules (including four backup rules on the sidecar's textfile metrics). Counters for CSV imports and webhook deliveries arrive with the ingestion phase;
their rules are added to this file when those metrics exist.
