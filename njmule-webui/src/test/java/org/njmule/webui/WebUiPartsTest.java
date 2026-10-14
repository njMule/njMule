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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Password hashes, sessions, settings and page building. */
class WebUiPartsTest {

    @TempDir
    Path dir;

    @Test
    void hashesPasswordsWithSaltAndRounds() {
        String stored = Passwords.hash("secret".toCharArray(), new SecureRandom());
        assertThat(stored).startsWith("pbkdf2-sha512$" + Passwords.ROUNDS + "$");
        assertThat(Passwords.matches("secret".toCharArray(), stored)).isTrue();
        assertThat(Passwords.matches("Secret".toCharArray(), stored)).isFalse();
        assertThat(Passwords.hash("secret".toCharArray(), new SecureRandom())).isNotEqualTo(stored);

        String fast = Passwords.hash("x".toCharArray(), new byte[16], 10);
        assertThat(Passwords.matches("x".toCharArray(), fast)).isTrue();
        assertThat(Passwords.matches("x".toCharArray(), "")).isFalse();
        assertThat(Passwords.matches("x".toCharArray(), "md5$1$a$b")).isFalse();
        assertThat(Passwords.matches("x".toCharArray(), "pbkdf2-sha512$0$AA$AA"))
                .isFalse();
        assertThat(Passwords.matches("x".toCharArray(), "pbkdf2-sha512$ten$AA$AA"))
                .isFalse();
        assertThat(Passwords.matches("x".toCharArray(), "pbkdf2-sha512$10$%%$AA"))
                .isFalse();

        char[] used = "wipe me".toCharArray();
        Passwords.wipe(used);
        assertThat(new String(used)).isEqualTo("\0".repeat(7));
    }

    @Test
    void sessionsEndWhenIdleAndFailuresWait() {
        AtomicLong now = new AtomicLong();
        Sessions sessions = new Sessions(new SecureRandom(), now::get);
        Sessions.Session one = sessions.open();
        assertThat(one.id()).hasSize(22).isNotEqualTo(one.csrf());
        assertThat(sessions.find(one.id())).contains(one);
        assertThat(sessions.find("unknown")).isEmpty();
        assertThat(Sessions.validForm(one, one.csrf())).isTrue();
        assertThat(Sessions.validForm(one, one.csrf() + "x")).isFalse();
        assertThat(Sessions.validForm(one, null)).isFalse();

        now.addAndGet(Sessions.IDLE.toNanos() - 1);
        assertThat(sessions.find(one.id())).isPresent();
        now.addAndGet(Sessions.IDLE.toNanos());
        assertThat(sessions.find(one.id())).isEmpty();
        assertThat(sessions.count()).isZero();

        Sessions.Session two = sessions.open();
        sessions.close(two.id());
        assertThat(sessions.find(two.id())).isEmpty();

        for (int i = 0; i < Sessions.MAX_FAILURES; i++) {
            assertThat(sessions.mayTry("10.0.0.1")).isTrue();
        }
        assertThat(sessions.mayTry("10.0.0.1"))
                .as("attempts count before their password is checked: parallel ones too")
                .isFalse();
        assertThat(sessions.mayTry("10.0.0.2")).isTrue();
        now.addAndGet(Sessions.LOCKOUT.toNanos());
        assertThat(sessions.mayTry("10.0.0.1")).isTrue();
        sessions.succeeded("10.0.0.2");
        for (int i = 0; i < Sessions.MAX_FAILURES; i++) {
            assertThat(sessions.mayTry("10.0.0.2")).isTrue();
        }
    }

    @Test
    void addressesThatFailedAreForgottenAndBounded() {
        AtomicLong now = new AtomicLong();
        Sessions sessions = new Sessions(new SecureRandom(), now::get);
        for (int i = 0; i < Sessions.MAX_ADDRESSES + 10; i++) {
            sessions.mayTry("10.0." + (i >> 8) + "." + (i & 0xFF));
        }
        assertThat(sessions.addresses()).isEqualTo(Sessions.MAX_ADDRESSES);
        assertThat(sessions.mayTry("10.0.0.0")).as("the oldest went first").isTrue();

        now.addAndGet(Sessions.LOCKOUT.toNanos());
        sessions.mayTry("10.1.0.1");
        assertThat(sessions.addresses()).isEqualTo(1);
    }

