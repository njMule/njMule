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
package org.njmule.core.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the download categories as {@code categories.properties}: {@code category.ID.*} keys. A broken
 * category is left out and logged.
 */
public final class CategoriesCodec {

    public static final String FILE_NAME = "categories.properties";

    private static final Logger logger = LoggerFactory.getLogger(CategoriesCodec.class);
    private static final String PREFIX = "category.";

    private CategoriesCodec() {}

    /** The categories in the order of their ids; none without the file. */
    public static List<Category> load(Path file) throws IOException {
        if (!Files.exists(file) && !Files.exists(AtomicFiles.bakPath(file.toAbsolutePath()))) {
            return List.of();
        }
        Properties p = PropertyLines.read(file);
        List<Category> categories = new ArrayList<>();
        for (int id : PropertyLines.numbers(p, PREFIX)) {
            String k = PREFIX + id + ".";
            try {
                String incoming = p.getProperty(k + "incoming", "").strip();
                categories.add(new Category(
                        id,
                        p.getProperty(k + "name", ""),
                        incoming.isEmpty() ? Optional.empty() : Optional.of(Path.of(incoming)),
                        p.getProperty(k + "comment", ""),
                        Integer.parseInt(p.getProperty(k + "color", "0").strip()),
                        DownloadPriority.valueOf(
                                p.getProperty(k + "priority", "AUTO").strip().toUpperCase(Locale.ROOT)),
                        p.getProperty(k + "patterns", ""),
                        Boolean.parseBoolean(p.getProperty(k + "regex", "false"))));
            } catch (IllegalArgumentException e) {
                logger.warn("Ignoring category {} of {}: {}", id, file.getFileName(), e.getMessage());
            }
        }
        return categories;
    }

    public static void save(Path file, List<Category> categories) throws IOException {
        StringBuilder text = new StringBuilder("# njMule download categories\n");
        for (Category c : categories) {
            String k = PREFIX + c.id() + ".";
            PropertyLines.append(text, k + "name", c.name());
            PropertyLines.append(
                    text, k + "incoming", c.incoming().map(Path::toString).orElse(""));
            PropertyLines.append(text, k + "comment", c.comment());
            PropertyLines.append(text, k + "color", String.valueOf(c.color()));
            PropertyLines.append(text, k + "priority", c.priority().name());
            PropertyLines.append(text, k + "patterns", c.patterns());
            PropertyLines.append(text, k + "regex", String.valueOf(c.regex()));
        }
        AtomicFiles.writeBytes(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
