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
package org.njmule.gui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class I18nTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern KEY = Pattern.compile("\"((?:IDS_[A-Z0-9_]+)|(?:nj\\.[A-Za-z0-9.]+[A-Za-z0-9]))\"");
    // Keys built at run time: a prefix followed by an enum constant in lower case.
    private static final List<String> DYNAMIC = List.of(
            "nj.theme.nord",
            "nj.theme.primer",
            "nj.theme.cupertino",
            "nj.theme.dracula",
            "nj.mode.system",
            "nj.mode.light",
            "nj.mode.dark",
            "nj.obfuscation.disabled",
            "nj.obfuscation.supported",
            "nj.obfuscation.requested",
            "nj.obfuscation.required",
            "nj.import.outcome.imported",
            "nj.import.outcome.would_import",
            "nj.import.outcome.skipped",
            "nj.import.outcome.missing",
            "nj.import.outcome.failed",
            "nj.proxy.none",
            "nj.proxy.socks",
            "nj.proxy.http");

    @Test
    void englishAndItalianTextsLoad() {
        I18n english = I18n.of(Locale.ENGLISH);
        I18n italian = I18n.of(Locale.ITALIAN);

        assertThat(english.t("IDS_DL_FILENAME")).isEqualTo("File Name");
        assertThat(italian.t("IDS_DL_FILENAME")).isEqualTo("Nome file");
        assertThat(english.t("nj.top.tools")).isEqualTo("Tools");
        assertThat(italian.t("nj.top.tools")).isEqualTo("Strumenti");
        // Not translated in Italian: English stands in.
        assertThat(italian.t("IDS_KADEMLIA")).isEqualTo("Kad");
    }

    @Test
    void placeholdersAndUnknownKeys() {
        I18n english = I18n.of(Locale.ENGLISH);

        assertThat(english.f("IDS_SHAREDFILESCOUNT", 12)).isEqualTo("Number of Shared Files: 12");
        assertThat(english.f("IDS_SHAREDFILESCOUNT", "not a number")).contains("%d");
        assertThat(english.t("nj.no.such.key")).isEqualTo("[nj.no.such.key]");
        assertThat(english.has("IDS_DL_FILENAME")).isTrue();
        assertThat(english.has("IDS_NO_SUCH")).isFalse();
    }

    @Test
    void everyKeyTheSourcesUseHasAText() throws IOException {
        Set<String> used = new TreeSet<>(DYNAMIC);
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(file));
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (Locale language : I18n.LANGUAGES) {
            I18n texts = I18n.of(language);
            for (String key : used) {
                if (!texts.has(key)) {
                    missing.add(language + ":" + key);
                }
            }
        }
        assertThat(used).hasSizeGreaterThan(100);
        assertThat(missing).isEmpty();
    }

    @Test
    void italianKeepsUploadAndDownload() {
        I18n english = I18n.of(Locale.ENGLISH);
        I18n italian = I18n.of(Locale.ITALIAN);

        assertThat(italian.t("IDS_PW_CON_UPLBL")).isEqualTo("Upload");
        assertThat(italian.t("IDS_CD_DOWN")).isEqualTo("Download (sessione):");
        assertThat(english.t("IDS_CD_DOWN")).isEqualTo("Downloaded (this session):");
        assertThat(italian.t("IDS_DL_FILENAME"))
                .as("eMule's translation elsewhere")
                .isEqualTo("Nome file");
    }
}
