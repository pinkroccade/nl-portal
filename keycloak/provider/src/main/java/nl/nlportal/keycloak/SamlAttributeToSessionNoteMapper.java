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

import java.util.ArrayList;
import java.util.List;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.dom.saml.v2.assertion.AssertionType;
import org.keycloak.dom.saml.v2.assertion.AttributeStatementType;
import org.keycloak.dom.saml.v2.assertion.AttributeType;
import org.keycloak.dom.saml.v2.assertion.NameIDType;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * zet een SAML-attribuut (bijvoorbeeld ActingSubjectID of LegalSubjectID) in een gebruikerssessie-notitie in plaats
 * van in een gebruikersattribuut. Ontbreekt het attribuut, dan zet de mapper een lege notitie. Het attribuut moet
 * precies een waarde hebben van een ondersteund type; anders faalt de login (liever geen login dan een gekozen
 * waarde), ook bij een attribuut zonder waarde. Een versleuteld attribuut (de naam zit in de versleuteling) laat de
 * login ook falen.
 */
public class SamlAttributeToSessionNoteMapper extends SamlSessionNoteMapper {
    public static final String PROVIDER_ID = "nlportal-saml-attribute-note-mapper";
    public static final String ATTRIBUTE_NAME = "attribute.name";

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
    protected List<ProviderConfigProperty> sourceProperties() {
        return List.of(property(ATTRIBUTE_NAME, "Attribuutnaam", "Naam van het SAML-attribuut."));
    }

    @Override
    protected String readValue(IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        String wanted = required(model, ATTRIBUTE_NAME);
        AssertionType assertion = assertion(context);
        if (assertion == null) return null;
        List<String> values = new ArrayList<>();
        for (AttributeStatementType statement : assertion.getAttributeStatements()) {
            for (AttributeStatementType.ASTChoiceType choice : statement.getAttributes()) {
                // De naam van een versleuteld attribuut zit in de versleuteling: het kan het gezochte zijn. Stil
                // overslaan zou een lege notitie geven terwijl de waarde er wel was; liever geen login.
                if (choice.getEncryptedAssertion() != null) {
                    throw new IdentityBrokerException(
                            "De assertion bevat een versleuteld attribuut; ontsleutelen wordt niet ondersteund");
                }
                AttributeType attribute = choice.getAttribute();
                if (attribute != null && wanted.equals(attribute.getName())) {
                    if (attribute.getAttributeValue().isEmpty()) {
                        throw new IdentityBrokerException("Attribuut " + wanted + " heeft geen waarde");
                    }
                    for (Object value : attribute.getAttributeValue()) values.add(text(wanted, value));
                }
            }
        }
        if (values.size() > 1) {
            throw new IdentityBrokerException("Attribuut " + wanted + " heeft meer dan een waarde");
        }
        // Geen attribuut: expliciet leeg, niet weglaten (zie SessionNoteMapper).
        return values.isEmpty() ? "" : values.get(0);
    }

    // Alleen tekst of een NameID (BSN in de NameID zonder versleuteling); een EncryptedID of een ander type laat de login
    // falen. Het NameQualifier-type (BSN of pseudoniem) controleren staat open (zie README en #506).
    private static String text(String attribute, Object value) {
        String text = null;
        if (value instanceof String s) text = s;
        else if (value instanceof NameIDType nameId) text = nameId.getValue();
        // Keycloak levert een onbekend kindelement in een AttributeValue (zoals een EncryptedID) als tekst van de
        // geserialiseerde XML; markup is geen waarde.
        if (text == null || text.isBlank() || text.stripLeading().startsWith("<")) {
            throw new IdentityBrokerException("Attribuut " + attribute + " heeft een lege of niet-ondersteunde waarde");
        }
        return text;
    }
}
