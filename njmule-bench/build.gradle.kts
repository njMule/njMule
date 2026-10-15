// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// A classpath (non-modular) build, so the benchmarks can reach the internal core packages. Not distributed.
plugins {
    id("njmule.java-conventions")
    alias(libs.plugins.shadow)
}

description = "njMule Benchmarks"

dependencies {
    implementation(project(":njmule-core"))
    implementation(libs.bouncycastle.prov)
    implementation(libs.guava)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
}

tasks.named("spotbugsMain") {
    enabled = false
}

tasks.named("cyclonedxDirectBom") {
    enabled = false
}

// build/libs/benchmarks.jar: java -jar njmule-bench/build/libs/benchmarks.jar [JMH options]
tasks.shadowJar {
    archiveFileName = "benchmarks.jar"
    manifest.attributes(mapOf("Main-Class" to "org.openjdk.jmh.Main"))
    mergeServiceFiles()
    exclude(
        "module-info.class",
        "META-INF/versions/*/module-info.class",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        // The jar only runs locally and is never distributed: the licence files are left out, not merged.
        "META-INF/LICENSE",
        "META-INF/LICENSE.txt",
        "META-INF/NOTICE",
    )
}
