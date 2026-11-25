// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// njMule - New Jack Mule: a modern eD2K and Kad client, wire-compatible with eMule.
// The engine is built with Cargo (njmule-engine/); Gradle drives the Vue interface (njmule-ui),
// the Tauri desktop shell (njmule-desktop), the documentation website (website), the icon tools
// and the Windows service host.

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.node) apply false
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

// ./gradlew nativeService → build/windows/njmule-service.exe (packaging/windows/service.c), the host of the
// Windows service that runs njmule.exe headless (njmule --service=install), compiled with the Visual C++ tools.
val serviceResourceSources =
    tasks.register<Sync>("nativeServiceResourceSources") {
        val version = project.version.toString()
        inputs.property("version", version)
        from("packaging/windows") {
            include("njmule-service.rc")
            filter { line -> line.replace("\${project.version}", version) }
        }
        from("branding/generated/njmule.ico")
        into(layout.buildDirectory.dir("windows-res"))
    }

val serviceResources =
    tasks.register<Exec>("nativeServiceResources") {
        dependsOn(serviceResourceSources)
        val dir = layout.buildDirectory.dir("windows-res")
        workingDir(dir)
        val rc = file("packaging/windows/rc.cmd").path
        commandLine("cmd", "/c", rc, "njmule-service.rc", "njmule-service.res", project.version)
        inputs.file(dir.map { it.file("njmule-service.rc") })
        outputs.file(dir.map { it.file("njmule-service.res") })
    }

tasks.register<Exec>("nativeService") {
    group = "build"
    description = "Builds njmule-service.exe, the Windows service of njMule."
    dependsOn(serviceResources)
    val code = file("packaging/windows/service.c")
    val dir = layout.buildDirectory.dir("windows-res")
    val exe = layout.buildDirectory.file("windows/njmule-service.exe")
    val cl = file("packaging/windows/cl.cmd")
    inputs.files(code, cl)
    inputs.file(dir.map { it.file("njmule-service.res") })
    outputs.file(exe)
    workingDir(dir)
    commandLine("cmd", "/c", cl.path, exe.get().asFile.path, code.path, "njmule-service.res")
    doFirst { exe.get().asFile.parentFile.mkdirs() }
}
