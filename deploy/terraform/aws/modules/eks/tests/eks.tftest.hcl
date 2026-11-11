mock_provider "aws" {
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/mock"
    }
  }

  mock_resource "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::123456789012:oidc-provider/oidc.eks.eu-west-1.amazonaws.com/id/ABCDEF"
      url = "https://oidc.eks.eu-west-1.amazonaws.com/id/ABCDEF"
    }
  }

  mock_resource "aws_launch_template" {
    defaults = {
      id             = "lt-0123456789abcdef0"
      latest_version = 1
    }
  }

  mock_resource "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:eu-west-1:123456789012:key/11111111-2222-3333-4444-555555555555"
    }
  }

  mock_resource "aws_eks_cluster" {
    defaults = {
      identity = [{ oidc = [{ issuer = "https://oidc.eks.eu-west-1.amazonaws.com/id/ABCDEF" }] }]
    }
  }
}

variables {
  name               = "vs-test"
  private_subnet_ids = ["subnet-1", "subnet-2"]
}

run "api_endpoint_is_private_by_default" {
  command = plan

  assert {
    condition     = aws_eks_cluster.this.vpc_config[0].endpoint_public_access == false
    error_message = "the Kubernetes API must not be public by default"
  }
}

run "public_endpoint_requires_cidrs" {
  command = plan

  variables {
    endpoint_public_access = true
    public_access_cidrs    = []
  }

  expect_failures = [aws_eks_cluster.this]
}

run "world_open_cidr_is_rejected" {
  command = plan

  variables {
    endpoint_public_access = true
    public_access_cidrs    = ["0.0.0.0/0"]
  }

  expect_failures = [var.public_access_cidrs]
}

run "public_endpoint_with_a_specific_cidr_is_allowed" {
  command = plan

  variables {
    endpoint_public_access = true
    public_access_cidrs    = ["203.0.113.0/24"]
  }

  assert {
    condition     = aws_eks_cluster.this.vpc_config[0].endpoint_public_access
    error_message = "public access should be on when requested"
  }
}

run "nodes_enforce_imdsv2_with_hop_limit_one" {
  command = plan

  assert {
    condition = (
      aws_launch_template.node.metadata_options[0].http_tokens == "required" &&
      aws_launch_template.node.metadata_options[0].http_put_response_hop_limit == 1 &&
      aws_launch_template.node.metadata_options[0].http_endpoint == "enabled"
    )
    error_message = "launch template must require IMDSv2 tokens and a hop limit of 1"
  }
}

run "cni_policy_is_not_on_the_node_role" {
  command = plan

  assert {
    condition     = !contains(keys(aws_iam_role_policy_attachment.node), "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy")
    error_message = "the CNI policy belongs on the aws-node IRSA role, not on every pod through the node role"
  }
}

run "cni_policy_is_on_a_scoped_irsa_role" {
  command = apply

  assert {
    condition     = strcontains(aws_iam_role.vpc_cni.assume_role_policy, "system:serviceaccount:kube-system:aws-node")
    error_message = "vpc-cni role must trust only kube-system/aws-node"
  }

  assert {
    condition     = aws_iam_role_policy_attachment.vpc_cni.policy_arn == "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
    error_message = "vpc-cni role must carry the CNI policy"
  }
}
