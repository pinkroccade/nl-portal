# Prototype: betrouwbaarheidsniveau en inlogmiddel als claim uit DigiD en wallet (Keycloak 26.8)

Geraadpleegd op: 2026-11-21. Prototype voor #500 (epic #499), gebruikt door #506 (BFF-tracer bullet) en het realm-template. Peilversie: Keycloak **26.8.2** (`quay.io/keycloak/keycloak:26.8.2`).

De prototypecode staat op de branch `prototype/digid-wallet-claims-500` (map `prototypes/digid-wallet-claims-500`, niet gemerged). Draaien: `mise run prototype:digid-wallet-claims` op die branch (Docker nodig, poort 18080, ongeveer drie minuten).

## Samenvatting

- **Uitkomst: gedeeltelijk positief.** Keycloak kan inlogmiddel, BSN van de actor (`ActingSubjectID`) en BSN van de vertegenwoordigde (`LegalSubjectID`) met standaardmappers als claim in het ID-token van `nl-portal-backend` zetten. Het **betrouwbaarheidsniveau van DigiD kan dat niet**: geen enkele ingebouwde mapper leest de `AuthnContextClassRef` uit de `AuthnStatement` van de SAML-assertion. Het prototype lost dat op met een eigen IdP-mapper van ongeveer 100 regels in een eigen Keycloak-image. Dat is een vervolgticket: **#527**.
- **Voor de wallet staat het niveau wel standaard in het token**, mits de wallet-koppeling een `acr`-claim meestuurt: de ingebouwde mapper "User Session Note Mapper" neemt die over. Welk niveau een echte wallet levert, is met dit prototype **niet** vastgesteld (zie "Wat het prototype wel en niet bewijst").
- **De backend moet het niveau zelf toetsen.** Keycloak laat een login met een lager niveau dan gevraagd gewoon slagen, `acr_values` van de login-start gaan niet naar een SAML-koppeling, en DigiD kan een hoger niveau leveren dan gevraagd. Het minimum per tenant staat dus in twee lagen: de koppeling vraagt het op (`RequestedAuthnContext`, vergelijking `minimum`) en de backend toetst de claim bij de callback.
- **Eén sleutel voor gegevens: de BSN-claim.** Een burger die met DigiD en met de wallet inlogt krijgt in Keycloak twee verschillende gebruikers (twee `sub`-waarden) met dezelfde `acting_subject_id`. Alleen die claim koppelt ze, zoals ADR 0022 besluit 11 vraagt.

## Wat het prototype wel en niet bewijst

**Opzet.** Eén Keycloak 26.8.2 met twee realms: `portal` (de gemeente-realm met de vertrouwelijke client `nl-portal-backend` en twee koppelingen, `digid` en `wallet`) en `idp-sim` (de simulator van DigiD en wallet). De test (`node --test`, 13 tests) doorloopt per login een authorization-code-flow met PKCE zoals de BFF en een browser dat doen: login-start met `kc_idp_hint`, de simulator, de broker-callback, het ID-token uit het token-endpoint. De gebruiker is `jan` (BSN `999990008`) of `maria` (BSN `999990020`, die voor `999990008` handelt). Alle waarden zijn verzonnen testwaarden.

| Onderdeel | Gesimuleerd door | Wat dat bewijst | Wat niet |
|---|---|---|---|
| DigiD | Keycloak-realm `idp-sim` als SAML-IdP, met stapsgewijs inloggen (niveau 1 tot 3) dat de `AuthnContextClassRef` van het bereikte niveau meestuurt | Hoe Keycloak als SAML-broker de assertion verwerkt, welke mappers wat kunnen, welke claims in het token komen, hoe `RequestedAuthnContext` uitgaat, het gedrag bij de eerste en latere login | Het echte gedrag van DigiD: PKIoverheid-sleutels, versleuteling, artifact-binding, de echte URN's en attributen van de actuele koppelvlakspecificatie, foutafhandeling (`NoAuthnContext`, annulering) |
| `ActingSubjectID` en `LegalSubjectID` | Gewone SAML-attributen in `urn:nl-eid-gdi:1.0:...`, onversleuteld | De attribuut-naar-claim-route en het gedrag bij een ontbrekend attribuut | Ontsleuteling van een `EncryptedID`, het `NameQualifier`-type (BSN of pseudoniem), het echte DigiD Machtigen-verkeer |
| Wallet | Keycloak-realm `idp-sim` als OIDC-IdP met een vaste `acr` en een claim `bsn` | De OIDC-route: `acr` naar sessie, claim naar token, doorsturen van `acr_values` | **Welk niveau en welke claims een echte wallet of tussenbroker levert**; de claimnamen `acr=http://eidas.europa.eu/LoA/high` en `bsn` zijn aannames van de simulator. De OpenID4VP-route van de wallet zelf is niet geprobeerd |

