// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    id("com.github.node-gradle.node")
}

description = "njMule's desktop shell: the window around the interface, which starts the engine (Tauri)"

// The Tauri command line comes from npm as njmule-ui's packages do; Rust and Cargo from rustup (CLAUDE.md).
node {
    version = libs.versions.nodejs.get()
    download = true
    distBaseUrl = null
    npmInstallCommand = "ci"
}

// The interface the shell embeds, uncompressed (tauri.conf.json reads it from njmule-ui/build/site).
val uiDeclared = configurations.dependencyScope("uiDeclared")
val ui = configurations.resolvable("ui") {
    extendsFrom(uiDeclared.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-desktop-app")) }
}

dependencies {
    uiDeclared(project(":njmule-ui"))
}

val cargoHome = providers.environmentVariable("CARGO_HOME").orElse(System.getProperty("user.home") + "/.cargo")
val program = if (System.getProperty("os.name").startsWith("Windows")) "njmule-gui.exe" else "njmule-gui"

// Not part of build: it needs the Rust toolchain, which only the desktop packages need.
tasks.register<NpmTask>("shell") {
    description = "Builds the desktop shell with Tauri (release, no installer)."
    dependsOn(tasks.npmInstall)
    inputs.files(ui).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src-tauri/src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files("src-tauri/Cargo.toml", "src-tauri/Cargo.lock", "src-tauri/tauri.conf.json", "src-tauri/build.rs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src-tauri/capabilities").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file("src-tauri/target/release/$program")
    npmCommand = listOf("run", "build")
    environment.put(
        "PATH",
        cargoHome.map { it + "/bin" + File.pathSeparator + System.getenv("PATH") },
    )
}
