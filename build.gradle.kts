// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// njMule - Next generation Java Mule: a modern eD2K and Kad client, wire-compatible with eMule.
// The modules share their set-up through build-logic (njmule.java-conventions).

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

// Every plugin of the modules is loaded here once, so that the modules share its classes (and build services).
plugins {
    id("njmule.java-conventions") apply false
    alias(libs.plugins.graalvm.native) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.node) apply false
    alias(libs.plugins.license.report) apply false
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.dependency.check)
    alias(libs.plugins.sonarqube)
}

// Every resolved version is locked in gradle.lockfile and every artifact checked against
// gradle/verification-metadata.xml; both are rewritten after a version change (CLAUDE.md).
dependencyLocking {
    lockAllConfigurations()
}

// JSVG for tools/IconGen.java (branding/README.md): ./gradlew iconTools → build/tools/
val iconTools = configurations.resolvable("iconTools")
val iconToolsDeclared = configurations.dependencyScope("iconToolsDeclared")

dependencies {
    iconToolsDeclared(libs.jsvg)
}

iconTools {
    extendsFrom(iconToolsDeclared.get())
}

tasks.register<Sync>("iconTools") {
    description = "Copies the jars of the icon tools into build/tools."
    from(iconTools)
    into(layout.buildDirectory.dir("tools"))
}

// The benchmarks (JMH and its dependencies) are not distributed; the web app is, inside the web interface's jar.
val distributed = subprojects.filter { it.name !in setOf("njmule-bench", "njmule-webapp", "website") }

// Software bill of materials, part of every release: ./gradlew cyclonedxBom → build/reports/cyclonedx/.
// The third-party license report comes from the desktop app, whose runtime holds every distributed
// dependency: ./gradlew :njmule-gui:generateLicenseReport.

// OWASP dependency vulnerability scan, run by hand (needs an NVD API key in NVD_API_KEY):
// ./gradlew dependencyCheckAggregate
dependencyCheck {
    failBuildOnCVSS = 7f
    nvd.apiKey = providers.environmentVariable("NVD_API_KEY").orNull
}

// SonarQube Cloud analysis: ./gradlew build sonar -Dsonar.token=<token>; coverage comes from the JaCoCo
// XML report of each module plus the test kit's aggregate, which counts the end-to-end tests.
sonar {
    properties {
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "njmule")
        property("sonar.projectKey", "njMule_njMule")
        property("sonar.projectName", "njMule")
        // MD5 and SHA-1 are mandated by the eD2K and Kad protocols and file formats (hashes, key
        // derivation, signatures), as in config/spotbugs/exclude.xml; RC4 is the protocol obfuscation
        // key stream, not a security cipher.
        val ignored =
            mapOf(
                "wh1" to ("java:S4790" to "**/org/njmule/core/crypto/**"),
                "wh2" to ("java:S4790" to "**/org/njmule/core/files/hash/**"),
                "wh3" to ("java:S4790" to "**/org/njmule/core/persist/format/**"),
                "wh4" to ("java:S4790" to "**/org/njmule/core/protocol/**"),
                "rc4" to ("java:S5547" to "**/org/njmule/core/crypto/Rc4.java"),
                "rc4pad" to ("java:S5542" to "**/org/njmule/core/crypto/Rc4.java"),
            )
        property("sonar.issue.ignore.multicriteria", ignored.keys.joinToString(","))
        ignored.forEach { (key, rule) ->
            property("sonar.issue.ignore.multicriteria.$key.ruleKey", rule.first)
            property("sonar.issue.ignore.multicriteria.$key.resourceKey", rule.second)
        }
    }
}

val aggregateCoverage =
    project(":njmule-testkit")
        .layout
        .buildDirectory
        .file("reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")
        .get()
        .asFile

distributed.forEach { module ->
    module.sonar {
        properties {
            property(
                "sonar.coverage.jacoco.xmlReportPaths",
                listOf(
                    module.layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile,
                    aggregateCoverage,
                ),
            )
        }
    }
}

project(":njmule-bench").sonar {
    isSkipProject = true
}
