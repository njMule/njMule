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

// What the site takes from the other projects: how to start the web interface on example data, whose pictures stand
// for the desktop app's too, and the command line's help. The site is not part of `build`: ./gradlew :website:site
// builds it.
val webCaptureDeclared = configurations.dependencyScope("webCaptureDeclared")
val webCapture =
    configurations.resolvable("webCapture") {
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-web-capture")) }
        extendsFrom(webCaptureDeclared.get())
    }
val cliHelpDeclared = configurations.dependencyScope("cliHelpDeclared")
val cliHelp =
    configurations.resolvable("cliHelp") {
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("njmule-cli-help")) }
        extendsFrom(cliHelpDeclared.get())
    }

dependencies {
    webCaptureDeclared(project(":njmule-http"))
    cliHelpDeclared(project(":njmule-app"))
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
        dependsOn(tasks.npmInstall)
        script = file("scripts/capture-web.mjs")
        val launcher = webCapture.map { it.singleFile.resolve("launcher.json").path }
        val folder = layout.buildDirectory.dir("web-captures")
        args = launcher.zip(folder) { l, f -> listOf(l, f.asFile.path) }
        inputs.files(webCapture).withPropertyName("webCapture")
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
        inputs.file(layout.settingsDirectory.file("njmule-core/src/main/resources/org/njmule/core/remote/openapi.yaml"))
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
        dependsOn(tasks.npmInstall, webScreenshots, apiDocs)
        script = file("scripts/prepare.mjs")
        val web = layout.buildDirectory.dir("web-captures").map { it.asFile.path }
        val help = cliHelp.map { it.singleFile.resolve("help.txt").path }
        val remote = cliHelp.map { it.singleFile.resolve("remote.txt").path }
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
