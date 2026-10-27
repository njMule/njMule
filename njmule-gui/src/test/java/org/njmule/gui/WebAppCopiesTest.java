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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.ui.Icons;

/** The web app keeps copies of the desktop app's languages and icons in its own sources: they must not drift apart. */
class WebAppCopiesTest {

    private static final Path SOURCES = Path.of("..", "njmule-webapp", "src");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");
    private static final Pattern ENTRY = Pattern.compile("(?m)^    \\w+: \\{");
    private static final Pattern ICON =
            Pattern.compile("(\\w+): \\{\\s*path: '([^']*)',?(?:\\s*kind: '(\\w+)',?)?\\s*}", Pattern.DOTALL);

    private String source = "";
    private List<String> webCopy = List.of();
    private Map<String, String> webIcons = Map.of();

    @Test
    void shouldOfferTheLanguagesOfTheDesktopAppInTheirOrder() throws IOException {
        givenTheWebAppSource("i18n.ts");

        whenTheListIsRead("export const LANGUAGES = [", "] as const");

        thenItIs(I18n.LANGUAGES.stream().map(Locale::toLanguageTag).toList());
    }

    @Test
    void shouldLayOutRightToLeftTheLanguagesTheDesktopAppDoes() throws IOException {
        givenTheWebAppSource("i18n.ts");

        whenTheListIsRead("document.documentElement.dir = [", "].includes(tag)");

        thenItIs(I18n.LANGUAGES.stream()
                .filter(l -> I18n.of(l).isRightToLeft())
                .map(Locale::toLanguageTag)
                .toList());
    }

    @Test
    void shouldDrawEachIconAsTheDesktopAppDoes() throws IOException {
        givenTheWebAppSource("icons.ts");

        whenTheIconsAreRead();

        thenEachIconIsTheDesktopAppsOfItsName();
    }

    private void givenTheWebAppSource(String file) throws IOException {
        this.source = Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8);
    }

    private void whenTheListIsRead(String start, String end) {
        int from = this.source.indexOf(start);
        String list = this.source.substring(from + start.length(), this.source.indexOf(end, from));
        this.webCopy = QUOTED.matcher(list).results().map(m -> m.group(1)).toList();
    }

    // Each icon as "path kind", the kind empty for a line.
    private void whenTheIconsAreRead() {
        Map<String, String> icons = new LinkedHashMap<>();
        Matcher m = ICON.matcher(this.source);
        while (m.find()) {
            icons.put(m.group(1), m.group(2) + " " + (m.group(3) == null ? "" : m.group(3)));
        }
        this.webIcons = icons;
    }

    private void thenItIs(List<String> expected) {
        assertThat(this.webCopy).containsExactlyElementsOf(expected);
    }

    private void thenEachIconIsTheDesktopAppsOfItsName() {
        // Every entry read: one the pattern missed would go unchecked.
        assertThat(this.webIcons)
                .hasSize((int) ENTRY.matcher(this.source).results().count());
        Map<String, String> desktop = new LinkedHashMap<>();
        Arrays.stream(Icons.values())
                .filter(i -> this.webIcons.containsKey(i.name()))
                .forEach(i -> desktop.put(
                        i.name(), i.path() + " " + (i.filled() ? "filled" : i.directional() ? "directional" : "")));
        assertThat(this.webIcons).containsExactlyInAnyOrderEntriesOf(desktop);
    }
}
