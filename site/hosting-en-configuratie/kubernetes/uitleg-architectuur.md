# Uitleg: architectuur van de chart

*Diátaxis: uitleg.* Wat de chart in het cluster neerzet, hoe verkeer loopt en wat buiten de chart blijft.

## Het plaatje

```text
                      internet (burger, Host: portaal.gemeente.nl)
                                   │
                         ┌─────────▼──────────┐
                         │ ingresscontroller  │   ← base-chart, of de optionele Ingress van de chart
                         └──┬──────────────┬──┘
   /graphql /api /oauth2    │              │   alles anders
   /login /logout           │              │
                 ┌──────────▼───┐   ┌──────▼─────────┐
                 │ Service      │   │ Service        │
                 │ …-backend:80 │   │ …-frontend:80  │
                 └──────┬───────┘   └──────┬─────────┘
                        │                  │ nginx: /graphql /api /oauth2 /login /logout
            ┌───────────▼─────────┐        │ worden doorgestuurd naar de backend-Service
            │ backend-pod(s)      │◄───────┘
            │ :8080 http          │
            │ :8091 actuator ✗    │   ← nooit in een Service of Ingress
            └──┬────┬────┬────┬───┘
               │    │    │    └──────────► bronnen per module (Open Zaak, Open Object,
               │    │    │                 OpenKlant, OpenProduct, Haal Centraal, ClamAV)
               │    │    └───────────────► Keycloak (login, private_key_jwt)
               │    └────────────────────► Redis (portaalsessies)
               └─────────────────────────► (geen database)

   Binnen de chart:  de twee Deployments en Services, ConfigMaps, optioneel ServiceAccount en Ingress.
   Buiten de chart:  Redis, Keycloak, de bronnen, Secrets, NetworkPolicy, PDB, HPA, ServiceMonitor, Argo CD.
```

## De onderdelen

- **Frontend** (nginx met de SPA, poort 8081 in de pod, 80 in de Service). Geeft de statische bestanden en proxy't de API-paden naar de backend-Service.
  Daardoor ziet de browser één oorsprong en kan de sessie met cookies werken.
- **Backend** (poort 8080, actuator op 8091). De backend is de *backend-for-frontend*: hij is zelf de OIDC-client, bewaart de portaalsessie in Redis en geeft de browser
  alleen cookies ([ADR 0022](../../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)). Hij praat met Keycloak en met de bronnen van de modules.
- **Tenants.** Eén release kan meerdere tenants (gemeenten) dragen; de tenant volgt uit de `Host`-header ([ADR 0021](../../development/adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md)).
  Ingress en nginx geven `Host` daarom ongewijzigd door. Tenantbestanden staan in een ConfigMap en bevatten alleen de namen van geheimen.
- **Secrets** komen van buiten: één bestaand Secret per component, gevuld door bijvoorbeeld ESO uit Vault.
- **Redis** houdt de sessies vast. De pods zelf zijn vrij te herstarten en te schalen ([ADR 0013](../../development/adr/0013-stateless-backend-zonder-database.md),
  [ADR 0017](../../development/adr/0017-vluchtige-state-toegestaan.md)).

## Waarom zo?

De chart is een *child-chart*: hij bevat alleen wat de applicatie nodig heeft om te draaien en laat clusterbeleid (NetworkPolicy, ExternalSecret, Argo, ingress) aan de base-chart.
Zo botst hij niet met wat jullie al regelen. Het contract tussen beide is klein en vast: [Services, poorten en labels](./referentie-contract.md). Meer over de afwegingen:
[Ontwerpkeuzes](./uitleg-ontwerpkeuzes.md).
