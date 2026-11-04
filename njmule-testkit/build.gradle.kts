// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("njmule.java-conventions")
    `jacoco-report-aggregation`
}

description = "njMule Test Kit"

dependencies {
    api(project(":njmule-core"))
    implementation(libs.slf4j.api)
    // Runtime rather than test, so the coverage report aggregates the launcher and the web interface too.
    runtimeOnly(project(":njmule-app"))

    testImplementation(project(":njmule-app"))
    testImplementation(project(":njmule-http"))
    testImplementation(libs.jackson.databind)
    testImplementation(testFixtures(project(":njmule-core")))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

tasks.compileJava {
    // The test kit implements core types exported only to it.
    options.compilerArgs.add("-Xlint:-exports")
}

// The end-to-end tests here exercise the core and the launcher: one coverage report for all of them,
// which SonarQube Cloud reads too.
// The flows against an njmule executable (-Pnjmule.engine=<path>) run again whenever it changes.
providers.gradleProperty("njmule.engine").orNull?.let { engine ->
    tasks.test {
        inputs.file(engine).withPathSensitivity(PathSensitivity.NONE)
    }
}

tasks.check {
    dependsOn(tasks.named("testCodeCoverageReport"))
}
