# nl-portal

Helm chart for NL Portal Pink Edition: the backend and the frontend as separate Deployments and Services.

The chart contains only what the application needs to run. It is meant as a child chart of a base chart (Argo CD, ingress,
NetworkPolicy and ExternalSecret live there); a plain `helm install` works as a reference.

## What the chart creates

| Resource | When |
|---|---|
| Deployment and Service `<release>-nl-portal-backend` | always |
| Deployment and Service `<release>-nl-portal-frontend` | always |
| ConfigMap `<release>-nl-portal-backend` (non-secret environment), `...-frontend` | always |
| ConfigMap `<release>-nl-portal-tenants` (tenant files) | when `tenancy.tenants` is set |
| ConfigMap `<release>-nl-portal-flags` (feature flags, flagd JSON) | when `backend.featureFlags` is set |
| ServiceAccount | `serviceAccount.create` |
| Ingress | `ingress.enabled` (default off) |

The chart creates **no** Secret, NetworkPolicy, PodDisruptionBudget, HorizontalPodAutoscaler, ExternalSecret, ServiceMonitor
or Argo CD resource, and bundles no subcharts. With `nameOverride` the names follow `<release>-<nameOverride>-<component>`;
`fullnameOverride` gives `<fullnameOverride>-backend` and `-frontend`. Every resource carries the Kubernetes recommended
labels (`app.kubernetes.io/name`, `instance`, `version`, `component`, `part-of`, `managed-by`) and `helm.sh/chart`.

## Prerequisites (not part of the chart)

- **Redis** for the sessions of the BFF (ADR 0022). Use an ACL user, no persistence, and TLS (`backend.redis.ssl`, default on).
  The backend is stateless in the sense of ADR 0013 (no database, no persistent state), but not free of state: the sessions
  live in Redis. Without a reachable Redis nobody can log in.
- **Keycloak** (own image with the mappers) with a realm per tenant and a `private_key_jwt` client; see `site/hosting-en-configuratie/keycloak.md`.
- The **sources** of the enabled modules (Open Zaak, Open Object, OpenKlant, OpenProduct, Haal Centraal, ClamAV).
- A **Secret** per component with the keys below, for example filled by External Secrets Operator.
- The **tenant files** and a **client key** per tenant (see below).

## Multi-tenant

NL Portal is multi-tenant: one release can carry one or more tenants. The tenant follows from the `Host` header; the
Ingress and the nginx of the frontend pass `Host` on unchanged.

- `tenancy.tenants` holds the tenant files by file name (`base.yaml` and `<oin>.yaml`); they contain secret *names*, never
  secrets. Use `tenancy.existingConfigMap` to bring your own ConfigMap.
- `tenancy.keys` lists the client key per tenant: a Secret per OIN, mounted at `/etc/nl-portal/sleutels/<oin>/clientsleutel.jwk.json`
  (`nl-portal.sleutelmap`). The tenant file refers to it with `authenticatie.sleutelBestand: <oin>/clientsleutel.jwk.json`.
- The secrets that tenant files refer to (`<oin>.zaken-secret`) are environment variables `NL_PORTAL_GEHEIMEN_<OIN>_<NAME>`
  in the backend Secret (see below).

## Secret keys

The chart takes one existing Secret per component. The Secret is mounted as environment variables; the keys below are fixed
and documented, and each key name can be overridden under `backend.secretKeys`.

### Backend (`backend.existingSecret`, required)

| Key (default name) | Needed when | Meaning |
|---|---|---|
| `REDIS_PASSWORD` | always | Password of the Redis ACL user |
| `REDIS_USERNAME` | optional | ACL user; overrides `backend.redis.username` |
| `REDIS_SSL` | optional | `true` or `false`; overrides `backend.redis.ssl` |
| `NLPORTAL_BEHEER_WACHTWOORDHASH` | `backend.beheer.enabled` | Password hash of the management user, with prefix (`{bcrypt}$2a$...`) |
| `NLPORTAL_CONFIG_BESLUITENAPI_PROPERTIES_SECRET` | `modules.besluitenapi.enabled` | Client secret of the Besluiten API |
| `NLPORTAL_CONFIG_OPENKLANT2_PROPERTIES_TOKEN` | `modules.openklant2.enabled` | API token of OpenKlant 2 |
| `NLPORTAL_CONFIG_OPENPRODUCT_PROPERTIES_TOKEN` | `modules.openproduct.enabled` | API token of OpenProduct |
| `NL_PORTAL_GEHEIMEN_<OIN>_<NAME>` | per tenant file | The secrets the tenant file refers to, for example `NL_PORTAL_GEHEIMEN_00000099000000000001_ZAKEN_SECRET`, `..._CATALOGIAPI_SECRET`, `..._OBJECTENAPI_TOKEN` (value `Token <token>`), `..._DOCUMENTEN_API_SECRET`, `..._VERIFICATIE_SECRET` |

