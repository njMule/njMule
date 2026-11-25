// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.task.NodeTask

plugins {
    base
    id("com.github.node-gradle.node")
}

description = "njMule's documentation website (Docusaurus), published on GitHub Pages"

// As the web app: Node comes from the repository declared in the settings, checked as every other download is, and
// npm installs exactly what package-lock.json holds without running the packages' install scripts (.npmrc).
node {
    version = libs.versions.nodejs.get()
    download = true
    distBaseUrl = null
    npmInstallCommand = "ci"
}

// What the site takes from the Rust engine: the web interface on example data (whose pictures stand for the desktop
// app's too) and the command line's help. The site is not part of `build`: ./gradlew :website:site builds it.
val windows = System.getProperty("os.name").lowercase().startsWith("windows")
val exeSuffix = if (windows) ".exe" else ""

val engineToolsDir = layout.buildDirectory.dir("engine-tools")
val cliHelpDir = layout.buildDirectory.dir("cli-help")

abstract class BuildEngineTools : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Internal
    abstract val engineDir: DirectoryProperty

    @get:Internal
    abstract val cargoTargetDir: Property<String>

    @get:Input
    abstract val suffix: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun buildTools() {
        val dir = engineDir.get().asFile
        exec.exec {
            workingDir = dir
            commandLine("cargo", "build", "--locked", "--bin", "njmule", "--example", "web_capture_demo")
        }
        val debugDir = File(cargoTargetDir.get()).resolve("debug")
        val ext = suffix.get()
        fs.sync {
            from(debugDir.resolve("njmule$ext"))
            from(debugDir.resolve("examples/web_capture_demo$ext"))
            into(output)
        }
    }
}

