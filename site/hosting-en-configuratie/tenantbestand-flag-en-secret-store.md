# Tenantbestand, flag en secret store

> **Voorgesteld, nog niet geïmplementeerd.** Deze pagina beschrijft het doelbeeld uit
> [ADR 0021](../development/adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md) (status: voorgesteld). Wat vandaag
> werkt, staat in de [Deployment guide](./deployment-guide.md): één deployment is nu één gemeente.

Als NL Portal als gedeelde dienst voor meerdere gemeenten (tenants) draait, staat de inrichting van een
tenant op drie verschillende plekken. Elke plek heeft een eigen soort inhoud en een eigen wijzigingsroute.
Het onderscheid is bewust: zo weet je altijd waar je een wijziging doet en wat die kost.

## De drie lagen

| | Tenantbestand | Flag | Secret store |
|---|---|---|---|
| **Wat staat erin** | Domeinen, token-issuer, URL's van koppelingen, thema, de cel waarin de tenant draait, en *verwijzingen* naar secrets (op naam) | Alleen aan of uit per module, per tenant | De secrets zelf: client secrets, API-sleutels, sleutelmateriaal |
| **Wat staat er nooit in** | Secrets en tokens | URL's, credentials, tenantconfiguratie, beveiligingsbesluiten | Niets anders dan secrets |
| **Waar leeft het** | Git, één bestand per tenant per omgeving | Het flagbestand (OpenFeature/flagd) | De secret store, ontsloten via External Secrets Operator |
| **Hoe wijzig je het** | Pull request, daarna uitrol door de gitops-tooling | Het flagbestand aanpassen | Roteren in de secret store |
| **Wanneer werkt het** | Na een rolling restart | Direct, zonder herstart | Na een rolling restart |

Een wijziging in het tenantbestand of de secrets herstart alleen de cel van die tenant, nooit de hele
dienst. Er is geen hot reload en er is geen Configuration Panel meer.

## Wat doe je waar

- **Een koppeling instellen of wijzigen** (bijvoorbeeld de URL van de Zaken API): in het tenantbestand.
  Dit kost een rolling restart van de cel.
- **Het thema, logo of de domeinnaam wijzigen**: in het tenantbestand, ook via een rolling restart.
  Zie [Eigen vormgeving](../gebruik-en-inrichting/eigen-vormgeving.md) voor de ontwerpkant.
- **Een secret toevoegen of roteren** (bijvoorbeeld het client secret van een koppeling): in de secret store.
  Het tenantbestand verwijst alleen naar de naam. De nieuwe waarde komt na een rolling restart van de cel in
  gebruik.
- **Een module voor een tenant aan- of uitzetten**: met de flag van die module. Ook dit landt via een rolling restart van de eigen cel. Een module
  is alleen bruikbaar als de configuratie in het tenantbestand aanwezig is **én** de flag aanstaat. Een flag
  zonder configuratie doet niets (CI waarschuwt hiervoor).
- **Een module uitschakelen voor één tenant**: de flag van die tenant uitzetten. Dat is een rolling restart van
  alleen zijn cel; een noodrem zonder herstart bestaat niet (ADR 0014 punt 7).

## Een module uitrollen in twee stappen

1. Zet de configuratie van de module in het tenantbestand en rol uit via een rolling restart. De module blijft
   uit, want de flag staat nog uit. Zo zie je fouten voordat een burger iets merkt (een onvolledige tenant
   start niet, en de oude pods blijven draaien).
2. Zet de flag voor die tenant aan, opnieuw via een rolling restart van de cel.

## Waarom geen URL of secret in een flag

Een flag-systeem is bedoeld voor schakelaars. Een URL is zonder secret niet bruikbaar en secrets horen
nooit in een flag-systeem. Daarom bevat een flag alleen aan of uit. Ook flagwijzigingen landen via een
rolling restart van de eigen cel; er is geen uitzondering op "elke wijziging is een rolling restart". Het
flagbestand wordt wel periodiek herladen, maar het ontwerp rekent daar niet op.

## Wat al werkt: het tenantbestand voor tenantresolutie

Sinds epic #499 (stap 2) leest de runtime een map met tenantbestanden: `base.yaml` (de gedeelde basis van de
omgeving, optioneel) en per tenant `<oin>.yaml`, dat de basis overschrijft. De map staat in
`NL_PORTAL_TENANTS_DIRECTORY` en is verplicht; `docker-compose/imports/tenants` bevat de demo-tenant *De Bosrand*.

```yaml
oin: "00000099000000000001"   # altijd tussen aanhalingstekens
naam: Gemeente De Bosrand
omgevingstype: ontwikkeling   # productie | acceptatie | test | ontwikkeling (mag in base.yaml)
domeinen: [localhost]
thema:
    titel: Mijn De Bosrand
inlogmiddelen: [test]         # digid | eherkenning | wallet | test (test niet in productie)
minimumNiveau: substantieel   # basis | midden | substantieel | hoog (standaard substantieel)
sessie:
    inactiviteit: PT15M       # hoogstens 15 minuten
    absoluut: PT8H            # hoogstens 8 uur
```

