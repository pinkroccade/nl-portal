# How-to: de chart opnemen in een base-chart

*Diátaxis: how-to.* Je hebt een eigen base-chart die de cluster-instellingen regelt (Argo CD, NetworkPolicy, ExternalSecret, ingress) en wilt NL Portal als
child-chart opnemen.

## 1. Voeg de dependency toe

```yaml
# Chart.yaml van de base-chart
dependencies:
  - name: nl-portal
    version: 0.1.0                              # pin altijd
    repository: oci://ghcr.io/pinkroccade/charts
```

```shell
helm dependency update    # schrijft Chart.lock; commit die mee
```

Ontbreekt je registry of mirror, kijk dan bij [via Harbor](./how-to-harbor.md). Verifieer de handtekening van de chart vóór je hem pint
([de handtekening verifiëren](./how-to-handtekening-verifieren.md)).

## 2. Zet de values onder de alias

De values van een child-chart staan onder de naam (of `alias`) van de dependency. De chart gaat uit van de naam `nl-portal`:

```yaml
# values.yaml van de base-chart
nl-portal:
  backend:
    existingSecret: nl-portal-backend
    keycloakBrokerUrl: https://login.gemeente.nl
    redis: { host: redis.redis.svc, username: nlportal }
  tenancy: { ... }       # zie "Installeren"
```

Alleen `global.imageRegistry` en `global.imagePullSecrets` worden uit de globals van je base-chart gelezen; andere globals negeert de chart.

## 3. Regel in de base-chart wat de chart niet aanmaakt

De chart maakt geen Secret, NetworkPolicy, PodDisruptionBudget, HorizontalPodAutoscaler, ExternalSecret, ServiceMonitor of Argo-resource. Die horen in
de base-chart, en je bouwt ze op het [contract](./referentie-contract.md): vaste Service-namen, poorten en labels.

| Je base-chart regelt | Hoe |
|---|---|
| Secrets | een `ExternalSecret` per component: [Secrets uit Vault via ESO](./how-to-secrets-eso.md) |
| Ingress | de eigen Ingress van de chart (`ingress.enabled`) of je eigen routing; [pad-verdeling](./referentie-contract.md#ingress-routing) |
| NetworkPolicy | selecteer op `app.kubernetes.io/instance` en `app.kubernetes.io/component`; egress per module: [Infra-vereisten](./referentie-infra-vereisten.md#egress-per-module) |
| PodDisruptionBudget, HPA | selecteer dezelfde labels; de backend kan met meerdere replicas draaien |
| Namespace | label `pod-security.kubernetes.io/enforce=restricted`; de pods voldoen daaraan |

## 4. Controleer het resultaat

```shell
helm template mijngemeente . -f values.yaml | kubectl apply --dry-run=server -f -
helm lint --strict .
```

De Services heten `<release>-nl-portal-backend` en `<release>-nl-portal-frontend`: bij een base-chart-release `mijngemeente` dus
`mijngemeente-nl-portal-backend`. Bevat de releasenaam al `nl-portal`, dan blijft hij staan (`nl-portal-backend`). Wil je de naam zelf bepalen, gebruik dan
`nl-portal.fullnameOverride`.

Loop daarna de [beproevings-checklist](./how-to-beproeving.md) langs: ze noemt de aannames die de chart over jouw base-chart maakt.
