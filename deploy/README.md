# deploy: Helm chart, kind environment, Terraform

Purpose: run the five VitalStream application services on Kubernetes, locally in kind and on AWS (EKS), with the same chart.
Infrastructure (Kafka, Postgres, Redis, Cassandra, Elasticsearch, RabbitMQ) is **not** part of the chart: kind gets lightweight dev infra from `deploy/kind`, AWS gets managed services from `deploy/terraform/aws`.

```
deploy/
  helm/vitalstream/        umbrella chart (+ values-kind.yaml, tests/)
  kind/                    kind-config.yaml, infra.yaml, up.sh, smoke.sh, down.sh
  terraform/aws/           root module + modules/{vpc,eks,rds,msk,elasticache,sqs,s3,ecr,iam}
  terraform/localstack/    S3 + SQS only, reuses the AWS modules, applies against LocalStack
```

## Prerequisites

helm 3/4, kubectl, kind, docker, openssl; for tests also the `helm-unittest` plugin, `kubeconform`, `terraform >= 1.7`. On Colima: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.

## kind quick start

```bash
deploy/kind/up.sh        # cluster vitalstream-platform, build + load images, dev infra, helm install
deploy/kind/smoke.sh     # port-forwards 26081/26083 and curls the health endpoints
deploy/kind/down.sh      # kind delete cluster
```

`up.sh` builds the images that have a Dockerfile on main (ingest-gateway, copilot), generates random 48-character secrets (so the `APP_ENV=prod` guard passes), and installs the chart with `values-kind.yaml`. `stream-processor`, `clinical-api` and `dashboard` are `enabled: false` there until their images exist (clinical-api and stream-processor also need Cassandra/RabbitMQ/LocalStack, which the kind infra does not include). Set `BUILD=0` to reuse local images.

Dev infra uses the official images pinned in `infra/compose.yaml` as plain manifests (`kind/infra.yaml`), not Bitnami charts, because the free Bitnami images left Docker Hub in 2025. It has no persistence and no TLS.

## Chart values

| Key | Default | Meaning |
|---|---|---|
| `global.imageRegistry` | `""` | Registry prefix (ECR host on AWS) |
| `global.namePrefix` | `""` | Prefix for all resource names. Empty keeps CONTRACTS service DNS names |
| `global.appEnv` | `prod` | Activates the secret startup guard: every secret must be set, non-default and at least 32 characters, otherwise the service refuses to start |
| `global.probeHost` | `localhost` | Host header sent by kubelet probes; always in `ALLOWED_HOSTS` |
| `global.extraAllowedHosts` | `[]` | Extra Host values accepted by all services |
| `infra.*` | in-cluster names | Endpoints of Kafka, Redis, Postgres, Cassandra, Elasticsearch, RabbitMQ, `awsEndpointUrl`, `awsRegion` |
| `secrets.existingSecret` | `""` | Secret you created (default name `<release>-secrets`). Keys: `INGEST_API_KEY COPILOT_ADMIN_TOKEN MCP_TOKEN LOG_PSEUDONYM_KEY POSTGRES_PASSWORD RABBITMQ_PASSWORD`, optional `ANTHROPIC_API_KEY` |
| `secrets.create` / `secrets.values` | `false` / `{}` | Let the chart render the Secret from `--set secrets.values.X=...` (fails below 32 chars). Prefer External Secrets |
| `services.<name>.enabled` | `true` | Toggle a service |
| `services.<name>.image.{repository,tag}` | `vitalstream/<name>`, appVersion | |
| `services.<name>.replicaCount` | 1-2 | Ignored when autoscaling is on |
| `services.<name>.resources` | small requests, memory limits | |
| `services.<name>.autoscaling` | on for ingest-gateway, clinical-api | autoscaling/v2, CPU 70 % |
| `services.<name>.pdb` | `maxUnavailable: 1` | PodDisruptionBudget |
| `services.<name>.config` | per service | Non-secret env (ConfigMap). Values go through `tpl`; empty results are omitted |
| `services.<name>.serviceAccount.annotations` | `{}` | Put the IRSA `eks.amazonaws.com/role-arn` here |
| `services.<name>.networkPolicy.*` | see values.yaml | Allowed callers, infra ports, internet egress |
| `networkPolicy.enabled` | `true` | Default deny plus per-service allows plus DNS to kube-dns |
| `networkPolicy.infra.{namespaceSelector,cidrs}` | `vitalstream-infra`, `[]` | Where infra lives; add the VPC CIDR on AWS |
| `networkPolicy.clusterCIDRs` | `[]` | Pod and Service CIDRs, excluded from every internet rule |
| `ingress.*` | disabled | Optional Ingress; path to service map in values |

Every pod: `runAsNonRoot` with numeric uid 10001 (the images use named users, which kubelet cannot verify), `seccompProfile: RuntimeDefault`, no privilege escalation, `capabilities.drop: [ALL]`, `readOnlyRootFilesystem` with emptyDir mounts for `/tmp` (nginx also `/var/cache/nginx`, `/var/run`), no service account token mount.

