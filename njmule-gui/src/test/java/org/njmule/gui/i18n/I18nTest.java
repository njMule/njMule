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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
            "nj.events.downloadCompleted",
            "nj.events.downloadFailed",
            "nj.events.chatMessage",
            "nj.events.diskSpaceShort",
            "nj.proxy.none",
            "nj.proxy.socks",
            "nj.proxy.http");

    private I18n texts;
    private String text;
    private boolean has;
    private final Set<String> used = new TreeSet<>(DYNAMIC);
    private final List<String> missing = new ArrayList<>();

    @ParameterizedTest
    @CsvSource({
        "en, IDS_DL_FILENAME, File Name",
        "it, IDS_DL_FILENAME, Nome file",
        "en, nj.sidebar.collapse, Collapse the sidebar",
        "it, nj.sidebar.collapse, Comprimi la barra laterale"
    })
    void shouldLoadTheEnglishAndItalianTexts(String language, String key, String text) {
        givenTheTextsIn(Locale.forLanguageTag(language));

        whenTheTextIsLookedUp(key);

        thenItReads(text);
    }

    // Not translated in Italian: English stands in.
    @Test
    void shouldLetEnglishStandInForATextNotTranslatedInItalian() {
        givenTheTextsIn(Locale.ITALIAN);

        whenTheTextIsLookedUp("IDS_KADEMLIA");

        thenItReads("Kad");
    }

    @Test
    void shouldFillThePlaceholders() {
        givenTheTextsIn(Locale.ENGLISH);

        whenTheTextIsFormattedWith("IDS_SHAREDFILESCOUNT", 12);

        thenItReads("Number of Shared Files: 12");
    }

    @Test
    void shouldKeepThePlaceholderOfAnArgumentThatDoesNotFit() {
        givenTheTextsIn(Locale.ENGLISH);

        whenTheTextIsFormattedWith("IDS_SHAREDFILESCOUNT", "not a number");

        thenItContains("%d");
    }

    @Test
    void shouldShowAnUnknownKeyInBrackets() {
        givenTheTextsIn(Locale.ENGLISH);

        whenTheTextIsLookedUp("nj.no.such.key");

        thenItReads("[nj.no.such.key]");
    }

    @ParameterizedTest
    @CsvSource({"IDS_DL_FILENAME, true", "IDS_NO_SUCH, false"})
    void shouldTellWhetherAKeyHasAText(String key, boolean has) {
        givenTheTextsIn(Locale.ENGLISH);

        whenAskedWhetherThereIsATextFor(key);

        thenThereIsAText(has);
    }

    @Test
    void shouldHaveATextForEveryKeyTheSourcesUse() throws IOException {
        givenTheKeysTheSourcesUse();

        whenTheKeysAreLookedUpInEveryLanguage();

        thenTheKeysUsedAreMoreThan(100);
        thenNoTextIsMissing();
    }

    @ParameterizedTest
    @CsvSource({
        "it, IDS_PW_CON_UPLBL, Upload",
        "it, IDS_CD_DOWN, 'Download (sessione):'",
        "en, IDS_CD_DOWN, 'Downloaded (this session):'"
    })
    void shouldKeepUploadAndDownloadInItalian(String language, String key, String text) {
        givenTheTextsIn(Locale.forLanguageTag(language));

        whenTheTextIsLookedUp(key);

        thenItReads(text);
    }

    @Test
    void shouldKeepTheItalianTranslationOfTheOtherTexts() {
        givenTheTextsIn(Locale.ITALIAN);

        whenTheTextIsLookedUp("IDS_DL_FILENAME");

        thenItReadsTheEmuleTranslation("Nome file");
    }

    private void givenTheTextsIn(Locale language) {
        this.texts = I18n.of(language);
    }

    private void givenTheKeysTheSourcesUse() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY.matcher(Files.readString(file));
                while (m.find()) {
                    this.used.add(m.group(1));
                }
            }
        }
    }

    private void whenTheTextIsLookedUp(String key) {
        this.text = this.texts.t(key);
    }

    private void whenTheTextIsFormattedWith(String key, Object argument) {
        this.text = this.texts.f(key, argument);
    }

    private void whenAskedWhetherThereIsATextFor(String key) {
        this.has = this.texts.has(key);
    }

    private void whenTheKeysAreLookedUpInEveryLanguage() {
        for (Locale language : I18n.LANGUAGES) {
            I18n inLanguage = I18n.of(language);
            for (String key : this.used) {
                if (!inLanguage.has(key)) {
                    this.missing.add(language + ":" + key);
                }
            }
        }
    }

    private void thenItReads(String text) {
        assertThat(this.text).isEqualTo(text);
    }

    private void thenItReadsTheEmuleTranslation(String text) {
        assertThat(this.text).as("eMule's translation elsewhere").isEqualTo(text);
    }

    private void thenItContains(String part) {
        assertThat(this.text).contains(part);
    }

    private void thenThereIsAText(boolean has) {
        assertThat(this.has).isEqualTo(has);
    }

    private void thenTheKeysUsedAreMoreThan(int count) {
        assertThat(this.used).hasSizeGreaterThan(count);
    }

    private void thenNoTextIsMissing() {
        assertThat(this.missing).isEmpty();
    }
}