The `NL_PORTAL_GEHEIMEN_*` keys depend on the tenants, so the whole Secret is exposed to the backend container as environment.
The other keys are referenced one by one: a missing key stops the pod (`CreateContainerConfigError`) instead of failing at runtime.
The chart cannot read the Secret (no `lookup`); it fails at render time on a missing `backend.existingSecret`. After a rotation
the pods only pick up the new value on restart: bump `backend.secretVersion` or use a secret reloader via `backend.podAnnotations`.

### Frontend

The frontend needs no secrets: its configuration (`GRAPHQL_URI`, `REST_URI`, `AUTO_IDLE_SESSION_LOGOUT`, `IDLE_TIMEOUT_MINUTES`)
is public. Use `frontend.extraEnv` for anything else.

## Ports, probes and the actuator

| Port | Container | In the Service |
|---|---|---|
| backend `http` 8080 | yes | yes (`backend.service.port`) |
| backend `management` 8091 (`backend.management.port`, `MANAGEMENT_SERVER_PORT`) | yes | **never** |
| frontend `http` 8081 | yes | yes (Service port 80) |

The probes use the health groups on the management port (`/actuator/health/liveness` and `/readiness`, plus a startup probe).
Do not publish the management port through an Ingress. The frontend nginx proxies `/graphql`, `/api`, `/oauth2`, `/login` and
`/logout` to the backend Service (`NL_PORTAL_BACKEND_UPSTREAM`, set by the chart).

## Pod Security

Both pods meet Pod Security "restricted" by default: `runAsNonRoot` with a numeric uid (1001 and 101), `readOnlyRootFilesystem`,
`allowPrivilegeEscalation: false`, all capabilities dropped and `seccompProfile: RuntimeDefault`. Writable paths are emptyDirs:
`/tmp` on both, plus `/etc/nginx/conf.d` on the frontend (the rendered nginx configuration). The ServiceAccount token is not mounted.

## Modules

Every module is off by default. A module needs its tenant configuration (`koppelingen` and `modules` in the tenant file); the
chart holds the switch and the non-secret settings that are still environment configuration. Render fails on a missing dependency:

| Module | Needs |
|---|---|
| `taak`, `formulieren`, `product`, `prefill` | `objectenapi` |
| `berichten` | `objectenapi`, `documentenapis` |
| `zakenapi` | `catalogiapi` |

`berichten` is switched on with the flag `ops.berichten.enabled` in `backend.featureFlags` and `modules.berichten` in the tenant
file; `backend.modules.berichten.enabled` only declares the intent so the dependency check runs.

## Values

Validated by `values.schema.json`: unknown keys and wrong types fail the render. The chart uses only `global.imageRegistry` and
`global.imagePullSecrets`; it ignores other globals from a parent chart. Use `backend.extraEnv` and `frontend.extraEnv` (EnvVar objects) for what is not modelled yet.
The table is generated from the comments in `values.yaml` by helm-docs (`mise run helm:docs`); do not edit it by hand.

