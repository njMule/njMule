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
package org.njmule.webui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Password hashes, sessions, settings and page building. */
class WebUiPartsTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong();
    private final Sessions sessions = new Sessions(new SecureRandom(), this.now::get);
    private String stored;
    private String hash;
    private boolean matches;
    private char[] password;
    private Sessions.Session session;
    private boolean validForm;
    private boolean allowed;
    private boolean lanAllowed;
    private boolean trusted;
    private WebUiSettings chosen;
    private WebUiSettings settings;
    private String text;
    private Throwable exception;

    @Test
    void shouldHashAPasswordWithPbkdf2AndTheDefaultRounds() {
        whenThePasswordIsHashed("secret");

        thenTheHashStartsWith("pbkdf2-sha512$" + Passwords.ROUNDS + "$");
    }

    @Test
    void shouldMatchTheHashedPassword() {
        givenAStoredHashOf("secret");

        whenThePasswordIsChecked("secret");

        thenThePasswordMatches(true);
    }

    @Test
    void shouldNotMatchThePasswordWrittenWithOtherCases() {
        givenAStoredHashOf("secret");

        whenThePasswordIsChecked("Secret");

        thenThePasswordMatches(false);
    }

    @Test
    void shouldSaltEveryHash() {
        givenAStoredHashOf("secret");

        whenThePasswordIsHashed("secret");

        thenTheHashDiffersFromTheStoredOne();
    }

    @Test
    void shouldMatchAHashOfTheGivenSaltAndRounds() {
        givenAStoredHashOf("x", new byte[16], 10);

        whenThePasswordIsChecked("x");

        thenThePasswordMatches(true);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "md5$1$a$b", "pbkdf2-sha512$0$AA$AA", "pbkdf2-sha512$ten$AA$AA", "pbkdf2-sha512$10$%%$AA"})
    void shouldNotMatchAStoredHashItCannotRead(String unreadable) {
        givenTheStoredHash(unreadable);

        whenThePasswordIsChecked("x");

        thenThePasswordMatches(false);
    }

    @Test
    void shouldNotMatchAStoredHashOfTooManyRoundsToCheck() {
        givenTheStoredHash("pbkdf2-sha512$10000001$AA$AA");

        whenThePasswordIsChecked("x");

        thenThePasswordMatches(false, "too many rounds to check");
    }

    @Test
    void shouldWipeAPasswordOnceUsed() {
        givenThePassword("wipe me");

        whenThePasswordIsWiped();

        thenThePasswordHolds("\0".repeat(7));
    }

    @Test
    void shouldOpenASessionFoundByItsIdWithAFormTokenOfItsOwn() {
        whenASessionIsOpened();

        thenTheSessionIdHasTheLengthAndDiffersFromTheFormToken(22);
        thenTheSessionIsOpen();
        thenNoSessionIsFoundFor("unknown");
    }

    @Test
    void shouldTakeAFormWithTheTokenOfTheSession() {
        givenASessionOpen();

        whenAFormComesWithTheTokenOfTheSessionFollowedBy("");

        thenTheFormIsValid(true);
    }

    @Test
    void shouldRefuseAFormWithAnotherToken() {
        givenASessionOpen();

        whenAFormComesWithTheTokenOfTheSessionFollowedBy("x");

        thenTheFormIsValid(false);
    }

    @Test
    void shouldRefuseAFormWithoutToken() {
        givenASessionOpen();

        whenAFormComesWithoutToken();

        thenTheFormIsValid(false);
    }

    @Test
    void shouldKeepASessionWithinTheIdleTime() {
        givenASessionOpen();

        whenTimePasses(Sessions.IDLE.toNanos() - 1);

        thenTheSessionIsOpen();
    }

    @Test
    void shouldEndASessionLeftIdle() {
        givenASessionOpen();
        givenTimePassed(Sessions.IDLE.toNanos() - 1);
        givenTheSessionIsStillOpen();

        whenTimePasses(Sessions.IDLE.toNanos());

        thenTheSessionIsGone();
        thenTheOpenSessionsAre(0);
    }

    @Test
    void shouldEndASessionThatIsClosed() {
        givenASessionOpen();

        whenTheSessionIsClosed();

        thenTheSessionIsGone();
    }

    @Test
    void shouldCountTheAttemptsBeforeTheirPasswordIsChecked() {
        givenAttemptsFrom("10.0.0.1", Sessions.MAX_FAILURES);

        whenAnAttemptComesFrom("10.0.0.1");

        thenTheAttemptIsAllowed(false, "attempts count before their password is checked: parallel ones too");
    }

    @Test
    void shouldLetAnotherAddressTryWhileOneIsLockedOut() {
        givenAttemptsFrom("10.0.0.1", Sessions.MAX_FAILURES);

        whenAnAttemptComesFrom("10.0.0.2");

        thenTheAttemptIsAllowed(true);
    }

    @Test
    void shouldLetAnAddressTryAgainOnceTheLockoutIsOver() {
        givenAttemptsFrom("10.0.0.1", Sessions.MAX_FAILURES);
        givenTimePassed(Sessions.LOCKOUT.toNanos());

        whenAnAttemptComesFrom("10.0.0.1");

        thenTheAttemptIsAllowed(true);
    }

    @Test
    void shouldForgetTheAttemptsOfAnAddressThatLogsIn() {
        givenAttemptsFrom("10.0.0.2", Sessions.MAX_FAILURES);

        whenTheLoginSucceedsFrom("10.0.0.2");

        thenTheAddressMayTryAgain("10.0.0.2", Sessions.MAX_FAILURES);
    }

    @Test
    void shouldKeepTheAttemptsOfABoundedNumberOfAddresses() {
        whenAttemptsComeFromAddresses(Sessions.MAX_ADDRESSES + 10);

        thenTheAddressesCountedAre(Sessions.MAX_ADDRESSES);
    }

    @Test
    void shouldForgetTheOldestAddressFirst() {
        givenAttemptsFromAddresses(Sessions.MAX_ADDRESSES + 10);

        whenAnAttemptComesFrom("10.0.0.0");

        thenTheAttemptIsAllowed(true, "the oldest went first");
    }

    @Test
    void shouldForgetTheAddressesOnceTheLockoutIsOver() {
        givenAttemptsFromAddresses(Sessions.MAX_ADDRESSES + 10);
        givenTimePassed(Sessions.LOCKOUT.toNanos());

        whenAnAttemptComesFrom("10.1.0.1");

        thenTheAddressesCountedAre(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:4711", "127.0.0.1:4711", "[::1]:4711"})
    void shouldLetTheLoopbackHostsInWhileTheLanIsOff(String host) {
        givenTheLanAllowed(false);

        whenTheHostIsChecked(host);

        thenTheHostIsTrusted(true);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "192.168.1.10:4711")
    void shouldKeepTheOtherHostsOutWhileTheLanIsOff(String host) {
        givenTheLanAllowed(false);

        whenTheHostIsChecked(host);

        thenTheHostIsTrusted(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.10:4711", "192.168.1.10", "[fe80::1]:4711", "LOCALHOST:4711"})
    void shouldLetAddressesAndLoopbackNamesInWhileTheLanIsAllowed(String host) {
        givenTheLanAllowed(true);

        whenTheHostIsChecked(host);

        thenTheHostIsTrusted(true);
    }

    // A name of the attacker's that resolves to this computer: DNS rebinding.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"rebound.example:4711", "192.168.1.10.nip.io:4711", "[rebound.example]:4711"})
    void shouldKeepOtherNamesOutWhileTheLanIsAllowed(String host) {
        givenTheLanAllowed(true);

        whenTheHostIsChecked(host);

        thenTheHostIsTrusted(false);
    }

    @Test
    void shouldKeepOutAHostWithAnUnclosedBracket() {
        givenTheLanAllowed(true);

        whenTheHostIsChecked("[fe80::1");

        thenTheHostIsTrusted(false, "an unclosed bracket");
    }

    @Test
    void shouldLoadTheDefaultsWhenNothingWasSaved() throws IOException {
        whenTheSettingsAreLoaded();

        thenTheSettingsAre(WebUiSettings.DEFAULTS);
        thenTheSettingsAreUsable(false);
    }

    @Test
    void shouldLoadTheSettingsSaved() throws IOException {
        givenTheSettingsSaved(true, 8080, true, "pbkdf2-sha512$1$AA$AA");

        whenTheSettingsAreLoaded();

        thenTheSettingsAreTheChosenOnes();
        thenTheSettingsAreUsable(true);
    }

    @Test
    void shouldHideThePasswordHashInTheTextOfTheSettings() {
        givenTheSettings(true, 8080, true, "pbkdf2-sha512$1$AA$AA");

        whenTheSettingsAreShownAsText();

        thenTheTextDoesNotContain("pbkdf2");
    }

    @Test
    void shouldNotUseSettingsWithoutAPassword() {
        whenTheSettingsAreMade(true, 80, false, "");

        thenTheSettingsAreUsable(false);
    }

    @Test
    void shouldRefuseSettingsOfPortZero() {
        whenTheSettingsAreMade(true, 0, false, "");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldLoadTheDefaultsFromAFileOfAPortOutOfRange() throws IOException {
        givenTheSettingsFileHolds("port=99999\n");

        whenTheSettingsAreLoaded();

        thenTheSettingsAre(WebUiSettings.DEFAULTS);
    }

    @Test
    void shouldLetOnlyTheOwnerReadThePasswordHash() throws IOException {
        givenTheSettingsFileHolds("enabled=false\n");

        whenTheSettingsAreSaved(true, 8080, false, "pbkdf2-sha512$1$AA$AA");

        thenOnlyTheOwnerMayReadTheSettingsFile();
    }

    @Test
    void shouldEscapeText() {
        whenTheTextIsEscaped("<a href='x'>\"&\"</a>");

        thenTheTextIs("&lt;a href=&#39;x&#39;&gt;&quot;&amp;&quot;&lt;/a&gt;");
    }

    @Test
    void shouldLeaveTextWithNothingToEscapeAsItIs() {
        whenTheTextIsEscaped("Ubuntu 24.04 – 東京.iso");

        thenTheTextIs("Ubuntu 24.04 – 東京.iso");
    }

    @ParameterizedTest
    @MethodSource("sizes")
    void shouldShowASizeInTheLargestUnitThatFits(long bytes, String shown) {
        whenTheSizeIsShown(bytes);

        thenTheTextIs(shown);
    }

    @Test
    void shouldShowASizePastTerabytesStillInTerabytes() {
        whenTheSizeIsShown(2048L << 40);

        thenTheTextIs("2048.00 TB", "no unit past TB");
    }

    @ParameterizedTest
    @MethodSource("rates")
    void shouldShowARateInTheLargestUnitThatFits(long bytesPerSecond, String shown) {
        whenTheRateIsShown(bytesPerSecond);

        thenTheTextIs(shown);
    }

    @Test
    void shouldEscapeTheNameAndValueOfAHiddenField() {
        whenAHiddenFieldIsMade("a\"", "<b>");

        thenTheTextIs("<input type=\"hidden\" name=\"a&quot;\" value=\"&lt;b&gt;\">");
    }

    @Test
    void shouldMakeAPageWithoutNavigationOutsideASession() {
        whenAPageIsMade("T<", "", "body");

        thenTheTextContains("<h1>T&lt;</h1>");
        thenTheTextDoesNotContain("nav");
    }

    @Test
    void shouldMakeAPageWithNavigationAndTheFormTokenInASession() {
        whenAPageIsMade("T", "tok", "body");

        thenTheTextContains("<nav>", "value=\"tok\"");
    }

    private static Stream<Arguments> sizes() {
        return Stream.of(arguments(512L, "512 B"), arguments(1536L, "1.50 KB"), arguments(5L << 40, "5.00 TB"));
    }

    private static Stream<Arguments> rates() {
        return Stream.of(
                arguments(2048L, "2.0 KB/s"),
                arguments(1_355_000L, "1.29 MB/s"),
                arguments(100L << 20, "100.0 MB/s"),
                arguments(1_181_116_006L, "1.10 GB/s"));
    }

    private void givenAStoredHashOf(String plain) {
        this.stored = Passwords.hash(plain.toCharArray(), new SecureRandom());
    }

    private void givenAStoredHashOf(String plain, byte[] salt, int rounds) {
        this.stored = Passwords.hash(plain.toCharArray(), salt, rounds);
    }

    private void givenTheStoredHash(String hashed) {
        this.stored = hashed;
    }

    private void givenThePassword(String plain) {
        this.password = plain.toCharArray();
    }

    private void givenASessionOpen() {
        this.session = this.sessions.open();
    }

    // A lookup notes the use of the session.
    private void givenTheSessionIsStillOpen() {
        assertThat(this.sessions.find(this.session.id())).isPresent();
    }

    private void givenTimePassed(long nanos) {
        this.now.addAndGet(nanos);
    }

    private void givenAttemptsFrom(String address, int count) {
        assertMayTry(address, count);
    }

    private void givenAttemptsFromAddresses(int count) {
        tryFromAddresses(count);
    }

    private void givenTheLanAllowed(boolean allowed) {
        this.lanAllowed = allowed;
    }

    private void givenTheSettings(boolean enabled, int port, boolean lan, String passwordHash) {
        this.chosen = new WebUiSettings(enabled, port, lan, passwordHash);
    }

    private void givenTheSettingsSaved(boolean enabled, int port, boolean lan, String passwordHash) throws IOException {
        this.chosen = new WebUiSettings(enabled, port, lan, passwordHash);
        this.chosen.save(this.dir);
    }

    private void givenTheSettingsFileHolds(String content) throws IOException {
        Files.writeString(this.dir.resolve(WebUiSettings.FILE_NAME), content, StandardCharsets.UTF_8);
    }

    private void whenThePasswordIsHashed(String plain) {
        this.hash = Passwords.hash(plain.toCharArray(), new SecureRandom());
    }

    private void whenThePasswordIsChecked(String plain) {
        this.matches = Passwords.matches(plain.toCharArray(), this.stored);
    }

    private void whenThePasswordIsWiped() {
        Passwords.wipe(this.password);
    }

    private void whenASessionIsOpened() {
        this.session = this.sessions.open();
    }

    private void whenAFormComesWithTheTokenOfTheSessionFollowedBy(String tail) {
        this.validForm = Sessions.validForm(this.session, this.session.csrf() + tail);
    }

    private void whenAFormComesWithoutToken() {
        this.validForm = Sessions.validForm(this.session, null);
    }

    private void whenTimePasses(long nanos) {
        this.now.addAndGet(nanos);
    }

    private void whenTheSessionIsClosed() {
        this.sessions.close(this.session.id());
    }

    private void whenAnAttemptComesFrom(String address) {
        this.allowed = this.sessions.mayTry(address);
    }

    private void whenTheLoginSucceedsFrom(String address) {
        this.sessions.succeeded(address);
    }

    private void whenAttemptsComeFromAddresses(int count) {
        tryFromAddresses(count);
    }

    private void whenTheHostIsChecked(String host) {
        this.trusted = WebUi.trustedHost(host, this.lanAllowed);
    }

    private void whenTheSettingsAreLoaded() throws IOException {
        this.settings = WebUiSettings.load(this.dir);
    }

    private void whenTheSettingsAreSaved(boolean enabled, int port, boolean lan, String passwordHash)
            throws IOException {
        new WebUiSettings(enabled, port, lan, passwordHash).save(this.dir);
    }

    private void whenTheSettingsAreMade(boolean enabled, int port, boolean lan, String passwordHash) {
        this.exception = catchThrowable(() -> this.settings = new WebUiSettings(enabled, port, lan, passwordHash));
    }

    private void whenTheSettingsAreShownAsText() {
        this.text = this.chosen.toString();
    }

    private void whenTheTextIsEscaped(String value) {
        this.text = Html.text(value);
    }

    private void whenTheSizeIsShown(long bytes) {
        this.text = Html.size(bytes);
    }

    private void whenTheRateIsShown(long bytesPerSecond) {
        this.text = Html.rate(bytesPerSecond);
    }

    private void whenAHiddenFieldIsMade(String name, String value) {
        this.text = Html.hidden(name, value);
    }

    private void whenAPageIsMade(String title, String csrf, String content) {
        this.text = Html.page(title, csrf, content);
    }

    private void thenTheHashStartsWith(String prefix) {
        assertThat(this.hash).startsWith(prefix);
    }

    private void thenTheHashDiffersFromTheStoredOne() {
        assertThat(this.hash).isNotEqualTo(this.stored);
    }

    private void thenThePasswordMatches(boolean expected) {
        assertThat(this.matches).isEqualTo(expected);
    }

    private void thenThePasswordMatches(boolean expected, String why) {
        assertThat(this.matches).as(why).isEqualTo(expected);
    }

    private void thenThePasswordHolds(String expected) {
        assertThat(new String(this.password)).isEqualTo(expected);
    }

    private void thenTheSessionIdHasTheLengthAndDiffersFromTheFormToken(int length) {
        assertThat(this.session.id()).hasSize(length).isNotEqualTo(this.session.csrf());
    }

    private void thenTheSessionIsOpen() {
        assertThat(this.sessions.find(this.session.id())).contains(this.session);
    }

    private void thenTheSessionIsGone() {
        assertThat(this.sessions.find(this.session.id())).isEmpty();
    }

    private void thenNoSessionIsFoundFor(String id) {
        assertThat(this.sessions.find(id)).isEmpty();
    }

    private void thenTheOpenSessionsAre(int count) {
        assertThat(this.sessions.count()).isEqualTo(count);
    }

    private void thenTheFormIsValid(boolean expected) {
        assertThat(this.validForm).isEqualTo(expected);
    }

    private void thenTheAttemptIsAllowed(boolean expected) {
        assertThat(this.allowed).isEqualTo(expected);
    }

    private void thenTheAttemptIsAllowed(boolean expected, String why) {
        assertThat(this.allowed).as(why).isEqualTo(expected);
    }

    private void thenTheAddressMayTryAgain(String address, int times) {
        assertMayTry(address, times);
    }

    private void thenTheAddressesCountedAre(int count) {
        assertThat(this.sessions.addresses()).isEqualTo(count);
    }

    private void thenTheHostIsTrusted(boolean expected) {
        assertThat(this.trusted).isEqualTo(expected);
    }

    private void thenTheHostIsTrusted(boolean expected, String why) {
        assertThat(this.trusted).as(why).isEqualTo(expected);
    }

    private void thenTheSettingsAre(WebUiSettings expected) {
        assertThat(this.settings).isEqualTo(expected);
    }

    private void thenTheSettingsAreTheChosenOnes() {
        assertThat(this.settings).isEqualTo(this.chosen);
    }

    private void thenTheSettingsAreUsable(boolean expected) {
        assertThat(this.settings.usable()).isEqualTo(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenOnlyTheOwnerMayReadTheSettingsFile() throws IOException {
        Path file = this.dir.resolve(WebUiSettings.FILE_NAME);
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    private void thenTheTextIs(String expected) {
        assertThat(this.text).isEqualTo(expected);
    }

    private void thenTheTextIs(String expected, String why) {
        assertThat(this.text).as(why).isEqualTo(expected);
    }

    private void thenTheTextContains(String... parts) {
        assertThat(this.text).contains(parts);
    }

    private void thenTheTextDoesNotContain(String part) {
        assertThat(this.text).doesNotContain(part);
    }

    private void assertMayTry(String address, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(this.sessions.mayTry(address)).isTrue();
        }
    }

    private void tryFromAddresses(int count) {
        for (int i = 0; i < count; i++) {
            this.sessions.mayTry("10.0." + (i >> 8) + "." + (i & 0xFF));
        }
    }
}
