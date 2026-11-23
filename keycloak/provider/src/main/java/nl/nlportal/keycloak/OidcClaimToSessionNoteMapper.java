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
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.OIDCIdentityProviderFactory;
import org.keycloak.broker.oidc.mappers.AbstractClaimMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.JsonWebToken;

/**
 * zet een claim van het gevalideerde ID-token van een OIDC-koppeling (de wallet) in een gebruikerssessie-notitie.
 * Anders dan de ingebouwde {@code oidc-user-session-note-idp-mapper} zet deze mapper bij elke login de notitie, leeg
 * als de claim ontbreekt. De claim komt alleen uit het ID-token, nooit uit het access token of de ongetekende
 * userinfo-respons waar {@code getClaimValue(context, naam)} ook in zoekt. De claim moet een enkele tekstwaarde zijn;
 * een lijst of ander type laat de login falen.
 */
public class OidcClaimToSessionNoteMapper extends SessionNoteMapper {
    public static final String PROVIDER_ID = "nlportal-oidc-claim-note-mapper";
    public static final String CLAIM_NAME = "claim.name";

    @Override
    public String[] getCompatibleProviders() {
        return new String[] {OIDCIdentityProviderFactory.PROVIDER_ID};
    }

    @Override
    public String getDisplayType() {
        return "OIDC Claim to User Session Note (wissend)";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Zet de waarde van een claim van het ID-token in een sessienotitie, bij elke login (leeg als de claim ontbreekt).";
    }

    @Override
    protected List<ProviderConfigProperty> sourceProperties() {
        return List.of(property(CLAIM_NAME, "Claimnaam", "Naam van de claim in het ID-token."));
    }

    @Override
    protected String readValue(IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        String name = required(model, CLAIM_NAME);
        if (!(context.getContextData().get(OIDCIdentityProvider.VALIDATED_ID_TOKEN) instanceof JsonWebToken idToken)) {
            return null;
        }
        Object value = AbstractClaimMapper.getClaimValue(idToken, name);
        if (value == null) return "";
        if (value instanceof String s) return s;
        throw new IdentityBrokerException("Claim " + name + " is geen enkele tekstwaarde");
    }
}