val engineTools =
    tasks.register<BuildEngineTools>("engineTools") {
        description = "Builds the Rust engine binary and web capture demo for the documentation."
        group = "documentation"
        dependsOn(":njmule-ui:compress")
        val rootDir = layout.settingsDirectory
        engineDir = rootDir.dir("njmule-engine")
        cargoTargetDir =
            providers
                .environmentVariable("CARGO_TARGET_DIR")
                .orElse(rootDir.dir("njmule-engine/target").asFile.path)
        suffix = exeSuffix
        inputs.files(
            rootDir.file("njmule-engine/Cargo.toml"),
            rootDir.file("njmule-engine/Cargo.lock"),
            rootDir.file("njmule-engine/build.rs"),
            rootDir.file("njmule-engine/examples/web_capture_demo.rs"),
            rootDir.file("branding/njmule-icon.svg"),
            rootDir.file("branding/generated/njmule-32.png"),
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(rootDir.dir("njmule-engine/src")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(rootDir.dir("njmule-engine/assets")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.dir(rootDir.dir("njmule-ui/build/shipped")).withPathSensitivity(PathSensitivity.RELATIVE)
        output = engineToolsDir
    }

// The command line's help and its list of remote commands, as the engine prints them.
@CacheableTask
abstract class CliHelp : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val executable: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun print() {
        val dir = output.get().asFile
        dir.mkdirs()
        val exe = executable.get().asFile.path
        for ((name, arguments) in listOf("help.txt" to listOf("--help"), "remote.txt" to listOf("--remote", "help"))) {
            dir.resolve(name).outputStream().use { out ->
                exec.exec {
                    commandLine(listOf(exe) + arguments)
                    standardOutput = out
                }
            }
        }
    }
}

val cliHelp =
    tasks.register<CliHelp>("cliHelp") {
        description = "Prints the command line's help for the documentation."
        group = "documentation"
        dependsOn(engineTools)
        executable = engineToolsDir.map { it.file("njmule$exeSuffix") }
        output = cliHelpDir
    }

// The pages as written, without what prepare and apiDocs put among them.
val pages =
    files(
        fileTree("docs") { exclude("_generated/**") },
        fileTree("developer") { exclude("_generated/**", "api/reference/**") },
        "src",
        "plugins",
        "static",
    )
val configFiles =
    listOf("docusaurus.config.ts", "sidebars.ts", "sidebarsDeveloper.ts", "tsconfig.json", "package.json", "package-lock.json")

// The web interface pictured on example data in the browser of this computer (scripts/capture-web.mjs).
val webScreenshots =
    tasks.register<NodeTask>("webScreenshots") {
        description = "Pictures the web interface for the documentation."
        group = "documentation"
        dependsOn(tasks.npmInstall, engineTools)
        script = file("scripts/capture-web.mjs")
        val demo = engineToolsDir.map { it.file("web_capture_demo$exeSuffix").asFile.path }
        val folder = layout.buildDirectory.dir("web-captures")
        args = demo.zip(folder) { d, f -> listOf(d, f.asFile.path) }
        inputs.files(engineTools).withPropertyName("demo")
        inputs.file("scripts/capture-web.mjs").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(folder)
    }

// The API reference, generated from the OpenAPI document the core serves (developer/api/reference, not committed).
val apiDocs =
    tasks.register<NpmTask>("apiDocs") {
        description = "Generates the pages of the API reference."
        group = "documentation"
        dependsOn(tasks.npmInstall)
        npmCommand = listOf("run", "api")
        inputs.file(layout.settingsDirectory.file("njmule-engine/assets/openapi.yaml"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(configFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir("developer/api/reference")
        outputs.cacheIf { true }
    }

// The pictures the pages name, the brand's images, the repository's own pages and the command line's help.
val prepare =
    tasks.register<NodeTask>("prepare") {
        description = "Puts in place what the site takes from the rest of the repository."
        group = "documentation"
        // The developer guide's sidebar reads the generated API reference: a preview needs it too.
        dependsOn(tasks.npmInstall, webScreenshots, apiDocs, cliHelp)
        script = file("scripts/prepare.mjs")
        val web = layout.buildDirectory.dir("web-captures").map { it.asFile.path }
        val help = cliHelpDir.map { it.file("help.txt").asFile.path }
        val remote = cliHelpDir.map { it.file("remote.txt").asFile.path }
        args = web.map { listOf("--strict", "--web", it) }
            .zip(help.zip(remote) { h, r -> listOf("--cli", h, "--remote", r) }) { a, b -> a + b }
        inputs.files(cliHelp, webScreenshots).withPropertyName("generated")
        inputs.files(pages).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(
            layout.settingsDirectory.dir("branding"),
            layout.settingsDirectory.files(
                "CHANGELOG.md",
                "CONTRIBUTING.md",
                "SECURITY.md",
                "CODE_OF_CONDUCT.md",
                ".github/pull_request_template.md",
            ),
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(layout.buildDirectory.dir("generated"))
        outputs.dir("docs/_generated")
        outputs.dir("developer/_generated")
    }

// The site, for GitHub Pages: → build/site
val site =
    tasks.register<NpmTask>("site") {
        description = "Builds the documentation website."
        group = "documentation"
        dependsOn(prepare, apiDocs)
        npmCommand = listOf("run", "build", "--", "--out-dir", "build/site")
        environment.put("NJMULE_VERSION", project.version.toString())
        inputs.property("version", project.version.toString())
        inputs.files(pages, configFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.files(prepare, apiDocs).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(layout.buildDirectory.dir("site"))
        outputs.cacheIf { true }
    }

// Formatting (Prettier) and types (TypeScript) of the site's code.
val siteCheck =
    tasks.register<NpmTask>("siteCheck") {
        description = "Checks the formatting and types of the site's code."
        group = "verification"
        dependsOn(tasks.npmInstall, apiDocs)
        npmCommand = listOf("run", "check")
        inputs
            .files("src", "plugins", "scripts", configFiles, ".prettierrc.json")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.cacheIf { true }
        val done = layout.buildDirectory.file("site-check.done")
        outputs.file(done)
        doLast { done.get().asFile.writeText("") }
    }
