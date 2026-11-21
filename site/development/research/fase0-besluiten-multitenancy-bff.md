# Besluitenlog fase 0: multitenancy en authenticatie

Grilling-sessie van 2026-11-20 en 2026-11-21, voorafgaand aan de spec voor de multitenancy-epic
([ADR 0021](../adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md)). Hier staat wat besloten is, kort en met
een verwijzing. De onderbouwing staat in de ADR's en in
[`auth-bff-nlgov-oauth.md`](./auth-bff-nlgov-oauth.md).

## Reikwijdte

- ADR 0021 wordt gerealiseerd als een image, een docker-compose-omgeving om lokaal op te starten,
  configuratie- en flagd-bestanden om multitenancy mee te testen, en een werkende Helm-chart voor het
  Kubernetes-cluster.
- Volgorde: eerst multitenancy (front- en backend, werkend getest); daarna #485 met zijn sub-issues en
  #215, met de configuratie via #143; de security-issues #165, #166 en #167 en de guardrails van #211 gaan
  daarin op; daarna #421 en #191.
- De werkwijze per ticket: een bouw-agent, daarna een reviewer, een securityresearcher (ASVS en overige
  securitychecks), en een fixer die de code niet zelf schreef, net zo lang tot er geen blokkerende
  bevindingen zijn; aan het eind werkt een technical writer de documentatie bij.

## Besluiten

| # | Vraag | Besluit | Vastgelegd in |
|---|---|---|---|
| — | ADR 0021 accepteren | Ja, op 2026-11-21 | ADR 0021 |
| Q1 | Locatie tenantbestanden | Schema en validator publiek; echte tenantbestanden nooit in de publieke repo (voorlopig een bestand, mogelijk later een private repo); demo-tenants *Gemeente De Bosrand* en *Gemeente Het Wijde Veld* met fictieve, gequote OIN's | ADR 0021, aanvulling 2 |
| Q2 | Eén configuratiepad | Altijd multitenant; één gemeente is een cel met één tenant | ADR 0021, aanvulling 1 |
| Q3/Q12 | Verhouding register en ADR 0002 | Diepe `KoppelingClient` in `core.koppeling` via het tenantregister, met RFC 9457-foutafhandeling en één `ResultPage`; scope alle koppelingen; `WebClient` nooit buiten `core` | ADR 0021 aanvulling 6, ADR 0002 |
| Q4/Q10 | ADR 0016 | Geaccepteerd; punt 2 vervangen door 0021; in punt 6 vervalt "geen Reloader" (keuze bij #191) | ADR 0016 |
| Q5 | Type moduleflag | Permission-flag `permission.<module>.enabled`, fail-closed | ADR 0020, ADR 0021 aanvulling 5 |
| Q6 | #165 | Gaat op in tenancy; met de BFF wordt het `iss`/`aud`-validatie tegen de client-registratie per tenant | ADR 0021 besluit 2.6, ADR 0022 |
| Q7/Q11 | #166 | Het terugvalpad en de uid-flow verdwijnen; met de BFF vervalt de converter helemaal. Het organisatiepad blijft in de code maar is per tenant onbereikbaar zolang eHerkenning uit staat | ADR 0022 besluit 7 en 11 |
| Q8/Q13/Q24 | Domeintaal | Burger, Bedrijf (eHerkenning: dienstafnemer), Gebruiker, Vertegenwoordigde, Gemachtigde, Machtiging, Ketenmachtiging, Intermediair, Inlogmiddel, Betrouwbaarheidsniveau; code-term `aanvrager` wordt `vertegenwoordigde` | `GLOSSARY.md` |
| Q9 | Reikwijdte livegang | Alleen de burger voor zichzelf via DigiD of wallet; organisaties en machtigen later, het model is erop voorbereid | ADR 0021 aanvulling 8 |
| Q14 | Gebruikerssleutel | Datatoegang: (tenant, BSN van de vertegenwoordigde) = `LegalSubjectID`, anders `ActingSubjectID`; actor in logs: (`iss`, `sub`), geen BSN in logs; token met `LegalSubjectID` geweigerd zolang machtigen uit staat | ADR 0022 besluit 11 |
| Q15 | ID-token als Bearer | Opgelost doordat er geen Bearer-pad meer is; `at+jwt` (RFC 9068) staat wel aan in het realm-template | ADR 0022 besluit 8 |
| Q16 | Betrouwbaarheidsniveau | Minimum per tenant (standaard DigiD Substantieel), afgedwongen in de backend; afwijking ASVS 6.3.3 L3 vastgelegd | ADR 0022 besluit 12 |
| Q17 | DPoP | Niet nodig voor de browser met een BFF | ADR 0022 besluit 10 |
| Q18 | Authenticatiepatroon | BFF in de bestaande backend | ADR 0022 |
| Q19 | Token exchange | Vervalt, net als de client `nl-portal-m2m` | ADR 0022 besluit 7 |
| Q20 | Clientauthenticatie | `private_key_jwt` vóór livegang, sleutel per tenant uit de secret store | ADR 0022 besluit 9, ADR 0021 aanvulling 4 |
| Q21 | Bearer-pad van buiten | Vervalt | ADR 0022 besluit 8 |
| Q22 | Keycloak-versie | Laatste stabiele release, nu 26.8 (minimaal 26.4) | ADR 0022 besluit 13, ADR 0021 aanvulling 9 |
| Q23 | Sessietijden | 15 minuten inactief, maximaal 8 uur, nieuwe sessie-ID bij inloggen, uitloggen beëindigt ook de Keycloak-sessie | ADR 0022 besluit 5 en 6 |
| — | Clientnaam | `nl-portal-backend` (was `nl-portal-m2m`) | ADR 0022 besluit 1 |

## Open punten voor de spec

- Hoe de login-callback werkt met een `SameSite=Strict`-sessiecookie.
- Het CSRF-mechanisme (anti-forgery-token of een verplichte eigen header met strikte CORS).
- Of de DigiD-koppeling het betrouwbaarheidsniveau als claim doorgeeft.
- Of de DigiD-aansluitvoorwaarden een andere sessieduur eisen.
- De Keycloak-versie van de broker in productie.

## Aanvullingen na ronde 4 (2026-11-21)

| Nr | Onderwerp | Besluit | Vastgelegd in |
|---|---|---|---|
| Q19-borging | Eén authenticatiepad | BFF-login is het enige pad; geborgd in arc42 §8 en met ArchUnit-regels | ADR 0022 besluit 14 |
| Q22-bijstelling | Keycloak-versie | Laatste stabiele release (nu 26.8), minimaal 26.4 | ADR 0022 besluit 13 |
| Q23-bron | DigiD-sessie-eis | Maximaal 15 min inactiviteit (koppelvlakspecificatie 3.7, checklist T15); 8 uur absoluut is eigen keuze | ADR 0022 besluit 5 |
| Q24-bijstelling | Domeintaal | **Bedrijf** in plaats van Organisatie (gangbare domeintaal) | `GLOSSARY.md` |
| Q25 | Specscope | Eén spec voor ADR 0021 en 0022 | Spec-issue |
| Q26 | Backend-seams | De app via HTTP (twee tenants, test-OIDC-provider, nep-koppelingen), `KoppelingClient`, ArchUnit; 80%-mutatiegate op `core` | Spec-issue |
| Q27 | Frontend-seams | Vitest plus een Playwright-suite tegen docker-compose met twee realms | Spec-issue |
| Q28 | Lokale hosts | `debosrand.localhost`, `hetwijdeveld.localhost`, `keycloak.localhost` | Spec-issue |
