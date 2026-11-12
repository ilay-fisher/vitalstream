// VitalStream declarative pipeline: lint -> unit -> integration -> build images -> e2e.
// Every command is the one a developer runs locally (./mvnw, uv, npm, make), so a red stage
// reproduces on a laptop. Needs an agent with Docker (Testcontainers + compose), JDK 21, uv and Node 22.
// Run it without a Jenkins server: see docs/delivery.md (jenkinsfile-runner).

pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 90, unit: 'MINUTES')
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  environment {
    JAVA_HOME = '/opt/java/openjdk'
    PATH = "${env.HOME}/.local/bin:${env.JAVA_HOME}/bin:${env.PATH}"
    IMAGE_PREFIX = 'ghcr.io/ilay-fisher/vitalstream'
    IMAGE_TAG = "${env.GIT_COMMIT ? env.GIT_COMMIT.take(12) : 'local'}"
  }

  stages {
    stage('Lint') {
      parallel {
        stage('ingest-gateway') {
          steps { dir('services/ingest-gateway') { sh './mvnw -B -q spotless:check' } }
        }
        stage('clinical-api') {
          steps { dir('services/clinical-api') { sh './mvnw -B -q spotless:check' } }
        }
        stage('stream-processor') {
          steps {
            dir('services/stream-processor') {
              sh 'uv sync --frozen && uv run ruff check . && uv run ruff format --check . && uv run mypy'
            }
          }
        }
        stage('copilot') {
          steps {
            dir('services/copilot') {
              sh 'uv sync --frozen && uv run ruff check . && uv run ruff format --check . && uv run mypy --strict src'
            }
          }
        }
        stage('simulator') {
          steps { dir('tools/simulator') { sh 'uv sync --frozen && make lint' } }
        }
        stage('dashboard') {
          steps { dir('web/dashboard') { sh 'npm ci && npm run typecheck && npm run lint' } }
        }
        stage('e2e helpers') {
          steps {
            dir('e2e') {
              sh 'uv sync --frozen && uv run ruff check . && uv run ruff format --check . && uv run mypy --strict src'
            }
          }
        }
      }
    }

    stage('Unit') {
      parallel {
        stage('contracts') {
          steps { dir('contracts') { sh 'uv run pytest -q' } }
        }
        stage('ingest-gateway') {
          steps { dir('services/ingest-gateway') { sh './mvnw -B test' } }
        }
        stage('clinical-api') {
          steps { dir('services/clinical-api') { sh './mvnw -B test' } }
        }
        stage('stream-processor') {
          steps { dir('services/stream-processor') { sh 'uv run pytest -m "not integration" --cov' } }
        }
        stage('copilot') {
          steps { dir('services/copilot') { sh 'uv run pytest' } }
        }
        stage('simulator') {
          steps { dir('tools/simulator') { sh 'make test' } }
        }
        stage('dashboard') {
          steps { dir('web/dashboard') { sh 'npm test' } }
        }
        stage('e2e helpers') {
          steps { dir('e2e') { sh 'uv run pytest' } }
        }
      }
    }

    // Testcontainers-backed suites. Heavy, so the Java and Python groups run side by side but
    // each service keeps its own containers (host port blocks differ per service).
    stage('Integration') {
      environment {
        TESTCONTAINERS_RYUK_DISABLED = 'false'
      }
      parallel {
        stage('ingest-gateway') {
          steps { dir('services/ingest-gateway') { sh './mvnw -B verify' } }
        }
        stage('clinical-api') {
          steps { dir('services/clinical-api') { sh './mvnw -B verify' } }
        }
        stage('stream-processor') {
          steps { dir('services/stream-processor') { sh 'uv run pytest -m integration' } }
        }
        stage('copilot') {
          steps { dir('services/copilot') { sh 'uv run pytest -m integration' } }
        }
        stage('simulator') {
          steps { dir('tools/simulator') { sh 'make integration' } }
        }
        stage('dashboard') {
          steps {
            dir('web/dashboard') {
              sh 'npx playwright install --with-deps chromium && npm run e2e'
            }
          }
        }
      }
    }

    stage('Build images') {
      parallel {
        stage('ingest-gateway') {
          steps { sh 'docker build -t "$IMAGE_PREFIX-ingest-gateway:$IMAGE_TAG" services/ingest-gateway' }
        }
        stage('stream-processor') {
          steps { sh 'docker build -t "$IMAGE_PREFIX-stream-processor:$IMAGE_TAG" services/stream-processor' }
        }
        stage('clinical-api') {
          steps { sh 'docker build -t "$IMAGE_PREFIX-clinical-api:$IMAGE_TAG" services/clinical-api' }
        }
        stage('copilot') {
          steps { sh 'docker build -t "$IMAGE_PREFIX-copilot:$IMAGE_TAG" services/copilot' }
        }
        stage('simulator') {
          steps { sh 'docker build -t "$IMAGE_PREFIX-simulator:$IMAGE_TAG" tools/simulator' }
        }
        stage('dashboard') {
          steps { sh 'docker build -t "$IMAGE_PREFIX-dashboard:$IMAGE_TAG" web/dashboard' }
        }
      }
    }

    // One stack for the whole platform, so this stage is not parallel. Tears everything down itself.
    stage('E2E') {
      steps {
        dir('e2e') {
          sh 'uv sync --frozen'
          sh 'E2E_TEARDOWN=all uv run pytest -m e2e -o addopts="" -v -s'
        }
      }
    }
  }

  post {
    always {
      junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml'
      sh 'make down || true'
    }
  }
}
