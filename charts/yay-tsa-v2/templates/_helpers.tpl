{{/*
Chart-scoped aliases delegating to the yay-tsa-common naming helpers.
*/}}
{{- define "yay-tsa-v2.name" -}}
{{- include "yay-tsa-common.name" . }}
{{- end }}

{{- define "yay-tsa-v2.fullname" -}}
{{- include "yay-tsa-common.fullname" . }}
{{- end }}

{{- define "yay-tsa-v2.chart" -}}
{{- include "yay-tsa-common.chart" . }}
{{- end }}

{{- define "yay-tsa-v2.labels" -}}
{{- include "yay-tsa-common.labels" . }}
{{- end }}

{{- define "yay-tsa-v2.selectorLabels" -}}
{{- include "yay-tsa-common.selectorLabels" . }}
{{- end }}
