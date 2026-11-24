# Referentie: infra-vereisten

*Diátaxis: referentie.* Wat je vooraf moet regelen; de chart bundelt geen afhankelijkheden.

| Vereiste | Waarom |
|---|---|
| **Redis** (ACL-gebruiker, zonder persistentie, met TLS) | Sessies van de BFF ([ADR 0022](../../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)). Zonder bereikbare Redis kan niemand inloggen. |
| **Keycloak** 26.x (eigen image met mappers) met een realm per tenant en een `private_key_jwt`-client | De login; zie [Keycloak](../keycloak.md). |
| De **bronnen** van de modules die je aanzet | Open Zaak, Open Object, OpenKlant, OpenProduct, Haal Centraal, ClamAV. |
| Een **Secret** per component met vaste keys | Zie [secret-keys](./referentie-secret-keys.md). |
| De **tenantbestanden** en een **clientsleutel** per tenant | Zie [Tenants en tenantbestanden](./how-to-installeren.md#tenants-en-tenantbestanden). |
| Kubernetes `>=1.27` | `kubeVersion` van de chart; de CI valideert tegen de laatste Kubernetes-versie die Haven ondersteunt. |

**Let op: "stateless" is genuanceerd.** [ADR 0013](../../development/adr/0013-stateless-backend-zonder-database.md) zegt dat de backend geen database
en geen persistente state heeft, en dat klopt voor de pods: je kunt ze zonder verlies herstarten en met meerdere replicas draaien. De portaalsessies
staan echter vluchtig in Redis ([ADR 0017](../../development/adr/0017-vluchtige-state-toegestaan.md)). Redis is dus een harde runtimevereiste (niet meegebundeld) en een uitval van Redis betekent dat ingelogde burgers hun
sessie kwijt zijn.

## Keycloak

- Eén realm per tenant, met één client `nl-portal-backend`: vertrouwelijk, alleen standard flow, `private_key_jwt` (`PS256`), PKCE `S256` verplicht.
- De publieke URL van Keycloak staat in `backend.keycloakBrokerUrl`; de issuer van elke tenant moet daaronder vallen.
- De redirect-URI's zijn exact `https://<domein van de tenant>/login/oauth2/code/<OIN>`.
- Details en de mappers: [Keycloak](../keycloak.md).

## Egress per module

De chart maakt geen NetworkPolicy; je base-chart moet de egress van de backend-pods openzetten. Bestemmingen:

| Bestemming | Poort | Wanneer |
|---|---|---|
| Redis | `backend.redis.port` (6379), TLS | altijd |
| Keycloak (publieke URL en realm) | 443 | altijd, voor de login |
| Open Zaak (Zaken-, Catalogi-, Documenten-API) | 443 | `zakenapi`, `catalogiapi`, `documentenapis` |
| Open Object (Objecten API) | 443 | `objectenapi` en de modules die daarop bouwen (`taak`, `berichten`, `formulieren`, `product`, `prefill`) |
| Besluiten API | 443 | `besluitenapi` (`backend.modules.besluitenapi.url`) |
| OpenKlant 2 | 443 | `openklant2` (`contactgegevensApiUrl`, `klantinteractiesApiUrl`) |
| OpenProduct | 443 | `openproduct` (`productApiUrl`, `productTypeApiUrl`) |
| Haal Centraal BRP | 443 | `haalcentraalBrp` |
| KvK (Handelsregister) | 443 | `kvk` |
| Verificatiedienst | 443 | `verificatie` |
| ClamAV | `backend.modules.clamav.port` (3310) | `clamav` |
| DMN-engine | 443 | `dmn` (`backend.modules.dmn.url`) |

De exacte URL's staan per tenant in het tenantbestand (`koppelingen`); de poorten hierboven zijn de gebruikelijke. Ingress: de frontend en de backend ontvangen verkeer van je
ingresscontroller; de frontend praat daarnaast alleen met de backend-Service.

## Resources

| Component | Requests | Limits |
|---|---|---|
| backend | 250m CPU, 768Mi | 1 CPU, 1Gi |
| frontend | 50m CPU, 64Mi | 200m CPU, 128Mi |

De JVM gebruikt `-XX:MaxRAMPercentage=75.0` (`backend.javaOpts`). Pas de waarden aan met `backend.resources` en `frontend.resources`.
