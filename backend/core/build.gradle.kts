/*
 * Copyright 2015-2023 Den Haag, Ritense, the Netherlands.
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
    kotlin("jvm")
    id("info.solidsoft.pitest")
}

val jar: Jar by tasks
val bootJar: org.springframework.boot.gradle.tasks.bundling.BootJar by tasks

bootJar.enabled = false
jar.enabled = true

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    api(Dependencies.springWebFlux)
    api(Dependencies.springBootWebClient)
    api("org.springframework.security:spring-security-oauth2-jose")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    // Jackson
    api(Dependencies.jacksonKotlinModule)
    implementation("io.micrometer:context-propagation")
    implementation(Dependencies.kotlinCoroutinesReactor)

    api(Dependencies.commonsCodec)

    // Apache Commons
    api(Dependencies.commonsIo)
    api(Dependencies.apacheCommons)

    // Feature flags: OpenFeature API (spec-compliant) with flagd file provider as server-less default (#143)
    api(Dependencies.openFeatureSdk)
    // grpc-netty-shaded bundles its own netty (4.2.17) that the netty-bom pin does not reach (CVE-2026-44891 e.a., #323).
    // Only flagd's ChannelBuilder (RPC/in-process resolvers) uses it; the FILE resolver above never does, so it is excluded.
    implementation(Dependencies.openFeatureFlagd) {
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
    }

    // Logging for Kotlin
    api(Dependencies.kotlinLogging)
    testImplementation(TestDependencies.springBootTest)
    testImplementation(TestDependencies.springBootTestWebClient)
    testImplementation(TestDependencies.kotlinCoroutines)
    testImplementation(TestDependencies.mockitoKotlin)
}

// Mutation testing (#95, ADR 0011). Bewust een eigen taak, niet onderdeel van `build`/`check`.
pitest {
    pitestVersion.set("1.30.1")
    junit5PluginVersion.set("1.2.3")
    // CI (PR-run) beperkt de run tot gewijzigde klassen via -PpitestTargetClasses=a.B,c.D en eist dan een hogere drempel.
    targetClasses.set(
        providers
            .gradleProperty("pitestTargetClasses")
            .map { it.split(",").toSet() }
            .orElse(setOf("nl.nlportal.core.*")),
    )
    targetTests.set(setOf("nl.nlportal.core.*"))
    // Kotlin genereert null-checks en logging-aanroepen waarvan mutaties geen gedrag veranderen (equivalente mutanten).
    avoidCallsTo.set(setOf("kotlin.jvm.internal", "mu", "io.github.oshai.kotlinlogging", "org.slf4j"))
    threads.set(4)
    outputFormats.set(setOf("HTML", "XML"))
    timestampedReports.set(false)
    // Ratchet: vloer = gemeten baseline (21%, 15/70 gedood). Alleen verhogen, nooit verlagen (ADR 0011).
    mutationThreshold.set(providers.gradleProperty("pitestThreshold").map { it.toInt() }.orElse(20))
    failWhenNoMutations.set(false)
}

// Terminal-samenvatting van overlevende mutanten per bestand en regel, naast het HTML-rapport.
tasks.named("pitest") {
    doLast {
        val report =
            layout.buildDirectory
                .file("reports/pitest/mutations.xml")
                .get()
                .asFile
        if (!report.exists()) return@doLast
        val mutations =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(report)
                .getElementsByTagName("mutation")
        val survivors =
            (0 until mutations.length)
                .map { mutations.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("status") == "SURVIVED" || it.getAttribute("status") == "NO_COVERAGE" }
                .map {
                    fun text(tag: String) = it.getElementsByTagName(tag).item(0).textContent
                    Triple(text("sourceFile"), text("lineNumber").toInt(), "${it.getAttribute("status")}: ${text("description")}")
                }.sortedWith(compareBy({ it.first }, { it.second }))
        println("Overlevende mutanten: ${survivors.size} (details: build/reports/pitest/index.html)")
        survivors.forEach { (file, line, what) -> println("  $file:$line  $what") }
    }
}
// Tenantbestand (epic #499): het JSON-schema wordt uit de Kotlin-typen gegenereerd en gecommit; TenantSchemaTest
// faalt als het bestand verouderd is. `mise run backend:tenant-schema` werkt het bij.
tasks.register<JavaExec>("generateTenantSchema") {
    group = "tenancy"
    description = "Genereert schema/tenant.schema.json uit de typen van het tenantbestand"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nl.nlportal.core.tenancy.TenantSchemaGenerator")
    args(
        layout.projectDirectory
            .file("schema/tenant.schema.json")
            .asFile.absolutePath,
    )
}

// De tenantvalidator als releaseartefact: een zelfstandige jar met alleen wat de validator nodig heeft (geen Spring).
tasks.register<Jar>("tenantValidatorJar") {
    group = "tenancy"
    description = "Bouwt build/libs/nl-portal-tenant-validator.jar, de CLI voor CI van tenantbestanden"
    archiveFileName.set("nl-portal-tenant-validator.jar")
    manifest { attributes["Main-Class"] = "nl.nlportal.core.tenancy.TenantValidatorCli" }
    dependsOn(tasks.named("classes"))
    from(sourceSets["main"].output)
    val nodig = setOf("org.jetbrains.kotlin", "org.jetbrains", "tools.jackson.core", "tools.jackson.module", "com.fasterxml.jackson.core", "org.yaml")
    from({
        configurations.runtimeClasspath
            .get()
            .resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group in nodig }
            .map { zipTree(it.file) }
    })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/spring/**", "META-INF/MANIFEST.MF", "module-info.class", "META-INF/versions/**/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}