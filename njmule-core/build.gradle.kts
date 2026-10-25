// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("njmule.java-conventions")
}

description = "njMule Core"

// The build's own converter of the API's document from YAML to JSON; not shipped, so SpotBugs leaves it out as it
// does the tests.
val openapi = sourceSets.create("openapi")
tasks.named("spotbugsOpenapi") {
    enabled = false
}

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
    // The tar.xz packages of new versions, unpacked before they take the place of this one.
    implementation(libs.commons.compress)
    implementation(libs.xz)
    // HTTP client: web lists (with SOCKS proxies), UPnP.
    implementation(platform(libs.jetty.bom))
    implementation(libs.jetty.client)
    // The remote-control API: a handler that front ends serve on their Jetty server, routed and checked by
    // its OpenAPI document (JSON Schema 2020-12), with JSON answers. The document is written in YAML and read as
    // the JSON the build makes of it: the validator's YAML support stays out of the executables.
    api(platform(libs.jetty.bom))
    api(libs.jetty.server)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.json.schema.validator) {
        exclude(group = "tools.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    "openapiImplementation"(libs.jackson.databind)
    "openapiImplementation"(libs.jackson.dataformat.yaml)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jqwik)
    // In-memory file systems for simulations whose many nodes would otherwise wait on the disk.
    testImplementation(libs.jimfs)
    // Checks that the API's document is a valid OpenAPI 3.2 description.
    testImplementation(libs.openapi.parser)
    testImplementation(libs.openapi.parser.jackson)
    testImplementation(libs.jackson.dataformat.yaml)
}

tasks.compileJava {
    // The qualified export to org.njmule.testkit targets a module built later: "module not found" is
    // expected here.
    options.compilerArgs.add("-Xlint:-module")
}

val openApiJson = tasks.register<JavaExec>("openApiJson") {
    description = "Converts the API's OpenAPI document to the JSON the engine reads."
    val source = file("src/main/resources/org/njmule/core/remote/openapi.yaml")
    val target = layout.buildDirectory.file("openapi/openapi.json")
    classpath = openapi.runtimeClasspath
    mainClass = "org.njmule.openapi.OpenApiJson"
    inputs.file(source).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(target)
    outputs.cacheIf { true }
    argumentProviders.add(CommandLineArgumentProvider { listOf(source.path, target.get().asFile.path) })
}

tasks.processResources {
    from(openApiJson) {
        into("org/njmule/core/remote")
    }
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
