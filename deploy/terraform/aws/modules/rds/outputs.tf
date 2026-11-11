output "address" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "master_user_secret_arn" {
  description = "Secrets Manager secret holding the generated master password (sync it into the POSTGRES_PASSWORD key of the app secret)."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}
