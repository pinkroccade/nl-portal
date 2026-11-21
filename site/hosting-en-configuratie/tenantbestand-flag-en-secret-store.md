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

De clientsleutel van de login (`authenticatie.sleutelBestand`) staat onder de vaste sleutelmap `nl-portal.sleutelmap` (`NL_PORTAL_SLEUTELMAP`, verplicht), in een map met de OIN van de tenant als naam; het pad in het tenantbestand is relatief aan de sleutelmap. De runtime start niet als het bestand ontbreekt, geen bruikbare sleutel is (privé RSA, minstens 3072 bit, `alg: PS256`, met `kid`), na het oplossen van symlinks buiten `<sleutelmap>/<oin>/` ligt, of als twee tenants dezelfde `kid` of sleutel delen. De validator-CLI toetst het pad altijd en met `--sleutelmap=<map>` ook het bestand zelf.

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

Het adres van de nginx-sidecar van de frontend (`127.0.0.1`/`::1`) hoort **nooit** in `NL_PORTAL_TRUSTED_PROXIES`: de sidecar
stuurt de `Host`-header van de browser door en overschrijft `X-Forwarded-Host` met diezelfde waarde en `Forwarded` met een lege
waarde, maar vertrouwt de ingress ervoor niet. Zet alleen het adres of blok van de ingress zelf in de lijst. De sidecar meldt
`Strict-Transport-Security` alleen als de ingress `X-Forwarded-Proto: https` meegeeft. De CSP heeft bewust geen `form-action`:
Chromium toetst die ook op de redirect na de uitlog-POST naar Keycloak, en de broker-URL is in de sidecar niet bekend.

Koppelingen en modulegegevens staan in hetzelfde bestand. Geheimen staan er nooit zelf in, alleen als naam van een geheim in de secret store:

```yaml
koppelingen:
    zaken:                    # de modulenaam, of een eigen naam voor een tweede koppeling van dezelfde module
        url: https://zaken.example.nl
        leverancier: Open Zaak
        auth:
            type: zgw-jwt     # api-key | bearer | zgw-jwt | oauth2-client-credentials | oauth2-private-key-jwt | gcs-v1-hmac | geen
            clientId: bosrand # per tenant, nooit uit base.yaml
            secret: 00000099000000000001.zaken-secret   # naam van het geheim, altijd met de eigen OIN vooraan; de waarde staat in de secret store
modules:
    brp:                      # de configuratie die de module bijdraagt (TenantModuleConfiguratie)
        velden: [naam, geboortedatum]
```

