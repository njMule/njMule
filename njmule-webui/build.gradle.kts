// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("njmule.java-conventions")
}

description = "njMule Web UI"

// The browser app, built by njmule-ui.
val webAppDeclared = configurations.dependencyScope("webAppDeclared")
val webApp = configurations.resolvable("webApp") {
    extendsFrom(webAppDeclared.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-web-app")) }
}

dependencies {
    webAppDeclared(project(":njmule-ui"))
    api(project(":njmule-core"))
    implementation(libs.slf4j.api)
    implementation(libs.guava)
    implementation(libs.jackson.core)
    implementation(platform(libs.jetty.bom))
    implementation(libs.jetty.client)
    implementation(libs.jetty.server)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

// The browser icons come straight from the branding (no copy in the sources).
tasks.processResources {
    from(webApp) {
        into("org/njmule/webui/app")
    }
    from(layout.settingsDirectory.file("branding/njmule-icon.svg")) {
        into("org/njmule/webui")
        rename { "favicon.svg" }
    }
    from(layout.settingsDirectory.file("branding/generated/njmule-32.png")) {
        into("org/njmule/webui")
        rename { "favicon.png" }
    }
}
