variable "name_prefix" {}
variable "aws_region" {}
variable "vpc" {}
variable "ecs_cluster_name" {}
variable "subnet_id" {}
variable "capacity_provider_name" {}
variable "service_discovery_private_dns_namespace" {}
variable "baregate_cpu" {
  type    = number
  default = 2048
}
variable "baregate_memory" {
  type    = number
  default = 15000
}
data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "sequencer_task_doc" {
  statement {
    actions = ["sts:AssumeRole"]
    effect  = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}