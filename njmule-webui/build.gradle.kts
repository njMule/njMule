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

// How to start the web interface on example data (WebCaptureDemo) for the documentation's pictures, which
// website/scripts/capture-web.mjs takes: the Java launcher and an argument file with the class path.
// → build/web-capture/launcher.json
@CacheableTask
abstract class WebCaptureLauncher : DefaultTask() {
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = output.get().asFile
        dir.mkdirs()
        // Forward slashes, quoted: an argument file reads backslashes as escapes.
        val path = classpath.files.joinToString(File.pathSeparator) { it.invariantSeparatorsPath }
        val args = dir.resolve("java.args")
        args.writeText(
            listOf(
                    "-cp",
                    "\"$path\"",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.awt.headless=true",
                    "-Dnjmule.upnp.disabled=true",
                    "-Dnjmule.defaultlists.disabled=true",
                    "-Dnjmule.update.disabled=true",
                    "org.njmule.webui.WebCaptureDemo",
                )
                .joinToString("\n", postfix = "\n")
        )
        val executable = launcher.get().executablePath.asFile.invariantSeparatorsPath
        dir.resolve("launcher.json")
            .writeText("{\"java\": \"$executable\", \"args\": \"${args.invariantSeparatorsPath}\"}\n")
    }
}

val webCaptureArgs =
    tasks.register<WebCaptureLauncher>("webCaptureArgs") {
        description = "Writes how to start the web interface on example data, for the documentation."
        group = "documentation"
        classpath.from(sourceSets.test.get().runtimeClasspath)
        launcher = javaToolchains.launcherFor(java.toolchain)
        output = layout.buildDirectory.dir("web-capture")
    }

// For the documentation website (website/build.gradle.kts).
val webCapture =
    configurations.consumable("webCapture") {
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-web-capture")) }
    }
artifacts {
    add(webCapture.name, webCaptureArgs.flatMap { it.output }) { builtBy(webCaptureArgs) }
}
