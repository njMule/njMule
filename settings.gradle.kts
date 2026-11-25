// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // The Node.js builds the interface (njmule-ui), desktop shell (njmule-desktop) and site are made with.
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
    "njmule-ui",
    "njmule-desktop",
    "website",
)
