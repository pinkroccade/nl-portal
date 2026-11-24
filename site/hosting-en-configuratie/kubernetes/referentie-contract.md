# Referentie: het contract met de base-chart

*Diátaxis: referentie.* Services, poorten, labels en routing waar een base-chart op kan bouwen.

## Wat de chart aanmaakt

| Resource | Wanneer |
|---|---|
| Deployment en Service `<release>-nl-portal-backend` | altijd |
| Deployment en Service `<release>-nl-portal-frontend` | altijd |
| ConfigMap `<release>-nl-portal-backend` (niet-geheime omgeving) en `...-frontend` | altijd |
| ConfigMap `<release>-nl-portal-tenants` (tenantbestanden) | als `tenancy.tenants` is gezet |
| ConfigMap `<release>-nl-portal-flags` (feature flags, flagd JSON) | als `backend.featureFlags` is gezet |
| ServiceAccount | `serviceAccount.create` |
| Ingress | `ingress.enabled` (standaard uit) |

De chart maakt **geen** Secret, NetworkPolicy, PodDisruptionBudget, HorizontalPodAutoscaler, ExternalSecret, ServiceMonitor of Argo CD-resource en bundelt
geen subcharts. Met `nameOverride` volgen de namen `<release>-<nameOverride>-<component>`; `fullnameOverride` geeft `<fullnameOverride>-backend` en `-frontend`. Bevat
de releasenaam al de chartnaam, dan wordt die niet herhaald (release `nl-portal` geeft `nl-portal-backend`).

## Labels

Elke resource draagt de recommended labels van Kubernetes en `helm.sh/chart`:

| Label | Waarde |
|---|---|
| `app.kubernetes.io/name` | `nl-portal` (of `nameOverride`) |
| `app.kubernetes.io/instance` | de releasenaam |
| `app.kubernetes.io/component` | `backend` of `frontend` |
| `app.kubernetes.io/version` | `appVersion` van de chart |
| `app.kubernetes.io/part-of` | `nl-portal` |
| `app.kubernetes.io/managed-by` | `Helm` |

De selector van pods en Services bestaat uit `name`, `instance` en `component` en verandert na de eerste installatie niet. Gebruik die drie in je
NetworkPolicy, PodDisruptionBudget en ServiceMonitor.

## Poorten, probes en de actuator

| Poort | In de container | In de Service |
|---|---|---|
| backend `http` 8080 | ja | ja (`backend.service.port`) |
| backend `management` 8091 (`backend.management.port`, `MANAGEMENT_SERVER_PORT`) | ja | **nooit** |
| frontend `http` 8081 | ja | ja (Service-poort 80) |

De probes gebruiken de health-groups op de managementpoort (`/actuator/health/liveness` en `/readiness`, plus een startup-probe). Publiceer de managementpoort nooit
via een Ingress; zie [Beheerpoort: modulestatus](../beheer-modulestatus.md). De frontend-nginx stuurt `/graphql`, `/api`, `/oauth2`, `/login` en `/logout` door naar de
backend-Service (`NL_PORTAL_BACKEND_UPSTREAM`, door de chart gezet).

## Ingress-routing

De Ingress staat standaard uit, zodat je base-chart de routing kan regelen. Zet je hem aan (`ingress.enabled`, met `ingress.hosts`), dan gaan per host `/graphql`, `/api`,
`/oauth2`, `/login` en `/logout` naar de backend-Service en al het andere naar de frontend-Service. `Host` wordt ongewijzigd doorgegeven: de tenant volgt daaruit.
Bouw je de routing zelf, doe dan hetzelfde. Als de Ingress het enige adres is dat doorstuurheaders mag zetten, zet je de CIDR in `backend.trustedProxies`; laat de
lijst anders leeg.

## Herstarten bij wijziging

Pods herstarten bij een wijziging van de omgevings-ConfigMap, de tenantbestanden en de feature flags (checksum-annotaties) en bij een nieuwe `backend.secretVersion`.
De chart rendert deterministisch: geen `lookup`, `rand*`, `now` of hooks.

## Pod Security

Beide pods voldoen standaard aan Pod Security "restricted": `runAsNonRoot` met een numerieke uid (1001 en 101), `readOnlyRootFilesystem`,
`allowPrivilegeEscalation: false`, alle capabilities gedropt en `seccompProfile: RuntimeDefault`. Schrijfbare paden zijn emptyDirs: `/tmp` op beide, en
`/etc/nginx/conf.d` op de frontend (de gerenderde nginx-configuratie). Het ServiceAccount-token wordt niet gemount.
