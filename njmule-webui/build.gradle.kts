// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("njmule.java-conventions")
}

description = "njMule Web UI"

dependencies {
    api(project(":njmule-core"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.core)
    implementation(platform(libs.jetty.bom))
    implementation(libs.jetty.client)
    implementation(libs.jetty.server)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
