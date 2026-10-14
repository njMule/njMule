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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadPriority;

class CategoriesCodecTest {

    @TempDir
    Path dir;

    @Test
    void categoriesRoundTripAndBrokenOnesAreLeftOut() throws Exception {
        Path file = this.dir.resolve(CategoriesCodec.FILE_NAME);
        List<Category> categories = List.of(
                new Category(
                        2,
                        "Films = ok",
                        Optional.of(this.dir.resolve("films").toAbsolutePath()),
                        "évening",
                        3,
                        DownloadPriority.HIGH,
                        "*.mkv|*.avi",
                        false),
                new Category(5, "Music", Optional.empty(), "", 0, DownloadPriority.AUTO, "\\.flac$", true));

        assertThat(CategoriesCodec.load(file)).isEmpty();
        CategoriesCodec.save(file, categories);
        assertThat(CategoriesCodec.load(file)).isEqualTo(categories);

        Files.writeString(file, Files.readString(file) + "category.7.name=\ncategory.8.color=99\n");
        assertThat(CategoriesCodec.load(file)).isEqualTo(categories);
    }

    @Test
    void aMalformedEscapeCostsOnlyItsLine() throws Exception {
        Path file = this.dir.resolve(CategoriesCodec.FILE_NAME);
        Files.writeString(
                file, "\uFEFFcategory.1.name=Films\ncategory.1.comment=\\u12x\ncategory.2.name=Music\n", UTF_8);

        assertThat(CategoriesCodec.load(file)).extracting(Category::name).containsExactly("Films", "Music");
    }
}