De DigiD-niveaus gebruiken de URN's uit de DigiD-koppelvlakspecificatie SAML 3.5 (Logius, 2020): `urn:oasis:names:tc:SAML:2.0:ac:classes:` plus `PasswordProtectedTransport` (Basis), `MobileTwoFactorContract` (Midden), `Smartcard` (Substantieel) en `SmartcardPKI` (Hoog). Dat is uit een zoekresultaat over die specificatie overgenomen en niet zelf in het PDF nagelezen. Of de nieuwere eID-SAML-koppelvlakspecificatie of Stelsel Toegang SAML andere waarden gebruikt, is niet nagegaan (zie [auth-bff-nlgov-oauth.md](auth-bff-nlgov-oauth.md) §6 voor de domeintaal). **Open punt vóór livegang:** de URN's en attribuutnamen tegen de koppelvlakspecificatie van de aansluiting nalopen, en een test met de DigiD-preproductieomgeving of de test-IdP van Logius.

## Claims en mappers

Alle claims komen in het **ID-token** (niet in het access token) van `nl-portal-backend`.

| Claim | Bron bij DigiD | Bron bij wallet | Mapper op de koppeling (IdP-mapper) | Mapper op de client (`nl-portal-backend`) |
|---|---|---|---|---|
| `acr` (betrouwbaarheidsniveau) | `AuthnContextClassRef` | claim `acr` | DigiD: **eigen** `nlportal-saml-authncontext-note-mapper` (sessienotitie `acr`). Wallet: ingebouwd `oidc-user-session-note-idp-mapper` met `claims=[{"key":"acr","value":".*"}]` en `are.claim.values.regex=true` | `oidc-usersessionmodel-note-mapper`, notitie `acr`, claim `acr` |
| `inlogmiddel` | n.v.t. | n.v.t. | geen: Keycloak zet zelf de notitie `identity_provider` (de alias van de koppeling) | `oidc-usersessionmodel-note-mapper`, notitie `identity_provider`, claim `inlogmiddel` |
| `acting_subject_id` | attribuut `urn:nl-eid-gdi:1.0:ActingSubjectID` | claim `bsn` | DigiD: `nlportal-saml-attribute-note-mapper` (eigen, sessienotitie) of ingebouwd `saml-user-attribute-idp-mapper` (gebruikersattribuut). Wallet: `oidc-user-session-note-idp-mapper` (notitie `bsn`) | notitie `acting_subject_id` en notitie `bsn`, beide naar claim `acting_subject_id` |
| `legal_subject_id` | attribuut `urn:nl-eid-gdi:1.0:LegalSubjectID`, alleen bij vertegenwoordiging | n.v.t. | idem als `acting_subject_id` | notitie `legal_subject_id`, claim `legal_subject_id` |

De volledige configuratie staat in `realms/portal-realm.json` (`clients[0].protocolMappers` en `identityProviderMappers`).

Gevolgen en keuzes:

- **`acr` is hier het niveau van de koppeling, niet dat van Keycloak.** Keycloak zet zelf een `acr` (`"1"`, het niveau van de Keycloak-sessie) via de standaard client scope `acr`. Het prototype haalt die scope van de client af en zet het niveau van de koppeling in `acr`. Houdt de client de scope, dan ontstaan twee claims met dezelfde naam. De NL GOV-profielnotitie noemt `acr` met een eIDAS-niveau als aanbeveling; de DigiD-URN's zijn geen eIDAS-waarden. De backend heeft dus een **volgorde per URN** nodig (Basis < Midden < Substantieel < Hoog, en dezelfde reeks voor de wallet-waarden). Waar die tabel staat en of de waarden naar eIDAS worden vertaald, is een besluit voor #506 en het tenantschema.
- **Sessienotitie of gebruikersattribuut.** Een gebruikersattribuut hoort bij de gebruiker en wordt gelezen op het moment dat het token wordt uitgegeven; een notitie hoort bij precies één login. Het prototype bewijst dat twee gelijktijdige logins van dezelfde gebruiker, met en zonder `LegalSubjectID`, met notities elk hun eigen waarde houden. Bij een ontbrekend attribuut verdwijnt een eerder geïmporteerd gebruikersattribuut in 26.8.2 ook zonder `allow.nullable.property`; er blijft dus geen oude `legal_subject_id` achter. Notities zijn de veilige keuze omdat een vertegenwoordigde nooit van een andere login mag lekken (ADR 0022 besluit 11); beide varianten werken.
- **De `inlogmiddel`-waarde is de alias van de koppeling.** De naamgeving van de koppelingen (`digid`, `wallet`, later `eherkenning`) is daarmee een contract tussen realm-template en backend. De callback-controle op uitgeschakelde inlogmiddelen leest dit veld.

