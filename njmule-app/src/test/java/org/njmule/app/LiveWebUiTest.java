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
package org.njmule.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.njmule.http.WebUiSettings;

class LiveWebUiTest {

    private static final String HASH = "pbkdf2-sha512$1$AA$AA";

    private final FakeEngine fake = new FakeEngine();
    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private LiveWebUi web;
    private int first;
    private int second;

    @AfterEach
    void close() {
        if (this.web != null) {
            this.web.close();
        }
    }

    @Test
    void shouldStartTheInterfaceWhenItIsTurnedOn() {
        givenTheInterfaceOff();

        whenTheSettingsAre(true, this.first);

        thenItAnswersOn(this.first);
    }

    @Test
    void shouldStartTheInterfaceAgainOnTheNewPort() {
        givenTheInterfaceRunsOnTheFirstPort();

        whenTheSettingsAre(true, this.second);

        thenItAnswersOn(this.second);
        thenNothingAnswersOn(this.first);
    }

    @Test
    void shouldStopTheInterfaceWhenItIsTurnedOff() {
        givenTheInterfaceRunsOnTheFirstPort();

        whenTheSettingsAre(false, this.first);

        thenItDoesNotRun();
        thenNothingAnswersOn(this.first);
    }

    @Test
    void shouldKeepTheInterfaceRunningWhenTheSettingsStayTheSame() {
        givenTheInterfaceRunsOnTheFirstPort();

        whenTheSettingsAre(true, this.first);

        thenItAnswersOn(this.first);
        thenItStartedOnce();
    }

    // Given

    private void givenTheInterfaceOff() {
        this.first = FreePorts.tcp();
        do {
            this.second = FreePorts.tcp();
        } while (this.second == this.first);
        this.web = new LiveWebUi(
                this.fake.engine(), new PrintStream(this.printed, true, StandardCharsets.UTF_8), () -> {});
        this.web.apply(settings(false, this.first));
    }

    private void givenTheInterfaceRunsOnTheFirstPort() {
        givenTheInterfaceOff();
        this.web.apply(settings(true, this.first));
    }

    // When

    private void whenTheSettingsAre(boolean enabled, int port) {
        this.web.apply(settings(enabled, port));
    }

    // Then

    private void thenItAnswersOn(int port) {
        assertThat(this.web.port()).contains(port);
        assertThat(answers(port)).isTrue();
    }

    private void thenNothingAnswersOn(int port) {
        assertThat(answers(port)).isFalse();
    }

    private void thenItDoesNotRun() {
        assertThat(this.web.port()).isEmpty();
    }

    private void thenItStartedOnce() {
        assertThat(this.printed.toString(StandardCharsets.UTF_8)).containsOnlyOnce("Web interface on port");
    }

    private static WebUiSettings settings(boolean enabled, int port) {
        return new WebUiSettings(enabled, port, false, HASH, false);
    }

    private static boolean answers(int port) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }
}
