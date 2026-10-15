// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import javax.inject.Inject
import org.gradle.api.artifacts.CacheableRule
import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule

/**
 * Points an OpenJFX module at the jar of one platform: the POMs pick it through OS-activated Maven profiles,
 * which Gradle does not evaluate, and publish no jar without a classifier.
 */
@CacheableRule
abstract class JavaFxPlatformRule @Inject constructor(private val classifier: String) : ComponentMetadataRule {
    override fun execute(context: ComponentMetadataContext) {
        val id = context.details.id
        context.details.allVariants {
            withDependencies {
                // The POM's dependency on its own platform jar, by an unresolved ${javafx.platform}.
                removeIf { it.group == id.group && it.name == id.name }
            }
            withFiles {
                removeAllFiles()
                addFile("${id.name}-${id.version}-$classifier.jar")
            }
        }
    }

    companion object {
        /** The classifier of the build machine, as the OpenJFX POM profiles choose it. */
        fun hostClassifier(): String {
            val os = System.getProperty("os.name").lowercase()
            val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
            val base =
                when {
                    os.startsWith("windows") -> "win"
                    os.startsWith("mac") -> "mac"
                    else -> "linux"
                }
            return if (arm) "$base-aarch64" else base
        }
    }
}