## Bevindingen over Keycloak (elk met een test)

| Bevinding | Test in `test/claims.test.mjs` | Gevolg |
|---|---|---|
| Inlogmiddel, niveau en BSN van de actor komen in het ID-token na een DigiD-login | `zet inlogmiddel digid, betrouwbaarheidsniveau en BSN ...` | Claims vastgelegd, zie tabel |
| De koppeling krijgt het niveau dat de simulator bij het gevraagde niveau levert (Basis, Midden, Substantieel) | `levert het niveau dat de koppeling bij DigiD opvraagt ...` | De claim volgt de assertion, niet de configuratie |
| De uitgaande `AuthnRequest` bevat `RequestedAuthnContext Comparison="minimum"` met de ingestelde `AuthnContextClassRef` (IdP-instelling `authnContextClassRefs` en `authnContextComparisonType`) | `vraagt het minimum bij DigiD op ...` | Het minimum per tenant staat in de koppelinginstelling van het realm-template |
| **`acr_values` van de login-start gaan niet naar een SAML-koppeling** | `neemt acr_values van de BFF niet over ...` | De BFF kan het minimum niet per login verhogen; de instelling op de koppeling bepaalt het. Bij de wallet (OIDC) worden ze wel doorgestuurd (zie hieronder) |
| **`acr_values` gaan wel naar een OIDC-koppeling** | `stuurt acr_values van de login-start door naar de wallet-koppeling` | Per login een hoger niveau vragen bij de wallet kan; of de echte wallet er iets mee doet is niet getest |
| **Keycloak weigert een login met een te laag niveau niet** (zonder verzoek slaagt de login met Basis) | `weigert een login met een lager niveau dan het minimum zelf niet ...` | De backend toetst `acr` bij de callback; dat sluit aan op de koppelvlakspecificatie die van de afnemer vraagt het niveau in de assertion te controleren |
| `LegalSubjectID` komt als aparte claim naast de BSN van de actor bij vertegenwoordiging | `geeft bij vertegenwoordiging LegalSubjectID ...` | De claim `legal_subject_id` is aanwezig of afwezig, nooit leeg |
| `legal_subject_id` verdwijnt bij een volgende login zonder attribuut | `laat legal_subject_id vervallen ...`; `standaard attribuutmapper ... verdwijnt ...` | Geen restant van een eerdere login, ook met de standaardmapper |
| Twee gelijktijdige logins houden elk de eigen waarde met sessienotities | `houdt bij twee gelijktijdige logins ...` | Sessienotities hebben de voorkeur boven gebruikersattributen |
| Wallet: inlogmiddel, niveau en BSN in het token | `zet inlogmiddel wallet, het niveau uit de acr ...` | Claims vastgelegd, zie tabel |
| DigiD en wallet geven voor dezelfde burger dezelfde BSN-claim bij een andere `sub` | `geeft DigiD en wallet voor dezelfde burger ...` | Sleutel voor gegevensaanvragen is de BSN-claim, niet de `sub` |

Andere bevindingen zonder eigen test:

- **De eerste login gedraagt zich anders dan latere.** Bij een nieuwe gebruiker serialiseert Keycloak de broker-context tussen de assertion en de first-broker-login-flow. De `AuthnStatement` en de assertion zijn daarna niet meer beschikbaar en notities die alleen bij de eerste verwerking staan, komen niet in de sessie. De eigen mappers onthouden de waarde daarom als tekst in de contextdata en zetten de notitie bij elke fase (`preprocessFederatedIdentity`, `importNewUser`, `updateBrokeredUser`). Zonder die aanpassing ontbrak `acr` bij precies de eerste login van een gebruiker, een fout die een test met een bestaande gebruiker niet vindt. De test draait daarom op een schone start met een nieuwe gebruiker.
- **De IdP-mapper-SPI is intern.** Keycloak waarschuwt bij het laden (`KC-SERVICES0047`: de SPI is intern en kan zonder bericht wijzigen). De mapper moet bij elke Keycloak-upgrade opnieuw gebouwd en getest worden.
- **De first-broker-login-flow en de gebruikersprofielen moeten kloppen.** Een brokergebruiker zonder e-mail of naam vraagt in een standaardrealm om een profielupdate. Het prototype gebruikt een flow met alleen `idp-create-user-if-unique` en `idp-auto-link`, en een gebruikersprofiel waarin e-mail en namen niet verplicht zijn. Dat moet in het realm-template van #502 terugkomen, anders loopt de login vast op een formulier.
- **OpenID4VP in Keycloak 26.8.2.** De image bevat een identity provider `oid4vp` met een mapper naar gebruikerssessie-attributen (`OID4VPSdJwtUserSessionAttributeMapper`), maar de bijbehorende feature `OID4VC_VP` staat standaard uit en is experimenteel. Niet geprobeerd; relevant als de wallet rechtstreeks zonder tussenbroker op Keycloak wordt aangesloten.
- **Ondertekening.** Keycloak ondertekent de `AuthnRequest` en valideert de assertion met een certificaat dat het prototype per start uit de simulator haalt. De uitkomst over één sleutel per realm uit [multitenancy-keycloak-realms.md](multitenancy-keycloak-realms.md) is hier niet opnieuw getoetst.

