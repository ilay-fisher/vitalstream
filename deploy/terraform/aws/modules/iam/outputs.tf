output "clinical_api_role_arn" {
  value = aws_iam_role.clinical_api.arn
}

output "stream_processor_role_arn" {
  value = aws_iam_role.stream_processor.arn
}
