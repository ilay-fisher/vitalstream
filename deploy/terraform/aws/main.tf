terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.81.0, < 6.0.0"
    }
  }

  # Remote state: create the bucket and lock table once, out of band, then uncomment.
  # backend "s3" {
  #   bucket         = "my-org-terraform-state"
  #   key            = "vitalstream/aws/terraform.tfstate"
  #   region         = "eu-west-1"
  #   encrypt        = true
  #   dynamodb_table = "terraform-locks"
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "${var.project}-${var.environment}"
  azs  = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  tags = merge({
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }, var.extra_tags)
}

module "vpc" {
  source = "./modules/vpc"

  name               = local.name
  region             = var.region
  vpc_cidr           = var.vpc_cidr
  azs                = local.azs
  single_nat_gateway = var.single_nat_gateway
}

module "eks" {
  source = "./modules/eks"

  name                   = local.name
  kubernetes_version     = var.kubernetes_version
  private_subnet_ids     = module.vpc.private_subnet_ids
  endpoint_public_access = var.eks_endpoint_public_access
  public_access_cidrs    = var.eks_public_access_cidrs
  node_instance_types    = var.node_instance_types
  node_min_size          = var.node_min_size
  node_desired_size      = var.node_desired_size
  node_max_size          = var.node_max_size
}

module "rds" {
  source = "./modules/rds"

  name                      = local.name
  vpc_id                    = module.vpc.vpc_id
  subnet_ids                = module.vpc.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  instance_class            = var.rds_instance_class
  multi_az                  = var.rds_multi_az
  deletion_protection       = var.rds_deletion_protection
  skip_final_snapshot       = var.rds_skip_final_snapshot
}

module "msk" {
  source = "./modules/msk"

  name                      = local.name
  vpc_id                    = module.vpc.vpc_id
  subnet_ids                = module.vpc.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  broker_count              = var.msk_broker_count
  instance_type             = var.msk_instance_type
  client_broker_encryption  = var.msk_client_broker_encryption
}

module "elasticache" {
  source = "./modules/elasticache"

  name                      = local.name
  vpc_id                    = module.vpc.vpc_id
  subnet_ids                = module.vpc.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  node_type                 = var.redis_node_type
  num_cache_clusters        = var.redis_num_cache_clusters
  auth_token                = var.redis_auth_token
}

module "sqs" {
  source = "./modules/sqs"
}

module "s3" {
  source = "./modules/s3"

  bucket_name   = var.archive_bucket_name
  force_destroy = var.archive_force_destroy
}

module "ecr" {
  source = "./modules/ecr"

  force_delete = var.ecr_force_delete
}

module "iam" {
  source = "./modules/iam"

  name_prefix       = local.name
  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_provider_url = module.eks.oidc_provider_url
  namespace         = var.k8s_namespace
  sqs_queue_arn     = module.sqs.queue_arn
  s3_bucket_arn     = module.s3.bucket_arn
}
