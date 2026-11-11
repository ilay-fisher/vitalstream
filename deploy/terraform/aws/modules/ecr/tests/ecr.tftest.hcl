mock_provider "aws" {}

variables {
  repositories = ["ingest-gateway", "copilot"]
}

run "one_repository_per_service_under_the_vitalstream_namespace" {
  command = plan

  assert {
    condition     = toset(keys(aws_ecr_repository.this)) == toset(["copilot", "ingest-gateway"])
    error_message = "expected one repository per service"
  }

  assert {
    condition     = aws_ecr_repository.this["copilot"].name == "vitalstream/copilot"
    error_message = "repository names must match the Helm chart image repositories"
  }
}

run "tags_are_immutable_and_scanned" {
  command = plan

  assert {
    condition = alltrue([
      for r in aws_ecr_repository.this : r.image_tag_mutability == "IMMUTABLE" && r.image_scanning_configuration[0].scan_on_push
    ])
    error_message = "tags must be immutable and images scanned on push"
  }
}
