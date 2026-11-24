# Referentie: secret-keys

*Diátaxis: referentie.* Welk Secret met welke keys de chart verwacht, per component.

De chart neemt per component één bestaand Secret. Het Secret komt als omgevingsvariabelen in de container; de keys staan vast. Elke keynaam is te overschrijven
onder `backend.secretKeys` (de standaard is de naam van de omgevingsvariabele). Hoe je het Secret vult: [Secrets uit Vault via ESO](./how-to-secrets-eso.md).

## Backend (`backend.existingSecret`, verplicht)

<!-- secret-tabel:start (gegenereerd door scripts/secret-tabel.mjs; niet met de hand wijzigen) -->
| Key (standaardnaam) | Nodig wanneer | Betekenis |
|---|---|---|
| `REDIS_PASSWORD` | altijd | Wachtwoord van de Redis ACL-gebruiker |
| `REDIS_USERNAME` | optioneel | ACL-gebruiker; overschrijft `backend.redis.username` |
| `REDIS_SSL` | optioneel | `true` of `false`; overschrijft `backend.redis.ssl` |
| `NLPORTAL_BEHEER_WACHTWOORDHASH` | `backend.beheer.enabled` | Wachtwoordhash van de beheergebruiker, met prefix (`{bcrypt}$2a$...`) |
| `NLPORTAL_CONFIG_BESLUITENAPI_PROPERTIES_SECRET` | `modules.besluitenapi.enabled` | Client-secret van de Besluiten API |
| `NLPORTAL_CONFIG_OPENKLANT2_PROPERTIES_TOKEN` | `modules.openklant2.enabled` | API-token van OpenKlant 2 |
| `NLPORTAL_CONFIG_OPENPRODUCT_PROPERTIES_TOKEN` | `modules.openproduct.enabled` | API-token van OpenProduct |
| `NL_PORTAL_GEHEIMEN_<OIN>_<NAAM>` | per tenantbestand | De geheimen waar het tenantbestand naar verwijst, bijvoorbeeld `NL_PORTAL_GEHEIMEN_00000099000000000001_ZAKEN_SECRET`, `..._CATALOGIAPI_SECRET`, `..._OBJECTENAPI_TOKEN` (waarde `Token <token>`), `..._DOCUMENTEN_API_SECRET`, `..._VERIFICATIE_SECRET` |
<!-- secret-tabel:einde -->

De `NL_PORTAL_GEHEIMEN_*`-keys hangen van de tenants af, dus het hele Secret staat als omgeving in de backend-container. De overige keys worden één voor één
gerefereerd: een ontbrekende key stopt de pod (`CreateContainerConfigError`) in plaats van pas tijdens runtime te falen. De chart kan het Secret niet lezen
(geen `lookup`); hij faalt bij het renderen alleen op een ontbrekende `backend.existingSecret`. Na een rotatie pakken de pods de nieuwe waarde pas na een
herstart: verhoog `backend.secretVersion` of gebruik een secret-reloader via `backend.podAnnotations`.

## Clientsleutel per tenant (`tenancy.keys[].existingSecret`)

Een Secret per OIN met de private JWK voor `private_key_jwt` (RSA, minstens 3072 bit). De chart mount het als
`/etc/nl-portal/sleutels/<oin>/clientsleutel.jwk.json` (bestandsmodus `tenancy.keysDefaultMode`, standaard 0440); het tenantbestand verwijst ernaar met
`authenticatie.sleutelBestand: <oin>/clientsleutel.jwk.json`. De naam van de key in het Secret is `key` (standaard `clientsleutel.jwk.json`).

## Frontend

De frontend heeft geen secrets nodig: zijn configuratie (`GRAPHQL_URI`, `REST_URI`, `AUTO_IDLE_SESSION_LOGOUT`, `IDLE_TIMEOUT_MINUTES`) is openbaar. Gebruik
`frontend.extraEnv` voor al het andere.

> De backend-tabel is gegenereerd uit `helm/nl-portal/values.schema.json` (`x-secret` per key) en `values.yaml`; `mise run helm:secret-tabel` ververst hem en
> `mise run helm:secret-tabel-check` (CI) faalt als hij afwijkt. De Engelstalige tabel in de [chart-README](https://github.com/pinkroccade/nl-portal/blob/main/helm/nl-portal/README.md#secret-keys) is handmatig.
