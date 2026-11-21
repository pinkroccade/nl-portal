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
import org.keycloak.broker.provider.AbstractIdentityProviderMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Gemeenschappelijke basis van de mappers die een waarde van een inlogmiddel in een gebruikerssessie-notitie zetten.
 * Een notitie hoort bij precies een login (een gebruikersattribuut is gedeeld door gelijktijdige logins en lekt dus
 * van de ene login naar het token van de andere). De kernregel staat hier, op een plek: <strong>elke login zet de
 * notitie expliciet</strong>, de waarde of een lege tekst, zodat een waarde van een eerdere login in dezelfde
 * Keycloak-sessie niet blijft staan.
 *
 * <p>Bij de eerste login serialiseert Keycloak de context tussen de assertion of het token en de
 * first-broker-login-flow; het bronobject gaat dan verloren, een tekstwaarde in de contextdata niet. Daarom wordt de
 * waarde in de contextdata onthouden en op elk haakje de notitie gezet. Faalt de bron (dubbelzinnig, of een ontbrekend
 * bronobject zonder eerder onthouden waarde), dan faalt de login: liever geen login dan een gekozen waarde.
 */
public abstract class SessionNoteMapper extends AbstractIdentityProviderMapper {
    /** Configuratiesleutel van de naam van de notitie, gelijk voor alle mappers. */
    public static final String NOTE = "note";

    @Override
    public boolean supportsSyncMode(IdentityProviderSyncMode syncMode) {
        return true;
    }

    @Override
    public String getDisplayCategory() {
        return "Session Importer";
    }

    /** De configuratie van deze bron, zonder de notitienaam (die voegt de basis toe). */
    protected abstract List<ProviderConfigProperty> sourceProperties();

    /**
     * Leest de waarde uit de bron van deze login.
     *
     * @return de waarde (leeg als de bron er geen heeft), of {@code null} als het bronobject ontbreekt (een herhaalde
     *     aanroep na serialisatie van de context)
     * @throws IdentityBrokerException als de bron dubbelzinnig of niet ondersteund is
     */
    protected abstract String readValue(IdentityProviderMapperModel model, BrokeredIdentityContext context);

    /** Controle van de koppeling voordat een waarde wordt overgenomen; faalt met een {@link IdentityBrokerException}. */
    protected void checkIdentityProvider(BrokeredIdentityContext context) {}

    @Override
    public final List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>(sourceProperties());
        properties.add(property(
                NOTE,
                "Sessienotitie",
                "Naam van de notitie, bijvoorbeeld acr (de naam die de client-mapper in het token leest)."));
        return properties;
    }

    protected static ProviderConfigProperty property(String name, String label, String helpText) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name);
        p.setLabel(label);
        p.setHelpText(helpText);
        p.setType(ProviderConfigProperty.STRING_TYPE);
        return p;
    }

    /** Verplichte configuratie: er is geen validatiehaakje in de mapper-SPI, dus faalt de login met een duidelijke fout. */
    protected static String required(IdentityProviderMapperModel model, String key) {
        String value = model.getConfig() == null ? null : model.getConfig().get(key);
        if (value == null || value.isBlank()) {
            throw new IdentityBrokerException("Mapper " + model.getName() + ": configuratie " + key + " ontbreekt");
        }
        return value;
    }

    private String contextKey(IdentityProviderMapperModel model) {
        return "nlportal." + getId() + "." + required(model, NOTE);
    }

    @Override
    public final void preprocessFederatedIdentity(
            KeycloakSession session, RealmModel realm, IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        String key = contextKey(model);
        checkIdentityProvider(context);
        String value = readValue(model, context);
        if (value != null) {
            context.getContextData().put(key, value);
        } else if (!context.getContextData().containsKey(key)) {
            throw new IdentityBrokerException("Mapper " + model.getName() + ": de bron van de login ontbreekt");
        }
        apply(model, context);
    }

    @Override
    public final void importNewUser(
            KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        apply(model, context);
    }

    @Override
    public final void updateBrokeredUser(
            KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        apply(model, context);
    }

    private void apply(IdentityProviderMapperModel model, BrokeredIdentityContext context) {
        Object value = context.getContextData().get(contextKey(model));
        context.setSessionNote(required(model, NOTE), value == null ? "" : value.toString());
    }
}