Probes: Java `/actuator/health/liveness` and `/readiness`, Python `/health`, dashboard `/`; plus a startup probe. All probes send `Host: localhost`, and `ALLOWED_HOSTS` contains the Service DNS names (`name`, `name.ns`, `.svc`, `.svc.cluster.local`, with `:port` variants for Spring, names only for Starlette), so kubelet and in-cluster calls pass the Host allowlist. `kubectl port-forward` sends `Host: localhost:<port>`, so add that to `extraAllowedHosts` (done in values-kind).

## Tests

```bash
helm lint deploy/helm/vitalstream
helm unittest deploy/helm/vitalstream
helm template vs deploy/helm/vitalstream | kubeconform -strict -summary
for m in s3 sqs ecr iam eks msk elasticache; do (cd deploy/terraform/aws/modules/$m && terraform init -backend=false && terraform test); done
```

CI: `.github/workflows/platform.yml` (paths filter `deploy/**`).

## Terraform layout

```
            +-------------------------- vpc ---------------------------+
            | public subnets (NAT) | private subnets | S3 + SQS endpoints|
            +--------+-----------------------+------------------------+
                     |                       |
                    eks ----(cluster SG)---> rds (Postgres 16, pgvector)
                     |  \                    msk (Kafka, TLS)
                     |   \-- OIDC --> iam    elasticache (Redis, TLS)
                     |                 |
                     |          roles for sqs / s3 (IRSA)
           ecr (per service)      sqs (alert-escalations + DLQ)   s3 (vitalstream-archive)
```

`terraform/aws` is plan/validate only in this repository: **nothing here has been applied to AWS**. Copy `terraform.tfvars.example`, uncomment the S3 backend in `main.tf` after creating the state bucket. `terraform/localstack` is applied against LocalStack (default `http://localhost:4566`, override with `-var endpoint=...`).

pgvector on RDS: no parameter group change is needed on PostgreSQL 15.2+; run `CREATE EXTENSION IF NOT EXISTS vector;` once as the master user (`infra/postgres/init.sql` does this).

## Security notes

- Secrets are never in values files; production should sync them from Secrets Manager (RDS generates its master password there) with External Secrets or CSI.
- Default-deny NetworkPolicy for ingress and egress; DNS egress only to kube-dns in kube-system.
- Internet egress (`services.<n>.networkPolicy.egress.internet`) is an ipBlock `0.0.0.0/0` **with `except`** for RFC1918, link-local (cloud metadata), CGNAT, loopback and `networkPolicy.clusterCIDRs`, so it cannot reach internal 443 endpoints. It is on for stream-processor and clinical-api (S3/SQS), **off for copilot**, gateway and dashboard. Enable it for copilot only with `LLM_PROVIDER=anthropic`.
- Prefer VPC endpoints for S3 (gateway) and SQS (interface): the vpc module creates both, after which the internet rule can be switched off for those services.
- Vanilla NetworkPolicy cannot filter by FQDN. For copilot in production restrict egress to `api.anthropic.com` with an egress proxy or a Cilium/Calico FQDN policy; copilot runs tool calls driven by model output, so it is the highest-risk workload.
- Node egress: the Terraform leaves the EKS-created cluster security group on its default allow-all egress; tighten with a custom node security group once the required endpoints are known.
- MSK: SASL/IAM only (port 9098, TLS, no plaintext, no unauthenticated). Each service's IRSA role gets `kafka-cluster:*` actions scoped to its own topics and groups (ingest-gateway writes `vitals.raw`; stream-processor reads `vitals.raw`, writes `alerts.detected`; clinical-api reads `alerts.detected`, writes `alerts.detected.dlq`; group ids assumed to start with the service name). Clients need the AWS MSK IAM auth library and `security.protocol=SASL_SSL`, `sasl.mechanism=AWS_MSK_IAM` (for Spring via `services.<n>.config`): not yet in the services, see known gaps.
- ElastiCache: TLS plus AUTH token generated by `random_password` and stored in Secrets Manager (also present in Terraform state, so use an encrypted remote backend). `infra.redisUrl` must be `rediss://:<token>@host:6379`.
- EKS: the Kubernetes API is private by default; if `eks_endpoint_public_access` is on, `eks_public_access_cidrs` must be non-empty and `0.0.0.0/0` is rejected. Nodes use a launch template with IMDSv2 required and hop limit 1; the CNI policy is on a dedicated IRSA role for `kube-system/aws-node`, not on the node role. Secrets are KMS-encrypted.

## Known gaps

- Ports/health for stream-processor (8084) and dashboard (3000, `/`) and the S3 bucket / SQS queue env names are not in CONTRACTS.md; clinical-api DB credential variable names (`POSTGRES_USER`/`POSTGRES_PASSWORD`) and the broker URL form are assumptions.
- No Cassandra, Elasticsearch or RabbitMQ in Terraform (use Amazon Keyspaces / OpenSearch / Amazon MQ, or in-cluster operators); no TLS/auth for Redis in kind.
- Kafka topics are not created by the chart or by Terraform (kind auto-creates); no real end-user auth (CONTRACTS known gap).
- `tflint` is not installed (not available through Homebrew and an automated download was blocked); only `terraform fmt`, `validate` and `test` run.
- Services do not yet speak SASL/IAM to Kafka or send a Redis AUTH token (contract change needed).
- HPAs need metrics-server (absent in kind, so disabled there). Single NAT gateway by default.
