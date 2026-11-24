# Uitleg: het securitymodel van de chart

*Diátaxis: uitleg.* Welke beveiligingskeuzes de chart maakt en waarom.

## Geen geheimen in de chart of de values

De chart maakt geen Secrets en de values bevatten geen geheimen. Je wijst per component een bestaand Secret aan met vaste, gedocumenteerde keys
([secret-keys](./referentie-secret-keys.md)). Zo bevat de GitOps-repository geen geheimen en past de chart bij ESO met Vault: de `ExternalSecret` leeft in je base-chart.
Tenantbestanden staan wél in de values, maar bevatten alleen de *namen* van geheimen (`00000099000000000001.zaken-secret`), nooit de waarden.

Het schema dwingt af dat de Secret-naam gezet is zodra gevoelige configuratie nodig is, en de chart faalt bij het renderen op een ontbrekende verplichte waarde, zodat de fout
zichtbaar wordt vóór Argo synct. Ontbrekende keys laten de pod stoppen met `CreateContainerConfigError`.

## Pods die voldoen aan Pod Security "restricted"

Backend en frontend draaien als niet-root met een numerieke uid (1001 en 101), met een read-only root-bestandssysteem, zonder privilege-escalatie, met alle capabilities
gedropt en `seccompProfile: RuntimeDefault`. Schrijfbare paden zijn emptyDirs (`/tmp`, en `/etc/nginx/conf.d` voor nginx). Het ServiceAccount-token wordt niet gemount: de pods
hebben geen Kubernetes-API nodig. Daardoor mogen ze draaien in een namespace die `restricted` afdwingt, zonder uitzonderingen. Details: [het contract](./referentie-contract.md#pod-security).

## De actuator is nooit publiek

Spring Boot Actuator draait op een eigen poort (8091) die niet in een Service of Ingress staat, zodat `refresh` en `restart` nooit publiek openstaan. De probes en de beheerendpoints
gebruiken die poort. `/actuator/modulestatus` is alleen bereikbaar met basic auth (`backend.beheer`) en blijft zonder dat dicht (401).

## Sessies in plaats van tokens in de browser

De backend is de OIDC-client (BFF). De browser krijgt alleen een sessiecookie; tokens blijven in de backend en in Redis. De backend authenticeert bij Keycloak met `private_key_jwt`: er is
geen `client_secret`, alleen een sleutel per tenant in een eigen Secret. Zie [ADR 0022](../../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md) en [Keycloak](../keycloak.md).

## Wat bij jou ligt

De chart legt geen netwerkbeleid op. Een NetworkPolicy die egress beperkt tot Redis, Keycloak en de bronnen ([egress per module](./referentie-infra-vereisten.md#egress-per-module))
hoort in je base-chart, net als TLS naar Redis (`backend.redis.ssl` staat standaard aan), de ingresscontroller en `backend.trustedProxies`. Valideer chart en handtekening vóór gebruik:
[de handtekening verifiëren](./how-to-handtekening-verifieren.md). Voor de wijze waarop de chart zelf wordt geleverd en gecontroleerd (gesigneerde OCI-artifacts, gepinde workflows) zie het
[beveiligingsbeleid](https://github.com/pinkroccade/nl-portal/blob/main/SECURITY.md).
