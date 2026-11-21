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
import static nl.nlportal.keycloak.MapperTestSupport.newContext;
import static nl.nlportal.keycloak.MapperTestSupport.sessionNotes;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.saml.SAMLEndpoint;
import org.keycloak.dom.saml.v2.assertion.AuthnContextClassRefType;
import org.keycloak.dom.saml.v2.assertion.AuthnContextType;
import org.keycloak.dom.saml.v2.assertion.AuthnContextType.AuthnContextTypeSequence;
import org.keycloak.dom.saml.v2.assertion.AuthnStatementType;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.saml.processing.core.saml.v2.util.XMLTimeUtil;

class SamlAuthnContextClassRefMapperTest {
    private static final String BASIS = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport";
    private static final String SUBSTANTIEEL = "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract";
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

    private BrokeredIdentityContext loginMet(Object statement) {
        BrokeredIdentityContext context = newContext();
        if (statement != null) context.getContextData().put(SAMLEndpoint.SAML_AUTHN_STATEMENT, statement);
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
    void zetEenLegeNotitieAlsDeAssertionGeenAuthnStatementHeeft() {
        BrokeredIdentityContext context = loginMet(null);

        mapper.preprocessFederatedIdentity(null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
    }

    @Test
    void zetEenLegeNotitieAlsDeAuthnStatementGeenAuthnContextHeeft() {
        AuthnStatementType zonderContext = new AuthnStatementType(XMLTimeUtil.getIssueInstant());
        BrokeredIdentityContext context = loginMet(zonderContext);

        mapper.preprocessFederatedIdentity(null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
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
    void eenLatereLoginZonderAuthnStatementLaatHetNiveauVanEenEerdereLoginNietStaan() {
        // Dezelfde context (first-broker-login-flow): de assertion is weg, de waarde in de contextdata blijft. Een nieuwe
        // context heeft die waarde niet: de notitie is dan leeg, niet de waarde van de login ervoor.
        BrokeredIdentityContext eerste = loginMet(statement(SUBSTANTIEEL));
        mapper.preprocessFederatedIdentity(null, null, model, eerste);
        BrokeredIdentityContext tweede = loginMet(null);

        mapper.preprocessFederatedIdentity(null, null, model, tweede);

        assertThat(sessionNotes(tweede)).containsEntry("acr", "");
    }

    @Test
    void eersteLoginHoudtDeWaardeNaDeSerialisatieVanDeContextVast() {
        // Bij de eerste login serialiseert Keycloak de context tussen de assertion en de first-broker-login-flow: de
        // AuthnStatement is dan weg, een tekstwaarde in de contextdata niet. De latere haakjes zetten de notitie opnieuw.
        BrokeredIdentityContext context = loginMet(statement(SUBSTANTIEEL));
        mapper.preprocessFederatedIdentity(null, null, model, context);
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
        BrokeredIdentityContext context = newContext();

        mapper.importNewUser(null, null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
    }

    @Test
    void isAlleenBedoeldVoorSamlKoppelingenEnDraaitInElkeSynchronisatiemodus() {
        assertThat(mapper.getCompatibleProviders()).containsExactly("saml");
        assertThat(mapper.supportsSyncMode(org.keycloak.models.IdentityProviderSyncMode.FORCE)).isTrue();
        assertThat(mapper.getId()).isEqualTo("nlportal-saml-authncontext-note-mapper");
        assertThat(mapper.getConfigProperties()).extracting("name").containsExactly("note");
        assertThat(mapper.getDisplayCategory()).isNotBlank();
        assertThat(mapper.getDisplayType()).isNotBlank();
        assertThat(mapper.getHelpText()).isNotBlank();
    }
}
