module "vpc" {
  source      = "../modules/vpc"
  aws_region  = var.aws_region
  cidr_prefix = var.cidr_prefix
}

locals {
  name_prefix = "test"
}

module "alb" {
  source      = "../modules/alb"
  name_prefix = local.name_prefix
  subnet_id_1 = module.vpc.private_subnet_id_1
  subnet_id_2 = module.vpc.private_subnet_id_2
  vpc         = module.vpc.vpc
}

module "ecs" {
  source      = "../modules/ecs"
  name_prefix = local.name_prefix
  vpc_id      = module.vpc.vpc.id
}

module "api" {
  source                = "../modules/ecs_task"
  name_prefix           = local.name_prefix
  task_name             = "api"
  image                 = "backend"
  ecs_cluster_id        = module.ecs.cluster.id
  app_ecs_task_role     = module.ecs.app_ecs_task_role
  aws_region            = var.aws_region
  subnet_id_1           = module.vpc.private_subnet_id_1
  subnet_id_2           = module.vpc.private_subnet_id_2
  vpc                   = module.vpc.vpc
  allow_inbound         = true
  hostnames             = ["${local.name_prefix}-api.${data.terraform_remote_state.shared.outputs.zone.name}"]
  lb_https_listener_arn = module.alb.https_listener_arn
  lb_dns_name           = module.alb.dns_name
  zone                  = data.terraform_remote_state.shared.outputs.zone
  tcp_ports             = [9000]
}

module "bastion" {
  source      = "../modules/bastion"
  name_prefix = local.name_prefix
  vpc         = module.vpc.vpc
  subnet_id   = module.vpc.public_subnet_id
  zone        = data.terraform_remote_state.shared.outputs.zone
}

module "rds" {
  source          = "../modules/rds"
  name_prefix     = local.name_prefix
  subnet_id_1     = module.vpc.private_subnet_id_1
  subnet_id_2     = module.vpc.private_subnet_id_2
  instance_class  = "db.t3.medium"
  security_groups = [module.api.security_group_id, module.bastion.security_group.id]
  vpc             = module.vpc.vpc
}

module "github_oidc" {
  source = "../modules/github_oidc"
}

module "web" {
  source      = "../modules/web"
  name_prefix = local.name_prefix
  zone        = data.terraform_remote_state.shared.outputs.zone
  providers = {
    aws.us_east_1 = aws.us_east_1
  }
  ci_role_arn = module.github_oidc.role
}
