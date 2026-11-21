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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.representations.JsonWebToken;

class OidcClaimToSessionNoteMapperTest {
    private final OidcClaimToSessionNoteMapper mapper = new OidcClaimToSessionNoteMapper();
    private final IdentityProviderMapperModel model = mapperModel("claim.name", "acr", "note", "acr");

    private static BrokeredIdentityContext loginMet(String claim, Object waarde) {
        JsonWebToken token = new JsonWebToken();
        if (claim != null) token.setOtherClaims(claim, waarde);
        BrokeredIdentityContext context = newContext();
        context.getContextData().put(OIDCIdentityProvider.VALIDATED_ID_TOKEN, token);
        return context;
    }

    private void verwerk(BrokeredIdentityContext context) {
        mapper.preprocessFederatedIdentity(null, null, model, context);
    }

    @Test
    void zetDeClaimVanHetIdTokenVanDeWalletInDeSessienotitie() {
        BrokeredIdentityContext context = loginMet("acr", "substantial");

        verwerk(context);

        assertThat(sessionNotes(context)).containsEntry("acr", "substantial");
    }

    @Test
    void gebruiktDeNotitienaamUitDeConfiguratieEnNietDeClaimnaam() {
        BrokeredIdentityContext context = loginMet("acr", "high");

        mapper.preprocessFederatedIdentity(null, null, mapperModel("claim.name", "acr", "note", "niveau"), context);

        assertThat(sessionNotes(context)).containsOnlyKeys("niveau").containsEntry("niveau", "high");
    }

    @Test
    void zetEenLegeNotitieAlsDeClaimOntbreekt() {
        BrokeredIdentityContext context = loginMet(null, null);

        verwerk(context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
    }

    @Test
    void laatDeLoginFalenBijEenClaimDieGeenTekstwaardeIs() {
        BrokeredIdentityContext lijst = loginMet("acr", List.of("a", "b"));
        BrokeredIdentityContext getal = loginMet("acr", 3);

        assertThatThrownBy(() -> verwerk(lijst))
                .isInstanceOf(IdentityBrokerException.class)
                .hasMessageContaining("acr");
        assertThatThrownBy(() -> verwerk(getal)).isInstanceOf(IdentityBrokerException.class);
    }

    @Test
    void eenLatereLoginZonderDeClaimLaatDeWaardeVanEenEerdereLoginNietStaan() {
        verwerk(loginMet("acr", "high"));
        BrokeredIdentityContext tweede = loginMet(null, null);

        verwerk(tweede);

        assertThat(sessionNotes(tweede)).containsEntry("acr", "");
    }

    @Test
    void eersteLoginHoudtDeWaardeNaDeSerialisatieVanDeContextVast() {
        BrokeredIdentityContext context = loginMet("acr", "high");
        verwerk(context);
        context.getContextData().remove(OIDCIdentityProvider.VALIDATED_ID_TOKEN);

        mapper.importNewUser(null, null, null, model, context);
        assertThat(sessionNotes(context)).containsEntry("acr", "high");
        mapper.updateBrokeredUser(null, null, null, model, context);
        assertThat(sessionNotes(context)).containsEntry("acr", "high");
    }

    @Test
    void zetZonderTokenOpEenLaterHaakjeEenLegeNotitie() {
        BrokeredIdentityContext context = newContext();

        mapper.importNewUser(null, null, null, model, context);

        assertThat(sessionNotes(context)).containsEntry("acr", "");
    }

    @Test
    void isAlleenBedoeldVoorOidcKoppelingenEnDraaitInElkeSynchronisatiemodus() {
        assertThat(mapper.getCompatibleProviders()).containsExactly("oidc");
        assertThat(mapper.supportsSyncMode(IdentityProviderSyncMode.LEGACY)).isTrue();
        assertThat(mapper.getId()).isEqualTo("nlportal-oidc-claim-note-mapper");
        assertThat(mapper.getConfigProperties()).extracting("name").containsExactly("claim.name", "note");
        assertThat(mapper.getDisplayCategory()).isNotBlank();
        assertThat(mapper.getDisplayType()).isNotBlank();
        assertThat(mapper.getHelpText()).isNotBlank();
    }
}
