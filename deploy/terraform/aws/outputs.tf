output "eks_cluster_name" {
  description = "aws eks update-kubeconfig --name <this> --region <region>"
  value       = module.eks.cluster_name
}

output "ecr_repository_urls" {
  description = "Push images here; set global.imageRegistry to the registry host."
  value       = module.ecr.repository_urls
}

output "kafka_bootstrap_servers" {
  description = "infra.kafkaBootstrapServers"
  value       = module.msk.bootstrap_brokers_tls
}

output "redis_url" {
  description = "infra.redisUrl (TLS)"
  value       = "rediss://${module.elasticache.primary_endpoint}:6379"
}

output "postgres_host" {
  description = "infra.postgres.host"
  value       = module.rds.address
}

output "postgres_master_secret_arn" {
  description = "Secrets Manager secret with the generated master password"
  value       = module.rds.master_user_secret_arn
}

output "sqs_queue_url" {
  value = module.sqs.queue_url
}

output "archive_bucket" {
  value = module.s3.bucket_name
}

output "clinical_api_role_arn" {
  description = "services.clinical-api.serviceAccount.annotations[eks.amazonaws.com/role-arn]"
  value       = module.iam.clinical_api_role_arn
}

output "stream_processor_role_arn" {
  description = "services.stream-processor.serviceAccount.annotations[eks.amazonaws.com/role-arn]"
  value       = module.iam.stream_processor_role_arn
}

output "vpc_cidr" {
  description = "Add to networkPolicy.infra.cidrs in the Helm values so pods may reach RDS, MSK and ElastiCache."
  value       = module.vpc.vpc_cidr
}
