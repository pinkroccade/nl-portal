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
package nl.nlportal.app

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Maakt het sleutelpaar van de testtenant (`private_key_jwt`, PS256) als het nog niet bestaat. Een privésleutel in Git hoort niet;
 * het bestand staat in de build-map en het tenantbestand van de test verwijst er relatief aan de sleutelmap (`nl-portal.sleutelmap`) naartoe, in de map met de OIN van de testtenant.
 */
class TestSleutelInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        val bestand = Path.of("build/test-sleutels/00000099000000000001/clientsleutel.jwk.json")
        if (Files.exists(bestand)) return
        Files.createDirectories(bestand.parent)
        Files.writeString(
            bestand,
            RSAKeyGenerator(3072)
                .keyID("test-sleutel-1")
                .algorithm(JWSAlgorithm.PS256)
                .generate()
                .toJSONString(),
        )
    }
}