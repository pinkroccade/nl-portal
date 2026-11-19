# Opzetten NL Portal

### Wat heb je nodig

Om de NL Portal lokaal op te zetten is enige basiskennis van de CLI (command-line interface) vereist. Daarnaast heb je de volgende software nodig:

* [Docker Desktop](https://www.docker.com/products/docker-desktop/) — de enige harde vereiste om de NL Portal demo te draaien.
* Voor het maken van een eigen portaal: [Git](https://git-scm.com/) en een [GitHub](https://github.com/) account. Het bouwen van de applicatie gebeurt via Docker, een lokale JDK- of Node-installatie is niet nodig.

#### Waar kan ik de code vinden

Alle code en informatie is opensource en kan gevonden worden in deze GitHub-repository. De backend, frontend, docker-compose stack en documentatie leven samen in één monorepo.

| Onderdeel                                                                         | Beschrijving                                                                                                    |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Deze repository                                                                   | De monorepo: backend (`backend/`), frontend (`frontend/`), docker-compose demo (`docker-compose/`) en docs.    |
| Helm Charts                                                                       | Eigen chart `nl-portal` als OCI-artifact op ghcr; zie [Deployen op Kubernetes met Helm](./kubernetes/index.md).   |

Voor de meeste implementaties gebruik je de gepubliceerde images (zie Route 1). Voor maatwerk dienen de app-directories (`backend/app`, `frontend/packages/app`) als startpunt.

### Route 1: Quickstart (lokaal gebouwd)

De snelste manier om de NL Portal te bekijken is de quickstart in de `docker-compose/` map van de monorepo. De standaardopzet start alleen wat bij livegang draait: de NL Portal backend en frontend, een Keycloak met per tenant een realm en de client `nl-portal-backend` (zie de [Keycloak configuratie](./keycloak.md) pagina) en een Redis voor de sessies. Backend en frontend worden uit je checkout gebouwd: de gepubliceerde images bevatten de BFF-authenticatie nog niet.

```shell
mise run dev:setup        # in de repo-root: secrets, realm en sleutelpaar
mise run keycloak:image   # het eigen Keycloak-image
cd docker-compose
docker compose up -d --build
```

De NL Portal is daarna bereikbaar op `http://localhost:3000`. Je kunt inloggen met de volgende testgebruikers:

| Gebruikersnaam | Wachtwoord | Identificatie (BSN/KVK) |
| -------------- | ---------- | ----------------------- |
| burger         | burger     | 999993847               |

Optionele onderdelen start je met profielen: `brp-mock` (BRP Personen-mock, voor persoonsgegevens), `bewoning`, `openzaak`, `objecten`, `openklant`, `openproduct` en `clamav`. De demo met zaken en taken en fixtures start met `docker compose -f compose.yaml -f compose.demo.yaml up -d`; het laden van de fixtures kan enkele minuten duren. De volledige tabel met services, poorten en profielen vind je in `docker-compose/README.md`.

### Route 2: Dev-variant met debugger

Draai je eigen wijzigingen met een debugger op poort 8000 (`compose.dev.yaml`):

```shell
docker compose -f compose.yaml -f compose.dev.yaml up -d --build
```

### Route 3: Vanuit broncode (sources)

Voor actieve ontwikkeling draai je alleen de ondersteunende services in compose en start je de backend en frontend zelf (snelle reload). Start vanuit de repo-root:

```shell
mise run dev:diensten
```

Start daarna de backend (`./gradlew :app:bootRun` vanuit `backend/`; vereist een `.env.properties` (genereer die met `mise run dev:setup`), zie `backend/app/README.md`) en de frontend (`pnpm install && pnpm dev` vanuit `frontend/`). Zie `docker-compose/README.md` voor de details.

### Configureren via bestanden

NL Portal is stateless: module-instellingen, frontend-teksten en -URL's, het thema en de feature flags komen uit config-bestanden en omgevingsvariabelen die per deployment worden geladen, zonder database of beheerapplicatie. Een wijziging rolt je uit met een rolling restart. De achtergrond staat in [ADR 0016](../development/adr/0016-stateless-configuratie-en-thema-via-bestanden.md).

### Maatwerk

Voor uitbreidingen die niet via configuratie mogelijk zijn (eigen componenten, plugins of authenticatiemethoden) dienen de app-directories `backend/app` en `frontend/packages/app` als startpunt. Bouw en draai zoals in Route 2.

### Configuratie via environment variabelen

De NL Portal images kunnen ook volledig geconfigureerd worden via environment variabelen. De volledige referentie met inline documentatie vind je in de monorepo, in de bestanden `docker-compose/imports/backend.env` en `docker-compose/imports/frontend.env`. Zie de [Deployment guide](./deployment-guide.md) voor de naamconventie, de module-configuratie en het deployen met Helm.

### Vervolgstappen

Nu je eerste NL Portal draait kun je verder met:

* [Eigen vormgeving](../gebruik-en-inrichting/eigen-vormgeving.md) — meer informatie over design tokens en huisstijl.
* [Deployment guide](./deployment-guide.md) — deploy de NL Portal naar je eigen omgeving (Azure, AWS of Kubernetes).
* [Keycloak configuratie](./keycloak.md) — sluit de NL Portal aan op je eigen Keycloak.
