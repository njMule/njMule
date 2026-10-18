// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("njmule.java-conventions")
}

description = "njMule Core"

dependencies {
    // Compile-time annotations for Error Prone (@CanIgnoreReturnValue).
    compileOnly(libs.errorprone.annotations)
    implementation(libs.slf4j.api)
    implementation(libs.bouncycastle.prov)
    // QUIC between firewalled clients, through their routers.
    implementation(libs.kwik)
    implementation(libs.guava)
    // Semantic Versioning order of the versions offered as updates.
    implementation(libs.semver4j)
    // HTTP client: web lists (with SOCKS proxies), UPnP.
    implementation(platform(libs.jetty.bom))
    implementation(libs.jetty.client)
    // The remote-control API: a handler that front ends serve on their Jetty server, routed and checked by
    // its OpenAPI document (YAML, JSON Schema 2020-12), with JSON answers.
    api(platform(libs.jetty.bom))
    api(libs.jetty.server)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.json.schema.validator)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jqwik)
    // In-memory file systems for simulations whose many nodes would otherwise wait on the disk.
    testImplementation(libs.jimfs)
    // Checks that the API's document is a valid OpenAPI 3.2 description.
    testImplementation(libs.openapi.parser)
    testImplementation(libs.openapi.parser.jackson)
}

tasks.compileJava {
    // The qualified export to org.njmule.testkit targets a module built later: "module not found" is
    // expected here.
    options.compilerArgs.add("-Xlint:-module")
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    from("src/main/resources-filtered") {
        filter { line -> line.replace("\${project.version}", version) }
    }
    // LICENSE and NOTICE also next to NjMuleInfo, where the launcher reads them (META-INF names are
    // ambiguous on the class path).
    from(files(layout.settingsDirectory.file("LICENSE"), layout.settingsDirectory.file("NOTICE"))) {
        into("org/njmule/core/api")
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = providers.gradleProperty("jacoco.minimum.line.coverage").getOrElse("0.70").toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
