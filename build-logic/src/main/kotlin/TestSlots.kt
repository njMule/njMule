// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Bounds how many modules run their tests at once (each with its own forks). */
abstract class TestSlots : BuildService<BuildServiceParameters.None>
