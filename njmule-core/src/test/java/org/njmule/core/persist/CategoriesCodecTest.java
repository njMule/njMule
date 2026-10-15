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

import java.io.IOException;
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

    private List<Category> categories;
    private List<Category> loaded;

    @Test
    void shouldLoadNoCategoryFromAMissingFile() throws IOException {
        whenTheCategoriesAreLoaded();

        thenNoCategoryIsLoaded();
    }

    @Test
    void shouldLoadTheCategoriesItSaved() throws IOException {
        givenTwoSavedCategories();

        whenTheCategoriesAreLoaded();

        thenTheSavedCategoriesAreLoaded();
    }

    @Test
    void shouldLeaveOutBrokenCategories() throws IOException {
        givenTwoSavedCategories();
        givenTheLinesAppended("category.7.name=\ncategory.8.color=99\n");

        whenTheCategoriesAreLoaded();

        thenTheSavedCategoriesAreLoaded();
    }

    @Test
    void shouldLoseOnlyTheLineOfAMalformedEscape() throws IOException {
        givenTheFileContent("\uFEFFcategory.1.name=Films\ncategory.1.comment=\\u12x\ncategory.2.name=Music\n");

        whenTheCategoriesAreLoaded();

        thenTheLoadedNamesAre("Films", "Music");
    }

    private void givenTwoSavedCategories() throws IOException {
        this.categories = List.of(
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
        CategoriesCodec.save(file(), this.categories);
    }

    private void givenTheLinesAppended(String lines) throws IOException {
        Files.writeString(file(), Files.readString(file()) + lines);
    }

    private void givenTheFileContent(String content) throws IOException {
        Files.writeString(file(), content, UTF_8);
    }

    private void whenTheCategoriesAreLoaded() throws IOException {
        this.loaded = CategoriesCodec.load(file());
    }

    private void thenNoCategoryIsLoaded() {
        assertThat(this.loaded).isEmpty();
    }

    private void thenTheSavedCategoriesAreLoaded() {
        assertThat(this.loaded).isEqualTo(this.categories);
    }

    private void thenTheLoadedNamesAre(String... names) {
        assertThat(this.loaded).extracting(Category::name).containsExactly(names);
    }

    private Path file() {
        return this.dir.resolve(CategoriesCodec.FILE_NAME);
    }
}