<!-- #region values -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| backend.affinity | object | `{}` |  |
| backend.beheer.enabled | bool | `false` |  |
| backend.beheer.username | string | `"beheer"` |  |
| backend.containerSecurityContext.allowPrivilegeEscalation | bool | `false` |  |
| backend.containerSecurityContext.capabilities.drop[0] | string | `"ALL"` |  |
| backend.containerSecurityContext.readOnlyRootFilesystem | bool | `true` |  |
| backend.cors.allowedHeaders[0] | string | `"*"` |  |
| backend.cors.allowedMethods[0] | string | `"GET"` |  |
| backend.cors.allowedMethods[1] | string | `"POST"` |  |
| backend.cors.allowedMethods[2] | string | `"PUT"` |  |
| backend.cors.allowedMethods[3] | string | `"DELETE"` |  |
| backend.cors.allowedMethods[4] | string | `"OPTIONS"` |  |
| backend.cors.allowedOrigins | list | `[]` | Origins allowed cross-origin (NLPORTAL_SECURITY_CORS_0_*). Empty: no cross-origin requests (default, right for the BFF). |
| backend.existingSecret | string | `""` | Name of an existing Secret with the keys of `secretKeys` (and the tenant secrets `NL_PORTAL_GEHEIMEN_<OIN>_<NAME>`). Required. |
| backend.extraEnv | list | `[]` | Extra environment variables for what the chart does not model yet (EnvVar objects). |
| backend.extraVolumeMounts | list | `[]` |  |
| backend.extraVolumes | list | `[]` |  |
| backend.featureFlagContext.environment | string | `""` | Environment name (NLPORTAL_FEATUREFLAGS_CONTEXT_ENVIRONMENT) for `environment` targeting, for example `test` or `productie`; empty: not set. |
| backend.featureFlagContext.tenant | string | `""` | Tenant name (NLPORTAL_FEATUREFLAGS_CONTEXT_TENANT) for `tenant` targeting; empty: the application default (`default`). |
| backend.featureFlags | object | `{}` | Feature flags in flagd format; rendered into a ConfigMap and mounted (NLPORTAL_FEATUREFLAGS_FILE). Empty: the defaults compiled into the image apply (all off). |
| backend.frontendProperties | object | `{}` | Frontend properties served via GET /api/public/frontend (`NLPORTAL_FRONTEND_PROPERTIES_<NAME>`), by camelCase name. |
| backend.image.digest | string | `""` | When set, the digest replaces the tag. |
| backend.image.pullPolicy | string | `"IfNotPresent"` |  |
| backend.image.registry | string | `"ghcr.io"` |  |
| backend.image.repository | string | `"pinkroccade/nl-portal-backend"` |  |
| backend.image.tag | string | `""` | Defaults to the chart appVersion. |
| backend.javaOpts | string | `"-XX:MaxRAMPercentage=75.0"` | JAVA_TOOL_OPTIONS. |
| backend.keycloakBrokerUrl | string | `""` | Public base URL of Keycloak (KEYCLOAK_BROKER_URL); the issuer of every tenant must fall under it. Required. |
| backend.logLevel | string | `"INFO"` | LOGLEVEL of the root logger. |
| backend.management.port | int | `8091` |  |
| backend.modules.berichten.enabled | bool | `false` |  |
| backend.modules.besluitenapi.clientId | string | `""` |  |
| backend.modules.besluitenapi.enabled | bool | `false` |  |
| backend.modules.besluitenapi.url | string | `""` |  |
| backend.modules.catalogiapi.enabled | bool | `false` |  |
| backend.modules.clamav.enabled | bool | `false` |  |
| backend.modules.clamav.hostname | string | `""` |  |
| backend.modules.clamav.port | int | `3310` |  |
| backend.modules.dmn.enabled | bool | `false` |  |
| backend.modules.dmn.url | string | `""` |  |
| backend.modules.documentenapis.enabled | bool | `false` |  |
| backend.modules.formulieren.enabled | bool | `false` |  |
| backend.modules.formulieren.formDefinitionObjectTypeUrl | string | `""` |  |
| backend.modules.haalcentraalBrp.enabled | bool | `false` |  |
| backend.modules.kvk.enabled | bool | `false` |  |
| backend.modules.objectenapi.enabled | bool | `false` |  |
| backend.modules.openklant2.contactgegevensApiUrl | string | `""` |  |
| backend.modules.openklant2.enabled | bool | `false` |  |
| backend.modules.openklant2.klantinteractiesApiUrl | string | `""` |  |
| backend.modules.openproduct.enabled | bool | `false` |  |
| backend.modules.openproduct.productApiUrl | string | `""` |  |
| backend.modules.openproduct.productTypeApiUrl | string | `""` |  |
| backend.modules.prefill.enabled | bool | `false` |  |
| backend.modules.prefill.typeUrl | string | `""` |  |
| backend.modules.product.enabled | bool | `false` |  |
| backend.modules.product.productDetailsTypeUrl | string | `""` |  |
| backend.modules.product.productInstantieTypeUrl | string | `""` |  |
| backend.modules.product.productTypeUrl | string | `""` |  |
| backend.modules.product.productVerbruiksobjectTypeUrl | string | `""` |  |
| backend.modules.taak.enabled | bool | `false` |  |
| backend.modules.verificatie.enabled | bool | `false` |  |
| backend.modules.zakenapi.enabled | bool | `false` |  |
| backend.nodeSelector | object | `{}` |  |
| backend.podAnnotations | object | `{}` |  |
| backend.podLabels | object | `{}` |  |
| backend.podSecurityContext.fsGroup | int | `1001` |  |
| backend.podSecurityContext.runAsGroup | int | `1001` |  |
| backend.podSecurityContext.runAsNonRoot | bool | `true` |  |
| backend.podSecurityContext.runAsUser | int | `1001` |  |
| backend.podSecurityContext.seccompProfile.type | string | `"RuntimeDefault"` |  |
| backend.priorityClassName | string | `""` |  |
| backend.probes.liveness.failureThreshold | int | `3` |  |
| backend.probes.liveness.periodSeconds | int | `10` |  |
| backend.probes.liveness.timeoutSeconds | int | `3` |  |
| backend.probes.readiness.failureThreshold | int | `3` |  |
| backend.probes.readiness.periodSeconds | int | `10` |  |
| backend.probes.readiness.timeoutSeconds | int | `3` |  |
| backend.probes.startup.failureThreshold | int | `36` |  |
| backend.probes.startup.periodSeconds | int | `5` |  |
| backend.redis.host | string | `""` | Required. |
| backend.redis.port | int | `6379` |  |
| backend.redis.ssl | bool | `true` | TLS to Redis (REDIS_SSL). |
| backend.redis.username | string | `""` | ACL user; empty for the default user. |
| backend.replicaCount | int | `1` | Number of replicas; the backend keeps no persistent state (sessions live in Redis). |
| backend.resources.limits.cpu | string | `"1"` |  |
| backend.resources.limits.memory | string | `"1Gi"` |  |
| backend.resources.requests.cpu | string | `"250m"` |  |
| backend.resources.requests.memory | string | `"768Mi"` |  |
| backend.revisionHistoryLimit | int | `3` |  |
| backend.secretKeys | object | `{"beheerPasswordHash":"NLPORTAL_BEHEER_WACHTWOORDHASH","besluitenapiSecret":"NLPORTAL_CONFIG_BESLUITENAPI_PROPERTIES_SECRET","openklant2Token":"NLPORTAL_CONFIG_OPENKLANT2_PROPERTIES_TOKEN","openproductToken":"NLPORTAL_CONFIG_OPENPRODUCT_PROPERTIES_TOKEN","redisPassword":"REDIS_PASSWORD","redisSsl":"REDIS_SSL","redisUsername":"REDIS_USERNAME"}` | Key names in the existing Secret, by purpose. The defaults are the environment variable names; override a key name when your secret store uses another one. |
| backend.secretVersion | string | `""` | Changes the pod template (and so restarts the pods) when you bump it. Use it after rotating a value in the Secret, or use a secret reloader via podAnnotations. |
| backend.service.annotations | object | `{}` |  |
| backend.service.port | int | `8080` |  |
| backend.service.type | string | `"ClusterIP"` |  |
| backend.terminationGracePeriodSeconds | int | `30` |  |
| backend.tolerations | list | `[]` |  |
| backend.topologySpreadConstraints | list | `[]` |  |
| backend.trustedProxies | list | `[]` | IP addresses or CIDR blocks of the ingress controller (NL_PORTAL_TRUSTED_PROXIES); only from these addresses are Forwarded and X-Forwarded-Host honoured. Leave empty when the Host header is enough. |
| frontend.affinity | object | `{}` |  |
| frontend.autoIdleSessionLogout | bool | `true` | AUTO_IDLE_SESSION_LOGOUT. |
| frontend.containerSecurityContext.allowPrivilegeEscalation | bool | `false` |  |
| frontend.containerSecurityContext.capabilities.drop[0] | string | `"ALL"` |  |
| frontend.containerSecurityContext.readOnlyRootFilesystem | bool | `true` |  |
| frontend.extraEnv | list | `[]` |  |
| frontend.graphqlUri | string | `"/graphql"` | GRAPHQL_URI and REST_URI of the SPA: same-origin paths, nginx proxies them to the backend. |
| frontend.idleTimeoutMinutes | string | `""` | IDLE_TIMEOUT_MINUTES; empty follows the tenant file. |
| frontend.image.digest | string | `""` |  |
| frontend.image.pullPolicy | string | `"IfNotPresent"` |  |
| frontend.image.registry | string | `"ghcr.io"` |  |
| frontend.image.repository | string | `"pinkroccade/nl-portal-frontend"` |  |
| frontend.image.tag | string | `""` |  |
| frontend.nodeSelector | object | `{}` |  |
| frontend.podAnnotations | object | `{}` |  |
| frontend.podLabels | object | `{}` |  |
| frontend.podSecurityContext.fsGroup | int | `101` |  |
| frontend.podSecurityContext.runAsGroup | int | `101` |  |
| frontend.podSecurityContext.runAsNonRoot | bool | `true` |  |
| frontend.podSecurityContext.runAsUser | int | `101` |  |
| frontend.podSecurityContext.seccompProfile.type | string | `"RuntimeDefault"` |  |
| frontend.priorityClassName | string | `""` |  |
| frontend.probes.liveness.failureThreshold | int | `3` |  |
| frontend.probes.liveness.periodSeconds | int | `10` |  |
| frontend.probes.liveness.timeoutSeconds | int | `2` |  |
| frontend.probes.readiness.failureThreshold | int | `3` |  |
| frontend.probes.readiness.periodSeconds | int | `10` |  |
| frontend.probes.readiness.timeoutSeconds | int | `2` |  |
| frontend.replicaCount | int | `1` |  |
| frontend.resources.limits.cpu | string | `"200m"` |  |
| frontend.resources.limits.memory | string | `"128Mi"` |  |
| frontend.resources.requests.cpu | string | `"50m"` |  |
| frontend.resources.requests.memory | string | `"64Mi"` |  |
| frontend.restUri | string | `"/api"` |  |
| frontend.revisionHistoryLimit | int | `3` |  |
| frontend.service.annotations | object | `{}` |  |
| frontend.service.port | int | `80` |  |
| frontend.service.type | string | `"ClusterIP"` |  |
| frontend.terminationGracePeriodSeconds | int | `30` |  |
| frontend.tolerations | list | `[]` |  |
| frontend.topologySpreadConstraints | list | `[]` |  |
| fullnameOverride | string | `""` | Overrides the whole resource base name (services become `<fullnameOverride>-backend` and `-frontend`). |
| global.imagePullSecrets | list | `[]` | Pull secrets for every pod; a name or {name: ...}. |
| global.imageRegistry | string | `""` | Replaces the registry of every image (registry mirror). |
| ingress.annotations | object | `{}` |  |
| ingress.className | string | `""` |  |
| ingress.enabled | bool | `false` |  |
| ingress.hosts | list | `[]` | Hosts (the tenant domains). Required when enabled. |
| ingress.tls | list | `[]` |  |
| nameOverride | string | `""` | Overrides the chart name in resource names and labels. |
| serviceAccount.annotations | object | `{}` |  |
| serviceAccount.automountServiceAccountToken | bool | `false` | The pods need no Kubernetes API access. |
| serviceAccount.create | bool | `false` | Creates a ServiceAccount; otherwise `name` (or "default") is used. |
| serviceAccount.name | string | `""` |  |
| tenancy.existingConfigMap | string | `""` | Name of an existing ConfigMap with the tenant files; replaces `tenants`. |
| tenancy.keys | list | `[]` | Client key per tenant (private_key_jwt towards Keycloak): a Secret per OIN, mounted as `<sleutelmap>/<oin>/<key>`. |
| tenancy.keysDefaultMode | int | `288` | File mode of the mounted key files (octal, readable by the pod fsGroup). |
| tenancy.tenants | object | `{}` | Tenant files by file name (`base.yaml` and `<oin>.yaml`), rendered into a ConfigMap. Contains no secrets, only secret names. |
<!-- #endregion values -->