    @Test
    void onlyAddressesAndLoopbackNamesReachTheInterface() {
        assertThat(WebUi.trustedHost("localhost:4711", false)).isTrue();
        assertThat(WebUi.trustedHost("127.0.0.1:4711", false)).isTrue();
        assertThat(WebUi.trustedHost("[::1]:4711", false)).isTrue();
        assertThat(WebUi.trustedHost("192.168.1.10:4711", false)).isFalse();
        assertThat(WebUi.trustedHost(null, false)).isFalse();

        assertThat(WebUi.trustedHost("192.168.1.10:4711", true)).isTrue();
        assertThat(WebUi.trustedHost("192.168.1.10", true)).isTrue();
        assertThat(WebUi.trustedHost("[fe80::1]:4711", true)).isTrue();
        assertThat(WebUi.trustedHost("LOCALHOST:4711", true)).isTrue();
        // A name of the attacker's that resolves to this computer: DNS rebinding.
        assertThat(WebUi.trustedHost("rebound.example:4711", true)).isFalse();
        assertThat(WebUi.trustedHost("192.168.1.10.nip.io:4711", true)).isFalse();
        assertThat(WebUi.trustedHost("[rebound.example]:4711", true)).isFalse();
        assertThat(WebUi.trustedHost(null, true)).isFalse();
    }

    @Test
    void settingsSurviveASaveAndHideTheHash() throws IOException {
        assertThat(WebUiSettings.load(this.dir)).isEqualTo(WebUiSettings.DEFAULTS);
        assertThat(WebUiSettings.DEFAULTS.usable()).isFalse();
        WebUiSettings mine = new WebUiSettings(true, 8080, true, "pbkdf2-sha512$1$AA$AA");
        mine.save(this.dir);
        assertThat(WebUiSettings.load(this.dir)).isEqualTo(mine);
        assertThat(mine.usable()).isTrue();
        assertThat(mine.toString()).doesNotContain("pbkdf2");
        assertThat(new WebUiSettings(true, 80, false, "").usable()).isFalse();
        assertThatThrownBy(() -> new WebUiSettings(true, 0, false, "")).isInstanceOf(IllegalArgumentException.class);

        Files.writeString(this.dir.resolve(WebUiSettings.FILE_NAME), "port=99999\n", StandardCharsets.UTF_8);
        assertThat(WebUiSettings.load(this.dir)).isEqualTo(WebUiSettings.DEFAULTS);
    }

    @Test
    void onlyTheOwnerReadsThePasswordHash() throws IOException {
        Path file = this.dir.resolve(WebUiSettings.FILE_NAME);
        Files.writeString(file, "enabled=false\n", StandardCharsets.UTF_8);
        new WebUiSettings(true, 8080, false, "pbkdf2-sha512$1$AA$AA").save(this.dir);

        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    @Test
    void escapesTextAndFormatsSizes() {
        assertThat(Html.text("<a href='x'>\"&\"</a>"))
                .isEqualTo("&lt;a href=&#39;x&#39;&gt;&quot;&amp;&quot;&lt;/a&gt;");
        assertThat(Html.size(512)).isEqualTo("512 B");
        assertThat(Html.size(1536)).isEqualTo("1.50 KB");
        assertThat(Html.size(5L << 40)).isEqualTo("5.00 TB");
        assertThat(Html.rate(2048)).isEqualTo("2.0 KB/s");
        assertThat(Html.hidden("a\"", "<b>")).isEqualTo("<input type=\"hidden\" name=\"a&quot;\" value=\"&lt;b&gt;\">");
        assertThat(Html.page("T<", "", "body")).contains("<h1>T&lt;</h1>").doesNotContain("nav");
        assertThat(Html.page("T", "tok", "body")).contains("<nav>", "value=\"tok\"");
    }
}
