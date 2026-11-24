# Uitleg: ontwerpkeuzes van de chart

*Diátaxis: uitleg.* Welke keuzes zijn gemaakt, en welke alternatieven zijn afgewezen.

De keuzes komen uit epic [#191](https://github.com/pinkroccade/nl-portal/issues/191). Ze worden in een eigen ADR vastgelegd; tot die er is, is de epic de bron.

| Keuze | Waarom | Afgewezen alternatief |
|---|---|---|
| **Eigen chart in deze repository** | Chart en code evolueren samen; een wijziging van de app en de chart kan in één PR. | Een aparte charts-repo: levert alleen iets op bij een centrale catalogus of een ander eigenaarsteam. |
| **OCI-artifact op ghcr, gesigneerd met cosign** | Een vaste versie, verifieerbare herkomst, geen index om te beheren. | Een gh-pages-index. |
| **Eigen SemVer en tags `chart-nl-portal-X.Y.Z`** | Een chartfix kan zonder app-release; de tags botsen niet met de app-tags `X.Y.Z`. | Chartversie gelijk aan de appversie. |
| **Alleen de applicatie in de chart** | De base-chart van DevOps regelt clusterbeleid; zo botsen ze niet. | Een chart met NetworkPolicy, ExternalSecret, ServiceMonitor en Argo-resources. |
| **Geen subcharts** (Redis, Keycloak, database) | Afhankelijkheden hebben eigen beheer en updateritme. Er is geen database (ADR 0013). | De upstream-charts met meegebundelde (verouderde) Bitnami-subcharts. |
| **Geen Secrets in de chart; één bestaand Secret per component met vaste keys** | De GitOps-repository bevat geen geheimen; één `ExternalSecret` volstaat. | Secrets op drie manieren door elkaar zetten, of waarden in de values. |
| **Pod Security "restricted" als standaard** | Draait op Haven-clusters zonder uitzonderingen. | Securitycontexten aan de gebruiker overlaten. |
| **Actuator op een eigen poort buiten de Service** | `refresh` en `restart` kunnen niet publiek bereikbaar worden. | Actuator achter de gewone poort met een pad-filter. |
| **Een Ingress die standaard uit staat** | De base-chart regelt de routing. | Altijd een Ingress aanmaken. |
| **Een JSON-schema en renderfouten op ontbrekende waarden** | Typfouten en vergeten waarden vallen op vóór Argo synct. | Pas bij runtime falen. |
| **Een deterministische render** (geen `lookup`, `rand*`, `now`, hooks) | Argo CD vergelijkt het gerenderde resultaat; niet-determinisme geeft eeuwige diffs. | Hooks voor migraties of Secret-generatie. |
| **Checksum-annotaties en `backend.secretVersion`** | Een wijziging van config of secret herstart de pods echt. | Handmatig herstarten. |
| **`extraEnv` als ontsnappingsluik** | Je hoeft niet op een chart-release te wachten voor configuratie die nog niet gemodelleerd is. | Elke app-property direct in de chart modelleren. |
| **Alleen `global.imageRegistry` en `global.imagePullSecrets`** | Een klein, expliciet contract met de parent-chart. | Alle globals van de parent doorlezen. |

Het contract met de base-chart wordt pas definitief na de eerste beproeving: zie [de beproeving](./how-to-beproeving.md). De bijbehorende architectuuruitleg staat in
[Architectuur](./uitleg-architectuur.md); de achterliggende beslissingen voor stateless, sessies en multitenancy staan in
[ADR 0013](../../development/adr/0013-stateless-backend-zonder-database.md), [ADR 0017](../../development/adr/0017-vluchtige-state-toegestaan.md),
[ADR 0021](../../development/adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md) en [ADR 0022](../../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md).
