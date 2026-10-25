// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import java.util.Locale

plugins {
    id("njmule.java-conventions")
    application
    alias(libs.plugins.license.report)
}

description = "njMule GUI"

val javafxPlatformsDeclared = configurations.dependencyScope("javafxPlatformsDeclared")

configurations.resolvable("javafxPlatforms") {
    extendsFrom(javafxPlatformsDeclared.get())
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
}

dependencies {
    components {
        val classifier = JavaFxPlatformRule.hostClassifier()
        listOf("javafx-base", "javafx-graphics", "javafx-controls").forEach {
            withModule<JavaFxPlatformRule>("org.openjfx:$it") { params(classifier) }
        }
    }

    // The OpenJFX jars of every platform, resolved only so that their checksums are written to
    // gradle/verification-metadata.xml: a build resolves the jars of its own platform alone.
    for (platform in listOf("win", "linux", "linux-aarch64", "mac", "mac-aarch64")) {
        for (module in listOf("javafx-base", "javafx-graphics", "javafx-controls")) {
            javafxPlatformsDeclared("org.openjfx:$module:${libs.versions.javafx.get()}:$platform")
        }
    }

    implementation(project(":njmule-app"))
    implementation(project(":njmule-webui"))
    implementation(libs.slf4j.api)
    implementation(libs.javafx.controls)
    implementation(libs.atlantafx.base)
    implementation(libs.richtextfx)

    testImplementation(project(":njmule-testkit"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

// Third-party license report, part of every release: the desktop app's runtime holds every distributed
// dependency. ./gradlew :njmule-gui:generateLicenseReport → build/reports/licenses/THIRD-PARTY.txt
licenseReport {
    outputDir = rootProject.layout.buildDirectory.dir("reports/licenses").get().asFile.path
    configurations = arrayOf("runtimeClasspath")
    excludeOwnGroup = true
    renderers = arrayOf<ReportRenderer>(TextReportRenderer("THIRD-PARTY.txt"))
}

tasks.generateLicenseReport {
    notCompatibleWithConfigurationCache("the license report plugin reads the project while it runs")
}

// Window icons come straight from the branding renders (no copy in the sources).
tasks.processResources {
    from(layout.settingsDirectory.dir("branding/generated")) {
        include(listOf(16, 24, 32, 48, 64, 128, 256, 512).map { "njmule-$it.png" })
        into("org/njmule/gui/icons")
    }
}

// Development launcher: ./gradlew :njmule-gui:run
application {
    mainModule = "org.njmule.gui"
    mainClass = "org.njmule.gui.GuiMain"
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics")
}

// The launcher scripts and libraries are kept for installDist; no archives in every build.
tasks.distZip {
    enabled = false
}

tasks.distTar {
    enabled = false
}

// GUI tests run on JavaFX's headless platform with software rendering: no display is needed.
// Snapshots of the screens are written with java.desktop's ImageIO; each test JVM unpacks JavaFX's
// native libraries under build/javafx-cache (FxTesting). The capture tour (CaptureTour) pictures the
// interface only when given a folder: -Pnjmule.capture=build/gui-capture, optionally
// -Pnjmule.capture.sets=it-light-full,...
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("glass.platform", "Headless")
    systemProperty("prism.order", "sw")
    systemProperty("njmule.snapshots", layout.buildDirectory.dir("gui-snapshots").get().asFile.path)
    systemProperty("njmule.javafx.cache", layout.buildDirectory.dir("javafx-cache").get().asFile.path)
}

// Native executable of the desktop app with GraalVM (GRAALVM_HOME), from the module path:
// ./gradlew :njmule-gui:nativeGui → build/native/njmule-gui plus the JDK libraries it loads; on Windows
// build/native/njmule.exe, a console program, which build/launcher/njmule-gui.exe (nativeLauncher) starts
// for the desktop. JavaFX loads its own native libraries; the reachability metadata comes from the GUI tour
// (nativeTour) and a few hand-written JNI entries.
val hostOs = System.getProperty("os.name").lowercase()
val nativeOs =
    when {
        hostOs.startsWith("windows") -> "windows"
        hostOs.startsWith("mac") -> "macos"
        else -> "linux"
    }
// x86-64 executables run on CPUs without AVX2 too (native-image's default x86-64-v3 needs it); ARM keeps its default.
val nativeMarch = if (System.getProperty("os.arch") in setOf("amd64", "x86_64")) listOf("-march=x86-64-v2") else emptyList()
val graalvmBin = providers.environmentVariable("GRAALVM_HOME").map { "$it/bin/" }.orElse("")
val cmdSuffix = if (nativeOs == "windows") ".cmd" else ""
val guiModule = "org.njmule.gui/org.njmule.gui.GuiMain"
// The jar, not the class folders: on Windows native-image misses the metadata of an exploded module.
val nativeModulePath = files(tasks.jar, configurations.runtimeClasspath)

// Windows links the executables' icon and version information: njmule.exe those of the command line
// (njmule-app/native/windows), the launcher njmule-gui.exe its own (native/windows).
val nativeResourceSources =
    tasks.register<Sync>("nativeResourceSources") {
        val version = project.version.toString()
        inputs.property("version", version)
        from("native/windows") {
            include("njmule-gui.rc", "njmule-service.rc")
            filter { line -> line.replace("\${project.version}", version) }
        }
        from(layout.settingsDirectory.dir("njmule-app/native/windows")) {
            include("njmule.rc")
            filter { line -> line.replace("\${project.version}", version) }
        }
        from(layout.settingsDirectory.file("branding/generated/njmule.ico"))
        into(layout.buildDirectory.dir("native-res"))
    }

fun registerResources(name: String, program: String) =
    tasks.register<Exec>(name) {
        dependsOn(nativeResourceSources)
        val dir = layout.buildDirectory.dir("native-res")
        workingDir(dir)
        val rc = layout.settingsDirectory.file("packaging/windows/rc.cmd").asFile.path
        commandLine("cmd", "/c", rc, "$program.rc", "$program.res", project.version)
        inputs.file(dir.map { it.file("$program.rc") })
        outputs.file(dir.map { it.file("$program.res") })
    }

val nativeResources = registerResources("nativeResources", "njmule")
val launcherResources = registerResources("nativeLauncherResources", "njmule-gui")
val serviceResources = registerResources("nativeServiceResources", "njmule-service")

// ./gradlew :njmule-gui:nativeLauncher → build/launcher/njmule-gui.exe (native/windows/launcher.c), the
// small GUI program that starts njmule.exe without a console for the desktop, as javaw.exe stands for java.exe.
fun registerProgram(name: String, what: String, source: String, program: String, resources: TaskProvider<Exec>) =
    tasks.register<Exec>(name) {
        group = "build"
        description = "Builds $program.exe, $what."
        dependsOn(resources)
        val code = file("native/windows/$source")
        val dir = layout.buildDirectory.dir("native-res")
        val exe = layout.buildDirectory.file("launcher/$program.exe")
        val cl = layout.settingsDirectory.file("packaging/windows/cl.cmd").asFile
        inputs.files(code, cl)
        inputs.file(dir.map { it.file("$program.res") })
        outputs.file(exe)
        workingDir(dir)
        commandLine("cmd", "/c", cl.path, exe.get().asFile.path, code.path, "$program.res")
        doFirst { exe.get().asFile.parentFile.mkdirs() }
    }

registerProgram("nativeLauncher", "the launcher of the Windows desktop app", "launcher.c", "njmule-gui", launcherResources)

// ./gradlew :njmule-gui:nativeService → build/launcher/njmule-service.exe (native/windows/service.c), the Windows
// service that runs njmule.exe headless (njmule --service=install).
registerProgram("nativeService", "the Windows service of njMule", "service.c", "njmule-service", serviceResources)

tasks.register<Exec>("nativeGui") {
    group = "build"
    description = "Builds the native executable of the desktop app."
    val modulePath = nativeModulePath
    val module = guiModule
    val windows = nativeOs == "windows"
    if (windows) dependsOn(nativeResources)
    inputs.files(modulePath)
    val out = layout.buildDirectory.dir("native")
    outputs.dir(out)
    workingDir(out)
    executable = graalvmBin.get() + "native-image" + cmdSuffix
    val argsFile = file("native/$nativeOs.args")
    inputs.file(argsFile)
    val resFile = layout.buildDirectory.file("native-res/njmule.res")
    val generatedArgs = layout.buildDirectory.file("native-args/njmule-gui.args")
    val optimization = providers.gradleProperty("native.optimization")
    inputs.property("optimization", optimization)
    val march = nativeMarch
    inputs.property("march", march)
    val i18n = file("src/main/resources/org/njmule/gui/i18n")
    argumentProviders.add(
        CommandLineArgumentProvider {
            // Dates and numbers in the formats of every region of the interface's languages: all the JDK's
            // locales would add 37 MiB, the languages alone would show en-GB dates the American way.
            val nj = Regex("^nj_([a-z]+)")
            val languages = i18n.list().orEmpty().mapNotNull { nj.find(it)?.groupValues?.get(1) }.toSet() + "en"
            val locales =
                Locale.getAvailableLocales()
                    .filter { it.language in languages && it.variant.isEmpty() && !it.hasExtensions() }
                    .map { it.toLanguageTag() }
                    .sorted()
            val name = if (windows) "njmule" else "njmule-gui"
            // The module path and the locales pass the 8191 characters of a cmd.exe command line (native-image is
            // a .cmd on Windows): native-image reads them from a file of their own.
            val long = generatedArgs.get().asFile
            long.parentFile.mkdirs()
            long.writeText(
                listOf("--module-path", modulePath.asPath.replace('\\', '/'))
                    .plus("-H:IncludeLocales=" + locales.joinToString(","))
                    .joinToString("\n", postfix = "\n")
            )
            // Angus Mail's and Angus Activation's features for native images implement GraalVM's Feature, which
            // their modules do not read.
            listOf("@${long.path}", "--module", module, "-o", name, "@$argsFile") +
                listOf("--add-reads", "org.eclipse.angus.mail=org.graalvm.nativeimage") +
                listOf("--add-reads", "org.eclipse.angus.activation=org.graalvm.nativeimage") +
                (if (windows) listOf("-H:NativeLinkerOption=${resFile.get().asFile}") else emptyList()) +
                listOf(optimization.get()) +
                march
        }
    )
    doFirst { out.get().asFile.mkdirs() }
}

// Regenerates the GUI's reachability metadata: the tour (GuiTour) runs with the platform's accelerated
// pipeline and with software rendering under GraalVM's tracing agent, merging into src/main/resources.
// Needs a display (xvfb-run on Linux CI): ./gradlew :njmule-gui:nativeTour
val tourMetadata = file("src/main/resources/META-INF/native-image/org.njmule/njmule-gui/tour")
val tourConfig =
    tasks.register<Sync>("nativeTourConfig") {
        from("native/tour")
        into(layout.buildDirectory.dir("tour"))
    }

fun registerTour(name: String, prism: List<String>) =
    tasks.register<Exec>(name) {
        dependsOn(tourConfig)
        val modulePath = nativeModulePath
        val module = guiModule
        val metadata = tourMetadata
        inputs.files(modulePath)
        executable = graalvmBin.get() + "java"
        val configDir = layout.buildDirectory.dir("tour")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf("-agentlib:native-image-agent=config-merge-dir=$metadata", "-Dnjmule.gui.tour=true") +
                    prism +
                    listOf("--enable-native-access=javafx.graphics", "--module-path", modulePath.asPath) +
                    listOf("--module", module, "--config-dir", configDir.get().asFile.path)
            }
        )
    }

val tourAccelerated =
    registerTour(
        "nativeTourAccelerated",
        listOf(
            // OpenGL on macOS, the app's default there while JavaFX's Metal pipeline crashes (GuiMain).
            "-Dprism.order=" + mapOf("windows" to "d3d").getOrDefault(nativeOs, "es2"),
            // Also on virtual displays without a GPU (CI, Xvfb).
            "-Dprism.forceGPU=true",
        ),
    )
val tourSoftware = registerTour("nativeTourSoftware", listOf("-Dprism.order=sw"))
tourSoftware { mustRunAfter(tourAccelerated) }

tasks.register("nativeTour") {
    group = "build"
    description = "Regenerates the reachability metadata of the desktop app with the GUI tour."
    dependsOn(tourAccelerated, tourSoftware)
}
