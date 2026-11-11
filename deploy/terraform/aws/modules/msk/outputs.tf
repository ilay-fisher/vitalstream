output "bootstrap_brokers_tls" {
  description = "Value for infra.kafkaBootstrapServers in the Helm chart."
  value       = aws_msk_cluster.this.bootstrap_brokers_tls
}
