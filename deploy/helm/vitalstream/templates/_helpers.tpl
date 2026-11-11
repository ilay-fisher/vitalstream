{{/*
All helpers take a context dict: {root: $, name: <service key>, svc: <service values>}.
*/}}

{{/* Resource name of a service: optional global prefix plus the service key. */}}
{{- define "vs.name" -}}
{{- printf "%s%s" (default "" .root.Values.global.namePrefix) .name -}}
{{- end -}}

{{/* Labels that never change between upgrades (Deployment selector, Service selector). */}}
{{- define "vs.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "vs.labels" -}}
{{ include "vs.selectorLabels" . }}
app.kubernetes.io/component: {{ .name }}
app.kubernetes.io/part-of: vitalstream
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
{{- end -}}

{{/* Name of the Secret holding every credential. */}}
{{- define "vs.secretName" -}}
{{- default (printf "%s-secrets" .Release.Name) .Values.secrets.existingSecret -}}
{{- end -}}

{{- define "vs.image" -}}
{{- $tag := default .root.Chart.AppVersion .svc.image.tag -}}
{{- if .root.Values.global.imageRegistry -}}
{{- printf "%s/%s:%s" .root.Values.global.imageRegistry .svc.image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .svc.image.repository $tag -}}
{{- end -}}
{{- end -}}

{{/*
Host header allowlist (CONTRACTS 4.1). Contains the Service DNS names, localhost, the probe Host,
ingress hosts and user extras. Spring filters match host:port literally, so java services also get
port variants; Starlette ignores ports, so python services get names only.
*/}}
{{- define "vs.allowedHosts" -}}
{{- $root := .root -}}
{{- $svc := .svc -}}
{{- $f := include "vs.name" . -}}
{{- $ns := $root.Release.Namespace -}}
{{- $bases := list "localhost" "127.0.0.1" $f (printf "%s.%s" $f $ns) (printf "%s.%s.svc" $f $ns) (printf "%s.%s.svc.%s" $f $ns $root.Values.global.clusterDomain) -}}
{{- if ne $f .name -}}{{- $bases = append $bases .name -}}{{- end -}}
{{- $bases = append $bases $root.Values.global.probeHost -}}
{{- $hosts := list -}}
{{- range $bases -}}
{{- $hosts = append $hosts . -}}
{{- if eq $svc.kind "java" -}}{{- $hosts = append $hosts (printf "%s:%v" . $svc.port) -}}{{- end -}}
{{- end -}}
{{- if $root.Values.ingress.enabled -}}
{{- range $root.Values.ingress.hosts -}}{{- $hosts = append $hosts .host -}}{{- end -}}
{{- end -}}
{{- $hosts = concat $hosts $root.Values.global.extraAllowedHosts $svc.extraAllowedHosts -}}
{{- join "," (uniq $hosts) -}}
{{- end -}}

{{/*
Non-secret environment of a service as JSON (rendered into the ConfigMap). Entries from
`config` are passed through tpl; entries that render empty are dropped so optional endpoints
(e.g. AWS_ENDPOINT_URL) are simply absent.
*/}}
{{- define "vs.config" -}}
{{- $root := .root -}}
{{- $svc := .svc -}}
{{- $d := dict "LOG_LEVEL" $root.Values.global.logLevel -}}
{{- if ne $svc.kind "web" -}}
{{- $_ := set $d "APP_ENV" $root.Values.global.appEnv -}}
{{- $_ := set $d "ALLOWED_HOSTS" (include "vs.allowedHosts" .) -}}
{{- end -}}
{{- if eq $svc.kind "java" -}}{{- $_ := set $d "SERVER_PORT" (toString $svc.port) -}}{{- end -}}
{{- if eq $svc.kind "python" -}}{{- $_ := set $d (default "PORT" $svc.portEnv) (toString $svc.port) -}}{{- end -}}
{{- range $k, $v := $svc.config -}}
{{- $r := tpl (toString $v) $root -}}
{{- if $r -}}{{- $_ := set $d $k $r -}}{{- end -}}
{{- end -}}
{{- toJson $d -}}
{{- end -}}

{{/* One probe. Context: {probe: <values>, path: <string>, root: $, defaults: <dict>}. */}}
{{- define "vs.probe" -}}
httpGet:
  path: {{ .path }}
  port: http
  httpHeaders:
    - name: Host
      value: {{ .root.Values.global.probeHost | quote }}
initialDelaySeconds: {{ default .defaults.initialDelaySeconds .probe.initialDelaySeconds }}
periodSeconds: {{ default .defaults.periodSeconds .probe.periodSeconds }}
timeoutSeconds: {{ default .defaults.timeoutSeconds .probe.timeoutSeconds }}
failureThreshold: {{ default .defaults.failureThreshold .probe.failureThreshold }}
{{- end -}}
