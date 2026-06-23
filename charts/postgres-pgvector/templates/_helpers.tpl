{{/*
Deterministic name: "<release>-postgres". Kept release-scoped (not chart-name-scoped)
so an umbrella can predict the Service/host name as "{{ .Release.Name }}-postgres".
*/}}
{{- define "postgres-pgvector.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-postgres" .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "postgres-pgvector.labels" -}}
app.kubernetes.io/name: postgres-pgvector
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/component: database
{{- end }}

{{- define "postgres-pgvector.selectorLabels" -}}
app.kubernetes.io/name: postgres-pgvector
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: database
{{- end }}

{{/*
Name of the Secret carrying POSTGRES_HOST/PORT/USER/PASSWORD/DB. Shared with the
backend via global.yaytsaDatabase.secretName (the umbrella renders it).
*/}}
{{- define "postgres-pgvector.secretName" -}}
{{- $g := (.Values.global | default dict).yaytsaDatabase | default dict }}
{{- $g.secretName | default "yaytsa-db" }}
{{- end }}
