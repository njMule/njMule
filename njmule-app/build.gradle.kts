// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    id("njmule.java-conventions")
    application
    alias(libs.plugins.graalvm.native)
}

description = "njMule App"

dependencies {
    api(project(":njmule-core"))
    implementation(project(":njmule-webui"))
    implementation(libs.guava)
    implementation(libs.picocli)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logback.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

// Development runs from the module path: ./gradlew :njmule-app:run --args="--version"
application {
    mainModule = "org.njmule.app"
    mainClass = "org.njmule.app.Main"
    applicationName = "njmule"
}

tasks.jar {
    manifest.attributes(mapOf("Main-Class" to application.mainClass))
}

// The launcher scripts and libraries are kept for installDist; no archives in every build.
tasks.distZip {
    enabled = false
}

tasks.distTar {
    enabled = false
}

// Native executable with GraalVM (GRAALVM_HOME): ./gradlew :njmule-app:nativeCompile
// → build/native/nativeCompile/njmule[.exe]. On Windows it links its icon and version information (native/windows).
val windows = System.getProperty("os.name").lowercase().startsWith("windows")
val nativeResourceSources =
    tasks.register<Sync>("nativeResourceSources") {
        val version = project.version.toString()
        inputs.property("version", version)
        from("native/windows") {
            include("njmule.rc")
            filter { line -> line.replace("\${project.version}", version) }
        }
        from(layout.settingsDirectory.file("branding/generated/njmule.ico"))
        into(layout.buildDirectory.dir("native-res"))
    }
val nativeResources =
    tasks.register<Exec>("nativeResources") {
        dependsOn(nativeResourceSources)
        val dir = layout.buildDirectory.dir("native-res")
        workingDir(dir)
        val rc = layout.settingsDirectory.file("packaging/windows/rc.cmd").asFile.path
        commandLine("cmd", "/c", rc, "njmule.rc", "njmule.res", project.version)
        outputs.file(dir.map { it.file("njmule.res") })
    }
if (windows) {
    tasks.named("nativeCompile") { dependsOn(nativeResources) }
}

graalvmNative {
    toolchainDetection = false
    testSupport = false
    binaries.named("main") {
        imageName = "njmule"
        // java-library would make it a shared library.
        sharedLibrary = false
        mainClass = application.mainClass
        buildArgs.add("-H:+ReportExceptionStackTraces")
        buildArgs.add(providers.gradleProperty("native.optimization"))
        // -Pnative.static=musl: no libc of the system needed (Synology DSM's is too old), musl toolchain on PATH.
        if (providers.gradleProperty("native.static").orNull == "musl") {
            buildArgs.addAll("--static", "--libc=musl")
        }
        if (windows) {
            buildArgs.add(
                layout.buildDirectory.file("native-res/njmule.res").map {
                    "-H:NativeLinkerOption=" + it.asFile.path
                }
            )
        }
    }
}
