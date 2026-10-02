variable "aws_region" {
  description = "Region of the AWS API endpoint used by the provider. Budgets are account-wide regardless of region."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Short project name; prefixes the budget name and is applied as the Project tag."
  type        = string
  default     = "educore"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,40}$", var.project_name))
    error_message = "project_name must be 2-41 characters: lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "monthly_budget_amount" {
  description = "Monthly cost budget in budget_currency. Alerts fire at the percentages in alert_thresholds_percent."
  type        = number

  validation {
    condition     = var.monthly_budget_amount > 0
    error_message = "monthly_budget_amount must be greater than zero."
  }
}

variable "budget_currency" {
  description = "Currency of the budget limit. AWS Budgets evaluates cost budgets in USD."
  type        = string
  default     = "USD"

  validation {
    condition     = var.budget_currency == "USD"
    error_message = "AWS cost budgets use USD."
  }
}

variable "alert_emails" {
  description = "Email addresses notified at every threshold (AWS allows at most 10 email subscribers per notification)."
  type        = list(string)

  validation {
    condition     = length(var.alert_emails) >= 1 && length(var.alert_emails) <= 10
    error_message = "alert_emails must contain between 1 and 10 addresses."
  }

  validation {
    condition     = alltrue([for e in var.alert_emails : can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", e))])
    error_message = "Every entry of alert_emails must be an email address."
  }
}

variable "alert_thresholds_percent" {
  description = "Percentages of the monthly budget at which an ACTUAL-spend email alert is sent."
  type        = list(number)
  default     = [50, 80, 100]

  validation {
    condition     = length(var.alert_thresholds_percent) >= 1 && alltrue([for t in var.alert_thresholds_percent : t > 0 && t <= 1000])
    error_message = "alert_thresholds_percent must contain values between 0 (exclusive) and 1000."
  }
}

variable "forecast_alert_enabled" {
  description = "Also alert when AWS forecasts that spend will exceed 100 % of the budget by month end."
  type        = bool
  default     = true
}

variable "cost_filter_tag" {
  description = "Optional cost-allocation tag (activated in Billing) that limits the budget to EduCore resources, e.g. { key = \"Project\", value = \"educore\" }. null budgets the whole account."
  type = object({
    key   = string
    value = string
  })
  default = null
}
