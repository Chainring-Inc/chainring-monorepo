variable "github_org" {
  description = "Name of GitHub organization/user (case sensitive)"
  default     = "funkybit-labs"
}

variable "repository_name" {
  description = "Name of GitHub repository (case sensitive)"
  default     = "chainring-monorepo"
}

variable "contracts_repository_name" {
  description = "Name of GitHub repository (case sensitive)"
  default     = "chainring-contracts"
}

variable "oidc_provider_arn" {
  description = "Arn for the GitHub OIDC Provider."
  default     = ""
}

variable "oidc_audience" {
  description = "Audience supplied to configure-aws-credentials."
  default     = "sts.amazonaws.com"
}
