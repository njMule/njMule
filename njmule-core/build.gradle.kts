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
    implementation(libs.guava)
    // HTTP client: web lists (with SOCKS proxies), UPnP.
    implementation(platform(libs.jetty.bom))
    implementation(libs.jetty.client)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.jqwik)
    // In-memory file systems for simulations whose many nodes would otherwise wait on the disk.
    testImplementation(libs.jimfs)
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
