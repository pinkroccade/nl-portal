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
plugins {
    java
    jacoco
}

repositories {
    mavenCentral()
}

val keycloakVersion: String = providers.gradleProperty("keycloakVersion").get()
val keycloakJavaRelease: String = providers.gradleProperty("keycloakJavaRelease").get()

// De Keycloak-jars zitten in het basisimage, dus compileOnly: ze komen niet in de provider-jar.
val keycloakModules =
    listOf(
        "keycloak-core",
        "keycloak-server-spi",
        "keycloak-server-spi-private",
        "keycloak-services",
        "keycloak-saml-core",
        "keycloak-saml-core-public",
    )

dependencies {
    keycloakModules.forEach {
        compileOnly("org.keycloak:$it:$keycloakVersion")
        testImplementation("org.keycloak:$it:$keycloakVersion")
    }
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(keycloakJavaRelease.toInt())
    // De SPI van identity-provider-mappers is bij Keycloak intern gemarkeerd (KC-SERVICES0047); geen annotatieprocessors.
    options.compilerArgs.addAll(listOf("-proc:none", "-Xlint:all", "-Werror"))
}

tasks.jar {
    archiveBaseName.set("nlportal-keycloak-provider")
    archiveVersion.set("")
    manifest {
        attributes("Keycloak-Version" to keycloakVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
    }
}
