package nl.nlportal.prototype;

import java.util.List;
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.saml.SAMLEndpoint;
import org.keycloak.broker.saml.SAMLIdentityProviderFactory;
import org.keycloak.dom.saml.v2.assertion.AssertionType;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Prototype (#500): zet een SAML-attribuut (bijvoorbeeld ActingSubjectID of LegalSubjectID) in een
 * gebruikerssessie-notitie in plaats van in een gebruikersattribuut. Een gebruikersattribuut blijft staan als een
 * volgende login het attribuut niet meer meestuurt; een notitie hoort bij precies een login.
 */
public class SamlAttributeToSessionNoteMapper extends AbstractIdentityProviderMapper {
    public static final String PROVIDER_ID = "nlportal-saml-attribute-note-mapper";
    public static final String ATTRIBUTE_NAME = "attribute.name";
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
        return "SAML Attribute to User Session Note";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getHelpText() {
        return "Zet de waarde van een SAML-attribuut in een sessienotitie.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(property(ATTRIBUTE_NAME, "Attribuutnaam"), property(NOTE, "Sessienotitie"));
    }

    private static ProviderConfigProperty property(String name, String label) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name);
        p.setLabel(label);
        p.setType(ProviderConfigProperty.STRING_TYPE);
        return p;
    }

    // De assertion gaat bij de eerste login verloren tussen de assertion en de first-broker-login-flow; een
    // tekstwaarde in de contextdata niet. Daarom de waarde onthouden en op elk haakje de notitie zetten.
    private static String contextKey(IdentityProviderMapperModel model) {
        return "nlportal.saml-attribute." + model.getConfig().get(NOTE);
    }

    @Override
    public void preprocessFederatedIdentity(
            KeycloakSession session, RealmModel realm, IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        if (context.getContextData().get(SAMLEndpoint.SAML_ASSERTION) instanceof AssertionType assertion) {
            String wanted = model.getConfig().get(ATTRIBUTE_NAME);
            for (AttributeStatementType statement : assertion.getAttributeStatements()) {
                for (AttributeStatementType.ASTChoiceType choice : statement.getAttributes()) {
                    AttributeType attribute = choice.getAttribute();
                    if (attribute != null
                            && wanted.equals(attribute.getName())
                            && !attribute.getAttributeValue().isEmpty()) {
                        context.getContextData().put(contextKey(model), attribute.getAttributeValue().get(0).toString());
                    }
                }
            }
        }
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
        if (value != null) context.setSessionNote(model.getConfig().get(NOTE), value.toString());
    }
}
