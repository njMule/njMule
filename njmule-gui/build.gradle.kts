// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer

plugins {
    id("njmule.java-conventions")
    application
    alias(libs.plugins.license.report)
}

description = "njMule GUI"

dependencies {
    components {
        val classifier = JavaFxPlatformRule.hostClassifier()
        listOf("javafx-base", "javafx-graphics", "javafx-controls").forEach {
            withModule<JavaFxPlatformRule>("org.openjfx:$it") { params(classifier) }
        }
    }

    implementation(project(":njmule-app"))
    implementation(project(":njmule-webui"))
    implementation(libs.slf4j.api)
    implementation(libs.javafx.controls)
    implementation(libs.atlantafx.base)
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.material2.pack)

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
// ./gradlew :njmule-gui:nativeGui → build/native/njmule-gui[.exe] plus the JDK libraries it loads.
// JavaFX loads its own native libraries; the reachability metadata comes from the GUI tour (nativeTour)
// and a few hand-written JNI entries.
val hostOs = System.getProperty("os.name").lowercase()
val nativeOs =
    when {
        hostOs.startsWith("windows") -> "windows"
        hostOs.startsWith("mac") -> "macos"
        else -> "linux"
    }
val graalvmBin = providers.environmentVariable("GRAALVM_HOME").map { "$it/bin/" }.orElse("")
val cmdSuffix = if (nativeOs == "windows") ".cmd" else ""
val guiModule = "org.njmule.gui/org.njmule.gui.GuiMain"
// The jar, not the class folders: on Windows native-image misses the metadata of an exploded module.
val nativeModulePath = files(tasks.jar, configurations.runtimeClasspath)

// Windows links the executable's icon and version information (native/windows).
val nativeResourceSources =
    tasks.register<Sync>("nativeResourceSources") {
        val version = project.version.toString()
        inputs.property("version", version)
        from("native/windows") {
            include("njmule-gui.rc")
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
        commandLine("cmd", "/c", file("native/windows/rc.cmd").path, "njmule-gui.rc", "njmule-gui.res", project.version)
        outputs.file(dir.map { it.file("njmule-gui.res") })
    }

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
    val resFile = layout.buildDirectory.file("native-res/njmule-gui.res")
    val optimization = providers.gradleProperty("native.optimization")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--module-path", modulePath.asPath, "--module", module, "-o", "njmule-gui", "@$argsFile") +
                (if (windows) listOf("-H:NativeLinkerOption=${resFile.get().asFile}") else emptyList()) +
                optimization.get()
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
            "-Dprism.order=" + mapOf("windows" to "d3d", "macos" to "mtl").getOrDefault(nativeOs, "es2"),
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