## Ingress

Off by default. When enabled, per host: `/graphql`, `/api`, `/oauth2`, `/login` and `/logout` go to the backend Service,
everything else to the frontend Service. The `Host` header is passed on unchanged.

## Restarts on change

Pods restart on a change of the environment ConfigMap, the tenant files, the feature flags (checksum annotations) and on a new
`backend.secretVersion`. The chart renders deterministically: no `lookup`, `rand*`, `now` or hooks.

## Tests

From the repository root: `mise run helm:lint` (`helm lint --strict` and kubeconform per scenario in `ci/`) and `mise run helm:test`
(helm-unittest suites in `tests/`). `mise run helm:docs` regenerates this README from `values.yaml`; `mise run helm:docs-check` (part of `mise run lint`) fails when it is stale.
The user documentation (tutorial, how-tos, reference) lives in `site/hosting-en-configuratie/kubernetes/`.

## Releasing

The chart is published as a signed OCI artifact: `oci://ghcr.io/pinkroccade/charts/nl-portal`. To release:

1. Bump `version` in `Chart.yaml` (SemVer, independent of `appVersion`; a breaking values change is a major bump). Set `appVersion` to an
   app release whose images exist on ghcr.
2. Merge to `main`. A merge that changes `version` triggers `publish-helm-chart.yml`; nothing to tag by hand. (The app release also tags
   `chart-nl-portal-<version>` after its images exist, which starts the same workflow.)
3. The workflow checks that the images of `appVersion` exist, runs `helm:lint` and `helm:test`, pushes the chart, signs it with cosign
   (keyless, GitHub OIDC), and creates the tag `chart-nl-portal-<version>` and a GitHub Release with the changelog and the
   `cosign verify` command. Without a version bump, or when the version is already in the registry, nothing is published.
