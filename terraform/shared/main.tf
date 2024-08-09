module "github_oidc" {
  source = "../modules/github_oidc"
}
locals {
  repos = toset(["otterscan", "mocker", "sequencer", "anvil", "backend", "bitcoin"])
}
resource "aws_iam_role_policy" "auth" {
  role   = module.github_oidc.role.name
  policy = <<EOF
{
    "Version": "2008-10-17",
    "Statement": [
        {
            "Sid": "AllowGetAuthorizationToken",
            "Effect": "Allow",
            "Action": [
                "ecr:GetAuthorizationToken"
            ],
            "Resource": "*"
        },
        {
            "Sid": "AllowDeployToEcs",
            "Effect": "Allow",
            "Action": [
                "ecs:DescribeServices",
                "ecs:DescribeTaskDefinition",
                "ecs:RegisterTaskDefinition",
                "iam:PassRole",
                "ecs:UpdateService",
                "elasticloadbalancing:DescribeLoadBalancers",
                "elasticloadbalancing:DescribeTargetGroups",
                "elasticloadbalancing:DescribeListeners",
                "elasticloadbalancing:DescribeRules",
                "elasticloadbalancing:DescribeTags",
                "elasticloadbalancing:CreateRule",
                "elasticloadbalancing:DeleteRule",
                "elasticloadbalancing:AddTags"
            ],
            "Resource": "*"
        },
        {
            "Sid": "AllowCloudFrontCacheInvalidation",
            "Effect": "Allow",
            "Action": [
                "cloudfront:ListDistributions",
                "cloudfront:ListTagsForResource",
                "cloudfront:CreateInvalidation"
            ],
            "Resource": "*"
        }
    ]
}
EOF
}

module "ecr_repo" {
  for_each             = local.repos
  source               = "../modules/ecr_repo"
  github_oidc_role_arn = module.github_oidc.role.arn
  repo_name            = each.key
}

moved {
  from = aws_ecr_repository.backend
  to   = module.ecr_repo["backend"].aws_ecr_repository.repo
}
moved {
  from = aws_ecr_repository_policy.backend
  to   = module.ecr_repo["backend"].aws_ecr_repository_policy.policy
}
moved {
  from = aws_ecr_repository.anvil
  to   = module.ecr_repo["anvil"].aws_ecr_repository.repo
}
moved {
  from = aws_ecr_repository_policy.anvil
  to   = module.ecr_repo["anvil"].aws_ecr_repository_policy.policy
}
moved {
  from = aws_ecr_repository.sequencer
  to   = module.ecr_repo["sequencer"].aws_ecr_repository.repo
}
moved {
  from = aws_ecr_repository_policy.sequencer
  to   = module.ecr_repo["sequencer"].aws_ecr_repository_policy.policy
}
moved {
  from = aws_ecr_repository.mocker
  to   = module.ecr_repo["mocker"].aws_ecr_repository.repo
}
moved {
  from = aws_ecr_repository_policy.mocker
  to   = module.ecr_repo["mocker"].aws_ecr_repository_policy.policy
}
moved {
  from = aws_ecr_repository.ecr
  to   = aws_ecr_repository.backend
}
moved {
  from = aws_ecr_repository.bitcoin
  to   = module.ecr_repo["bitcoin"].aws_ecr_repository.repo
}
moved {
  from = aws_ecr_repository_policy.bitcoin
  to   = module.ecr_repo["bitcoin"].aws_ecr_repository_policy.policy
}

resource "aws_route53_zone" "zone" {
  name = var.chainring_zone
}

resource "aws_route53_zone" "zone-finance" {
  name = "chainring.finance"
}

resource "aws_route53_zone" "zone-labs" {
  name = "chainringlabs.com"
}

resource "aws_route53_zone" "fb-fun" {
  name = "funkybit.fun"
}

resource "aws_route53_zone" "fb-co" {
  name = "funkybit.co"
}

resource "aws_route53_zone" "fb-xyz" {
  name = "funkybit.xyz"
}

resource "aws_route53_zone" "fb-it" {
  name = "funkyb.it"
}

resource "aws_key_pair" "baregate" {
  key_name   = "baregate-key"
  public_key = var.baregate_key
}

resource "aws_key_pair" "deployer" {
  key_name   = "deployer-key"
  public_key = var.deployer_key
}

resource "aws_key_pair" "loadtest" {
  key_name   = "loadtest-key"
  public_key = var.loadtest_key
}

resource "aws_s3_bucket" "icons" {
  bucket = "chainring-web-icons"
}

resource "aws_s3_bucket_public_access_block" "icons" {
  bucket = aws_s3_bucket.icons.id

  block_public_acls   = false
  block_public_policy = false
}


resource "aws_s3_bucket_policy" "bucket_policy" {
  bucket = aws_s3_bucket.icons.id
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect    = "Allow",
        Principal = "*",
        Action = [
          "s3:GetObject"
        ],
        Resource = [
          "${aws_s3_bucket.icons.arn}/*"
        ]
      }
    ]
  })
}
