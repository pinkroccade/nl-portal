/*
 * Copyright 2026 PinkRoccade Local Government B.V.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.nlportal.keycloak;

import static nl.nlportal.keycloak.MapperTestSupport.mapperModel;
import static nl.nlportal.keycloak.MapperTestSupport.samlContext;
import static nl.nlportal.keycloak.MapperTestSupport.sessionNotes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.saml.SAMLEndpoint;
import org.keycloak.dom.saml.v2.assertion.AssertionType;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType.ASTChoiceType;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.dom.saml.v2.assertion.EncryptedElementType;
import org.keycloak.dom.saml.v2.assertion.NameIDType;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.saml.processing.core.saml.v2.util.XMLTimeUtil;

class SamlAttributeToSessionNoteMapperTest {
    private static final String BSN = "999990001";
    private static final String ACTING = "urn:nl-eid-gdi:1.0:ActingSubjectID";
    private static final String LEGAL = "urn:nl-eid-gdi:1.0:LegalSubjectID";
    private final SamlAttributeToSessionNoteMapper mapper = new SamlAttributeToSessionNoteMapper();
    private final IdentityProviderMapperModel model = mapperModel("attribute.name", ACTING, "note", "acting_subject_id");

    private static AttributeType attribuut(String naam, Object... waarden) {
        AttributeType attribute = new AttributeType(naam);
        for (Object waarde : waarden) attribute.addAttributeValue(waarde);
        return attribute;
    }

    private static NameIDType nameId(String waarde) {
        NameIDType nameId = new NameIDType();
        nameId.setValue(waarde);
        return nameId;
    }

    private static ASTChoiceType choice(AttributeType attribute) {
        return new ASTChoiceType(attribute);
    }

    private static ASTChoiceType versleuteld() {
        return new ASTChoiceType(new EncryptedElementType());
    }

    private static BrokeredIdentityContext loginMet(ASTChoiceType... attributen) {
        AssertionType assertion = new AssertionType("id", XMLTimeUtil.getIssueInstant());
        AttributeStatementType statement = new AttributeStatementType();
        for (ASTChoiceType attribuut : attributen) statement.addAttribute(attribuut);
        assertion.addStatement(statement);
        BrokeredIdentityContext context = samlContext();
        context.getContextData().put(SAMLEndpoint.SAML_ASSERTION, assertion);
        return context;
    }

    private void verwerk(BrokeredIdentityContext context) {
        mapper.preprocessFederatedIdentity(null, null, model, context);
    }

    @Test
    void zetEenTekstattribuutInDeSessienotitie() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, BSN)));

        verwerk(context);

        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", BSN);
    }

    @Test
    void leestEenNameIdAlsGetypeerdeWaarde() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, nameId(BSN))));

        verwerk(context);

        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", BSN);
    }

    @Test
    void negeertAttributenMetEenAndereNaam() {
        BrokeredIdentityContext context = loginMet(choice(attribuut("anders", "x")), choice(attribuut(ACTING, BSN)));

        verwerk(context);

        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", BSN);
    }

    @Test
    void zetEenLegeNotitieAlsHetAttribuutOntbreekt() {
        BrokeredIdentityContext context = loginMet(choice(attribuut("anders", "x")));

        verwerk(context);

        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", "");
    }

    @Test
    void zetEenLegeNotitieAlsDeAssertionGeenAttributenHeeft() {
        BrokeredIdentityContext context = loginMet();

        verwerk(context);

        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", "");
    }

    @Test
    void laatDeLoginFalenAlsHetAttribuutGeenWaardeHeeft() {
        // Een attribuut zonder AttributeValue is geen "ontbrekend attribuut": consistent met "lege waarde faalt".
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING)));

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void laatDeLoginFalenZonderAssertionEnZonderEerderGezetteWaarde() {
        BrokeredIdentityContext context = samlContext();

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void weigertEenKoppelingZonderHandtekeningcontrole() {
        SamlAuthnContextClassRefMapperTest.assertGeweigerdZonderHandtekeningcontrole(mapper, model);
    }

    @Test
    void weigertEenMapperZonderAttribuutnaamOfNotitienaam() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, BSN)));

        assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, mapperModel("note", "x"), context))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("attribute.name");
        assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, mapperModel("attribute.name", ACTING), context))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("note");
    }

    @Test
    void laatDeLoginFalenBijEenLegeWaarde() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, " ")));

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void laatDeLoginFalenBijMeerDanEenWaarde() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, BSN, "999990002")));

        assertThatThrownBy(() -> verwerk(context))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("meer dan een waarde");
    }

    @Test
    void laatDeLoginFalenBijEenDubbelAttribuut() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, BSN)), choice(attribuut(ACTING, "999990002")));

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void laatDeLoginFalenBijEenNameIdZonderWaarde() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, new NameIDType())));

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void laatDeLoginFalenBijEenWaardeVanEenNietOndersteundType() {
        // Bijvoorbeeld een EncryptedID: ontsleutelen staat open, dus geen waarde kiezen maar weigeren.
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, new EncryptedElementType())));

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void laatDeLoginFalenBijEenWaardeDieZelfMarkupIs() {
        // Keycloak levert een onbekend kindelement in een AttributeValue (bijvoorbeeld een EncryptedID) als tekst van de
        // geserialiseerde XML: dat is geen BSN en mag nooit als waarde in het token komen.
        String markup = "<saml:EncryptedID xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\">AAAA</saml:EncryptedID>";
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, markup)));

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
        assertThat(sessionNotes(context)).isEmpty();
    }

    @Test
    void laatDeLoginFalenBijEenVersleuteldAttribuut() {
        // De naam zit in de versleuteling: een niet-ontsleuteld attribuut kan het gezochte zijn en wordt niet stil
        // overgeslagen.
        BrokeredIdentityContext context = loginMet(versleuteld());

        assertThatThrownBy(() -> verwerk(context))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("versleuteld");
    }

    @Test
    void laatDeLoginFalenBijEenVersleuteldAttribuutNaastHetGezochteAttribuut() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, BSN)), versleuteld());

        assertThatThrownBy(() -> verwerk(context)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void eenLatereLoginZonderHetAttribuutLaatDeWaardeVanEenEerdereLoginNietStaan() {
        verwerk(loginMet(choice(attribuut(ACTING, BSN))));
        BrokeredIdentityContext tweede = loginMet(choice(attribuut("anders", "x")));

        verwerk(tweede);

        assertThat(sessionNotes(tweede)).containsEntry("acting_subject_id", "");
    }

    @Test
    void eersteLoginHoudtDeWaardeNaDeSerialisatieVanDeContextVast() {
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, BSN)));
        verwerk(context);
        context.getContextData().remove(SAMLEndpoint.SAML_ASSERTION);

        mapper.importNewUser(null, null, null, model, context);
        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", BSN);
        mapper.updateBrokeredUser(null, null, null, model, context);
        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", BSN);
        verwerk(context);
        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", BSN);
    }

    @Test
    void zetZonderAssertionOpEenLaterHaakjeEenLegeNotitie() {
        BrokeredIdentityContext context = samlContext();

        mapper.importNewUser(null, null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acting_subject_id", "");
    }

    @Test
    void ondersteuntTweeAttributenMetEenEigenNotitieNaastElkaar() {
        IdentityProviderMapperModel legal = mapperModel("attribute.name", LEGAL, "note", "legal_subject_id");
        BrokeredIdentityContext context = loginMet(choice(attribuut(ACTING, BSN)), choice(attribuut(LEGAL, "999990002")));

        verwerk(context);
        mapper.preprocessFederatedIdentity(null, null, legal, context);

        assertThat(sessionNotes(context))
                .containsEntry("acting_subject_id", BSN)
                .containsEntry("legal_subject_id", "999990002");
    }

    @Test
    void isAlleenBedoeldVoorSamlKoppelingenEnDraaitInElkeSynchronisatiemodus() {
        assertThat(mapper.getCompatibleProviders()).containsExactly("saml");
        assertThat(mapper.supportsSyncMode(IdentityProviderSyncMode.IMPORT)).isTrue();
        assertThat(mapper.getId()).isEqualTo("nlportal-saml-attribute-note-mapper");
        assertThat(mapper.getConfigProperties()).extracting("name").containsExactly("attribute.name", "note");
        assertThat(mapper.getDisplayCategory()).isNotBlank();
        assertThat(mapper.getDisplayType()).isNotBlank();
        assertThat(mapper.getHelpText()).isNotBlank();
    }
}
