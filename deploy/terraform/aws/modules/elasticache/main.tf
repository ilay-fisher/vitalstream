resource "aws_elasticache_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-redis"
  description = "Redis from the EKS nodes only"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from EKS nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.allowed_security_group_id]
  }

  tags = merge(var.tags, { Name = "${var.name}-redis" })
}

# Redis holds idempotency keys, rate-limit buckets and caches only: losing it is recoverable.
resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "VitalStream idempotency, rate limit and cache"
  engine               = "redis"
  engine_version       = var.engine_version
  node_type            = var.node_type
  port                 = 6379

  num_cache_clusters         = var.num_cache_clusters
  automatic_failover_enabled = var.num_cache_clusters > 1
  multi_az_enabled           = var.num_cache_clusters > 1

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  at_rest_encryption_enabled = true
  # Clients must use rediss:// (TLS). Set infra.redisUrl accordingly.
  transit_encryption_enabled = true
  auth_token                 = var.auth_token

  tags = var.tags
}
