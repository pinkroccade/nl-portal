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
import org.keycloak.broker.oidc.OIDCIdentityProviderFactory;
import org.keycloak.broker.oidc.mappers.AbstractClaimMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * zet een claim van het ID-token van een OIDC-koppeling (de wallet) in een gebruikerssessie-notitie.
 * Anders dan de ingebouwde {@code oidc-user-session-note-idp-mapper} zet deze mapper bij elke login de notitie, leeg als
 * de claim ontbreekt, zodat een waarde van een eerdere login in dezelfde Keycloak-sessie niet blijft staan (de
 * ingebouwde mapper zet een notitie alleen als de claim er is). De claim moet een enkele tekstwaarde zijn; een lijst
 * of ander type laat de login falen.
 */
public class OidcClaimToSessionNoteMapper extends AbstractClaimMapper {
    public static final String PROVIDER_ID = "nlportal-oidc-claim-note-mapper";
    public static final String CLAIM_NAME = "claim.name";
    public static final String NOTE = "note";

    @Override
    public String[] getCompatibleProviders() {
        return new String[] {OIDCIdentityProviderFactory.PROVIDER_ID};
    }

    @Override
    public boolean supportsSyncMode(IdentityProviderSyncMode syncMode) {
        return true;
    }

    @Override
    public String getDisplayCategory() {
        return "Session Importer";
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
        return "Zet de waarde van een claim in een sessienotitie, bij elke login (leeg als de claim ontbreekt).";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(property(CLAIM_NAME, "Claimnaam"), property(NOTE, "Sessienotitie"));
    }

    private static ProviderConfigProperty property(String name, String label) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name);
        p.setLabel(label);
        p.setType(ProviderConfigProperty.STRING_TYPE);
        return p;
    }

    // Net als bij de SAML-mapper: de waarde onthouden in de contextdata, zodat ook de first-broker-login-flow hem heeft.
    private static String contextKey(IdentityProviderMapperModel model) {
        return "nlportal.oidc-claim." + model.getConfig().get(NOTE);
    }

    @Override
    public void preprocessFederatedIdentity(
            KeycloakSession session, RealmModel realm, IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        String name = model.getConfig().get(CLAIM_NAME);
        Object value = getClaimValue(context, name);
        String text;
        if (value == null) text = "";
        else if (value instanceof String s) text = s;
        else throw new IdentityBrokerException("Claim " + name + " is geen enkele tekstwaarde");
        context.getContextData().put(contextKey(model), text);
        apply(model, context);
    }

    @Override
    public void importNewUser(
            KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        apply(model, context);
    }

    @Override
    public void updateBrokeredUser(
            KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        apply(model, context);
    }

    private void apply(IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        Object value = context.getContextData().get(contextKey(model));
        context.setSessionNote(model.getConfig().get(NOTE), value == null ? "" : value.toString());
    }
}
