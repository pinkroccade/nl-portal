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

Validated by `values.schema.json`: unknown keys and wrong types fail the render. Only `global.imageRegistry` and
`global.imagePullSecrets` are supported globals. Use `backend.extraEnv` and `frontend.extraEnv` (EnvVar objects) for what is not modelled yet.

| Key | Default | Description |
|---|---|---|
| `global.imageRegistry` | `""` | Replaces the registry of every image |
| `global.imagePullSecrets` | `[]` | Pull secrets for every pod (name or `{name: ...}`) |
| `nameOverride` / `fullnameOverride` | `""` | Override the chart name or the whole base name |
| `serviceAccount.create` / `name` / `annotations` | `false` / `""` / `{}` | Optional ServiceAccount |
| `serviceAccount.automountServiceAccountToken` | `false` | The pods need no API access |
| `tenancy.tenants` | `{}` | Tenant files by file name; required unless `existingConfigMap` is set |
| `tenancy.existingConfigMap` | `""` | Existing ConfigMap with the tenant files |
| `tenancy.keys` | `[]` | Client key per tenant: `{oin, existingSecret, key}`; at least one |
| `tenancy.keysDefaultMode` | `288` (0440) | File mode of the key files |
| `backend.replicaCount` | `1` | Replicas |
| `backend.image.registry` / `repository` / `tag` / `digest` / `pullPolicy` | `ghcr.io` / `pinkroccade/nl-portal-backend` / appVersion / `""` / `IfNotPresent` | Image; a digest replaces the tag |
| `backend.existingSecret` | `""` | **Required.** Secret with the keys above |
| `backend.secretKeys.*` | env var names | Key names in the Secret |
| `backend.secretVersion` | `""` | Bump to restart the pods after a secret rotation |
| `backend.logLevel` | `INFO` | `LOGLEVEL` |
| `backend.javaOpts` | `-XX:MaxRAMPercentage=75.0` | `JAVA_TOOL_OPTIONS` |
| `backend.keycloakBrokerUrl` | `""` | **Required.** `KEYCLOAK_BROKER_URL`: public base URL of Keycloak |
| `backend.trustedProxies` | `[]` | `NL_PORTAL_TRUSTED_PROXIES` (CIDR blocks of the ingress controller) |
| `backend.redis.host` / `port` / `ssl` / `username` | `""` / `6379` / `true` / `""` | Redis for the sessions; host required |
| `backend.management.port` | `8091` | Actuator port, never in the Service |
| `backend.beheer.enabled` / `username` | `false` / `beheer` | Basic auth for `/actuator/modulestatus` |
| `backend.cors.allowedOrigins` / `allowedMethods` / `allowedHeaders` | `[]` / common methods / `["*"]` | Cross-origin access; empty means none |
| `backend.featureFlags` | `{}` | flagd JSON (as YAML) mounted as a file |
| `backend.frontendProperties` | `{}` | `NLPORTAL_FRONTEND_PROPERTIES_<NAME>` by camelCase name |
| `backend.modules.<module>.enabled` | `false` | `zakenapi`, `catalogiapi`, `documentenapis`, `objectenapi`, `besluitenapi`, `openklant2`, `openproduct`, `haalcentraalBrp`, `kvk`, `verificatie`, `clamav`, `taak`, `berichten`, `formulieren`, `product`, `prefill`, `dmn` |
| `backend.modules.besluitenapi.url` / `clientId` | `""` | Required when enabled |
| `backend.modules.openklant2.contactgegevensApiUrl` / `klantinteractiesApiUrl` | `""` | Required when enabled |
| `backend.modules.openproduct.productApiUrl` / `productTypeApiUrl` | `""` | Required when enabled |
| `backend.modules.clamav.hostname` / `port` | `""` / `3310` | Hostname required when enabled |
| `backend.modules.formulieren.formDefinitionObjectTypeUrl` | `""` | Optional validation of form definitions |
| `backend.modules.product.*TypeUrl` | `""` | The four object type URLs; required when enabled |
| `backend.modules.prefill.typeUrl`, `backend.modules.dmn.url` | `""` | Required when enabled |
| `backend.extraEnv` / `extraVolumes` / `extraVolumeMounts` | `[]` | Escape hatches |
| `backend.service.type` / `port` / `annotations` | `ClusterIP` / `8080` / `{}` | Service |
| `backend.probes.startup` / `liveness` / `readiness` | see `values.yaml` | Timing of the probes |
| `backend.resources` | requests 250m / 768Mi, limits 1 / 1Gi | Resources |
| `backend.podSecurityContext` / `containerSecurityContext` | restricted | Security context |
| `backend.podAnnotations` / `podLabels` / `nodeSelector` / `tolerations` / `affinity` / `topologySpreadConstraints` / `priorityClassName` | empty | Scheduling and metadata |
| `backend.terminationGracePeriodSeconds` / `revisionHistoryLimit` | `30` / `3` | Deployment settings |
| `frontend.replicaCount` | `1` | Replicas |
| `frontend.image.*` | `ghcr.io/pinkroccade/nl-portal-frontend` | Image, as for the backend |
| `frontend.graphqlUri` / `restUri` | `/graphql` / `/api` | `GRAPHQL_URI` and `REST_URI` of the SPA |
| `frontend.autoIdleSessionLogout` / `idleTimeoutMinutes` | `true` / `""` | Idle logout of the SPA |
| `frontend.extraEnv` | `[]` | Extra environment |
| `frontend.service.type` / `port` / `annotations` | `ClusterIP` / `80` / `{}` | Service |
| `frontend.probes`, `resources`, `podSecurityContext`, `containerSecurityContext`, scheduling | see `values.yaml` | As for the backend (resources 50m / 64Mi, limits 200m / 128Mi) |
| `ingress.enabled` | `false` | Create an Ingress |
| `ingress.className` / `annotations` / `tls` | `""` / `{}` / `[]` | Ingress settings |
| `ingress.hosts` | `[]` | Tenant domains; required when enabled |

## Ingress

Off by default. When enabled, per host: `/graphql`, `/api`, `/oauth2`, `/login` and `/logout` go to the backend Service,
everything else to the frontend Service. The `Host` header is passed on unchanged.

## Restarts on change

Pods restart on a change of the environment ConfigMap, the tenant files, the feature flags (checksum annotations) and on a new
`backend.secretVersion`. The chart renders deterministically: no `lookup`, `rand*`, `now` or hooks.

## Tests

From the repository root: `mise run helm:lint` (`helm lint --strict` and kubeconform per scenario in `ci/`) and `mise run helm:test`
(helm-unittest suites in `tests/`).
