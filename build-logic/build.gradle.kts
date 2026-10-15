// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    `kotlin-dsl`
}

// Every resolved version is locked in gradle.lockfile and every artifact checked against
// gradle/verification-metadata.xml; both are rewritten after a version change (CLAUDE.md).
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.gradle.spotless)
    implementation(libs.gradle.errorprone)
    implementation(libs.gradle.spotbugs)
}
