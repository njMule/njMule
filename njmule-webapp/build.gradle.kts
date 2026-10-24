// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.github.gradle.node.npm.task.NpmTask
import java.util.Properties

plugins {
    base
    id("com.github.node-gradle.node")
}

description = "njMule web app: the browser side of the web interface (Vue and Element Plus)"

// Node comes from the repository declared in the settings, checked as every other download is; npm installs
// exactly what package-lock.json holds, without running the packages' install scripts (.npmrc).
node {
    version = libs.versions.nodejs.get()
    download = true
    distBaseUrl = null
    npmInstallCommand = "ci"
}

/**
 * The desktop app's translations as JSON, one file per language, holding only the keys the web app uses. The texts
 * keep their Java format placeholders (%s, %d, %1$s), which the app fills in itself.
 */
@CacheableTask
abstract class Translations : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun convert() {
        val key = Regex("""['"`]((?:IDS_[A-Z0-9_]+)|(?:nj\.[A-Za-z0-9_.]+[A-Za-z0-9]))['"`]""")
        val used = sources.files.flatMap { f -> key.findAll(f.readText()).map { it.groupValues[1] } }.toSortedSet()
        val files = bundles.files.associateBy { it.name }
        val languages = files.keys.mapNotNull { Regex("""nj(?:_(.+))?\.properties""").matchEntire(it) }
            .map { it.groupValues[1] }
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        for (language in languages) {
            val suffix = if (language.isEmpty()) "" else "_$language"
            // A language may word an eMule text its own way: its own bundle wins.
            val texts = sortedMapOf<String, String>()
            for (name in listOf("emule$suffix.properties", "nj$suffix.properties")) {
                val file = files[name] ?: continue
                val p = Properties()
                file.reader(Charsets.UTF_8).use { p.load(it) }
                p.stringPropertyNames().filter { it in used }.forEach { texts[it] = p.getProperty(it) }
            }
            val tag = if (language.isEmpty()) "en" else language.replace('_', '-')
            val json = texts.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  ${quote(k)}: ${quote(v)}" }
            dir.resolve("$tag.json").writeText(json, Charsets.UTF_8)
        }
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

val translations = tasks.register<Translations>("translations") {
    description = "Converts the desktop app's translations the web app uses to JSON."
    bundles.from(fileTree(rootDir.resolve("njmule-gui/src/main/resources/org/njmule/gui/i18n")) {
        include("*.properties")
    })
    sources.from(fileTree("src") { include("**/*.ts", "**/*.vue") })
    output = layout.buildDirectory.dir("translations/i18n")
}

val npmSources = listOf("src", "index.html", "vite.config.ts", "tsconfig.json", "package.json", "package-lock.json")

val bundle = tasks.register<NpmTask>("bundle") {
    description = "Checks the types of the web app and bundles it with Vite."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "build")
    inputs.files(npmSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("dist"))
    outputs.cacheIf { true }
}

val unitTest = tasks.register<NpmTask>("unitTest") {
    description = "Runs the web app's tests."
    group = "verification"
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "test")
    inputs.files(npmSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.cacheIf { true }
    val done = layout.buildDirectory.file("unit-test.done")
    outputs.file(done)
    doLast { done.get().asFile.writeText("") }
}

val formatCheck = tasks.register<NpmTask>("formatCheck") {
    description = "Checks that the web app's sources are formatted with Prettier."
    group = "verification"
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "lint")
    inputs.files(npmSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.cacheIf { true }
    val done = layout.buildDirectory.file("format-check.done")
    outputs.file(done)
    doLast { done.get().asFile.writeText("") }
}

tasks.assemble { dependsOn(bundle, translations) }
tasks.check { dependsOn(unitTest, formatCheck) }

// The bundle and the translations, for the web interface's jar.
val webApp = configurations.consumable("webApp") {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-web-app")) }
}
artifacts {
    add(webApp.name, layout.buildDirectory.dir("dist")) { builtBy(bundle) }
    add(webApp.name, layout.buildDirectory.dir("translations")) { builtBy(translations) }
}
