# 24. Eigen inlogpagina, Keycloak als ID-broker en afdwinging van inlogmiddelen

## Status

Geaccepteerd op 2026-11-25, in de grilling-sessie van epic [#485](https://github.com/pinkroccade/nl-portal/issues/485)
(ticket #486). Bouwt op [ADR 0021](./0021-gedeelde-multi-tenant-runtime-met-cellen.md) (realm per tenant, een
uitgeschakeld inlogmiddel is technisch onmogelijk) en [ADR 0022](./0022-backend-for-frontend-als-authenticatiepatroon.md)
(de backend is de OIDC-client en houdt de portaalsessie). Wijzigt ADR 0021 besluit 3 op één punt: het realm
is genoemd naar de gemeente (`de-bosrand`) in plaats van naar de OIN, en de clients heten `nl-portal-backend` en
`open-formulieren`. Nog niet gebouwd; de bouw loopt via de sub-tickets
#487 tot en met #495.

## Context

De frontend stuurt een niet-ingelogde bezoeker rechtstreeks naar de Keycloak-loginpagina, zonder te zeggen welk
inlogmiddel hij wil. Dat heeft drie nadelen:

- De huisstijl staat op twee plekken: in de frontend en in een Keycloak-thema.
- Per gemeente en omgeving sturen welke inlogmiddelen te zien zijn kan niet; de keuze ligt bij Keycloak.
- Dat een uitgeschakeld inlogmiddel niet bruikbaar is, hangt af van wat Keycloak toont. Wie de login rechtstreeks
  aanroept, komt er mogelijk toch langs.

Het doel is een eigen inlogpagina volgens NL Design System, met per tenant en omgeving instelbare inlogmiddelen, waarbij
een uitgeschakeld middel niet alleen verborgen maar technisch onmogelijk is (ASVS 5.0: 6.1.3, 6.3.4, 6.8.1, 8.3.1,
9.2.2, 9.2.3, 10.3.1, 10.4.4).

## Overwogen opties

### Waar de afdwinging zit

| Optie | Voordeel | Waarom niet of later |
|---|---|---|
| **Gelaagd: realm, backend, baseline; frontend alleen UX** (gekozen) | Elke laag vangt een fout in een andere op; geen enkele laag steunt op de browser | Meer plekken die dezelfde lijst lezen; opgevangen door één bron per tenant |
| Alleen de frontend verbergt kaarten | Eenvoudig | Geen afdwinging; de login is rechtstreeks aan te roepen |
| Flow-override per client in Keycloak | Per client een eigen flow | De beperking staat dan op de client, niet op de realm; `admin-cli` en andere clients blijven buiten bereik, en de configuratie is per tenant moeilijk te toetsen |
| Betrouwbaarheidsniveau (ACR/LoA) als filter | Standaardmechanisme | Zegt iets over de sterkte, niet over het middel; DigiD Basis en gebruikersnaam/wachtwoord zijn zo niet te scheiden |
| Nu al verder gaan met de BFF (PAR, `private_key_jwt` overal, claimcheck bij sessie-aanmaak) | Past bij ASVS L3 | Hoort bij de uitwerking van ADR 0022; de volgorde hier verandert daar niets aan |

### Waar de inlogmiddelen vandaan komen

Een feature flag valt af: vóór het inloggen bestaat er geen evaluatiecontext (zie ADR 0020). De bron is de
tenantconfiguratie (ADR 0016, ADR 0021).

## Besluit

1. **Een eigen inlogpagina**, publiek bereikbaar op `/inloggen`, met een kaart per inlogmiddel volgens NL Design
   System. De pagina verschijnt alleen als er geen portaalsessie is. Een deeplink keert na het inloggen terug op
   dezelfde pagina (bestaande controle op hetzelfde domein). Uitloggen eindigt op `/inloggen`. Het starten van een
   login zit achter één functie `login(middel)`; de pagina kent de vorm van de OIDC-flow niet.
2. **Keycloak is altijd de ID-broker.** Per gemeente één realm (genoemd naar de gemeente, niet naar een applicatie:
   `de-bosrand`) en per applicatie één client (`nl-portal-backend`, `open-formulieren`). Acceptatie en productie
   hebben elk een eigen Keycloak. Elk inlogmiddel heeft een vaste **IdP-alias**, voor alle tenants gelijk:
   `digid`, `digid-machtigen`, `yivi` en `gebruikersnaam`, later `eherkenning` en `eidas`. Wat bij de livegang open staat
blijft bepaald door ADR 0021 besluit 8; dit is het register, niet de reikwijdte van de livegang. Alleen `digid` en
`eherkenning` bestaan nu in de code; de overige aliassen komen met #487. Een kaart start de login met
   die alias als `kc_idp_hint`. Omdat de backend de client is (ADR 0022), zet de backend de hint op de
   authorization request; de browser stuurt alleen het gekozen inlogmiddel mee.
3. **Eén bron per tenant.** De actieve inlogmiddelen staan in het tenantbestand. Frontend (kaarten), backend
   (toegestane middelen), realm-as-code en baseline lezen dezelfde lijst. Er is geen tweede lijst.
4. **Harde afdwinging, in lagen.**
   1. *Realm.* In productie geen wachtwoordstap in de browser- en first-broker-flow, geen lokale gebruikers,
      registratie uit en inactieve IdP's afwezig. Het wachtwoord-grant (ROPC) wordt realmbreed geweigerd, ook voor
      `admin-cli`, met client policies en een direct-grant-flow die alles weigert.
   2. *Backend.* De callback accepteert alleen een login waarvan de session-note `identity_provider` bij de toegestane
      middelen van de tenant hoort, naast de controles op `iss`, `aud` en `azp`. De backend vraagt bij elke login om een nieuwe authenticatie
      (`prompt=login`), zodat een hergebruikte SSO-sessie het gekozen middel niet kan vervangen. De bron voor het gebruikte middel is
      die session-note en niet een gebruikersattribuut, want een attribuut zegt niets over de huidige sessie.
   3. *Baseline.* Een policy-as-code-controle in CI en tijdens runtime op het geëxporteerde realm (onder meer: geen
      performanceclient in productie).
   4. *Frontend.* Een uitgeschakeld middel niet renderen of laden is alleen UX.

   Lokaal en in de performanceomgeving blijven ROPC en lokale gebruikers bestaan, uitsluitend via de realmconfiguratie
   van die omgeving, nooit via een codevlag.
5. **Gebruikersnaam en wachtwoord** bestaan alleen lokaal, in dev en in acceptatie, via het standaardformulier van
   Keycloak zonder eigen thema.
6. **De DigiD-kaart volgt Logius:** het officiële logo, de knoppen "Inloggen" en "Inloggen als gemachtigde" (DigiD
   Machtigen is een eigen inlogmiddel met een eigen alias, geen afgeleide claim), een link naar `https://digid.nl` en
   geen eigen uitleg over DigiD. De pagina toont de naam van de organisatie (C2), een melding bij annuleren (T3), de
   letterlijke fouttekst (T12), geen pop-ups (T2) en de uitlogoptie de hele sessie (T9). De sessie is hoogstens 15
   minuten inactief (T10); de configuratievalidatie en het realm dwingen dat allebei af.
7. **Yivi via Ver.id** krijgt eerst een spike (BSN-claim, betrouwbaarheidsniveau, Handreiking Eenmalig inloggen). DigiD
   draait lokaal tegen een mock-IdP en in dev en acceptatie tegen DigiD-preprod; Yivi lokaal en in acceptatie tegen de
   acceptatieflow van Ver.id.
8. **Eenmalig inloggen met Open Formulieren** binnen het realm van de tenant, onder de voorwaarden van DigiD Eenmalig
   inloggen: (sub)domeinen van de gemeente (T6), single logout via Keycloak, realm-inactiviteit van hoogstens 15
   minuten, en de melding bij Logius (4.4) en de hostingtoestemming (3.7) vastgelegd in de deployment-guide.
9. **Toegankelijkheid:** WCAG 2.2 AA is de eis, AAA het streven. Teksten per tenant via `CUSTOM_MESSAGES`; logo's zijn
   assets in het pakket.

## Gevolgen

- De huisstijl van de inlogpagina staat op één plek, in de frontend. Keycloak toont alleen het standaardformulier voor
  gebruikersnaam en wachtwoord, en alleen waar dat is toegestaan.
- De bestaande `inlogmiddelen` in het tenantbestand (ADR 0021) worden uitgebreid met `digid-machtigen`, `yivi` en
  `gebruikersnaam` en krijgen elk een IdP-alias; de waarden `wallet` en `test` worden daarop afgestemd (#487).
- Het eenvoudige keuzescherm dat de frontend nu bij meerdere middelen toont, gaat op in de nieuwe pagina (#491).
- eHerkenning en eIDAS volgen via hetzelfde register, zonder codewijziging in de pagina.

## Restrisico's

- **Keycloak-beheerders** kunnen een gebruiker nabootsen of flows ombouwen. Mitigatie: MFA voor beheer, auditlog en de
  baseline-controle.
- **Semantiek van de session-note** bij hergebruik van een SSO-sessie en bij het koppelen van accounts verschilt per
  Keycloak-versie. Mitigatie: een integratietest per Keycloak-versie die we draaien.
- **Publieke clients.** Voor zover een flow nog via een publieke client loopt (bijvoorbeeld Open Formulieren), haalt die
  ASVS L3 10.4.16 en 10.3.5 niet; ADR 0022 beschrijft de weg naar vertrouwelijke clients.

## Bronnen

- [NL Design System: Login Link](https://www.nldesignsystem.nl/login-link) en
  [WCAG 3.3.9](https://www.nldesignsystem.nl/wcag/3.3.9/).
- [Logius: checklist aansluiten op DigiD](https://www.logius.nl/onze-dienstverlening/toegang/digid/documentatie/checklist-aansluiten-op-digid-en-digid-machtigen)
  en [aansluitvoorwaarden DigiD Eenmalig inloggen](https://www.logius.nl/onze-dienstverlening/toegang/digid/documentatie/aansluitvoorwaarden-digid-eenmalig-inloggen).
- [eToegang: gebruikersinterface](https://afsprakenstelsel.etoegang.nl/Startpagina/as/gebruikersinterface).
- [Ver.id: Keycloak-integratie](https://docs.ver.id/integrations/keycloak).
