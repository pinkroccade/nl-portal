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

import java.util.Arrays;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.saml.SAMLEndpoint;
import org.keycloak.broker.saml.SAMLIdentityProviderConfig;
import org.keycloak.broker.saml.SAMLIdentityProviderFactory;
import org.keycloak.dom.saml.v2.assertion.AssertionType;

/**
 * Basis van de mappers voor een SAML-koppeling (DigiD). De waarde (niveau, BSN) is alleen te vertrouwen als Keycloak de
 * handtekening op de assertion heeft gecontroleerd, en dat hangt aan de instellingen van de koppeling. Een mapper
 * neemt daarom niets over als de koppeling niet zo staat: handtekeningen valideren, de assertion zelf ondertekend
 * vereisen en een certificaat vastgepind. Anders zou iedereen die een SAML-response naar het endpoint van de broker
 * post een willekeurig niveau en BSN in het token kunnen zetten. Een foute instelling in het realm-template of in de
 * beheerconsole laat de login dus falen in plaats van stil onveilig te werken.
 */
public abstract class SamlSessionNoteMapper extends SessionNoteMapper {
    @Override
    public String[] getCompatibleProviders() {
        return new String[] {SAMLIdentityProviderFactory.PROVIDER_ID};
    }

    @Override
    protected void checkIdentityProvider(BrokeredIdentityContext context) {
        SAMLIdentityProviderConfig config = new SAMLIdentityProviderConfig(context.getIdpConfig());
        String[] certificates = config.getSigningCertificates();
        boolean pinned = certificates != null && Arrays.stream(certificates).anyMatch(c -> c != null && !c.isBlank());
        if (!config.isValidateSignature() || !config.isWantAssertionsSigned() || !pinned) {
            throw new IdentityBrokerException(
                    "De SAML-koppeling controleert de handtekening van de assertion niet (validateSignature,"
                            + " wantAssertionsSigned en een vastgepind signingCertificate zijn verplicht);"
                            + " de waarde wordt niet overgenomen");
        }
    }

    /** De assertion die Keycloak na zijn eigen validatie in de context zette, of {@code null} (herhaalde aanroep). */
    protected static AssertionType assertion(BrokeredIdentityContext context) {
        return context.getContextData().get(SAMLEndpoint.SAML_ASSERTION) instanceof AssertionType assertion
                ? assertion
                : null;
    }
}