De tenant volgt uit de host van het request (kleine letters, zonder poort en trailing dot, exact tegen
`domeinen`). `Forwarded` en `X-Forwarded-Host` tellen alleen van de adressen in `NL_PORTAL_TRUSTED_PROXIES` (de
ingress); van elk ander adres telt alleen de `Host`-header. Een onbekende host krijgt een kale 404.

Koppelingen en modulegegevens staan in hetzelfde bestand. Geheimen staan er nooit zelf in, alleen als naam van een geheim in de secret store:

```yaml
koppelingen:
    zaken:                    # de modulenaam, of een eigen naam voor een tweede koppeling van dezelfde module
        url: https://zaken.example.nl
        leverancier: Open Zaak
        auth:
            type: zgw-jwt     # api-key | bearer | zgw-jwt | oauth2-client-credentials | oauth2-private-key-jwt
            clientId: bosrand # per tenant, nooit uit base.yaml
            secret: bosrand-zaken-secret   # naam van het geheim; de waarde staat in de secret store
modules:
    brp:                      # de configuratie die de module bijdraagt (TenantModuleConfiguratie)
        brpFields: [naam, geboortedatum]
```

De runtime weigert te starten bij een koppeling zonder verplichte velden voor haar `auth.type` (de melding noemt de OIN), een geheim dat geen naam
is (de waarde zelf wordt nooit getoond), een geheim dat niet in de secret store staat, en een `modules.<naam>` zonder bijbehorende module. Credentials en
geheimverwijzingen mogen niet in `base.yaml` staan. Geheimen lost de runtime op uit de omgeving: de naam `bosrand-zaken-secret` is de omgevingsvariabele
`NL_PORTAL_GEHEIMEN_BOSRAND_ZAKEN_SECRET` (of de property `nl-portal.geheimen.bosrand-zaken-secret`). De validator-CLI kent de modules die op het
klassenpad staan: `java -cp nl-portal-tenant-validator.jar:<module-jars> nl.nlportal.core.tenancy.TenantValidatorCli <map>`.

Eisen aan de omgeving (ook als acceptatiecriterium voor de ingress in #191):

- **De ingress overschrijft of verwijdert zowel `Forwarded` als `X-Forwarded-Host`** die de client meestuurt.
  Gangbare ingress-controllers overschrijven alleen `X-Forwarded-Host` en laten een `Forwarded` van de client staan.
  De runtime geeft 404 als beide headers er zijn en een andere host noemen, en bij een `Forwarded` zonder `host=`,
  maar dat is een vangnet en geen vervanging.
- **`server.forward-headers-strategy` staat op `none`** (de app levert dat zo mee). Spring Boot zet de eigen
  forwarded-verwerking op Kubernetes aan als de waarde ontbreekt; die volgt de headers van elk adres. De runtime
  start niet met `native`, met `framework`, met een `ForwardedHeaderTransformer`, of op Kubernetes zonder expliciete
  waarde (`ForwardedHeadersGuard`). `NL_PORTAL_TRUSTED_PROXIES` weigert een blok dat alle adressen vertrouwt (`/0`).
- **Domeinen staan als ASCII**: een internationale domeinnaam staat als A-label (`xn--...`) in het tenantbestand;
  een host met Unicode-tekens matcht nooit en krijgt 404.
- **De managementpoort is alleen intern bereikbaar** (Service en NetworkPolicy, #191); hij luistert op alle interfaces.
- Er is geen `base.yaml` én `base.yml` tegelijk: dat weigert het opstarten.

Het JSON-schema staat in `backend/app/schema/tenant.schema.json` en wordt uit de Kotlin-typen gegenereerd
(`mise run backend:tenant-schema`). De validator draait bij opstarten en als CLI voor CI:
`mise run backend:tenant-validator` bouwt `backend/core/build/libs/nl-portal-tenant-validator.jar`, en
`java -jar nl-portal-tenant-validator.jar <map>` geeft exitcode 0 bij een geldige map.

## Controles

- **In CI:** elk tenantbestand wordt tegen een schema gevalideerd (onder andere gequote OIN's), domeinen en
  `keyId`'s moeten over alle tenants uniek zijn, en een overzicht voegt tenantbestanden en flags samen tot een
  modulematrix.
- **Bij opstarten:** de runtime weigert te starten bij een incomplete tenant, een dubbel domein of dubbele
  `keyId`, of een token-issuer die niet bij de publieke ID-broker hoort.

## Zie ook

- [ADR 0021](../development/adr/0021-gedeelde-multi-tenant-runtime-met-cellen.md), de beslissing en de afweging.
- [ADR 0016](../development/adr/0016-stateless-configuratie-en-thema-via-bestanden.md), configuratie via bestanden.
- [Deployment guide](./deployment-guide.md#feature-flags), hoe flags vandaag werken.
