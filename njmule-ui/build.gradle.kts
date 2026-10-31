// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.github.gradle.node.npm.task.NpmTask
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

plugins {
    base
    id("com.github.node-gradle.node")
}

description = "njMule's user interface: the desktop app's window and the web interface (Vue)"

// Node comes from the repository declared in the settings, checked as every other download is; npm installs
// exactly what package-lock.json holds, without running the packages' install scripts (.npmrc).
node {
    version = libs.versions.nodejs.get()
    download = true
    distBaseUrl = null
    npmInstallCommand = "ci"
}

/**
 * The translations as JSON, one file per language, holding only the keys the interface uses. The texts
 * keep their Java format placeholders (%s, %d, %1$s), which the interface fills in itself.
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

/**
 * The interface's files as the web interface ships them: text files compressed with gzip, which browsers take as they
 * are (the server unpacks them for a client that cannot), the rest unchanged.
 */
@CacheableTask
abstract class Compress : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val roots: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun compress() {
        val dir = output.get().asFile
        dir.deleteRecursively()
        for (root in roots.files) {
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                // The page stays as it is: the server fills in each session's token.
                if (file.extension in setOf("js", "css", "svg", "json", "md")) {
                    val target = dir.resolve("$path.gz")
                    target.parentFile.mkdirs()
                    target.outputStream().use { out ->
                        object : GZIPOutputStream(out) {
                            init {
                                def.setLevel(Deflater.BEST_COMPRESSION)
                            }
                        }.use { gzip -> file.inputStream().use { it.copyTo(gzip) } }
                    }
                } else {
                    file.copyTo(dir.resolve(path))
                }
            }
        }
    }
}


val translations = tasks.register<Translations>("translations") {
    description = "Converts the translations the interface uses to JSON."
    bundles.from(fileTree("i18n") {
        include("*.properties")
    })
    sources.from(fileTree("src") { include("**/*.ts", "**/*.vue") })
    output = layout.buildDirectory.dir("translations/i18n")
}

// The country flags, rendered to PNG by tools/FlagGen.java.
val flags = tasks.register<Sync>("flags") {
    from("flags") { include("*.png") }
    into(layout.buildDirectory.dir("flags/flags"))
}

val api = rootDir.resolve("njmule-core/src/main/resources/org/njmule/core/remote/openapi.yaml")
val npmSources = listOf("src", "index.html", "vite.config.ts", "tsconfig.json", "package.json", "package-lock.json")

val bundle = tasks.register<NpmTask>("bundle") {
    description = "Generates the API's types, checks the interface's types and bundles it with Vite."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "build")
    inputs.files(npmSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(api).withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(layout.buildDirectory.dir("dist"))
    outputs.cacheIf { true }
}

val unitTest = tasks.register<NpmTask>("unitTest") {
    description = "Runs the interface's tests."
    group = "verification"
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "test")
    inputs.files(npmSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(api).withPathSensitivity(PathSensitivity.NONE)
    outputs.cacheIf { true }
    val done = layout.buildDirectory.file("unit-test.done")
    outputs.file(done)
    doLast { done.get().asFile.writeText("") }
}

val formatCheck = tasks.register<NpmTask>("formatCheck") {
    description = "Checks that the interface's sources are formatted with Prettier."
    group = "verification"
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "lint")
    inputs.files(npmSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.cacheIf { true }
    val done = layout.buildDirectory.file("format-check.done")
    outputs.file(done)
    doLast { done.get().asFile.writeText("") }
}

// The whole interface in one folder, as the desktop app's shell embeds it.
val site = tasks.register<Sync>("site") {
    description = "Puts the bundle, the translations and the flags in one folder for the desktop shell."
    from(bundle)
    from(translations.map { it.output.get().asFile.parentFile })
    from(flags.map { it.destinationDir.parentFile })
    into(layout.buildDirectory.dir("site"))
}

val compress = tasks.register<Compress>("compress") {
    description = "Compresses the bundle and the translations for the web interface's jar."
    dependsOn(bundle, translations, flags)
    roots.from(
        layout.buildDirectory.dir("dist"),
        layout.buildDirectory.dir("translations"),
        layout.buildDirectory.dir("flags"),
    )
    output = layout.buildDirectory.dir("shipped")
}

tasks.assemble { dependsOn(compress, site) }
tasks.check { dependsOn(unitTest, formatCheck) }

// The bundle and the translations, compressed for the web interface's jar.
val webApp = configurations.consumable("webApp") {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-web-app")) }
}
// The same uncompressed, for the desktop shell.
val desktopApp = configurations.consumable("desktopApp") {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-desktop-app")) }
}
artifacts {
    add(webApp.name, compress.flatMap { it.output }) { builtBy(compress) }
    add(desktopApp.name, site.map { it.destinationDir }) { builtBy(site) }
}
