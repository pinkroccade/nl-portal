# Keycloak configuratie

NL Portal gebruikt Keycloak als OIDC-broker voor DigiD (en later eHerkenning en de wallet). De backend is de
backend-for-frontend (BFF, [ADR 0022](../development/adr/0022-backend-for-frontend-als-authenticatiepatroon.md)): hij is zelf de
OIDC-client, houdt de portaalsessie bij en geeft de browser alleen cookies (zie
[arc42 8.1](../architectuur/arc42/08-dwarsdoorsnede-concepten.md)). Er is geen publieke SPA-client meer, geen token exchange en geen
`nl-portal-m2m`-client.

## Eén client per realm: `nl-portal-backend`

Elke tenant heeft een eigen realm op de publieke ID-broker, gegenereerd uit het ene template onder `keycloak/realm-template/`
(`mise run dev:setup` doet dat lokaal; zie [keycloak/README.md](https://github.com/pinkroccade/nl-portal/blob/main/keycloak/README.md)).
Het realm bevat precies één client:

| Instelling | Waarde |
| --- | --- |
| Client-id | `nl-portal-backend` |
| Type | vertrouwelijk, alleen standard flow (geen direct access grants, geen service account, geen device flow) |
| Clientauthenticatie | "Signed JWT" (`private_key_jwt`), algoritme `PS256`; de publieke sleutel staat als JWKS in het realm, nooit een `client_secret` |
| PKCE | `S256` verplicht |
| Redirect-URI's | exact: `https://<domein van de tenant>/login/oauth2/code/<OIN>` per domein uit het tenantbestand |
| Back-channel logout | de URL staat in het realm; de backend beantwoordt hem nog niet (zie de open punten in ADR 0022) |
| Access-token | `at+jwt` (RFC 9068) aan |

De mappers zetten de gegevens van het inlogmiddel in het **ID-token** (nooit in het access-token):

| Claim | Betekenis |
| --- | --- |
| `acr` | het betrouwbaarheidsniveau van de login; de backend zet het om met de tabel in [arc42 8.1](../architectuur/arc42/08-dwarsdoorsnede-concepten.md) |
| `inlogmiddel` | `digid`, `eherkenning`, `wallet` of (alleen ontwikkeling) `test` |
| `acting_subject_id` | het BSN van de ingelogde persoon; negen cijfers met geldige elfproef |
| `legal_subject_id` | het identificerende nummer van de vertegenwoordigde; geweigerd zolang machtigen voor de tenant uit staat |

Een onbekende of lege `acr` wordt door de backend geweigerd, ook als Keycloak de login toestond: Keycloak laat een lager niveau door,
dus de backend toetst altijd zelf en vergelijkt het met het minimumniveau van de tenant.

## Koppeling met de backend

De realm-URL (issuer) en de clientsleutel staan per tenant in het tenantbestand (`authenticatie`, zie
[tenantbestand](./tenantbestand-flag-en-secret-store.md)):

```yaml
authenticatie:
    issuer: https://login.gemeente.nl/realms/<realm>
    sleutelBestand: <OIN>/clientsleutel.jwk.json   # relatief aan nl-portal.sleutelmap
    # clientId: nl-portal-backend                  # standaard
    # interneBasisUrl: http://keycloak.keycloak.svc:8080   # optioneel: interne route naar Keycloak
```

De backend zelf krijgt, naast het tenantbestand, deze instellingen (zie ook de [deployment guide](./deployment-guide.md)):

| Variabele | Betekenis |
| --- | --- |
| `KEYCLOAK_BROKER_URL` (`nl-portal.authentication.broker-url`) | Basis-URL van de publieke ID-broker. Een issuer in het tenantbestand moet eronder vallen; een andere issuer weigert het opstarten. |
| `NL_PORTAL_SLEUTELMAP` (`nl-portal.sleutelmap`) | Vaste map met per tenant een map met de OIN als naam, waarin de private clientsleutel staat. |
| `REDIS_*` | De sessieopslag (verplicht voor elke cel): host, poort, gebruiker en wachtwoord van een Redis met ACL en zonder persistentie. |

Aandachtspunten:

* **Issuer.** `issuer` moet teken voor teken gelijk zijn aan de `iss` van het ID-token, zonder slot-slash. In productie is het een
  `https`-URL zonder gebruikersnaam, query of fragment; `http` mag alleen voor een ontwikkeltenant.
* **Interne route.** Bereikt de backend Keycloak intern, zet dan `interneBasisUrl`. Het token-endpoint, de sleutelset en de
  logout-URL volgen daaruit; de issuer blijft het publieke adres. Laat Keycloak de publieke URL in de tokens zetten (`KC_HOSTNAME` op de
  publieke URL en `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`).
* **Clientsleutel.** RSA van minimaal 3072 bit, JWK met `alg: PS256` en een `kid`; elke tenant heeft een eigen sleutel en de `kid` is uniek
  over alle tenants. Het bestand moet onder `<sleutelmap>/<OIN>/` liggen (symlinks worden opgelost; ontsnapping weigert het opstarten) en is
  alleen leesbaar voor de gebruiker van de backend (0600). Draait de container onder een andere uid dan de eigenaar van het bestand, dan
  kan hij de sleutel niet lezen en start de backend niet: geef het bestand die uid als eigenaar (in de docker-compose gebeurt dat via
  `LOCAL_UID`/`LOCAL_GID`, die `mise run dev:setup` in `docker-compose/.env` zet) of laat de secret store (ESO) het bestand met de juiste
  eigenaar mounten. Verruim de rechten niet naar 0644.
* **Rotatie.** Zet tijdens een sleutelrotatie beide publieke sleutels in de JWKS van het realm en wissel daarna het bestand.
* **Gebruikersevents blijven uit** (`eventsEnabled: false`): de events bevatten de NameID (BSN) en het IP-adres. De backend logt zelf elke
  inlogpoging als securityevent met OIN en actor, zonder BSN.

## Gebruikers en externe identity providers

De BSN's en niveaus komen van de koppeling met de inlogmiddelen, niet uit gebruikersattributen die je met de hand zet. Voor DigiD
(SAML) gebruikt het realm de mappers van het eigen Keycloak-image (`nl-portal-keycloak`, bouw het met `mise run keycloak:image`) die
`AuthnContextClassRef` en BSN uit de gevalideerde assertion in de sessie zetten; de koppeling moet de handtekening controleren
(`validateSignature`, `wantAssertionsSigned` en een vastgepind certificaat), anders weigert de mapper de login. Zie
[keycloak/README.md](https://github.com/pinkroccade/nl-portal/blob/main/keycloak/README.md) voor de mappers en de integratietest.

In de ontwikkelomgeving staat een testgebruiker `burger` (BSN `999993847`, `inlogmiddel=test`, `acr=Basis`), alleen in het
ontwikkelrealm; het wachtwoordhash staat in `keycloak/realm-template/demo-gebruikers.json`. Een gebruiker `bedrijf` bestaat niet meer.

Keycloak bewaart bij een gekoppelde (federated) identiteit de NameID, met daarin het BSN, in zijn database. Dat is een bekend restrisico
(T26, ASVS-verificatierapport): beperk de toegang tot de beheerconsole en de database.

## Referentieconfiguratie

De docker-compose-omgeving in de monorepo is een volledig werkend voorbeeld:

* `docker-compose/docker-compose.yaml` — Keycloak, Redis (`nl-portal-sessies`) en het configuratieproces `nl-portal-keycloak-config`.
* `docker-compose/imports/tenants/` — het tenantbestand van de demo-tenant De Bosrand, met het `authenticatie`-blok.
* `keycloak/realm-template/` — het realm-template; `mise run dev:setup` genereert er per tenant een realm en een clientsleutelpaar uit.

**Let op bij een bestaande omgeving:** keycloak-config-cli werkt het realm bij, dus een wijziging in het template of de tenantbestanden neem
je over met `mise run dev:setup` en daarna `docker compose run --rm nl-portal-keycloak-config`. Handmatige wijzigingen in een door kcc
beheerd onderdeel worden teruggezet. Voor een geheel verse omgeving: `docker compose down -v` vanuit `docker-compose/` (verwijdert de
volumes van de **hele** demo-stack); wil je alleen Keycloak resetten, verwijder dan gericht het volume van `nl-portal-keycloak-database`.
