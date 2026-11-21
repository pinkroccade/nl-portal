package nl.nlportal.app

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Maakt het sleutelpaar van de testtenant (`private_key_jwt`, PS256) als het nog niet bestaat. Een privésleutel in Git hoort niet;
 * het bestand staat in de build-map en het tenantbestand van de test verwijst er relatief naartoe.
 */
class TestSleutelInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        val bestand = Path.of("build/test-sleutels/clientsleutel.jwk.json")
        if (Files.exists(bestand)) return
        Files.createDirectories(bestand.parent)
        Files.writeString(bestand, RSAKeyGenerator(3072).keyID("test-sleutel-1").algorithm(JWSAlgorithm.PS256).generate().toJSONString())
    }
}
