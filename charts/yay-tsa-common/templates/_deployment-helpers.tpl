{{/*
yay-tsa-common deployment helper templates.

Each helper produces content WITHOUT a leading indent. Call sites are
responsible for placing the include with `indent N` or `nindent N` so the
rendered output matches the surrounding pod-spec indentation.
*/}}

{{/*
imagePullSecrets block for a PodSpec.

Includes the surrounding `{{- with ... }}` guard, so when the values list
is empty/nil nothing is rendered.

Output (at column 0, conditional on .Values.imagePullSecrets):
  imagePullSecrets:
    - <toYaml of .Values.imagePullSecrets>

Typical call site (inside a Pod spec at 6-space indent):
      {{- include "yay-tsa-common.deployment.imagePullSecrets" . | nindent 6 }}
*/}}
{{- define "yay-tsa-common.deployment.imagePullSecrets" -}}
{{- with .Values.imagePullSecrets -}}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end -}}
{{- end -}}

{{/*
Pod-level securityContext block driven by a `toYaml` dict (matches the
audio-separator and feature-extractor pattern).

Parameters (dict):
  securityContext — map to render under `securityContext:`. If empty/nil
                    nothing is rendered.

Typical call site (inside a Pod spec at 6-space indent):
      {{- include "yay-tsa-common.deployment.podSecurityContext" (dict "securityContext" .Values.audioSeparator.podSecurityContext) | nindent 6 }}
*/}}
{{- define "yay-tsa-common.deployment.podSecurityContext" -}}
{{- with .securityContext -}}
securityContext:
  {{- toYaml . | nindent 2 }}
{{- end -}}
{{- end -}}

{{/*
Standard liveness + readiness HTTP probes block for a container.

Parameters (dict):
  port        — port name/number for httpGet. When set, overrides any per-probe
                `port` field (use this to force a port name like "http"). When
                nil, the helper falls back to each probe spec's own `port`.
  liveness    — probe spec (path, optional port, initialDelaySeconds,
                periodSeconds, timeoutSeconds, failureThreshold)
  readiness   — probe spec, same shape

Typical call site (inside a container spec at 10-space indent):
          {{- include "yay-tsa-common.deployment.standardProbes" (dict "port" "http" "liveness" .Values.audioSeparator.probes.liveness "readiness" .Values.audioSeparator.probes.readiness) | nindent 10 }}
*/}}
{{- define "yay-tsa-common.deployment.standardProbes" -}}
livenessProbe:
  httpGet:
    path: {{ .liveness.path }}
    port: {{ .port | default .liveness.port }}
  initialDelaySeconds: {{ .liveness.initialDelaySeconds }}
  periodSeconds: {{ .liveness.periodSeconds }}
  timeoutSeconds: {{ .liveness.timeoutSeconds }}
  failureThreshold: {{ .liveness.failureThreshold }}
readinessProbe:
  httpGet:
    path: {{ .readiness.path }}
    port: {{ .port | default .readiness.port }}
  initialDelaySeconds: {{ .readiness.initialDelaySeconds }}
  periodSeconds: {{ .readiness.periodSeconds }}
  timeoutSeconds: {{ .readiness.timeoutSeconds }}
  failureThreshold: {{ .readiness.failureThreshold }}
{{- end -}}

{{/*
Standard tmp + optional read-only media volumes used by audio-separator
and feature-extractor.

Parameters (dict):
  media   — { enabled: bool, hostPath: string }
  stems   — optional { enabled: bool, claimName: string } (audio-separator only)

Typical call site (inside a Pod spec at 6-space indent):
      {{- include "yay-tsa-common.deployment.standardVolumes" (dict "media" .Values.audioSeparator.media "stems" (dict "enabled" .Values.audioSeparator.stemsStorage.enabled "claimName" (printf "%s-audio-separator-stems" (include "yay-tsa.fullname" .)))) | nindent 6 }}
*/}}
{{- define "yay-tsa-common.deployment.standardVolumes" -}}
volumes:
  - name: tmp-volume
    emptyDir: {}
  {{- if .media.enabled }}
  - name: media
    hostPath:
      path: {{ .media.hostPath }}
      type: Directory
  {{- end }}
  {{- if and .stems .stems.enabled }}
  - name: stems
    persistentVolumeClaim:
      claimName: {{ .stems.claimName }}
  {{- end }}
{{- end -}}
