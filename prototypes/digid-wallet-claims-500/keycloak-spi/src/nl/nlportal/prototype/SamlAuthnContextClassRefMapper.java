package nl.nlportal.prototype;

import java.util.List;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.saml.SAMLEndpoint;
import org.keycloak.broker.saml.SAMLIdentityProviderFactory;
import org.keycloak.dom.saml.v2.assertion.AuthnStatementType;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Prototype (#500): zet de AuthnContextClassRef uit de AuthnStatement van de SAML-assertion in een
 * gebruikerssessie-notitie. Een notitie hoort bij de sessie, niet bij de gebruiker, en kan daarom via de mapper
 * "User Session Note" als claim in het token (anders dan een gebruikersattribuut, dat delen sessies met elkaar).
 */
public class SamlAuthnContextClassRefMapper extends AbstractIdentityProviderMapper {
    public static final String PROVIDER_ID = "nlportal-saml-authncontext-note-mapper";
    public static final String NOTE = "note";

    @Override
    public String[] getCompatibleProviders() {
        return new String[] {SAMLIdentityProviderFactory.PROVIDER_ID};
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
        return "AuthnContextClassRef to User Session Note";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Zet de AuthnContextClassRef van de SAML-assertion in een sessienotitie.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(NOTE);
        p.setLabel("Sessienotitie");
        p.setHelpText("Naam van de notitie, bijvoorbeeld idp_acr.");
        p.setType(ProviderConfigProperty.STRING_TYPE);
        return List.of(p);
    }

    // Bij de eerste login serialiseert Keycloak de context tussen de assertion en de first-broker-login-flow; de
    // AuthnStatement gaat dan verloren, een tekstwaarde in de contextdata niet. Daarom op elk haakje opnieuw zetten.
    private static final String CONTEXT_KEY = "nlportal.authn-context-class-ref";

    @Override
    public void preprocessFederatedIdentity(
            KeycloakSession session, RealmModel realm, IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        Object statement = context.getContextData().get(SAMLEndpoint.SAML_AUTHN_STATEMENT);
        if (statement instanceof AuthnStatementType authn
                && authn.getAuthnContext() != null
                && authn.getAuthnContext().getSequence() != null
                && authn.getAuthnContext().getSequence().getClassRef() != null) {
            context.getContextData()
                    .put(CONTEXT_KEY, authn.getAuthnContext().getSequence().getClassRef().getValue().toString());
        }
        applyNote(mapperModel, context);
    }

    @Override
    public void importNewUser(
            KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        applyNote(mapperModel, context);
    }

    @Override
    public void updateBrokeredUser(
            KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        applyNote(mapperModel, context);
    }

    private void applyNote(IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        Object value = context.getContextData().get(CONTEXT_KEY);
        if (value != null) context.setSessionNote(mapperModel.getConfig().get(NOTE), value.toString());
    }
}
