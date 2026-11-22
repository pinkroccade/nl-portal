{{/* Name of the chart, nameOverride respected. */}}
{{- define "nl-portal.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified base name; fullnameOverride respected. Truncated so that "-frontend" still fits in 63 characters. */}}
{{- define "nl-portal.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 54 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 54 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 54 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Name of a component resource: <fullname>-backend or <fullname>-frontend. Argument: dict "ctx" . "component" "backend". */}}
{{- define "nl-portal.componentName" -}}
{{- printf "%s-%s" (include "nl-portal.fullname" .ctx) .component -}}
{{- end -}}

{{- define "nl-portal.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Selector labels; never changes after the first install. Argument: dict "ctx" . "component" "backend". */}}
{{- define "nl-portal.selectorLabels" -}}
app.kubernetes.io/name: {{ include "nl-portal.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Kubernetes recommended labels. Argument: dict "ctx" . "component" "backend". */}}
{{- define "nl-portal.labels" -}}
{{ include "nl-portal.selectorLabels" . }}
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
app.kubernetes.io/part-of: nl-portal
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
helm.sh/chart: {{ include "nl-portal.chart" .ctx }}
{{- end -}}

{{/* Image reference. global.imageRegistry wins over the registry of the image. Argument: dict "ctx" . "image" .Values.backend.image. */}}
{{- define "nl-portal.image" -}}
{{- $registry := default .image.registry .ctx.Values.global.imageRegistry -}}
{{- $repository := .image.repository -}}
{{- if .image.digest -}}
{{- printf "%s/%s@%s" $registry $repository .image.digest -}}
{{- else -}}
{{- printf "%s/%s:%s" $registry $repository (default .ctx.Chart.AppVersion .image.tag) -}}
{{- end -}}
{{- end -}}

{{/* imagePullSecrets from global.imagePullSecrets; entries are a name or {name: ...}. */}}
{{- define "nl-portal.imagePullSecrets" -}}
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
{{- range . }}
  - name: {{ if kindIs "map" . }}{{ .name }}{{ else }}{{ . }}{{ end }}
{{- end }}
{{- end }}
{{- end -}}

{{- define "nl-portal.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "nl-portal.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Name of the tenant-files ConfigMap: the chart's own or an existing one. */}}
{{- define "nl-portal.tenantsConfigMap" -}}
{{- if .Values.tenancy.existingConfigMap -}}
{{- .Values.tenancy.existingConfigMap -}}
{{- else -}}
{{- printf "%s-tenants" (include "nl-portal.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/*
Checks that cannot be expressed in values.schema.json. Included from the ConfigMap so every render runs them.
Module dependencies come from site/hosting-en-configuratie/module-dependency-guide.md.
*/}}
{{- define "nl-portal.validate" -}}
{{- $m := .Values.backend.modules -}}
{{- $deps := dict "taak" (list "objectenapi") "berichten" (list "objectenapi" "documentenapis") "formulieren" (list "objectenapi") "product" (list "objectenapi") "prefill" (list "objectenapi") "zakenapi" (list "catalogiapi") -}}
{{- range $module := keys $deps | sortAlpha -}}
{{- if (index $m $module).enabled -}}
{{- range $needed := index $deps $module -}}
{{- if not (index $m $needed).enabled -}}
{{- fail (printf "backend.modules.%s.enabled needs backend.modules.%s.enabled (see the module dependencies in the chart README)" $module $needed) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if and (not .Values.tenancy.existingConfigMap) (not .Values.tenancy.tenants) -}}
{{- fail "tenancy.tenants (tenant files by file name) or tenancy.existingConfigMap is required: the backend does not start without tenant files" -}}
{{- end -}}
{{- if and .Values.ingress.enabled (not .Values.ingress.hosts) -}}
{{- fail "ingress.hosts must list at least one host when ingress.enabled is true (the tenant follows from the Host header)" -}}
{{- end -}}
{{- end -}}
