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

import java.util.List;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.saml.SAMLEndpoint;
import org.keycloak.dom.saml.v2.assertion.AssertionType;
import org.keycloak.dom.saml.v2.assertion.AuthnStatementType;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * zet de AuthnContextClassRef uit de AuthnStatement van de SAML-assertion in een gebruikerssessie-notitie, zodat de
 * client-mapper "User Session Note" hem als claim in het token zet. Ontbreekt de AuthnContextClassRef, dan zet de
 * mapper een lege notitie; de backend weigert een lege of onbekende acr. De assertion moet precies een AuthnStatement
 * hebben, en het statement dat Keycloak los in de context zet moet dat zijn: een tweede statement met een ander niveau
 * mag niet stil genegeerd worden.
 */
public class SamlAuthnContextClassRefMapper extends SamlSessionNoteMapper {
    public static final String PROVIDER_ID = "nlportal-saml-authncontext-note-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "AuthnContextClassRef to User Session Note";
    }

    @Override
    public String getHelpText() {
        return "Zet de AuthnContextClassRef van de SAML-assertion in een sessienotitie.";
    }

    @Override
    protected List<ProviderConfigProperty> sourceProperties() {
        return List.of();
    }

    @Override
    protected String readValue(IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        AssertionType assertion = assertion(context);
        if (assertion == null) return null;
        List<AuthnStatementType> statements = assertion.getStatements().stream()
                .filter(AuthnStatementType.class::isInstance)
                .map(AuthnStatementType.class::cast)
                .toList();
        if (statements.size() != 1) {
            throw new IdentityBrokerException(
                    "De assertion heeft " + statements.size() + " AuthnStatements in plaats van precies een");
        }
        AuthnStatementType authn = statements.get(0);
        Object loose = context.getContextData().get(SAMLEndpoint.SAML_AUTHN_STATEMENT);
        if (loose != null && loose != authn) {
            throw new IdentityBrokerException("Het AuthnStatement in de context is niet dat van de gevalideerde assertion");
        }
        if (authn.getAuthnContext() != null
                && authn.getAuthnContext().getSequence() != null
                && authn.getAuthnContext().getSequence().getClassRef() != null) {
            return authn.getAuthnContext().getSequence().getClassRef().getValue().toString();
        }
        return "";
    }
}
