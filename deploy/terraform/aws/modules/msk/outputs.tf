output "bootstrap_brokers_sasl_iam" {
  description = "Value for infra.kafkaBootstrapServers in the Helm chart (port 9098, SASL/IAM over TLS)."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

output "cluster_arn" {
  value = aws_msk_cluster.this.arn
}
