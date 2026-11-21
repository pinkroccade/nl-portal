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

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.saml.SAMLEndpoint;
import org.keycloak.dom.saml.v2.assertion.AssertionType;
import org.keycloak.dom.saml.v2.assertion.AuthnContextClassRefType;
import org.keycloak.dom.saml.v2.assertion.AuthnContextType;
import org.keycloak.dom.saml.v2.assertion.AuthnContextType.AuthnContextTypeSequence;
import org.keycloak.dom.saml.v2.assertion.AuthnStatementType;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.saml.processing.core.saml.v2.util.XMLTimeUtil;

class SamlAuthnContextClassRefMapperTest {
    private static final String BASIS = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport";
    private static final String SUBSTANTIEEL = "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract";
    private static final String HOOG = "urn:oasis:names:tc:SAML:2.0:ac:classes:SmartcardPKI";
    private final SamlAuthnContextClassRefMapper mapper = new SamlAuthnContextClassRefMapper();
    private final IdentityProviderMapperModel model = mapperModel("note", "acr");

    static AuthnStatementType statement(String classRef) {
        AuthnStatementType statement = new AuthnStatementType(XMLTimeUtil.getIssueInstant());
        AuthnContextType context = new AuthnContextType();
        if (classRef != null) {
            AuthnContextTypeSequence sequence = new AuthnContextTypeSequence();
            sequence.setClassRef(new AuthnContextClassRefType(URI.create(classRef)));
            context.setSequence(sequence);
        }
        statement.setAuthnContext(context);
        return statement;
    }

    /** Zet een login met een assertion met precies deze AuthnStatements; Keycloak zet de eerste ook los in de context. */
    static void zetAssertion(BrokeredIdentityContext context, AuthnStatementType... statements) {
        AssertionType assertion = new AssertionType("id", XMLTimeUtil.getIssueInstant());
        for (AuthnStatementType statement : statements) assertion.addStatement(statement);
        context.getContextData().put(SAMLEndpoint.SAML_ASSERTION, assertion);
        if (statements.length > 0) context.getContextData().put(SAMLEndpoint.SAML_AUTHN_STATEMENT, statements[0]);
    }

    private static BrokeredIdentityContext loginMet(AuthnStatementType... statements) {
        BrokeredIdentityContext context = samlContext();
        zetAssertion(context, statements);
        return context;
    }

