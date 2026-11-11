# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
# The official image renders /etc/nginx/templates/*.template with envsubst at start-up.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV CLINICAL_API_URL=http://clinical-api:8082 \
    COPILOT_URL=http://copilot:8083 \
    NGINX_ENVSUBST_FILTER=^(CLINICAL_API_URL|COPILOT_URL)$
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/healthz || exit 1
