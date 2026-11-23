# 1. Inleiding en doelen

## 1.1 Opgave

NL Portal is een open source "Mijn Omgeving"-platform voor de Nederlandse overheid, gebaseerd op
[Common Ground](https://commonground.nl/). Het verzorgt de interactie tussen een **klant** (burger of
ondernemer) en **ketenpartners** (organisaties die bijdragen aan de afhandeling van een verzoek):
berichten, taken, inzicht in zaakdossiers, inzicht in eigen gegevens (BRP), track-and-trace van
zaken, en (in ontwikkeling) domein-specifieke gegevens.

NL Portal doet zelf geen zaakafhandeling, is geen formulierencomponent en geen CMS — het haalt data
op bij bronsystemen (primair via VNG's "ZGW API's voor Zaakgericht Werken") en toont die aan de
klant. Zie [`site/architectuur/applicatiearchitectuur.md`](../applicatiearchitectuur.md)
voor de volledige productscope.

Motto van het project: **"create once, use 340 times"** — iedere (Nederlandse) overheidsorganisatie
kan het vrij gebruiken en verbeteren. Dat motto is architecturaal relevant: het drijft de nadruk op
configureerbaarheid, standaarden (ZGW API's) in plaats van maatwerkkoppelingen, en het scheiden van
data (bij de bron) en applicatielogica (in NL Portal).

## 1.2 Kwaliteitsdoelen

De kwaliteitsdoelen zijn vastgelegd als toetsbare principes in het
[principeregister](../principes.md) ([ADR 0023](../../development/adr/0023-architectuurprincipes-en-toetsing.md)).
Elk principe heeft een normatieve bron en een toetsing; `mise run compliance:check` dwingt dat af. De tabel
hieronder koppelt de kwaliteitsdoelen aan de principes die ze uitwerken.

| # | Kwaliteit | Waarom dit prioriteit heeft | Principes |
|---|---|---|---|
| 1 | **Vertrouwd / beveiligd** | Verwerkt BRP-gegevens en overheidsidentiteit (DigiD/eHerkenning); een van de vijf Common Ground-principes is expliciet "vertrouwd". | P-01 t/m P-07 |
| 2 | **Herbruikbaarheid / portabiliteit** | "340 keer hergebruiken" vereist dat het platform generiek blijft en niet vastgroeit aan de databron van één gemeente. | P-07, P-08, P-12 |
| 3 | **Interoperabiliteit** | Moet werken tegen de ZGW-API-standaard en tegen meerdere implementaties daarvan (Open Zaak, OpenKlant, ...), niet tegen één specifieke leverancier. | P-09, P-10, P-12 |
| 4 | **Onderhoudbaarheid** | Open source met een bredere contributorbasis dan één team; een lage instapdrempel voor nieuwe bijdragers is een expliciet doel (zie `CONTRIBUTING.md`). | P-08, P-13, P-14, P-15 |
| 5 | **Toegankelijkheid** | Wettelijk verplicht voor overheidswebsites; een burger die de portal niet kan gebruiken, wordt uitgesloten van zijn eigen zaken. | P-11 |
| 6 | **Testbaarheid** | Backend- en frontend-CI draaien verplicht tests; zie hoofdstuk 10 voor concrete scenario's. | P-13, P-15 |

Toegankelijkheid is als zesde doel opgenomen (P-11).

## 1.3 Stakeholders

| Rol | Belang |
|---|---|
| Burger / ondernemer (eindgebruiker) | Begrijpelijk, snel en betrouwbaar inzicht in eigen zaken en gegevens |
| Ketenpartner-medewerker | Zicht op voor hen relevante zaakinformatie en taken |
| Gemeente / overheidsorganisatie (afnemer) | Platform dat aansluit op hun eigen ZGW-landschap zonder vendor lock-in |
| pinkroccade (eigenaar/maintainer) | Kwaliteit en coherentie van het open source product bewaken |
| Bijdragende ontwikkelaars (community) | Begrijpelijke, goed gedocumenteerde codebase om in bij te dragen |
