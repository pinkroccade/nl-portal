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

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.saml.SAMLIdentityProviderConfig;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.UserSessionModel;

/** Hulp voor de mappertests: een mappermodel bouwen en de sessienotities lezen die een login zou meekrijgen. */
final class MapperTestSupport {
    private MapperTestSupport() {}

    static IdentityProviderMapperModel mapperModel(String... keysAndValues) {
        Map<String, String> config = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) config.put(keysAndValues[i], keysAndValues[i + 1]);
        IdentityProviderMapperModel model = new IdentityProviderMapperModel();
        model.setName("test");
        model.setConfig(config);
        return model;
    }

    static BrokeredIdentityContext newContext() {
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setEnabled(true);
        return new BrokeredIdentityContext("id", idp);
    }

    /** Een SAML-koppeling zoals bedoeld: handtekeningen gecontroleerd, assertion ondertekend, certificaat vastgepind. */
    static BrokeredIdentityContext samlContext() {
        return samlContext(true, true, "MIIBcertificaat");
    }

    static BrokeredIdentityContext samlContext(boolean validateSignature, boolean wantAssertionsSigned, String certificate) {
        SAMLIdentityProviderConfig idp = new SAMLIdentityProviderConfig();
        idp.setEnabled(true);
        idp.setValidateSignature(validateSignature);
        idp.setWantAssertionsSigned(wantAssertionsSigned);
        if (certificate != null) idp.addSigningCertificate(certificate);
        return new BrokeredIdentityContext("id", idp);
    }

    /** De notities die Keycloak op de gebruikerssessie van deze login zet (setNote) en die de client-mapper leest. */
    static Map<String, String> sessionNotes(BrokeredIdentityContext context) {
        Map<String, String> notes = new HashMap<>();
        UserSessionModel session =
                (UserSessionModel) Proxy.newProxyInstance(
                        MapperTestSupport.class.getClassLoader(),
                        new Class<?>[] {UserSessionModel.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("setNote")) {
                                notes.put((String) args[0], (String) args[1]);
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
        context.addSessionNotesToUserSession(session);
        return notes;
    }
}
