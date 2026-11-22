# Prototype: betrouwbaarheidsniveau en inlogmiddel als claim uit DigiD en wallet (Keycloak 26.8)

Geraadpleegd op: 2026-11-21. Prototype voor #500 (epic #499), gebruikt door #505 (realm-template), #506 (BFF-tracer bullet) en het tenantschema (#504). Peilversie: Keycloak **26.8.2** (`quay.io/keycloak/keycloak:26.8.2`).

De prototypecode staat op de branch `prototype/digid-wallet-claims-500` (map `prototypes/digid-wallet-claims-500`, niet gemerged). Draaien: `mise run prototype:digid-wallet-claims` op die branch (Docker nodig, poort 18080, ongeveer drie minuten).

## Samenvatting

- **Uitkomst: gedeeltelijk positief, voor het walletniveau negatief.** Keycloak kan inlogmiddel, BSN van de actor (`ActingSubjectID`) en BSN van de vertegenwoordigde (`LegalSubjectID`) met standaardmappers of een eigen mapper als claim in het ID-token van `nl-portal-backend` zetten. Het **betrouwbaarheidsniveau van DigiD kan dat niet** met een ingebouwde mapper: geen enkele leest de `AuthnContextClassRef` uit de `AuthnStatement` van de SAML-assertion. Het prototype lost dat op met een eigen IdP-mapper in een eigen Keycloak-image. Dat is een vervolgticket: **#527**.
- **Het walletniveau is niet vastgesteld.** Het prototype kan een `acr` van een wallet-koppeling in het token zetten, maar welk niveau een echte wallet of tussenbroker levert en onder welke claimnaam is met een simulator niet te weten. Dat is een negatieve uitkomst met een eigen vervolgticket, #529 (zie [Walletniveau](#walletniveau-niet-vastgesteld), vervolgticket #529).
- **De backend moet het niveau zelf toetsen.** Keycloak laat een login zonder gevraagd niveau gewoon slagen, `acr_values` van de login-start gaan niet naar een SAML-koppeling, en DigiD kan een hoger niveau leveren dan gevraagd. Het minimum per tenant staat dus in twee lagen: de koppeling vraagt het op (`RequestedAuthnContext`, vergelijking `minimum`) en de backend toetst de claim bij de callback (zie [Toetsen bij de callback](#toetsen-bij-de-callback)).
- **Dit wijkt af van de spec.** Epic #499 zegt dat `acr_values` met het minimum van de tenant meegaat. Dat werkt alleen bij een OIDC-koppeling (de wallet), niet bij DigiD (SAML). Zie [Afwijking van de spec](#afwijking-van-de-spec).
- **Eén sleutel voor gegevens: de BSN-claim.** Een burger die met DigiD en met de wallet inlogt krijgt in Keycloak twee verschillende gebruikers (twee `sub`-waarden) met dezelfde `acting_subject_id`. Alleen die claim koppelt ze, zoals ADR 0022 besluit 11 vraagt. Daarom mag Keycloak identiteiten van verschillende koppelingen **nooit** automatisch koppelen.
- **Sessienotities, nooit gebruikersattributen**, voor `acr`, `acting_subject_id` en `legal_subject_id`: een gebruikersattribuut lekt tussen gelijktijdige logins en is voor de gebruiker te bewerken (zie [Sessienotitie of gebruikersattribuut](#sessienotitie-of-gebruikersattribuut)).

## Wat het prototype wel en niet bewijst

**Opzet.** Eén Keycloak 26.8.2 met twee realms: `portal` (de gemeente-realm met de vertrouwelijke client `nl-portal-backend` en twee koppelingen, `digid` en `wallet`) en `idp-sim` (de simulator van DigiD en wallet). De test (`node --test`, 25 tests) doorloopt per login een authorization-code-flow met PKCE zoals de BFF en een browser dat doen: login-start met `kc_idp_hint`, de simulator, de broker-callback, het ID-token uit het token-endpoint. De gebruiker is `jan` (BSN `999990008`) of `maria` (BSN `999990020`, die voor `999990008` handelt). Alle waarden zijn verzonnen testwaarden.

| Onderdeel | Gesimuleerd door | Wat dat bewijst | Wat niet |
|---|---|---|---|
| DigiD | Keycloak-realm `idp-sim` als SAML-IdP, met stapsgewijs inloggen (niveau 1 tot 4) dat de `AuthnContextClassRef` van het bereikte niveau meestuurt | Hoe Keycloak als SAML-broker de assertion verwerkt, welke mappers wat kunnen, welke claims in het token komen, hoe `RequestedAuthnContext` uitgaat, het gedrag bij de eerste en latere login en bij herauthenticatie, alle vier de niveaus | Het echte gedrag van DigiD: PKIoverheid-sleutels, versleuteling, artifact-binding, de echte URN's en attributen van de actuele koppelvlakspecificatie, foutafhandeling (`NoAuthnContext`, annulering). **Dat DigiD een hoger niveau levert dan gevraagd**: de simulator levert altijd het gevraagde niveau of weigert de login, dus dit is niet getest |
| `ActingSubjectID` en `LegalSubjectID` | Gewone SAML-attributen in `urn:nl-eid-gdi:1.0:...`, onversleuteld | De attribuut-naar-claim-route, het gedrag bij een ontbrekend, leeg en dubbel attribuut | Ontsleuteling van een `EncryptedID`, het `NameQualifier`-type (BSN of pseudoniem), het echte DigiD Machtigen-verkeer. Bij gewone DigiD staat het BSN volgens de koppelvlakspecificatie in de `NameID` van het `Subject` (sectorprefix `s00000000:`), niet in een attribuut; welke route de aansluiting gebruikt is niet bepaald |
| Wallet | Keycloak-realm `idp-sim` als OIDC-IdP met een vaste `acr` en een claim `acting_subject_id` | De OIDC-route: `acr` naar sessie, claim naar token, doorsturen van `acr_values` | **Welk niveau en welke claims een echte wallet of tussenbroker levert**; `acr=http://eidas.europa.eu/LoA/high` en de claimnaam zijn aannames van de simulator. De OpenID4VP-route is niet geprobeerd |

De DigiD-niveaus gebruiken de URN's `urn:oasis:names:tc:SAML:2.0:ac:classes:` plus `PasswordProtectedTransport` (Basis), `MobileTwoFactorContract` (Midden), `Smartcard` (Substantieel) en `SmartcardPKI` (Hoog). Die staan ook in ADR 0022 (koppelvlakspecificatie versie 3.7) en in de documentatie van Open Forms, die Logius als bron noemt. Het PDF van de Logius-koppelvlakspecificatie (v3.5) was met de beschikbare middelen niet leesbaar, dus de waarden zijn **niet tegen de primaire bron geverifieerd**. Of de nieuwere eID-SAML-koppelvlakspecificatie of Stelsel Toegang SAML andere waarden gebruikt, is niet nagegaan (zie [auth-bff-nlgov-oauth.md](auth-bff-nlgov-oauth.md) §6 voor de domeintaal). **Open punt vóór livegang:** de URN's en attribuutnamen tegen de koppelvlakspecificatie van de aansluiting nalopen (versie 3.7 uit ADR 0022), en een test met de DigiD-preproductieomgeving of de test-IdP van Logius.

## Claims en mappers

Alle claims komen in het **ID-token** (niet in het access token) van `nl-portal-backend`.

| Claim | Bron bij DigiD | Bron bij wallet | Mapper op de koppeling (IdP-mapper) | Mapper op de client (`nl-portal-backend`) |
|---|---|---|---|---|
| `acr` (betrouwbaarheidsniveau) | `AuthnContextClassRef` | claim `acr` | DigiD: **eigen** `nlportal-saml-authncontext-note-mapper` (sessienotitie `acr`). Wallet: **eigen** `nlportal-oidc-claim-note-mapper` (claim `acr`, notitie `acr`); de ingebouwde `oidc-user-session-note-idp-mapper` is hier niet bruikbaar, zie [Herauthenticatie bij de wallet](#herauthenticatie-bij-de-wallet) | `oidc-usersessionmodel-note-mapper`, notitie `acr`, claim `acr` |
| `inlogmiddel` | n.v.t. | n.v.t. | geen: Keycloak zet zelf de notitie `identity_provider` (de alias van de koppeling) | `oidc-usersessionmodel-note-mapper`, notitie `identity_provider`, claim `inlogmiddel` |
| `acting_subject_id` | attribuut `urn:nl-eid-gdi:1.0:ActingSubjectID` | claim `acting_subject_id` | DigiD: **eigen** `nlportal-saml-attribute-note-mapper` (sessienotitie `acting_subject_id`). Wallet: eigen `nlportal-oidc-claim-note-mapper` (claim `acting_subject_id`, notitie `acting_subject_id`) | notitie `acting_subject_id`, claim `acting_subject_id` |
| `legal_subject_id` | attribuut `urn:nl-eid-gdi:1.0:LegalSubjectID`, alleen bij vertegenwoordiging | n.v.t. | idem als `acting_subject_id` | notitie `legal_subject_id`, claim `legal_subject_id` |

De volledige configuratie staat in `realms/portal-realm.json` (`clients[0].protocolMappers` en `identityProviderMappers`).

Gevolgen en keuzes:

- **`acr` is hier het niveau van de koppeling, niet dat van Keycloak.** Keycloak zet zelf een `acr` (`"1"`, het niveau van de Keycloak-sessie) via de standaard client scope `acr`. Het prototype haalt die scope van de client af en zet het niveau van de koppeling in `acr`. Houdt de client de scope, dan ontstaan twee claims met dezelfde naam; het realm-template mag de scope dus nooit terugzetten en de realmtest (#505) controleert dat. De NL GOV-profielnotitie noemt `acr` met een eIDAS-niveau als aanbeveling; de DigiD-URN's zijn geen eIDAS-waarden, en deze aanbeveling wordt hier bewust niet gevolgd zolang de waarden DigiD-URN's zijn. De backend heeft dus een **volgorde per URN** nodig (Basis < Midden < Substantieel < Hoog, en een aparte reeks voor de wallet-waarden). Waar die tabel staat is een besluit voor het tenantschema (#504) of #506. Bij een vertaling naar eIDAS zijn alleen DigiD Substantieel en Hoog als eIDAS substantial en high aangemerkt; Midden mag niet als substantial worden vertaald.
- **Eén notitienaam voor de BSN van de actor.** DigiD en wallet schrijven beide `acting_subject_id`, zodat één client-mapper volstaat en de mappervolgorde nooit bepaalt welke BSN in de claim komt. De eigen wallet-mapper heeft een aparte instelling voor de claimnaam (`claim.name`) en de notitienaam (`note`); een andere claimnaam van een echte wallet of tussenbroker is dus een configuratiewijziging. De ingebouwde mapper kan dat niet (de notitienaam is daar de claimnaam). Het prototype laat de simulator `acting_subject_id` sturen; welke claimnaam een echte wallet gebruikt, hoort bij het walletticket (#529).
- **De `inlogmiddel`-waarde is de alias van de koppeling.** De naamgeving van de koppelingen (`digid`, `wallet`, later `eherkenning`) is daarmee een contract tussen realm-template en backend. De callback-controle op uitgeschakelde inlogmiddelen leest dit veld.

### Sessienotitie of gebruikersattribuut

Een gebruikersattribuut hoort bij de gebruiker en wordt gelezen op het moment dat het token wordt uitgegeven; een notitie hoort bij precies één login. De twee varianten zijn naast elkaar getest:

- **Gebruikersattribuut** (`saml-user-attribute-idp-mapper` plus `oidc-usermodel-attribute-mapper`): login A zonder `LegalSubjectID` en nog niet ingewisseld, login B met `LegalSubjectID`; het token van A bevat daarna de `LegalSubjectID` van B. Een vertegenwoordigde lekt dus van de ene login naar de andere. Test: `lekt met een gebruikersattribuut ...`.
- **Sessienotitie** (eigen mapper): twee gelijktijdige logins houden elk hun eigen waarde, ook als de assertion van de een de `LegalSubjectID` mist. Test: `houdt bij twee gelijktijdige logins ...`.

Een gebruikersattribuut is bovendien voor de gebruiker zelf te bewerken zolang het gebruikersprofiel dat toestaat (`unmanagedAttributePolicy: ENABLED`, zoals in een standaardrealm). Daarom: **alleen sessienotities, nooit een gebruikersattribuut als bron van een BSN-claim.** Het prototype-realm zet `unmanagedAttributePolicy` op `ADMIN_VIEW`, laat alleen de beheerder profielattributen bewerken en schakelt de account-console uit; het realm-template neemt dat over.

## Bevindingen over Keycloak (elk met een test)

Alle namen verwijzen naar `test/claims.test.mjs`.

| Bevinding | Test | Gevolg |
|---|---|---|
| Inlogmiddel, niveau en BSN van de actor komen in het ID-token na een DigiD-login | `zet inlogmiddel digid, betrouwbaarheidsniveau en BSN ...` | Claims vastgelegd, zie tabel |
| De koppeling krijgt het niveau dat de simulator bij het gevraagde niveau levert (Basis, Midden, Substantieel en Hoog) | `levert het niveau dat de koppeling bij DigiD opvraagt, van Basis tot Hoog` | De claim komt uit de assertion. Gevraagd en geleverd zijn in de simulator gelijk; een hoger geleverd niveau is niet getest |
| De uitgaande `AuthnRequest` bevat `RequestedAuthnContext Comparison="minimum"` met de ingestelde `AuthnContextClassRef` (IdP-instelling `authnContextClassRefs` en `authnContextComparisonType`) | `vraagt het minimum bij DigiD op ...` | Het minimum per tenant staat in de koppelinginstelling van het realm-template. Het standaardniveau voor een tenant is Substantieel (`Smartcard`); Midden staat alleen in het prototype |
| **`acr_values` van de login-start gaan niet naar een SAML-koppeling** (getest met de URN van Substantieel bij een koppeling op Midden) | `neemt acr_values van de BFF niet over ...` | De BFF kan het minimum niet per login verhogen; de instelling op de koppeling bepaalt het. Zie [Afwijking van de spec](#afwijking-van-de-spec) |
| **`acr_values` gaan wel naar een OIDC-koppeling** | `stuurt acr_values van de login-start door naar de wallet-koppeling` | Per login een hoger niveau vragen bij de wallet kan; of de echte wallet er iets mee doet is niet getest |
| **Keycloak toetst het niveau niet zelf**: een koppeling zonder gevraagd niveau laat een login op Basis slagen | `laat een login zonder gevraagd niveau slagen op Basis ...` | De backend toetst `acr` bij de callback. Dat een IdP die het minimum negeert (Basis bij minimum Midden) ook slaagt, is niet te simuleren en daarom niet bewezen |
| `LegalSubjectID` komt als aparte claim naast de BSN van de actor bij vertegenwoordiging | `geeft bij vertegenwoordiging LegalSubjectID ...` | De claim `legal_subject_id` is aanwezig, of afwezig of leeg; de backend behandelt een lege waarde als afwezig |
| Een ontbrekend attribuut laat een eerder geïmporteerd gebruikersattribuut verdwijnen, ook zonder `allow.nullable.property` | `standaard attribuutmapper ... verdwijnt ...` (beide waarden van de instelling) | Alleen van belang voor de variant met gebruikersattribuut, die we niet gebruiken |
| Twee gelijktijdige logins houden elk de eigen waarde met sessienotities, en een gebruikersattribuut lekt wel | `houdt bij twee gelijktijdige logins ...`, `lekt met een gebruikersattribuut ...` | Sessienotities, nooit gebruikersattributen |
| **De eerste login van een nieuwe gebruiker** geeft dezelfde claims als een latere: Keycloak serialiseert de broker-context en verliest de assertion, de eigen mappers onthouden de waarde als tekst | `zet bij DigiD acr, inlogmiddel en de BSN's ook als de Keycloak-gebruiker nog niet bestaat`, idem voor de wallet (de test verwijdert eerst alle portal-gebruikers) | Zonder de aanpassing ontbrak `acr` bij precies de eerste login, een fout die een test met een bestaande gebruiker niet vindt |
| **Herauthenticatie in dezelfde Keycloak-sessie** (SSO-cookie blijft staan): met `prompt=login` zet de login alle notities opnieuw; een notitie die de nieuwe login niet zet blijft anders staan | `laat legal_subject_id niet blijven hangen ... met prompt=login ...` | De eigen mappers (DigiD en wallet) zetten bij elke login elke notitie, een lege waarde als het attribuut of de claim ontbreekt. Zonder deze regel zag de burger de gegevens van een vertegenwoordigde voor wie hij nu niet inlogde (de DigiD-test was rood op de eerste versie van de mapper). De ingebouwde wallet-mapper doet dit niet, zie [Herauthenticatie bij de wallet](#herauthenticatie-bij-de-wallet) |
| **Herauthenticatie via de wallet**: een tweede login zonder `acr` en zonder BSN laat geen waarden van de eerste login staan | `laat acr en acting_subject_id van de wallet niet blijven hangen ...` | Eigen wallet-mapper nodig, zie [Herauthenticatie bij de wallet](#herauthenticatie-bij-de-wallet) |
| **Zonder `prompt=login` is er geen nieuwe login** binnen een lopende SSO-sessie: Keycloak geeft de bestaande sessie terug, ook als de IdP inmiddels andere gegevens levert, en `kc_idp_hint=wallet` binnen een DigiD-sessie levert `inlogmiddel=digid` | `geeft zonder prompt=login binnen een lopende sessie ...`, `geeft zonder prompt=login binnen een lopende DigiD-sessie ...` | De BFF start elke login met `prompt=login` (of `max_age=0`) en toetst `auth_time` (zie [Toetsen bij de callback](#toetsen-bij-de-callback)) |
| Met `prompt=login` binnen een lopende sessie van een andere identiteit weigert Keycloak ("already authenticated as different user") | `weigert met prompt=login een login via een ander inlogmiddel ...` | Veilig, maar de burger ziet een Keycloak-foutpagina; uitloggen beëindigt ook de Keycloak-sessie (DigiD T14) |
| **Een attribuut met twee waarden** (of een dubbel attribuut) laat de login falen | `laat een login met twee waarden voor LegalSubjectID falen ...` | De mapper kiest nooit een waarde; een onbekend type (`EncryptedID`) laat ook falen, maar is niet getest |
| **Identiteiten van twee koppelingen worden niet automatisch gekoppeld**: een bestaande gebruiker met hetzelfde e-mailadres wordt niet de gebruiker van de wallet-login | `koppelt een tweede inlogmiddel met hetzelfde e-mailadres ... niet ...` | First-broker-login kent alleen `idp-create-user-if-unique`, zie [Beveiligingskeuzes voor het realm-template](#beveiligingskeuzes-voor-het-realm-template) |
| Elke koppeling krijgt een eigen gebruikersnaam (`digid.<NameID>`, `wallet.<sub>`) | `geeft elke koppeling een eigen gebruikersnaam ...` | De naam van de IdP-gebruiker is nooit de sleutel |
| Zonder `kc_idp_hint` biedt de browserflow geen wachtwoordformulier aan | `biedt zonder koppeling in de login-start geen wachtwoordformulier aan` | Verdediging in de diepte; de callbacktoets blijft de hoofdcontrole |
| Wallet: inlogmiddel, niveau en BSN in het token | `zet inlogmiddel wallet, het niveau uit de acr ...` | Claims vastgelegd, zie tabel |
| DigiD en wallet geven voor dezelfde burger dezelfde BSN-claim bij een andere `sub` | `geeft DigiD en wallet voor dezelfde burger ...` | Sleutel voor gegevensaanvragen is de BSN-claim, niet de `sub` |

Andere bevindingen zonder eigen test:

- **De IdP-mapper-SPI is intern.** Keycloak waarschuwt bij het laden (`KC-SERVICES0047`: de SPI is intern en kan zonder bericht wijzigen). Een wijziging in `BrokeredIdentityContext` of in de sleutels `SAML_AUTHN_STATEMENT` en `SAML_ASSERTION` kan de mapper bij een patchrelease stil breken (geen `acr`) of de eerste login weer laten falen. Of Keycloak een mapper van een onbekend type dan overslaat of de login afbreekt, is niet vastgesteld, dus de callbacktoets op een ontbrekende `acr` is verplicht. Zie #527 voor image, build en terugvalplan.
- **OpenID4VP in Keycloak 26.8.2.** De image bevat een identity provider `oid4vp` met een mapper naar gebruikerssessie-attributen (`OID4VPSdJwtUserSessionAttributeMapper`), maar de bijbehorende feature `OID4VC_VP` staat standaard uit en is experimenteel. Niet geprobeerd; relevant als de wallet rechtstreeks zonder tussenbroker op Keycloak wordt aangesloten.
- **Ondertekening.** Keycloak ondertekent de `AuthnRequest` en valideert de assertion met een certificaat dat het prototype per start uit de simulator haalt. De uitkomst over één sleutel per realm uit [multitenancy-keycloak-realms.md](multitenancy-keycloak-realms.md) is hier niet opnieuw getoetst.

## Beveiligingskeuzes voor het realm-template

Het prototype-realm vereenvoudigt op een aantal punten. Wat het template **niet** mag overnemen, en wat het wel vastlegt:

| Onderwerp | Prototype | Realm-template (#505) |
|---|---|---|
| First-broker-login | Alleen `idp-create-user-if-unique`. Een eerdere versie koppelde met `idp-auto-link` automatisch; dat is onveilig: een tweede inlogmiddel (wallet, later eHerkenning) met dezelfde gebruikersnaam of hetzelfde e-mailadres zou inloggen als de Keycloak-gebruiker van een DigiD-burger (ASVS 5.0 6.8.1, ADR 0022 besluit 11) | Geen `idp-auto-link`, `idp-review-profile` of `idp-confirm-link` met e-mail. Een bestaande gebruiker met dezelfde naam laat de login falen. Een realmtest controleert dat een tweede inlogmiddel met dezelfde naam of hetzelfde e-mailadres niet bij de bestaande gebruiker uitkomt |
| Gebruikersnaam | `saml-username-idp-mapper` met `${ALIAS}.${NAMEID}`, `oidc-username-idp-mapper` met `${ALIAS}.${CLAIM.sub}` | **Niet `${ALIAS}.${NAMEID}` overnemen bij echte DigiD.** De `NameID` is daar `s00000000:<BSN>`, dus het BSN komt in de Keycloak-gebruikersnaam, in `preferred_username` van de tokens en in de zoekresultaten van de beheerconsole (dataminimalisatie BSN, AVG/Wabb). De simulator gebruikt een pseudoniem, dus de test laat dit niet zien. Kies bij #505 een afgeleide gebruikersnaam (bijvoorbeeld `${ALIAS}.` plus een hash van de `NameID`) of laat `preferred_username` uit de tokens van `nl-portal-backend`. Koppel DigiD en wallet niet op BSN in Keycloak: de BSN-claim van de login is de sleutel voor gegevens, (`iss`, `sub`) de actor. Importeer geen e-mailadres uit een koppeling |
| Gebruikersprofiel | `unmanagedAttributePolicy: ADMIN_VIEW`, profielattributen alleen door de beheerder te bewerken, account-console uit | Idem, voor elke burgerrealm |
| Browserflow | Alleen `auth-cookie` en `identity-provider-redirector`, geen registratie | Idem; koppelingen die de tenant niet gebruikt staan uit in de realm |
| Minimum betrouwbaarheidsniveau | Midden (`MobileTwoFactorContract`), om de flow te kunnen beproeven | Standaard Substantieel (`Smartcard`) per tenant; Midden alleen als de tenant dat expliciet kiest |
| `nl-portal-backend` | `client_secret` | `private_key_jwt` (ADR 0022) |
| Wallet-koppeling | `clientAuthMethod: client_secret_post` | `private_key_jwt` volgens NL GOV |
| Transport | `sslRequired: none`, http | `sslRequired: all` |
| SAML-antwoord | POST-binding, onversleutelde waarden | Artifact-binding en `EncryptedID` volgens de aansluiting |

De standaard first-broker-login-flow en een gebruikersprofiel waarin e-mail en namen niet verplicht zijn moeten kloppen, anders loopt de login vast op een profielformulier; het prototype-realm bewijst dat dit zonder die formulieren werkt.

## Toetsen bij de callback

Keycloak toetst het niveau, het inlogmiddel en de vertegenwoordiging niet zelf. De BFF-callback (#506) toetst elk van de volgende punten **fail-closed met een securityevent**; ontbreekt er een, dan geen sessie:

1. `iss`, `aud`, `nonce` en `azp` van het ID-token tegen de tenant van de host.
2. `auth_time` niet ouder dan het moment waarop deze login is gestart. De BFF start elke login met `prompt=login` (of `max_age=0`), zodat een lopende Keycloak-sessie geen oude login teruggeeft. De Keycloak-sessie duurt niet langer dan de portaalsessie (15 minuten inactief, DigiD T15); uitloggen en het verlopen van de portaalsessie beëindigen de Keycloak-sessie (T14, back-channel).
3. `inlogmiddel` aanwezig en in de ingeschakelde inlogmiddelen van de tenant. Een lokale Keycloak-gebruiker (wachtwoordformulier) heeft geen `inlogmiddel`.
4. `acr` aanwezig en niet leeg, in een **witte lijst per inlogmiddel** (DigiD-URN's alleen bij `digid`, wallet-waarden alleen bij `wallet`), en volgens de vastgelegde volgorde minstens het tenantminimum. Een onbekende waarde is een weigering. De wallet-mapper zet elke `acr` van de wallet in de notitie (leeg als de claim ontbreekt), dus ook een DigiD-URN of een verzonnen waarde: de witte lijst per inlogmiddel is de enige bescherming.
5. `acting_subject_id` aanwezig en geldig (negen cijfers, elfproef).
6. `legal_subject_id` is **leeg of afwezig** tenzij machtigen voor de tenant aan staat; is het niet leeg en staat machtigen uit, dan een weigering. Een niet-lege `legal_subject_id` is bovendien negen cijfers en voldoet aan de elfproef, anders een weigering: zolang het `NameQualifier` niet gecontroleerd wordt kan een pseudoniem als BSN doorkomen en is dit het vangnet voor de vertegenwoordigde.
7. De realmtest (#505) controleert dat de eigen mapper op de DigiD-koppeling staat, dat de client geen eigen `acr`-scope heeft en dat de gebruikersattributen niet door een gebruiker te bewerken zijn.

## Afwijking van de spec

Epic #499 zegt: "`acr_values` gaat mee met het minimum van de tenant". Het prototype laat zien dat dit alleen werkt bij een OIDC-koppeling: bij een SAML-koppeling (DigiD) worden `acr_values` van de login-start niet in de `AuthnRequest` overgenomen, ook niet met de echte URN van een hoger niveau. Het minimum per tenant gaat daarom via de koppelinginstelling (`authnContextClassRefs` met `authnContextComparisonType=minimum`) in het realm-template, en `acr_values` werkt alleen bij OIDC-koppelingen. De epic zegt: "Valt een prototype negatief uit, dan komt het betreffende deel van de spec terug op tafel". **Actie voor de orchestrator**: epic #499 en #506 aanpassen; de lijn voor het realm-template is #505. De callbacktoets (punt 4 hierboven) blijft het enige dat het minimum afdwingt.

## Walletniveau (niet vastgesteld)

#500 vraagt om vast te leggen welk niveau de wallet levert. Dat is met dit prototype niet gelukt en is daarom een **negatieve uitkomst**: het niveau, de claimnaam en de route (tussenbroker of OpenID4VP) van een echte wallet zijn onbekend. Het vervolgticket is **#529** (sub-issue van #499); het stelt vast welke bron het niveau levert, onder welke claimnaam en met welke waarden het binnenkomt, hoe het zich verhoudt tot de DigiD-reeks en hoe de wallet-koppeling bij Keycloak authenticeert (`private_key_jwt`). **Voorlopige werkaanname voor #505 en #506:** zolang dat niet is vastgesteld staat er geen enkele `acr`-waarde in de witte lijst voor inlogmiddel `wallet`, dus de callback weigert de wallet. De wallet gaat pas aan als het vervolgticket de waarden en de volgorde heeft vastgelegd.

### Herauthenticatie bij de wallet

De ingebouwde wallet-mapper (`oidc-user-session-note-idp-mapper`) zet een notitie alleen als de claim er is (uit de klasse `ClaimToUserSessionNoteMapper` afgeleid: `setSessionNote` volgt alleen op een gevonden claim). Bij herauthenticatie in dezelfde Keycloak-sessie blijft dan de waarde van de eerste login staan: een tweede wallet-login zonder `acr` of zonder BSN zou het niveau of de BSN van de eerste houden. Het prototype gebruikt daarom voor de wallet een eigen mapper (`nlportal-oidc-claim-note-mapper`) die bij elke login de notitie zet, leeg als de claim ontbreekt, en faalt bij een claim die geen enkele tekstwaarde is. De test `laat acr en acting_subject_id van de wallet niet blijven hangen ...` toetst dit met één cookie-jar en `prompt=login`. De bewering "elke login zet elke notitie" geldt dus voor de drie eigen mappers (twee DigiD, één wallet) en niet voor de ingebouwde wallet-mapper; het realm-template mag die laatste niet gebruiken. Het acceptatiecriterium staat ook in #529. De test is rood met de ingebouwde mapper (`niveau van de eerdere login blijft staan: http://eidas.europa.eu/LoA/high`) en groen met de eigen.

## Aanbevelingen

1. **Maak een eigen Keycloak-image met de IdP-mappers** (vervolgticket #527) of kies een alternatief dat het niveau als attribuut aanlevert, bijvoorbeeld een tussenliggende broker. Zonder een van beide is het niveau niet in het token en blijft ADR 0022 besluit 12 (minimum afgedwongen in de backend) onuitvoerbaar op de claim.
2. **Zet het minimum per tenant in de koppelinginstelling** (`authnContextClassRefs` en `authnContextComparisonType=minimum`) via het realm-template, en laat de backend `acr` bij de callback toetsen tegen dezelfde tenantwaarde, volgens de punten in [Toetsen bij de callback](#toetsen-bij-de-callback). Leg de volgorde van de URN's vast.
3. **Gebruik sessienotities** voor `acr`, `acting_subject_id` en `legal_subject_id`, geen gebruikersattributen. De mappers daarvoor zijn eigen mappers, voor DigiD en voor de wallet: de ingebouwde wallet-mapper zet een notitie alleen als de claim er is en laat bij herauthenticatie de waarde van de vorige login staan. Elke login zet elke notitie, leeg als de waarde ontbreekt.
4. **Laat `inlogmiddel` uit de alias van de koppeling komen** en leg de aliasnamen vast in het tenantschema.
5. **Neem de beveiligingskeuzes over** uit [Beveiligingskeuzes voor het realm-template](#beveiligingskeuzes-voor-het-realm-template), vooral: geen automatisch koppelen van identiteiten.
6. **Verifieer met echte systemen** vóór livegang: de DigiD-preproductieomgeving (URN's, artifact-binding, versleuteling en type van `ActingSubjectID`, het `NameQualifier`) en een echte wallet of broker (welke claims, welk niveau).

## Open punten

- Welk niveau een echte wallet of tussenbroker in `acr` levert en onder welke claimnaam is niet bekend (zie [Walletniveau](#walletniveau-niet-vastgesteld), vervolgticket #529).
- DigiD gebruikt artifact-binding voor het antwoord en versleutelt de BSN; Keycloak ondersteunt beide, maar het prototype gebruikt POST-binding en onversleutelde waarden. Of het BSN bij de aansluiting als attribuut of in de `NameID` van het `Subject` komt is niet bepaald; de attribuutmapper leest nu alleen tekst of een `NameID`, ontsleutelt niet en controleert het `NameQualifier` niet. Een versleuteld attribuut (`EncryptedAttribute`) wordt door de mapper nu stil overgeslagen (de notitie wordt leeg) in plaats van dat de login faalt; voor `legal_subject_id` logt een gemachtigde dan stil voor zichzelf in. Dat moet falen (criterium in #527).
- Of de koppelvlakspecificatie voor DigiD Machtigen de attribuutnamen `urn:nl-eid-gdi:1.0:ActingSubjectID` en `LegalSubjectID` gebruikt zoals in [auth-bff-nlgov-oauth.md](auth-bff-nlgov-oauth.md) §6.2 staat, is hier overgenomen en niet opnieuw getoetst.
- De URN's van de niveaus zijn niet tegen de primaire bron geverifieerd (zie hierboven).
- Het prototype gebruikt een `client_secret` voor `nl-portal-backend` en `client_secret_post` voor de wallet-koppeling; de echte configuratie gebruikt `private_key_jwt`. Dat raakt de claims niet maar is hier niet bewezen.
- Een DigiD-assertion met een hoger niveau dan gevraagd en het gedrag van de echte DigiD bij een geweigerd of geannuleerd verzoek zijn niet getest.

## Bronnen

| Bron | Link of plek | Opmerking |
|---|---|---|
| Prototype en tests | branch `prototype/digid-wallet-claims-500`, map `prototypes/digid-wallet-claims-500` | 25 tests, groen op Keycloak 26.8.2 |
| Koppelvlakspecificatie DigiD SAML Authenticatie v3.5 (Logius, 2020) | https://www.logius.nl/sites/default/files/bestanden/website/Koppelvlakspecificatie%20SAML%20DigiD%20v3.5%20(definitief).pdf | PDF met de beschikbare middelen niet leesbaar; de vier URN's per niveau komen uit een secundaire bron (Open Forms, die Logius noemt) en uit ADR 0022 (versie 3.7). Niet tegen de primaire bron geverifieerd |
| Mappertypen en configuratie | `GET /admin/realms/portal/identity-provider/instances/<alias>/mapper-types` op Keycloak 26.8.2; klassen in `org.keycloak.keycloak-services-26.8.2.jar` | Gedrag van `XPathAttributeMapper` en `SAMLEndpoint` uit de klassen afgeleid en met een test bevestigd |
| Domeintaal en attributen | [auth-bff-nlgov-oauth.md](auth-bff-nlgov-oauth.md) §6, [fase0-besluiten-multitenancy-bff.md](fase0-besluiten-multitenancy-bff.md) | Q14 en Q16 |
