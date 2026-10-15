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
package org.njmule.gui.infra;

import static org.assertj.core.api.Assertions.assertThat;

import atlantafx.base.theme.Theme;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class InfraTest {

    private static final Path FOLDER = Path.of("downloads");
    private static final Path FILE = FOLDER.resolve("film.mkv");

    @TempDir
    Path dir;

    private Instant moment;
    private Formats formats;
    private String text;
    private GuiSettings settings;
    private GuiSettings loaded;
    private List<String> history;
    private Theme theme;
    private List<String> command;
    private ProcessBuilder process;
    private boolean set;

    @ParameterizedTest
    @CsvSource({"it-IT, 13/10/2026, 21:07:43", "en-US, 10/13/2026, 9:07:43"})
    void shouldWriteDatesAndTimesAsTheSystemWithAFullYear(String locale, String date, String time) {
        givenTheMoment("2026-10-13T19:07:43Z");

        whenItIsWrittenAsDateAndTime(Locale.forLanguageTag(locale), 2);

        thenTheTextStartsWith(date);
        thenTheTextContains(time);
    }

    @Test
    void shouldWriteTheTimeOfTheDayAsTheSystemInItaly() {
        givenTheMoment("2026-10-13T19:07:43Z");

        whenItIsWrittenAsTimeOfTheDay(Locale.ITALY, 2);

        thenTheTextIs("21:07");
    }

    @Test
    void shouldWriteTheTimeOfTheDayAsTheSystemInTheUnitedStates() {
        givenTheMoment("2026-10-13T19:07:43Z");

        whenItIsWrittenAsTimeOfTheDay(Locale.US, 2);

        thenTheTextStartsWith("9:07");
        thenTheTextEndsWith("PM");
    }

    @Test
    void shouldConfirmExitAndNotStartMinimisedByDefault() {
        whenTheSettingsAreLoaded();

        thenExitIsConfirmed(true);
        thenTheWindowStartsMinimised(false);
    }

    @Test
    void shouldRememberTheExitPromptAndStartMinimised() throws Exception {
        givenTheSettings();
        givenExitConfirmedAndStartMinimised(false, true);
        givenTheSettingsSaved();

        whenTheSettingsAreLoadedAgain();

        thenExitIsConfirmedAgain(false);
        thenTheWindowStartsMinimisedAgain(true);
    }

    @Test
    void shouldKeepTheLatestEntriesOfAHistoryFirstOnce() throws Exception {
        givenTheSettings();
        givenNumberedEntriesRemembered("serverMet", "https://example.org/", GuiSettings.MAX_HISTORY + 2);
        givenAnEntryRemembered("serverMet", "https://example.org/5");
        givenTheSettingsSaved();

        whenTheHistoryIsLoaded("serverMet");

        thenTheHistoryHasEntries(GuiSettings.MAX_HISTORY);
        thenTheHistoryStartsOnceWith("https://example.org/5");
        thenTheHistoryLacks("https://example.org/1", "https://example.org/2");
        thenTheHistoryOfIsEmpty("nodesDat");
    }

    @ParameterizedTest
    @MethodSource("sizes")
    void shouldWriteSizes(Locale language, long bytes, String shown) {
        givenTheFormatsOf(language);

        whenASizeIsWritten(bytes);

        thenTheTextIs(shown);
    }

    @ParameterizedTest
    @CsvSource({"2560, 2.5 KB/s", "-1, 0.0 KB/s"})
    void shouldWriteRates(long bytesPerSecond, String shown) {
        givenTheFormatsOf(Locale.ENGLISH);

        whenARateIsWritten(bytesPerSecond);

        thenTheTextIs(shown);
    }

    @ParameterizedTest
    @MethodSource("counts")
    void shouldWriteCounts(Locale language, long value, String shown) {
        givenTheFormatsOf(language);

        whenACountIsWritten(value);

        thenTheTextIs(shown);
    }

    @ParameterizedTest
    @CsvSource({"1, 3, 33.3 %", "5, 0, 0.0 %"})
    void shouldWritePercentages(long part, long whole, String shown) {
        givenTheFormatsOf(Locale.ENGLISH);

        whenAPercentageIsWritten(part, whole);

        thenTheTextIs(shown);
    }

    @ParameterizedTest
    @MethodSource("durations")
    void shouldWriteDurations(long seconds, String shown) {
        givenTheFormatsOf(Locale.ENGLISH);

        whenADurationIsWritten(seconds);

        thenTheTextIs(shown);
    }

    @ParameterizedTest
    @CsvSource({"102394, 99.99 KB", "1023938, 999.9 KB"})
    void shouldKeepTheDigitsOfASizeJustBelowTheNextForm(long bytes, String shown) {
        givenTheFormatsOf(Locale.ENGLISH);

        whenASizeIsWritten(bytes);

        thenTheTextIs(shown);
    }

    @ParameterizedTest
    @CsvSource({"99.995 KB, 102395, 100.0 KB", "999.95 KB, 1023949, '1,000 KB'", "99.995 MB, 104852358, 100.0 MB"})
    void shouldKeepTheDigitsOfTheNextFormForASizeRoundedUpToIt(String rounded, long bytes, String shown) {
        givenTheFormatsOf(Locale.ENGLISH);

        whenASizeIsWritten(bytes);

        thenTheSizeRoundedFromReads(rounded, shown);
    }

    @Test
    void shouldHaveDefaultSettings() {
        whenTheSettingsAreLoaded();

        thenTheThemeIs(Themes.Family.NORD, GuiSettings.Mode.SYSTEM);
        thenThereIsNoLanguage();
        thenTheScreenIs("transfers");
        thenThereIsNoWindowPlace();
        thenTheWindowIsMaximised(false);
    }

    @Test
    void shouldKeepTheSettingsThroughASave() throws Exception {
        givenTheSettings();
        givenTheLookChangedTo(Themes.Family.DRACULA, GuiSettings.Mode.DARK, Locale.ITALIAN);
        givenTheWindowChangedTo("search", 10, 20, 800, 600, true);
        givenTheSettingsSaved();
        givenTheSettingsDidNotExistBefore();

        whenTheSettingsAreLoadedAgain();

        thenTheSettingsExisted();
        thenTheLoadedLookIs(Themes.Family.DRACULA, GuiSettings.Mode.DARK, Locale.ITALIAN);
        thenTheLoadedWindowIs("search", 10, 20, 800, 600, true);
    }

    @Test
    void shouldFallBackToTheDefaultsForBrokenSettings() throws Exception {
        givenTheSettingsFile("theme=pink\nmode=\nwindow.x=a\nwindow.y=1\nwindow.width=50\nwindow.height=50\n");

        whenTheSettingsAreLoaded();

        thenTheThemeIs(Themes.Family.NORD, GuiSettings.Mode.SYSTEM);
        thenThereIsNoWindowPlace();
    }

    @Test
    void shouldUnderstandALanguageWrittenWithAnUnderscore() throws Exception {
        givenTheSettingsFile("language=it_IT\n");

        whenTheSettingsAreLoaded();

        thenTheLanguageIs("it");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "window.x=NaN\nwindow.y=0\nwindow.width=800\nwindow.height=600\n",
                "window.x=0\nwindow.y=-Infinity\nwindow.width=800\nwindow.height=600\n",
                "window.x=0\nwindow.y=0\nwindow.width=Infinity\nwindow.height=600\n"
            })
    void shouldLeaveWindowBoundsThatAreNoNumbers(String broken) throws Exception {
        givenTheSettingsFile(broken);

        whenTheSettingsAreLoaded();

        thenThereIsNoWindowPlace(broken);
    }

    @ParameterizedTest
    @EnumSource(Themes.Family.class)
    void shouldHaveADarkThemeInEveryFamily(Themes.Family family) {
        whenTheThemeIsTaken(family, true);

        thenTheThemeIsDark(true);
    }

    @ParameterizedTest
    @EnumSource(Themes.Family.class)
    void shouldHaveALightThemeInEveryFamily(Themes.Family family) {
        whenTheThemeIsTaken(family, false);

        thenTheThemeHasAStylesheet();
    }

    @Test
    void shouldHaveALightNordThemeThatIsNotDark() {
        whenTheThemeIsTaken(Themes.Family.NORD, false);

        thenTheThemeIsDark(false);
    }

    @ParameterizedTest
    @CsvSource({"Windows 11, explorer.exe", "Mac OS X, open", "Linux, xdg-open"})
    void shouldOpenAFolderInTheFileManagerOfEachSystem(String os, String program) {
        whenTheFolderIsOpenedOn(os);

        thenTheCommandIs(program, FOLDER.toString());
    }

    @Test
    void shouldPassEachArgumentOfThePlayerApartWithTheFileLast() {
        whenThePlayerIsStarted("C:\\VLC\\vlc.exe", " --fullscreen  --no-audio ");

        thenEachArgumentIsApartWithTheFileLast(
                "C:\\VLC\\vlc.exe",
                "--fullscreen",
                "--no-audio",
                FILE.toAbsolutePath().toString());
    }

    @Test
    void shouldStartAPlayerWithoutArgumentsOnTheFile() {
        whenThePlayerIsStarted("mpv", "");

        thenTheCommandIs("mpv", FILE.toAbsolutePath().toString());
    }

    @Test
    void shouldSelectTheFileInTheFileManagerOfWindows() {
        whenTheFileIsRevealedOn("Windows 11");

        thenTheFileIsSelectedBy("explorer.exe", "/select,", FILE.toString());
    }

    @Test
    void shouldRevealTheFileInTheFileManagerOfMacOs() {
        whenTheFileIsRevealedOn("Mac OS X");

        thenTheCommandIs("open", "-R", FILE.toString());
    }

    @Test
    void shouldOpenTheFolderOfTheFileOnLinux() {
        whenTheFileIsRevealedOn("Linux");

        thenTheCommandIs("xdg-open", FOLDER.toString());
    }

    @Test
    void shouldDropTheOutputOfThePlayer() {
        whenThePlayerProcessIsPrepared("vlc", "-v");

        thenItsOutputIsDropped();
    }

    @Test
    void shouldDropTheOutputOfTheFileManager() {
        whenTheFileManagerProcessIsPreparedOn("Linux");

        thenItsOutputIsDropped();
    }

    @Test
    void shouldUnquoteAPlayerPathCopiedWithDoubleQuotes() {
        whenThePlayerIsStarted(" \"C:\\Program Files\\VLC\\vlc.exe\" ", "--fullscreen");

        thenTheCommandIs(
                "C:\\Program Files\\VLC\\vlc.exe",
                "--fullscreen",
                FILE.toAbsolutePath().toString());
    }

    @Test
    void shouldUnquoteAPlayerPathCopiedWithSingleQuotes() {
        whenThePlayerIsStarted("'/opt/my player/mpv'", "");

        thenTheCommandIs("/opt/my player/mpv", FILE.toAbsolutePath().toString());
    }

    @Test
    void shouldTakeANamedPlayerAsSet() {
        whenAskedWhetherThePlayerIsSet("mpv");

        thenThePlayerIsSet();
    }

    @ParameterizedTest
    @CsvSource({"'   ', blank", "' \"\" ', only quotes"})
    void shouldNotTakeAPlayerWithoutANameAsSet(String player, String why) {
        whenAskedWhetherThePlayerIsSet(player);

        thenThePlayerIsNotSet(why);
    }

    static Stream<Arguments> sizes() {
        return Stream.of(
                Arguments.of(Locale.ENGLISH, -5L, "0 B"),
                Arguments.of(Locale.ENGLISH, 999L, "999 B"),
                Arguments.of(Locale.ENGLISH, 1024L, "1.00 KB"),
                Arguments.of(Locale.ENGLISH, 150L * 1024, "150.0 KB"),
                Arguments.of(Locale.ENGLISH, 5L * 1024 * 1024 * 1024, "5.00 GB"),
                Arguments.of(Locale.ENGLISH, 1023L * 1024 * 1024 + 1_000_000, "1,024 MB"),
                Arguments.of(Locale.ITALIAN, 1536L, "1,50 KB"));
    }

    static Stream<Arguments> counts() {
        return Stream.of(
                Arguments.of(Locale.ENGLISH, 1234567L, "1,234,567"),
                Arguments.of(Locale.ITALIAN, 1234567L, "1.234.567"));
    }

    static Stream<Arguments> durations() {
        return Stream.of(
                Arguments.of(9L, "9 secs"),
                Arguments.of(247L, "4:07 mins"),
                Arguments.of(3900L, "1:05 h"),
                Arguments.of(2L * 86400 + 3 * 3600, "2 d 3 h"));
    }

    private void givenTheMoment(String instant) {
        this.moment = Instant.parse(instant);
    }

    private void givenTheFormatsOf(Locale language) {
        this.formats = new Formats(language);
    }

    private void givenTheSettings() {
        this.settings = GuiSettings.load(this.dir);
    }

    private void givenTheSettingsFile(String content) throws IOException {
        Files.writeString(this.dir.resolve(GuiSettings.FILE_NAME), content);
    }

    private void givenExitConfirmedAndStartMinimised(boolean confirmExit, boolean startMinimised) {
        this.settings.setConfirmExit(confirmExit);
        this.settings.setStartMinimized(startMinimised);
    }

    private void givenNumberedEntriesRemembered(String name, String prefix, int count) {
        for (int i = 1; i <= count; i++) {
            this.settings.remember(name, prefix + i);
        }
    }

    private void givenAnEntryRemembered(String name, String entry) {
        this.settings.remember(name, entry);
    }

    private void givenTheLookChangedTo(Themes.Family theme, GuiSettings.Mode mode, Locale language) {
        this.settings.setTheme(theme);
        this.settings.setMode(mode);
        this.settings.setLanguage(Optional.of(language));
    }

    private void givenTheWindowChangedTo(
            String screen, double x, double y, double width, double height, boolean maximised) {
        this.settings.setScreen(screen);
        this.settings.setWindow(x, y, width, height);
        this.settings.setMaximized(maximised);
    }

    private void givenTheSettingsSaved() throws IOException {
        this.settings.save();
    }

    private void givenTheSettingsDidNotExistBefore() {
        assertThat(this.settings.existed()).isFalse();
    }

    private void whenItIsWrittenAsDateAndTime(Locale locale, int hoursFromUtc) {
        this.text = Formats.dateTime(locale, ZoneOffset.ofHours(hoursFromUtc)).format(this.moment);
    }

    private void whenItIsWrittenAsTimeOfTheDay(Locale locale, int hoursFromUtc) {
        this.text = Formats.time(locale, ZoneOffset.ofHours(hoursFromUtc)).format(this.moment);
    }

    private void whenASizeIsWritten(long bytes) {
        this.text = this.formats.size(bytes);
    }

    private void whenARateIsWritten(long bytesPerSecond) {
        this.text = this.formats.rate(bytesPerSecond);
    }

    private void whenACountIsWritten(long value) {
        this.text = this.formats.count(value);
    }

    private void whenAPercentageIsWritten(long part, long whole) {
        this.text = this.formats.percent(part, whole);
    }

    private void whenADurationIsWritten(long seconds) {
        this.text = this.formats.duration(seconds);
    }

    private void whenTheSettingsAreLoaded() {
        this.settings = GuiSettings.load(this.dir);
    }

    private void whenTheSettingsAreLoadedAgain() {
        this.loaded = GuiSettings.load(this.dir);
    }

    private void whenTheHistoryIsLoaded(String name) {
        this.history = GuiSettings.load(this.dir).history(name);
    }

    private void whenTheThemeIsTaken(Themes.Family family, boolean dark) {
        this.theme = Themes.theme(family, dark);
    }

    private void whenTheFolderIsOpenedOn(String os) {
        this.command = Folders.command(os, FOLDER);
    }

    private void whenTheFileIsRevealedOn(String os) {
        this.command = Folders.revealCommand(os, FILE);
    }

    private void whenThePlayerIsStarted(String player, String arguments) {
        this.command = Preview.command(player, arguments, FILE);
    }

    // Never started here: only how they would start.
    private void whenThePlayerProcessIsPrepared(String player, String arguments) {
        this.process = Preview.process(player, arguments, FILE);
    }

    private void whenTheFileManagerProcessIsPreparedOn(String os) {
        this.process = Folders.process(Folders.command(os, FILE));
    }

    private void whenAskedWhetherThePlayerIsSet(String player) {
        this.set = Preview.isSet(player);
    }

    private void thenTheTextIs(String text) {
        assertThat(this.text).isEqualTo(text);
    }

    private void thenTheTextStartsWith(String start) {
        assertThat(this.text).startsWith(start);
    }

    private void thenTheTextEndsWith(String end) {
        assertThat(this.text).endsWith(end);
    }

    private void thenTheTextContains(String part) {
        assertThat(this.text).contains(part);
    }

    private void thenTheSizeRoundedFromReads(String rounded, String shown) {
        assertThat(this.text).as(rounded).isEqualTo(shown);
    }

    private void thenExitIsConfirmed(boolean confirmed) {
        assertThat(this.settings.confirmExit()).as("on by default").isEqualTo(confirmed);
    }

    private void thenTheWindowStartsMinimised(boolean minimised) {
        assertThat(this.settings.startMinimized()).isEqualTo(minimised);
    }

    private void thenExitIsConfirmedAgain(boolean confirmed) {
        assertThat(this.loaded.confirmExit()).isEqualTo(confirmed);
    }

    private void thenTheWindowStartsMinimisedAgain(boolean minimised) {
        assertThat(this.loaded.startMinimized()).isEqualTo(minimised);
    }

    private void thenTheHistoryHasEntries(int count) {
        assertThat(this.history).hasSize(count);
    }

    private void thenTheHistoryStartsOnceWith(String entry) {
        assertThat(this.history).first().isEqualTo(entry);
        assertThat(this.history).containsOnlyOnce(entry);
    }

    private void thenTheHistoryLacks(String... entries) {
        assertThat(this.history).doesNotContain(entries);
    }

    private void thenTheHistoryOfIsEmpty(String name) {
        assertThat(this.settings.history(name)).isEmpty();
    }

    private void thenTheThemeIs(Themes.Family theme, GuiSettings.Mode mode) {
        assertThat(this.settings.theme()).isEqualTo(theme);
        assertThat(this.settings.mode()).isEqualTo(mode);
    }

    private void thenThereIsNoLanguage() {
        assertThat(this.settings.language()).isEmpty();
    }

    private void thenTheLanguageIs(String language) {
        assertThat(this.settings.language()).map(Locale::getLanguage).contains(language);
    }

    private void thenTheScreenIs(String screen) {
        assertThat(this.settings.screen()).isEqualTo(screen);
    }

    private void thenThereIsNoWindowPlace() {
        assertThat(this.settings.window()).isEmpty();
    }

    private void thenThereIsNoWindowPlace(String why) {
        assertThat(this.settings.window()).as(why).isEmpty();
    }

    private void thenTheWindowIsMaximised(boolean maximised) {
        assertThat(this.settings.maximized()).isEqualTo(maximised);
    }

    private void thenTheSettingsExisted() {
        assertThat(this.loaded.existed()).isTrue();
    }

    private void thenTheLoadedLookIs(Themes.Family theme, GuiSettings.Mode mode, Locale language) {
        assertThat(this.loaded.theme()).isEqualTo(theme);
        assertThat(this.loaded.mode()).isEqualTo(mode);
        assertThat(this.loaded.language()).contains(language);
    }

    private void thenTheLoadedWindowIs(
            String screen, double x, double y, double width, double height, boolean maximised) {
        assertThat(this.loaded.screen()).isEqualTo(screen);
        assertThat(this.loaded.window()).hasValueSatisfying(b -> assertThat(b).containsExactly(x, y, width, height));
        assertThat(this.loaded.maximized()).isEqualTo(maximised);
    }

    private void thenTheThemeIsDark(boolean dark) {
        assertThat(this.theme.isDarkMode()).isEqualTo(dark);
    }

    private void thenTheThemeHasAStylesheet() {
        assertThat(this.theme.getUserAgentStylesheet()).isNotBlank();
    }

    private void thenTheCommandIs(String... parts) {
        assertThat(this.command).containsExactly(parts);
    }

    private void thenEachArgumentIsApartWithTheFileLast(String... parts) {
        assertThat(this.command).as("each argument apart, the file last").containsExactly(parts);
    }

    private void thenTheFileIsSelectedBy(String... parts) {
        assertThat(this.command).as("the file selected").containsExactly(parts);
    }

    private void thenItsOutputIsDropped() {
        assertThat(this.process.redirectOutput())
                .as("a verbose program never fills a pipe")
                .isEqualTo(Redirect.DISCARD);
        assertThat(this.process.redirectError()).isEqualTo(Redirect.DISCARD);
    }

    private void thenThePlayerIsSet() {
        assertThat(this.set).isTrue();
    }

    private void thenThePlayerIsNotSet(String why) {
        assertThat(this.set).as(why).isFalse();
    }
}