De BRP-module (`haalcentraal-brp`) is zo gemigreerd: de bron staat onder `koppelingen.brp` (personen) en, voor het aantal bewoners, `koppelingen.brp-bewoning`
(optioneel), met `auth.type: geen`: de bron zit achter de cloud gateway, die per gemeente zelf authenticeert, dus er is geen sleutel en geen geheim. Een leverancier die wel een sleutel vraagt, gebruikt `api-key`. `modules.brp` bevat de doelbinding van de gemeente: `velden` (de velden die bij de BRP
worden opgevraagd; standaard 24 velden), `toegestaneWoonplaatsenBewoning` (leeg betekent alle woonplaatsen) en `aanvullendeHeaders` (vaste headers zoals `x-doelbinding`,
geen geheimen; ze zijn niet erfbaar uit `base.yaml`, en een headernaam die op een geheim lijkt, of gelijk is aan de auth-header van de koppeling, weigert het opstarten). `modules.brp` zonder `koppelingen.brp` weigert het opstarten. De module-schakelaar `nl-portal.config.haalcentraal-brp.enabled` blijft bestaan;
de oude properties `...haalcentraal-brp.properties.*` (URL's, `apiKey`, `ssl`, `brpFields`, `allowedCitiesForBewoning`) zijn vervallen en weigeren het opstarten zolang ze nog gezet zijn (ook als omgevingsvariabele). Een bearer-token van de
inwoner gaat niet meer naar de BRP; uitgaande clientcertificaten (`ssl`) vervallen omdat de cloud gateway de PKIoverheid-certificaten per gemeente beheert.

De runtime weigert te starten bij een koppeling zonder verplichte velden voor haar `auth.type` (de melding noemt de OIN), een geheim dat geen naam
is (de waarde zelf wordt nooit getoond), een geheim dat niet in de secret store staat, en een `modules.<naam>` zonder bijbehorende module. Credentials en
geheimverwijzingen mogen niet in `base.yaml` staan. Geheimen lost de runtime op uit de omgeving: de naam `00000099000000000001.zaken-secret` is de omgevingsvariabele
`NL_PORTAL_GEHEIMEN_00000099000000000001_ZAKEN_SECRET` (of de property `nl-portal.geheimen.00000099000000000001.zaken-secret`).
Elke geheimverwijzing begint met de OIN van het eigen bestand (gevolgd door `.`, `-` of `_`), en een `privateKeyBestand` staat in een map met de eigen
OIN (`/run/secrets/<oin>/client.pem`, zonder `..` en zonder andere OIN-map): een verwijzing naar de naamruimte van een andere tenant weigert het opstarten
(`GEHEIM_VAN_ANDERE_TENANT`), ook in de validator-CLI. Zo kan een gekopieerd tenantbestand nooit stil de geheimen van een andere gemeente gebruiken. De
meldingen noemen het veld en de OIN, nooit de naam of waarde van een geheim. Voor `modules.<naam>` geldt hetzelfde, en elk geheim daarin moet bij het opstarten
in de secret store staan. `url` en `tokenUrl` zijn in een productieomgeving alleen `https`. Let op: de controle "is dit een naam" is een heuristiek (een
geheim van alleen kleine letters en cijfers past in het patroon); de OIN-prefix en de Git-review zijn de echte bewaking. De validator-CLI kent de modules die op het
klassenpad staan: `java -cp nl-portal-tenant-validator.jar:<module-jars> nl.nlportal.core.tenancy.TenantValidatorCli <map>`.

### Betalen via Worldline Direct (`payment-direct`)

Een tenant die betaalt via Worldline Direct heeft een koppeling `payment-direct` en een sectie `modules.payment-direct`. De koppeling ondertekent elk
uitgaand verzoek zelf (`type: gcs-v1-hmac`, `clientId` is het API-sleutel-ID, `secret` verwijst naar het API-geheim); de module bewaart geen sleutel.
Een profiel dat een eigen API-sleutel heeft, noemt een eigen koppeling in `koppeling` (standaard `payment-direct`).

```yaml
koppelingen:
    payment-direct:
        url: https://payment.preprod.direct.worldline-solutions.com
        auth:
            type: gcs-v1-hmac
            clientId: <API-sleutel-ID>
            secret: 00000099000000000001.payment-direct-api-geheim
modules:
    payment-direct:
        webhookUrl: https://bosrand.example/api/public/payment/direct/postsale   # optioneel
        customTemplateUrl: <hosted checkout-variant>                             # optioneel
        toonResultaatPagina: false
        profielen:
            belastingzaken:       # naam van het profiel, ook te vinden op pspId
                pspId: TAX
                taal: nl_NL
                valuta: EUR
                returnUrl: https://bosrand.example/terug
                koppeling: payment-direct          # optioneel
                webhookSleutelId: <sleutel-ID van de webhook>    # samen met webhookGeheim
                webhookGeheim: 00000099000000000001.payment-direct-webhook-geheim
                creditcardAan: false
                aantalPogingen: 1
```

De postsale-webhook (`POST /api/public/payment/direct/postsale`) is publiek en anoniem; de handtekening (`X-GCS-Signature`, `X-GCS-KeyId`) is de enige
controle. De tenant volgt uit het hostdomein waarop de webhook binnenkomt en moet gelijk zijn aan de tenant van de `keyId`: alleen de `webhookSleutelId`'s uit het
eigen tenantbestand gelden, een sleutel van een andere tenant is onbekend en geeft `Request is not valid`. De webhook is een benoemde uitzondering op de CSRF- en
Origin-regel van de BFF. Een `webhookSleutelId` staat bij een tenant bij hoogstens een profiel en bij hoogstens een tenant: staat hij bij twee tenants (een gekopieerd
sleutelpaar), dan weigert de applicatie het opstarten, met de OIN's maar zonder sleutel of geheim in de melding (ADR 0021 besluit 5).

De handtekening van uitgaande verzoeken (`gcs-v1-hmac`) volgt de Worldline-SDK: methode, content-type, datum (`Tue, 03 Nov 2026 10:00:00 GMT`), de
`X-GCS-*`-headers van het verzoek, en het pad met de gedecodeerde query. Een `Date` die een module zelf meegeeft, wordt vervangen.

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

Het JSON-schema staat in `backend/schema/tenant.schema.json` en wordt uit de Kotlin-typen gegenereerd
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
