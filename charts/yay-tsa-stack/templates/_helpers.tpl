{{- define "yay-tsa-stack.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "yay-tsa-stack.dbSecretName" -}}
{{- ((.Values.global | default dict).yaytsaDatabase | default dict).secretName | default "yaytsa-db" }}
{{- end }}
