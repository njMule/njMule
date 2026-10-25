/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Writes the API's OpenAPI document as JSON, which the engine reads without a YAML parser. Run by the build. */
public final class OpenApiJson {

    private OpenApiJson() {}

    /** Converts the YAML document {@code args[0]} into the JSON file {@code args[1]}. */
    public static void main(String[] args) throws IOException {
        Path target = Path.of(args[1]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, JsonMapper.shared().writeValueAsBytes(YAMLMapper.shared().readTree(Path.of(args[0]))));
    }
}
