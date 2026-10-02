# Monthly project budget with email alerts at 50 / 80 / 100 % of current spend (configurable) and an
# optional forecast alert. See docs/ops/COST_GUARDRAILS.md for the response runbook.

data "google_project" "this" {
  project_id = var.project_id
}

resource "google_project_service" "billing_budgets" {
  project            = var.project_id
  service            = "billingbudgets.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "monitoring" {
  project            = var.project_id
  service            = "monitoring.googleapis.com"
  disable_on_destroy = false
}

resource "google_monitoring_notification_channel" "budget_email" {
  for_each = toset(var.alert_emails)

  project      = var.project_id
  display_name = "${var.project_name} budget alert: ${each.value}"
  type         = "email"
  labels = {
    email_address = each.value
  }

  depends_on = [google_project_service.monitoring]
}

resource "google_billing_budget" "monthly" {
  billing_account = var.billing_account_id
  display_name    = "${var.project_name}-monthly-cost"

  budget_filter {
    projects        = ["projects/${data.google_project.this.number}"]
    calendar_period = "MONTH"
  }

  amount {
    specified_amount {
      currency_code = var.budget_currency
      units         = format("%d", var.monthly_budget_amount)
    }
  }

  dynamic "threshold_rules" {
    for_each = toset(var.alert_thresholds_percent)
    content {
      threshold_percent = threshold_rules.value / 100
      spend_basis       = "CURRENT_SPEND"
    }
  }

  dynamic "threshold_rules" {
    for_each = var.forecast_alert_enabled ? [100] : []
    content {
      threshold_percent = threshold_rules.value / 100
      spend_basis       = "FORECASTED_SPEND"
    }
  }

  all_updates_rule {
    monitoring_notification_channels = [for c in google_monitoring_notification_channel.budget_email : c.id]
    disable_default_iam_recipients   = !var.notify_billing_admins
  }

  depends_on = [google_project_service.billing_budgets]
}

output "budget_id" {
  description = "Resource name of the created billing budget."
  value       = google_billing_budget.monthly.id
}

output "alert_thresholds_percent" {
  description = "Current-spend thresholds that trigger an alert."
  value       = sort(var.alert_thresholds_percent)
}
