# Monthly cost budget with email alerts at 50 / 80 / 100 % of actual spend (configurable) and an
# optional forecast alert. See docs/ops/COST_GUARDRAILS.md for the response runbook.

resource "aws_budgets_budget" "monthly" {
  name         = "${var.project_name}-monthly-cost"
  budget_type  = "COST"
  limit_amount = format("%.2f", var.monthly_budget_amount)
  limit_unit   = var.budget_currency
  time_unit    = "MONTHLY"

  dynamic "cost_filter" {
    for_each = var.cost_filter_tag == null ? [] : [var.cost_filter_tag]
    content {
      # AWS format for a user-defined cost-allocation tag: user:<key>$<value>
      name   = "TagKeyValue"
      values = [format("user:%s$%s", cost_filter.value.key, cost_filter.value.value)]
    }
  }

  dynamic "notification" {
    for_each = toset(var.alert_thresholds_percent)
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.alert_emails
    }
  }

  dynamic "notification" {
    for_each = var.forecast_alert_enabled ? [100] : []
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "FORECASTED"
      subscriber_email_addresses = var.alert_emails
    }
  }
}

output "budget_name" {
  description = "Name of the created AWS budget."
  value       = aws_budgets_budget.monthly.name
}

output "alert_thresholds_percent" {
  description = "Actual-spend thresholds that trigger an email."
  value       = sort(var.alert_thresholds_percent)
}
