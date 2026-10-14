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
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class LanguagesTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path BUNDLES = Path.of("src/main/resources/org/njmule/gui/i18n");
    private static final Pattern IDS_KEY = Pattern.compile("\"(IDS_[A-Z0-9_]+)\"");
    // A format specifier: argument index, flags with width and precision, conversion.
    private static final Pattern PLACEHOLDER =
            Pattern.compile("%(?:(\\d+)\\$)?([-#+ 0,(]*\\d*(?:\\.\\d+)?)([a-zA-Z%])");

    private List<Locale> offered;
    private Locale system;
    private I18n texts;
    private Properties english;
    private Properties englishEmule;
    private Properties translation;
    private Properties translatedEmule;
    private Set<String> usedEmuleKeys;

    /** Every language of the interface, with its own name and its word for "Language". */
    static Stream<Arguments> languages() {
        return Stream.of(
                arguments(Locale.ENGLISH, "English", "Language"),
                arguments(Locale.of("ca"), "Català", "Llengua"),
                arguments(Locale.of("cs"), "Čeština", "Jazyk"),
                arguments(Locale.of("da"), "Dansk", "Sprog"),
                arguments(Locale.GERMAN, "Deutsch", "Sprache"),
                arguments(Locale.of("et"), "Eesti", "Keel"),
                arguments(Locale.of("es"), "Español", "Idioma"),
                arguments(Locale.FRENCH, "Français", "Langue"),
                arguments(Locale.of("gl"), "Galego", "Lingua"),
                arguments(Locale.ITALIAN, "Italiano", "Lingua"),
                arguments(Locale.of("lv"), "Latviešu", "Valoda"),
                arguments(Locale.of("lt"), "Lietuvių", "Kalba"),
                arguments(Locale.of("hu"), "Magyar", "Nyelv"),
                arguments(Locale.of("nl"), "Nederlands", "Taal"),
                arguments(Locale.of("nb"), "Norsk bokmål", "Språk"),
                arguments(Locale.of("pl"), "Polski", "Język"),
                arguments(Locale.of("pt", "BR"), "Português (Brasil)", "Idioma"),
                arguments(Locale.of("pt", "PT"), "Português (Portugal)", "Linguagem"),
                arguments(Locale.of("ro"), "Română", "Limba"),
                arguments(Locale.of("sq"), "Shqip", "Gjuha"),
                arguments(Locale.of("fi"), "Suomi", "Kieli"),
                arguments(Locale.of("sv"), "Svenska", "Språk"),
                arguments(Locale.of("tr"), "Türkçe", "Dil"),
                arguments(Locale.of("el"), "Ελληνικά", "Γλώσσα"),
                arguments(Locale.of("bg"), "Български", "Език"),
                arguments(Locale.of("ru"), "Русский", "Язык"),
                arguments(Locale.of("uk"), "Українська", "Мова"),
                arguments(Locale.SIMPLIFIED_CHINESE, "简体中文", "语言"),
                arguments(Locale.TRADITIONAL_CHINESE, "繁體中文", "語言"),
                arguments(Locale.JAPANESE, "日本語", "言語"),
                arguments(Locale.KOREAN, "한국어", "언어"));
    }

    static Stream<Locale> translations() {
        return I18n.LANGUAGES.stream().filter(l -> !l.equals(Locale.ENGLISH));
    }

    @Test
    void theInterfaceOffersEnglishFirstThenEveryTranslation() {
        this.whenTheLanguagesOfTheInterfaceAreListed();

        this.thenTheLanguagesAre(
                Locale.ENGLISH,
                Locale.of("ca"),
                Locale.of("cs"),
                Locale.of("da"),
                Locale.GERMAN,
                Locale.of("et"),
                Locale.of("es"),
                Locale.FRENCH,
                Locale.of("gl"),
                Locale.ITALIAN,
                Locale.of("lv"),
                Locale.of("lt"),
                Locale.of("hu"),
                Locale.of("nl"),
                Locale.of("nb"),
                Locale.of("pl"),
                Locale.of("pt", "BR"),
                Locale.of("pt", "PT"),
                Locale.of("ro"),
                Locale.of("sq"),
                Locale.of("fi"),
                Locale.of("sv"),
                Locale.of("tr"),
                Locale.of("el"),
                Locale.of("bg"),
                Locale.of("ru"),
                Locale.of("uk"),
                Locale.SIMPLIFIED_CHINESE,
                Locale.TRADITIONAL_CHINESE,
                Locale.JAPANESE,
                Locale.KOREAN);
    }

    @ParameterizedTest
    @MethodSource("languages")
    void everyLanguageLoadsWithItsOwnNameAndEmulesTexts(Locale language, String name, String languageWord) {
        this.whenTheTextsOfALanguageAreLoaded(language);

        this.thenTheTextsAreIn(language);
        this.thenTheLanguageIsNamed(name);
        this.thenTheTextOfIs("IDS_PW_LANG", languageWord);
    }

    @ParameterizedTest
    @CsvSource({
        "zh-CN, IDS_EM_SEARCH, 搜索",
        "zh-CN, IDS_DL_PREVIEW, 预览",
        "ja, IDS_DL_PREVIEW, プレビュー",
        "ja, IDS_DL_RESUME, 再開",
        "ja, IDS_EXIT, 終了",
        "ko, IDS_EM_SEARCH, 검색",
        "ko, IDS_DL_PREVIEW, 미리 보기"
    })
    void aMnemonicWrittenInBracketsIsDropped(String language, String key, String text) {
        this.whenTheTextsOfALanguageAreLoaded(Locale.forLanguageTag(language));

        this.thenTheTextOfIs(key, text);
    }

    @ParameterizedTest
    @CsvSource({
        "en-GB, en",
        "ca-ES, ca",
        "cs-CZ, cs",
        "da-DK, da",
        "de-AT, de",
        "et-EE, et",
        "es-MX, es",
        "fr-CA, fr",
        "gl-ES, gl",
        "it-CH, it",
        "it, it",
        "lv-LV, lv",
        "lt-LT, lt",
        "hu-HU, hu",
        "nl-BE, nl",
        "nb-NO, nb",
        "no, nb",
        "no-NO, nb",
        "nn-NO, nb",
        "pl-PL, pl",
        "pt-BR, pt-BR",
        "pt-PT, pt-PT",
        "pt, pt-PT",
        "pt-AO, pt-PT",
        "pt-MZ, pt-PT",
        "ro-RO, ro",
        "ro-MD, ro",
        "sq-AL, sq",
        "sq-XK, sq",
        "fi-FI, fi",
        "el-GR, el",
        "bg-BG, bg",
        "ru-RU, ru",
        "uk-UA, uk",
        "zh, zh-CN",
        "zh-CN, zh-CN",
        "zh-SG, zh-CN",
        "zh-Hans-HK, zh-CN",
        "zh-Hans-TW, zh-CN",
        "zh-TW, zh-TW",
        "zh-HK, zh-TW",
        "zh-MO, zh-TW",
        "zh-Hant, zh-TW",
        "zh-Hant-CN, zh-TW",
        "sv-SE, sv",
        "sv-FI, sv",
        "tr-TR, tr",
        "ja-JP, ja",
        "ko-KR, ko",
        "ar-EG, en",
        "hi-IN, en"
    })
    void theSystemLanguagePicksItsTranslationOrEnglish(String systemLanguage, String chosen) {
        this.givenTheSystemLanguage(systemLanguage);

        this.whenTheTextsOfTheSystemLanguageAreLoaded();

        this.thenTheTextsAreIn(Locale.forLanguageTag(chosen));
    }

    @Test
    void nynorskInTheOldJavaFormOfNorwegianGetsBokmal() {
        this.givenTheSystemLocale(Locale.of("no", "NO", "NY"));

        this.whenTheTextsOfTheSystemLanguageAreLoaded();

        this.thenTheTextsAreIn(Locale.of("nb"));
        this.thenTheLanguageIsNamed("Norsk bokmål");
    }

    @ParameterizedTest
    @MethodSource("translations")
    void aTranslationHasEveryNjmuleTextWithTheSamePlaceholders(Locale language) throws IOException {
        this.givenTheEnglishTexts();

        this.whenTheTranslationIsRead(language);

        this.thenItHasEveryNjmuleTextAndNoOther();
        this.thenEveryTextKeepsTheEnglishPlaceholders();
        this.thenTheEmuleTextsItRewordsExist();
    }

    @ParameterizedTest
    @MethodSource("translations")
    void theEmuleTextsTheInterfaceUsesKeepTheirPlaceholders(Locale language) throws IOException {
        this.givenTheEnglishTexts();
        this.givenTheEmuleTextsTheInterfaceUses();

        this.whenTheTranslationIsRead(language);

        this.thenTheUsedEmuleTextsKeepTheEnglishPlaceholders();
    }

    private void givenTheSystemLanguage(String tag) {
        this.system = Locale.forLanguageTag(tag);
    }

    private void givenTheSystemLocale(Locale locale) {
        this.system = locale;
    }

    private void givenTheEnglishTexts() throws IOException {
        this.english = load("nj.properties");
        this.englishEmule = load("emule.properties");
    }

    private void givenTheEmuleTextsTheInterfaceUses() throws IOException {
        this.usedEmuleKeys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = IDS_KEY.matcher(Files.readString(file));
                while (m.find()) {
                    this.usedEmuleKeys.add(m.group(1));
                }
            }
        }
    }

    private void whenTheLanguagesOfTheInterfaceAreListed() {
        this.offered = I18n.LANGUAGES;
    }

    private void whenTheTextsOfALanguageAreLoaded(Locale language) {
        this.texts = I18n.of(language);
    }

    private void whenTheTextsOfTheSystemLanguageAreLoaded() {
        this.texts = I18n.of(this.system);
    }

    // Read strictly as UTF-8, as the resource bundles are: a malformed file fails here.
    private void whenTheTranslationIsRead(Locale language) throws IOException {
        this.translation = load("nj_" + language + ".properties");
        this.translatedEmule = load("emule_" + language + ".properties");
    }

    private void thenTheLanguagesAre(Locale... languages) {
        assertThat(this.offered).containsExactly(languages);
    }

    private void thenTheTextsAreIn(Locale language) {
        assertThat(this.texts.locale()).isEqualTo(language);
    }

    private void thenTheLanguageIsNamed(String name) {
        assertThat(this.texts.name()).isEqualTo(name);
    }

    private void thenTheTextOfIs(String key, String text) {
        assertThat(this.texts.t(key)).isEqualTo(text);
    }

    private void thenItHasEveryNjmuleTextAndNoOther() {
        Set<String> own = new TreeSet<>(this.translation.stringPropertyNames());
        own.removeIf(key -> key.startsWith("IDS_"));

        assertThat(own).isEqualTo(this.english.stringPropertyNames());
        assertThat(this.english.stringPropertyNames()).noneMatch(key -> key.startsWith("IDS_"));
        assertThat(this.translation.stringPropertyNames())
                .allSatisfy(key ->
                        assertThat(this.translation.getProperty(key)).as(key).isNotBlank());
    }

    private void thenEveryTextKeepsTheEnglishPlaceholders() {
        List<String> differ = new ArrayList<>();
        for (String key : this.translation.stringPropertyNames()) {
            Properties source = key.startsWith("IDS_") ? this.englishEmule : this.english;
            if (!placeholders(this.translation.getProperty(key)).equals(placeholders(source.getProperty(key, "")))) {
                differ.add(key);
            }
        }
        assertThat(differ).isEmpty();
    }

    private void thenTheEmuleTextsItRewordsExist() {
        assertThat(this.translation.stringPropertyNames())
                .filteredOn(key -> key.startsWith("IDS_"))
                .allMatch(this.englishEmule::containsKey);
    }

    private void thenTheUsedEmuleTextsKeepTheEnglishPlaceholders() {
        List<String> differ = new ArrayList<>();
        for (String key : this.usedEmuleKeys) {
            String translated = this.translation.getProperty(key, this.translatedEmule.getProperty(key));
            if (translated != null
                    && !placeholders(translated).equals(placeholders(this.englishEmule.getProperty(key, "")))) {
                differ.add(key);
            }
        }
        assertThat(this.usedEmuleKeys).hasSizeGreaterThan(100);
        assertThat(differ).isEmpty();
    }

    // Each argument with the specifier that formats it, so that a translation may reorder them with %2$s.
    private static Map<Integer, String> placeholders(String text) {
        Map<Integer, String> found = new TreeMap<>();
        Matcher m = PLACEHOLDER.matcher(text);
        int next = 1;
        while (m.find()) {
            String conversion = m.group(3);
            if (!conversion.equals("%") && !conversion.equals("n")) {
                int index = m.group(1) == null ? next++ : Integer.parseInt(m.group(1));
                found.put(index, m.group(2) + conversion);
            }
        }
        return found;
    }

    private static Properties load(String name) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(BUNDLES.resolve(name), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }
}