## Aanbevelingen

1. **Maak een eigen Keycloak-image met de IdP-mapper** (vervolgticket #527) of kies een alternatief dat het niveau als attribuut aanlevert, bijvoorbeeld een tussenliggende broker. Zonder een van beide is het niveau niet in het token en blijft ADR 0022 besluit 12 (minimum afgedwongen in de backend) onuitvoerbaar op de claim.
2. **Zet het minimum per tenant in de koppelinginstelling** (`authnContextClassRefs` en `authnContextComparisonType=minimum`) via het realm-template, en laat de backend `acr` bij de callback toetsen tegen dezelfde tenantwaarde. Leg de volgorde van de URN's vast.
3. **Gebruik sessienotities** voor `acr`, `acting_subject_id` en `legal_subject_id`, geen gebruikersattributen. De mappers daarvoor zijn ingebouwd voor de wallet en eigen voor DigiD.
4. **Laat `inlogmiddel` uit de alias van de koppeling komen** en leg de aliasnamen vast in het tenantschema.
5. **Verifieer met echte systemen** vóór livegang: de DigiD-preproductieomgeving (URN's, artifact-binding, versleuteling van `ActingSubjectID`) en een echte wallet of broker (welke claims, welk niveau).

## Open punten

- Welk niveau een echte wallet of tussenbroker in `acr` levert en onder welke claimnaam is niet bekend. De wallet is naar verwachting op eIDAS-niveau hoog ontworpen, maar dat is hier niet geverifieerd. Beslissing voor de eigenaar van de wallet-koppeling.
- DigiD gebruikt artifact-binding voor het antwoord en versleutelt de BSN; Keycloak ondersteunt beide, maar het prototype gebruikt POST-binding en onversleutelde waarden.
- Of de koppelvlakspecificatie voor DigiD Machtigen de attribuutnamen `urn:nl-eid-gdi:1.0:ActingSubjectID` en `LegalSubjectID` gebruikt zoals in [auth-bff-nlgov-oauth.md](auth-bff-nlgov-oauth.md) §6.2 staat, is hier overgenomen en niet opnieuw getoetst.
- Het prototype gebruikt een `client_secret` voor `nl-portal-backend` om het token op te halen. De echte client gebruikt `private_key_jwt` (ADR 0022); dat raakt de claims niet maar is hier niet bewezen.

## Bronnen

| Bron | Link of plek | Opmerking |
|---|---|---|
| Prototype en tests | branch `prototype/digid-wallet-claims-500`, map `prototypes/digid-wallet-claims-500` | 13 tests, groen op Keycloak 26.8.2 |
| Koppelvlakspecificatie DigiD SAML Authenticatie v3.5 (Logius, 2020) | https://www.logius.nl/sites/default/files/bestanden/website/Koppelvlakspecificatie%20SAML%20DigiD%20v3.5%20(definitief).pdf | Alleen via een zoekresultaat gezien: de URN's per niveau en de eis dat de afnemer het niveau in de assertion controleert |
| Mappertypen en configuratie | `GET /admin/realms/portal/identity-provider/instances/<alias>/mapper-types` op Keycloak 26.8.2; klassen in `org.keycloak.keycloak-services-26.8.2.jar` | Gedrag van `XPathAttributeMapper` en `SAMLEndpoint` uit de klassen afgeleid en met een test bevestigd |
| Domeintaal en attributen | [auth-bff-nlgov-oauth.md](auth-bff-nlgov-oauth.md) §6, [fase0-besluiten-multitenancy-bff.md](fase0-besluiten-multitenancy-bff.md) | Q14 en Q16 |
