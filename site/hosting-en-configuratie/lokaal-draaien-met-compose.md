# Lokaal draaien met Compose

Voor beheerders en ontwikkelaars die NL Portal lokaal opzetten en er eigen API's of mocks op aansluiten. Starten, inloggen, profielen en poorten staan in de [README van `docker-compose/`](https://github.com/pinkroccade/nl-portal/blob/main/docker-compose/README.md); deze pagina beschrijft wat daarna komt.

## Het netwerk `mijngemeente`

De stack hangt standaard aan het Docker-netwerk `mijngemeente`. Andere applicaties uit het mijngemeente-ecosysteem draaien op hetzelfde netwerk, zodat ze elkaar op hostnaam bereiken, bijvoorbeeld `http://nl-portal-openzaak:8001`.

- **Wie het netwerk aanmaakt:** de eerste stack die start zonder `external: true`, dus de NL Portal-stack zelf. Een stack die als extern netwerk aansluit (zoals de mockserver hieronder) maakt het niet aan en faalt als het netwerk er nog niet is. Bestaat het netwerk al, gemaakt door een andere stack of met de hand, dan gebruikt `docker compose up` het toch en toont een waarschuwing dat het niet door deze stack is aangemaakt. Dat is geen fout.
- **Opruimen:** `docker compose down` probeert het netwerk te verwijderen, maar dat lukt niet zolang er containers van een andere applicatie op zitten; Compose meldt dan dat het netwerk nog in gebruik is.
- **Een ander netwerk:** zet `NL_PORTAL_NETWORK` in `docker-compose/.env`. De taken `mise run dev:diensten` en de `perf:*`-taken zetten zelf een eigen netwerk, zodat ze een draaiende quickstart niet raken.

## Een eigen API of mock aansluiten

1. **Zet de API op het netwerk.** Een eigen Compose-bestand sluit aan als extern netwerk:

   ```yaml
   networks:
       default:
           name: ${NL_PORTAL_NETWORK:-mijngemeente}
           external: true
   ```

   De containernaam is de hostnaam. [`voorbeelden/mockserver`](https://github.com/pinkroccade/nl-portal/tree/main/docker-compose/voorbeelden/mockserver) is een werkend voorbeeld met MockServer.

2. **Wijs de koppeling ernaar.** Zet de `url` van de koppeling in het tenantbestand (`docker-compose/imports/tenants/00000099000000000001.yaml`, onder `koppelingen.<naam>`) op de hostnaam van de container. Voor een API zonder authenticatie is `auth.type: geen`. Zie [Tenantbestand, flag en secret store](./tenantbestand-flag-en-secret-store.md) voor alle velden en authenticatietypes.

3. **Lever het geheim aan.** Het tenantbestand bevat alleen de *naam* van een geheim, nooit de waarde; hoe een naam een omgevingsvariabele wordt staat in [Tenantbestand, flag en secret store](./tenantbestand-flag-en-secret-store.md). Lokaal zet je de variabele onder `nl-portal-backend` in `compose.yaml`; de waarden komen uit `docker-compose/.env`, dat `mise run dev:setup` genereert (bijvoorbeeld `OPENZAAK_NLPORTAL_SECRET`). Als filemount kan het ook: een bestand onder `docker-compose/imports/koppelingen/` met het pad `nl-portal/geheimen/<naam>` en de waarde als inhoud, zie de [README van die map](https://github.com/pinkroccade/nl-portal/blob/main/docker-compose/imports/koppelingen/README.md). Een clientsleutel (`privateKeyBestand`, `authenticatie.sleutelBestand`) staat in de sleutelmap: `NL_PORTAL_SLEUTELMAP`, lokaal `docker-compose/.generated/tenants`.

4. **Herstart de backend** vanuit `docker-compose/`, zodat hij het tenantbestand opnieuw leest:

   ```shell
   docker compose restart nl-portal-backend
   ```

5. **Controleer de koppeling** in de [modulestatus](#modulestatus-uitlezen).

## Modulestatus uitlezen

Werkt een koppeling niet, kijk dan eerst naar de modulestatus op de beheerpoort. Die toont per module en API of de koppeling bereikbaar is, en de laatste foutcode:

```shell
curl -u "beheer:$BACKEND_BEHEER_PASSWORD" http://127.0.0.1:8091/actuator/modulestatus
```

`BACKEND_BEHEER_PASSWORD` staat in `docker-compose/.env`. De gebruikersnaam is `beheer` (`NLPORTAL_BEHEER_GEBRUIKERSNAAM` in `imports/backend.env`) en de poort volgt `BEHEER_PORT`. De statussen, velden en beveiliging staan in [Beheerpoort: modulestatus](./beheer-modulestatus.md). Een module die aan staat zonder koppeling faalt bij het eerste gebruik en toont dan `NOT_CONFIGURED`. Bij sommige modules (bijvoorbeeld KvK en BRP) weigert de backend zelfs te starten; de melding in de log noemt dan de ontbrekende koppeling.

## Zie ook

- [Feature flags: referentie](./feature-flags.md): modules aan of uit zetten in `imports/flags/nl-portal.flagd.json`.
- [Connectiviteit](./connectiviteit.md): een API-account aanmaken in het aan te sluiten systeem.
