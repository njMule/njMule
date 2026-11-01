// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // The Node.js builds the interface (njmule-ui) and the site are made with, and nothing else from there.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "Node.js"
                    setUrl("https://nodejs.org/dist/")
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
    }
}

// The CI keeps the build cache apart from the Gradle user home, whose cache entry changes only with the build
// files, to save it after each build of main (.github/workflows/build.yml).
providers.environmentVariable("NJMULE_BUILD_CACHE").orNull?.let { dir -> buildCache { local { directory = File(dir) } } }

rootProject.name = "njmule"

include(
    "njmule-core",
    "njmule-testkit",
    "njmule-ui",
    "njmule-desktop",
    "njmule-http",
    "njmule-app",
    "njmule-bench",
    "website",
)
