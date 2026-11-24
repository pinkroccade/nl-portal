# Deployen op Kubernetes met Helm

NL Portal heeft een eigen Helm-chart, `nl-portal`, in de map [`helm/nl-portal`](https://github.com/pinkroccade/nl-portal/tree/main/helm/nl-portal) van de repository
(epic [#191](https://github.com/pinkroccade/nl-portal/issues/191)). De chart bevat alleen wat de applicatie nodig heeft om te draaien: de backend en de
frontend als aparte Deployments en Services, een ConfigMap met de niet-geheime configuratie, een optionele ServiceAccount en een optionele Ingress.
Hij is bedoeld als child-chart in een base-chart die de cluster-instellingen (Argo CD, NetworkPolicy, ExternalSecret, ingress) regelt; een gewone
`helm install` werkt ook, als referentie.

Deze sectie volgt [Diátaxis](https://diataxis.fr/): elke pagina is van één soort en de titel zegt welke. Zo weet je vooraf wat je krijgt.

| Type | Je wilt | Pagina's |
|---|---|---|
| **Tutorial** (leren) | een eerste werkende installatie, stap voor stap | [Een eerste NL Portal op kind](./tutorial-kind.md) |
| **How-to** (doen) | een bekende taak uitvoeren | [Installeren met helm install of Argo CD](./how-to-installeren.md), [De chart opnemen in een base-chart](./how-to-base-chart.md), [Secrets uit Vault via ESO](./how-to-secrets-eso.md), [Een module aanzetten](./how-to-module-aanzetten.md), [Upgraden](./how-to-upgraden.md), [De handtekening verifiëren](./how-to-handtekening-verifieren.md), [Via Harbor](./how-to-harbor.md), [De beproeving](./how-to-beproeving.md) |
| **Referentie** (opzoeken) | feiten: values, keys, poorten, labels, vereisten | [Values](./referentie-values.md), [Secret-keys](./referentie-secret-keys.md), [Contract](./referentie-contract.md), [Infra-vereisten](./referentie-infra-vereisten.md) |
| **Uitleg** (begrijpen) | weten waarom de chart zo is | [Architectuur](./uitleg-architectuur.md), [Securitymodel](./uitleg-securitymodel.md), [Ontwerpkeuzes](./uitleg-ontwerpkeuzes.md) |

Begin je nieuw, doe dan de tutorial. Ken je Helm en wil je de chart in je eigen cluster krijgen, ga dan naar [de chart opnemen in een base-chart](./how-to-base-chart.md)
en loop daarna de [beproevings-checklist](./how-to-beproeving.md) langs.

## De chart ophalen

De chart staat als OCI-artifact op ghcr, naast de images, en is gesigneerd met cosign:

| Wat | Locatie |
|---|---|
| Chart | `oci://ghcr.io/pinkroccade/charts/nl-portal` |
| Images | `ghcr.io/pinkroccade/nl-portal-backend` en `ghcr.io/pinkroccade/nl-portal-frontend` |

De chart heeft een eigen SemVer, los van de app-versie. Een release is een versiebump van de chart op `main`; die levert de tag `chart-nl-portal-X.Y.Z`, het gesigneerde artifact en een GitHub Release op; `appVersion` in de chart is de standaard
image-tag. Pin altijd een chartversie, zodat een sync nooit onbedoeld een andere chart uitrolt:

```shell
helm pull oci://ghcr.io/pinkroccade/charts/nl-portal --version 0.1.0
helm install nl-portal oci://ghcr.io/pinkroccade/charts/nl-portal --version 0.1.0 -n nl-portal -f values.yaml
```

Controleer de handtekening voor je de chart gebruikt: [de handtekening verifiëren](./how-to-handtekening-verifieren.md). Draai je in een cluster dat
alleen uit Harbor haalt: [via Harbor](./how-to-harbor.md).

## Lokaal blijft Docker Compose

Voor lokale ontwikkeling draait Redis in [docker-compose](../opzetten-nl-portal.md); de frontend-nginx wijst daar standaard naar de backend op `localhost:8080`
(`NL_PORTAL_BACKEND_UPSTREAM`). In de chart wijst hij naar de backend-Service.
