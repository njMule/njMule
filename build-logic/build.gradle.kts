// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.spotless)
    implementation(libs.gradle.errorprone)
    implementation(libs.gradle.spotbugs)
}
