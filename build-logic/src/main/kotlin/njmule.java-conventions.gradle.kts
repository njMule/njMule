// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Shared set-up of every njMule module: Java 25, Error Prone, Spotless, Checkstyle, JUnit, JaCoCo
// and SpotBugs + FindSecBugs.

import com.diffplug.gradle.spotless.SpotlessTask
import com.diffplug.spotless.LineEnding
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    checkstyle
    jacoco
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
    id("com.github.spotbugs")
}

val libs = versionCatalogs.named("libs")

fun versionOf(alias: String): String = libs.findVersion(alias).get().requiredVersion

fun library(alias: String) = libs.findLibrary(alias).get()

fun listProperty(name: String): List<String> =
    providers.gradleProperty(name).orNull?.split(',')?.map { it.trim() }?.filter(String::isNotEmpty) ?: emptyList()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(versionOf("java"))
}

// Every resolved version is locked in gradle.lockfile and every artifact checked against
// gradle/verification-metadata.xml; both are rewritten after a version change (CLAUDE.md).
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    errorprone(library("errorprone-core"))
    testImplementation(platform(library("junit-bom")))
    testRuntimeOnly(library("junit-platform-launcher"))
    spotbugsPlugins(library("findsecbugs"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = versionOf("java").toInt()
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Xlint:all,-processing,-requires-automatic,-requires-transitive-automatic,-missing-explicit-ctor",
        )
    )
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/.*"
    }
}

// Every artifact ships the license and the notices (GPL-3.0 section 4).
tasks.named<ProcessResources>("processResources") {
    from(files(layout.settingsDirectory.file("LICENSE"), layout.settingsDirectory.file("NOTICE"))) {
        into("META-INF")
    }
}

tasks.named<Jar>("jar") {
    manifest.attributes(
        mapOf(
            "Implementation-Title" to (project.description ?: project.name),
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "The njMule contributors",
            "License" to "GPL-3.0-or-later",
        )
    )
}

val excludedTags = listProperty("test.excludedTags")
val includedTags = listProperty("test.tags")
val testForks = providers.gradleProperty("test.forks").map(String::toInt)
val testSettings = providers.gradlePropertiesPrefixedBy("njmule.")

// The window tests wait at most 55 s for each step on the FX thread: on a machine with few cores, next to
// the test forks of the other modules, -Ptest.parallelModules=1 runs one module's tests at a time.
val testSlots =
    gradle.sharedServices.registerIfAbsent("testSlots", TestSlots::class) {
        providers.gradleProperty("test.parallelModules").orNull?.let { maxParallelUsages = it.toInt() }
    }

tasks.withType<Test>().configureEach {
    usesService(testSlots)
    useJUnitPlatform {
        excludeTags(*excludedTags.toTypedArray())
        includeTags(*includedTags.toTypedArray())
    }
    // Test classes spread over a few JVMs, each running many classes one after the other: what a class
    // leaves in static state, the next one finds, so tests and the engine take back what they set.
    maxParallelForks = testForks.getOrElse(4)
    // Gradle gives test JVMs 512 MiB unless told otherwise: within 1 GiB the window tests slow down to a
    // time-out.
    maxHeapSize = providers.gradleProperty("test.maxHeap").get()
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Dfile.encoding=UTF-8")
    // UPnP stays off in every test: it would change the router's port mappings.
    systemProperty("njmule.upnp.disabled", "true")
    systemProperty("njmule.defaultlists.disabled", "true")
    systemProperty("njmule.update.disabled", "true")
    // Nor is DB-IP's country database downloaded, which every profile asks for by default.
    systemProperty("njmule.countries.disabled", "true")
    // Test settings given on the command line, e.g. -Pnjmule.interop.kadPort=14897.
    systemProperties(testSettings.get())
}

spotless {
    lineEndings = LineEnding.UNIX
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        // PALANTIR style: 4-space indent, 120 columns, compact lambdas.
        palantirJavaFormat(versionOf("palantir-java-format")).style("PALANTIR").formatJavadoc(true)
        removeUnusedImports()
        licenseHeaderFile(layout.settingsDirectory.file("config/license/header-java.txt"))
    }
}

// Spotless closes its formatters when the first clean of the build ends, but orders its tasks only after
// their own module's clean: in a parallel "clean build" another module's check would lose its classes.
tasks.withType<SpotlessTask>().configureEach {
    mustRunAfter(rootProject.subprojects.map { "${it.path}:clean" })
}

// Conventions not covered by the formatter (e.g. mandatory "this.").
checkstyle {
    toolVersion = versionOf("checkstyle")
    configFile = layout.settingsDirectory.file("config/checkstyle/checkstyle.xml").asFile
    maxWarnings = 0
}

tasks.withType<Checkstyle>().configureEach {
    // The checks read the sources only: no need to wait for the compiler.
    classpath = files()
}

jacoco {
    toolVersion = versionOf("jacoco")
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named("check") {
    dependsOn("jacocoTestReport")
}

spotbugs {
    toolVersion = versionOf("spotbugs")
    effort = Effort.MAX
    reportLevel = Confidence.MEDIUM
    excludeFilter = layout.settingsDirectory.file("config/spotbugs/exclude.xml")
}

// Only the production code is analysed.
tasks.named("spotbugsTest") {
    enabled = false
}
