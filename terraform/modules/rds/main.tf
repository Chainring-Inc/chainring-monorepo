resource "aws_db_subnet_group" "db_subnet_group" {
  name       = "${var.name_prefix}-db-subnet-group"
  subnet_ids = [var.subnet_id_1, var.subnet_id_2]
}

resource "aws_security_group" "db_security_group" {
  vpc_id      = var.vpc.id
  name        = "${var.name_prefix}-db-security-group"
  description = "${var.name_prefix} DB security group"

  ingress {
    from_port = 5432
    to_port = 5432
    protocol = "tcp"
    security_groups = var.security_groups
  }

  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = ["38.125.187.6/32"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_rds_cluster" "db_cluster" {
  cluster_identifier           = "db-cluster"
  engine                       = "aurora-postgresql"
  engine_version               = "16.1"
  manage_master_user_password  = true
  master_username              = "chainring"
  database_name                = "chainring"
  db_subnet_group_name         = aws_db_subnet_group.db_subnet_group.name
  backup_retention_period      = 7
  preferred_backup_window      = "08:00-08:30"
  skip_final_snapshot          = true

  tags = {
    Name = "${var.name_prefix}-db-cluster"
  }
}

resource "aws_rds_cluster_instance" "db_instance" {
  count                     = 2
  identifier                = "aurora-instance-${count.index + 1}"
  instance_class            = var.instance_class
  cluster_identifier        = aws_rds_cluster.db_cluster.id
  engine                    = "aurora-postgresql"
  engine_version            = "16.1"
  publicly_accessible       = false
  db_subnet_group_name      = aws_db_subnet_group.db_subnet_group.name
}