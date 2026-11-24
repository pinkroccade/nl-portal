# How-to: de beproeving met de base-chart

*Diátaxis: how-to.* Een checklist voor de DevOps-engineer die de chart voor het eerst in een eigen base-chart uitprobeert.

De chart is ontworpen zonder je base-chart te kennen. Alles wat hij daarover aanneemt staat hieronder als te beoordelen punt. Het contract met de base-chart
wordt pas definitief na deze eerste beproeving: geef je feedback per punt (klopt / klopt niet / wijzigen), liefst als issue op
[pinkroccade/nl-portal](https://github.com/pinkroccade/nl-portal/issues).

## Aannames over de base-chart

| # | Aanname | Beoordeel |
|---|---|---|
| 1 | De chart staat als dependency onder de naam (alias) `nl-portal`. | Past dit bij jullie conventie voor aliassen? |
| 2 | De Services heten `<release>-nl-portal-backend` en `-frontend` (met `nameOverride`/`fullnameOverride` aan te passen). | Bouwen jullie Ingress, NetworkPolicy en ServiceMonitor op deze namen? |
| 3 | Alleen `global.imageRegistry` en `global.imagePullSecrets` worden gelezen uit de globals. | Gebruiken jullie andere globals die de chart moet kennen? |
| 4 | De Kubernetes-versie in CI is de laatste die Haven ondersteunt (`kubeVersion >=1.27`). | Draaien jullie clusters die versie of nieuwer? |
| 5 | De backend is stateless voor de pods en draait met meerdere replicas; sessies staan in Redis. | Is Redis (ACL-gebruiker, TLS, geen persistentie) beschikbaar en bereikbaar? |
| 6 | De chart maakt geen Secret, NetworkPolicy, PodDisruptionBudget, HPA, ExternalSecret, ServiceMonitor of Argo-resource. | Regelt de base-chart ze allemaal? Wat ontbreekt? |
| 7 | De Ingress is standaard uit; de base-chart regelt de routing en geeft `Host` ongewijzigd door. | Klopt de pad-verdeling ([contract](./referentie-contract.md#ingress-routing)) met jullie ingresscontroller? |
| 8 | De namespace dwingt Pod Security "restricted" af; de pods (uid 1001 en 101, read-only root) voldoen. | Heeft jullie cluster extra admission-regels (Kyverno, OPA) die iets anders eisen? |
| 9 | De actuator staat op poort 8091 en zit niet in een Service of Ingress. | Scrapen jullie die poort, of moet er een metrics-pad bij? |
| 10 | Secrets komen via ESO uit Vault met de vaste keys ([secret-keys](./referentie-secret-keys.md)). | Passen de key-namen in jullie Vault-indeling? Zijn de overrides in `backend.secretKeys` genoeg? |
| 11 | Tenantbestanden zijn niet geheim (alleen namen van geheimen) en staan in de values of een eigen ConfigMap. | Willen jullie ze per omgeving in de GitOps-repo, of centraal? |
| 12 | Een rotatie van een Secret herstart de pods niet vanzelf (`backend.secretVersion` of een reloader). | Gebruiken jullie een reloader? |
| 13 | De frontend-nginx wijst via `NL_PORTAL_BACKEND_UPSTREAM` naar de backend-Service. | Gaat het verkeer tussen frontend en backend door een service mesh of NetworkPolicy die dit blokkeert? |
| 14 | Chart en images komen van ghcr, of van Harbor met behoud van de handtekening. | Gebruiken jullie een eigen registry of proxy-cache, en verifiëren jullie handtekeningen? |
| 15 | Argo CD synct de chart via OCI met een gepinde versie; de chart rendert deterministisch. | Werkt `helm template` in Argo met jullie plugins en `valuesObject`? |
| 16 | Elke tenant heeft een Keycloak-realm met een `private_key_jwt`-client; Keycloak 26.x. | Is dit de Keycloak-inrichting die jullie hebben ([Infra-vereisten](./referentie-infra-vereisten.md))? |
| 17 | De egress naar de bronnen per module kan worden opengezet. | Zijn alle bestemmingen uit de [egress-tabel](./referentie-infra-vereisten.md#egress-per-module) bereikbaar vanuit de namespace? |
| 18 | De standaard-resources (backend 250m/768Mi tot 1/1Gi, frontend 50m/64Mi tot 200m/128Mi) passen binnen quota. | Passen ze bij jullie quota en node-groottes? |

## Uitvoeren

1. Neem de chart op ([de chart opnemen in een base-chart](./how-to-base-chart.md)) en render met `helm template` en `helm lint --strict`.
2. Installeer in een testnamespace met `pod-security.kubernetes.io/enforce=restricted`. Beide pods moeten ready worden.
3. Controleer de punten hierboven één voor één en noteer afwijkingen.
4. Test een upgrade en een secret-rotatie ([upgraden](./how-to-upgraden.md), [secrets](./how-to-secrets-eso.md#na-een-rotatie)).
5. Stuur je bevindingen terug.
