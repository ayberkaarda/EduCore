variable "project_id" {
  description = "ID of the Google Cloud project that runs EduCore; the budget is scoped to this project."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid Google Cloud project ID (6-30 characters)."
  }
}

variable "region" {
  description = "Default region for the provider. Budgets are not regional; this only sets the provider default."
  type        = string
  default     = "europe-west1"
}

variable "billing_account_id" {
  description = "Billing account that pays for project_id, in the form XXXXXX-XXXXXX-XXXXXX."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$", var.billing_account_id))
    error_message = "billing_account_id must look like 0REMOVED-DB-PASSWORD5-6789AB-CDEF01."
  }
}

variable "project_name" {
  description = "Short project name used in the budget display name and notification channel names."
  type        = string
  default     = "educore"
}

variable "monthly_budget_amount" {
  description = "Monthly budget as a whole number of currency units (the billing account's currency)."
  type        = number

  validation {
    condition     = var.monthly_budget_amount > 0 && floor(var.monthly_budget_amount) == var.monthly_budget_amount
    error_message = "monthly_budget_amount must be a positive whole number."
  }
}

variable "budget_currency" {
  description = "ISO 4217 currency code; it must equal the billing account's currency (e.g. USD, EUR, TRY)."
  type        = string
  default     = "USD"

  validation {
    condition     = can(regex("^[A-Z]{3}$", var.budget_currency))
    error_message = "budget_currency must be a three-letter ISO 4217 code."
  }
}

variable "alert_emails" {
  description = "Email addresses that receive every threshold alert (a budget accepts at most 5 notification channels)."
  type        = list(string)

  validation {
    condition     = length(var.alert_emails) >= 1 && length(var.alert_emails) <= 5
    error_message = "alert_emails must contain between 1 and 5 addresses."
  }

  validation {
    condition     = alltrue([for e in var.alert_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", e))])
    error_message = "Every entry of alert_emails must be an email address."
  }
}

variable "alert_thresholds_percent" {
  description = "Percentages of the monthly budget (current spend) at which alerts are sent."
  type        = list(number)
  default     = [50, 80, 100]

  validation {
    condition     = length(var.alert_thresholds_percent) >= 1 && alltrue([for t in var.alert_thresholds_percent : t > 0 && t <= 1000])
    error_message = "alert_thresholds_percent must contain values between 0 (exclusive) and 1000."
  }
}

variable "forecast_alert_enabled" {
  description = "Also alert when forecasted spend exceeds 100 % of the budget."
  type        = bool
  default     = true
}

variable "notify_billing_admins" {
  description = "Keep Google's default emails to billing account administrators in addition to alert_emails."
  type        = bool
  default     = true
}