    @Test
    void zetDeAuthnContextClassRefVanDeAssertionInDeSessienotitie() {
        BrokeredIdentityContext context = loginMet(statement(BASIS));

        mapper.preprocessFederatedIdentity(null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", BASIS);
    }

    @Test
    void gebruiktDeNotitienaamUitDeConfiguratie() {
        BrokeredIdentityContext context = loginMet(statement(BASIS));

        mapper.preprocessFederatedIdentity(null, null, mapperModel("note", "niveau"), context);

        assertThat(sessionNotes(context)).containsOnlyKeys("niveau");
    }

    @Test
    void zetEenLegeNotitieAlsDeAssertionGeenAuthnContextClassRefHeeft() {
        BrokeredIdentityContext context = loginMet(statement(null));

        mapper.preprocessFederatedIdentity(null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
    }

    @Test
    void zetEenLegeNotitieAlsDeAuthnStatementGeenAuthnContextHeeft() {
        BrokeredIdentityContext context = loginMet(new AuthnStatementType(XMLTimeUtil.getIssueInstant()));

        mapper.preprocessFederatedIdentity(null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
    }

    @Test
    void laatDeLoginFalenAlsDeAssertionGeenAuthnStatementHeeft() {
        BrokeredIdentityContext context = loginMet();

        assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, model, context))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("AuthnStatement");
    }

    @Test
    void laatDeLoginFalenBijMeerdereAuthnStatementsMetVerschillendNiveau() {
        // Keycloak zet alleen het eerste los in de context; een tweede met een hoger niveau mag niet stil verdwijnen.
        BrokeredIdentityContext context = loginMet(statement(BASIS), statement(HOOG));

        assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, model, context))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("AuthnStatement");
        assertThat(sessionNotes(context)).isEmpty();
    }

    @Test
    void laatDeLoginFalenAlsHetLosseAuthnStatementNietUitDezelfdeAssertionKomt() {
        BrokeredIdentityContext context = loginMet(statement(BASIS));
        context.getContextData().put(SAMLEndpoint.SAML_AUTHN_STATEMENT, statement(HOOG));

        assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, model, context))
                .isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void laatDeLoginFalenZonderAssertionEnZonderEerderGezetteWaarde() {
        BrokeredIdentityContext context = samlContext();

        assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, model, context))
                .isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void eenLatereLoginOverschrijftHetNiveauVanEenEerdereLogin() {
        BrokeredIdentityContext eerste = loginMet(statement(SUBSTANTIEEL));
        mapper.preprocessFederatedIdentity(null, null, model, eerste);
        BrokeredIdentityContext tweede = loginMet(statement(BASIS));

        mapper.preprocessFederatedIdentity(null, null, model, tweede);

        assertThat(sessionNotes(tweede)).containsEntry("acr", BASIS);
    }

    @Test
    void eersteLoginHoudtDeWaardeNaDeSerialisatieVanDeContextVast() {
        // Bij de eerste login serialiseert Keycloak de context tussen de assertion en de first-broker-login-flow: de
        // assertion is dan weg, een tekstwaarde in de contextdata niet. De latere haakjes zetten de notitie opnieuw.
        BrokeredIdentityContext context = loginMet(statement(SUBSTANTIEEL));
        mapper.preprocessFederatedIdentity(null, null, model, context);
        context.getContextData().remove(SAMLEndpoint.SAML_ASSERTION);
        context.getContextData().remove(SAMLEndpoint.SAML_AUTHN_STATEMENT);

        mapper.importNewUser(null, null, null, model, context);
        assertThat(sessionNotes(context)).containsEntry("acr", SUBSTANTIEEL);
        mapper.updateBrokeredUser(null, null, null, model, context);
        assertThat(sessionNotes(context)).containsEntry("acr", SUBSTANTIEEL);
        mapper.preprocessFederatedIdentity(null, null, model, context);
        assertThat(sessionNotes(context)).containsEntry("acr", SUBSTANTIEEL);
    }

    @Test
    void zetZonderAssertionOpEenLaterHaakjeEenLegeNotitieEnGeenWaardeUitHetNiets() {
        BrokeredIdentityContext context = samlContext();

        mapper.importNewUser(null, null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
    }

    @Test
    void weigertEenKoppelingZonderHandtekeningcontrole() {
        assertGeweigerdZonderHandtekeningcontrole(mapper, model);
    }

    /** Gedeelde controle voor de SAML-mappers: elke zwakke koppelingsinstelling laat de login falen. */
    static void assertGeweigerdZonderHandtekeningcontrole(
            AbstractIdentityProviderMapper mapper, IdentityProviderMapperModel model) {
        BrokeredIdentityContext[] zwak = {
            samlContext(false, true, "MIIBcertificaat"),
            samlContext(true, false, "MIIBcertificaat"),
            samlContext(true, true, null),
            samlContext(true, true, " "),
            MapperTestSupport.newContext(),
        };
        for (BrokeredIdentityContext context : zwak) {
            zetAssertion(context, statement(BASIS));

            assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, model, context))
                    .isInstanceOf(IdentityBrokerException.class)
                    .hasMessageContaining("handtekening");
            assertThat(sessionNotes(context)).isEmpty();
        }
    }

    @Test
    void weigertEenMapperZonderNotitienaam() {
        BrokeredIdentityContext context = loginMet(statement(BASIS));

        assertThatThrownBy(() -> mapper.preprocessFederatedIdentity(null, null, mapperModel(), context))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("note");
    }

    @Test
    void isAlleenBedoeldVoorSamlKoppelingenEnDraaitInElkeSynchronisatiemodus() {
        assertThat(mapper.getCompatibleProviders()).containsExactly("saml");
        assertThat(mapper.supportsSyncMode(IdentityProviderSyncMode.FORCE)).isTrue();
        assertThat(mapper.getId()).isEqualTo("nlportal-saml-authncontext-note-mapper");
        assertThat(mapper.getConfigProperties()).extracting("name").containsExactly("note");
        assertThat(mapper.getDisplayCategory()).isNotBlank();
        assertThat(mapper.getDisplayType()).isNotBlank();
        assertThat(mapper.getHelpText()).isNotBlank();
    }
}
