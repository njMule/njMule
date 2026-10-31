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
