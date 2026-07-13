{{/*
Chart-scoped aliases delegating to the yay-tsa-common naming helpers.
*/}}
{{- define "yay-tsa.name" -}}
{{- include "yay-tsa-common.name" . }}
{{- end }}

{{- define "yay-tsa.fullname" -}}
{{- include "yay-tsa-common.fullname" . }}
{{- end }}

{{- define "yay-tsa.chart" -}}
{{- include "yay-tsa-common.chart" . }}
{{- end }}

{{- define "yay-tsa.labels" -}}
{{- include "yay-tsa-common.labels" . }}
{{- end }}

{{- define "yay-tsa.selectorLabels" -}}
{{- include "yay-tsa-common.selectorLabels" . }}
{{- end }}
