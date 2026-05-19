{{/*
yay-tsa-common ingress helper templates.

Each helper produces content WITHOUT a leading indent. Call sites are
responsible for placing the include with `nindent N`.
*/}}

{{/*
Standard Ingress TLS block.

Parameters (dict):
  tls — list of { hosts: [string], secretName: string } (typically
        passed as `.Values.ingress.tls`). If empty/nil nothing is rendered.

Typical call site (inside an Ingress spec at 2-space indent):
  {{- include "yay-tsa-common.ingress.tls" (dict "tls" .Values.ingress.tls) | nindent 2 }}
*/}}
{{- define "yay-tsa-common.ingress.tls" -}}
{{- if .tls -}}
tls:
  {{- range .tls }}
  - hosts:
      {{- range .hosts }}
      - {{ . | quote }}
      {{- end }}
    secretName: {{ .secretName }}
  {{- end }}
{{- end -}}
{{- end -}}
