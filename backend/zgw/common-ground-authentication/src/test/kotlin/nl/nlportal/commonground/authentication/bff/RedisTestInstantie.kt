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
package nl.nlportal.commonground.authentication.bff

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration
import org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * De Redis uit de compose-stack (`nl-portal-sessies`) onder Testcontainers: hetzelfde image op digest, dezelfde opstartopdracht
 * (ACL-bestand, geen persistentie, noeviction) en hetzelfde ACL-sjabloon, zodat de test meeverandert met de compose.
 */
class RedisTestInstantie : AutoCloseable {
    val wachtwoord = "test-wachtwoord-" + System.nanoTime()
    private val compose = Files.readString(wortel().resolve("docker-compose/compose.yaml"))
    private val image = Regex("""nl-portal-sessies:.*?image:\s*(\S+)""", RegexOption.DOT_MATCHES_ALL).find(compose)!!.groupValues[1]
    private val opdracht: List<String> =
        jacksonObjectMapper()
            .readValue(
                Regex("""nl-portal-sessies:.*?command:\s*(\[[^\n]*])""", RegexOption.DOT_MATCHES_ALL).find(compose)!!.groupValues[1],
                Array<String>::class.java,
            ).toList()

    val container: GenericContainer<*> =
        GenericContainer(DockerImageName.parse(image))
            .withExposedPorts(6379)
            .withCommand(*opdracht.toTypedArray())
            .withCopyToContainer(Transferable.of(acl().toByteArray(), 420), "/etc/redis/users.acl")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
            .also { it.start() }

    val host: String get() = container.host
    val poort: Int get() = container.getMappedPort(6379)

    /** Een app-instantie: een eigen Spring-context met een eigen Lettuce-verbinding naar dezelfde Redis. */
    fun runner(): ReactiveWebApplicationContextRunner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataRedisAutoConfiguration::class.java,
                    DataRedisReactiveAutoConfiguration::class.java,
                    SessionAutoConfiguration::class.java,
                    SessionDataRedisAutoConfiguration::class.java,
                ),
            ).withBean(ServerProperties::class.java)
            .withUserConfiguration(BffRedisConfiguration::class.java)
            .withPropertyValues(
                "spring.data.redis.host=$host",
                "spring.data.redis.port=$poort",
                "spring.data.redis.username=nlportal",
                "spring.data.redis.password=$wachtwoord",
            )

    override fun close() = container.stop()

    private fun acl(): String = Files.readString(wortel().resolve("docker-compose/templates/redis/users.acl")).replace("{{REDIS_PASSWORD}}", wachtwoord)

    private fun wortel(): Path {
        var map: Path? = Path.of("").toAbsolutePath()
        while (map != null && !Files.exists(map.resolve("docker-compose/compose.yaml"))) map = map.parent
        return map ?: error("docker-compose/ niet gevonden vanaf " + Path.of("").toAbsolutePath())
    }
}