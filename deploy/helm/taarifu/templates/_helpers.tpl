{{/*
=============================================================================
Taarifu Helm chart — template helpers (names, labels, selectors).
Grounding: deploy/README §5, ARCHITECTURE §9. Keep DRY: one source of truth
for the names/labels every manifest shares.
=============================================================================
*/}}

{{/* Base name, honouring fullnameOverride / nameOverride. */}}
{{- define "taarifu.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "taarifu.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Chart label (name-version). */}}
{{- define "taarifu.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Common labels stamped on every object. */}}
{{- define "taarifu.labels" -}}
helm.sh/chart: {{ include "taarifu.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: taarifu
taarifu.io/environment: {{ .Values.environment | quote }}
{{ include "taarifu.selectorLabels" . }}
{{- end -}}

{{- define "taarifu.selectorLabels" -}}
app.kubernetes.io/name: {{ include "taarifu.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Per-component name + selector (backend / web-admin / db / redis). */}}
{{- define "taarifu.componentName" -}}
{{- printf "%s-%s" (include "taarifu.fullname" .root) .component | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Resolve the JDBC URL: in-cluster DB Service vs external managed host. */}}
{{- define "taarifu.dbUrl" -}}
{{- if .Values.postgresql.deployInCluster -}}
jdbc:postgresql://{{ include "taarifu.fullname" . }}-db:5432/{{ .Values.postgresql.database }}
{{- else -}}
jdbc:postgresql://{{ .Values.postgresql.external.host }}:{{ .Values.postgresql.external.port }}/{{ .Values.postgresql.external.database }}
{{- end -}}
{{- end -}}

{{- define "taarifu.dbUser" -}}
{{- if .Values.postgresql.deployInCluster -}}
{{ .Values.postgresql.username }}
{{- else -}}
{{ .Values.postgresql.external.username }}
{{- end -}}
{{- end -}}

{{/* Resolve Redis host: in-cluster Service vs external. */}}
{{- define "taarifu.redisHost" -}}
{{- if .Values.redis.deployInCluster -}}
{{ include "taarifu.fullname" . }}-redis
{{- else -}}
{{ .Values.redis.external.host }}
{{- end -}}
{{- end -}}

{{- define "taarifu.redisPort" -}}
{{- if .Values.redis.deployInCluster -}}6379{{- else -}}{{ .Values.redis.external.port }}{{- end -}}
{{- end -}}
