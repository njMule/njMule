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

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LaunchOptions;

@Timeout(60)
class FirstStartTest {

    @TempDir
    Path dir;

    private DesktopApp app;
    private Stage stage;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(() -> this.stage.close());
        }
        if (this.app != null) {
            this.app.close();
        }
    }

    @Test
    void theFirstStartAsksTheQuestionsOfTheWizard() throws Exception {
        givenAProfile(false);
        whenTheAppShows();
        thenTheWizardShows(true);
    }

    @Test
    void aLaterStartAsksNothing() throws Exception {
        givenAProfile(true);
        whenTheAppShows();
        thenTheWizardShows(false);
    }

    // On free ports and without Kad: never on the ports of a running eMule.
    private void givenAProfile(boolean usedBefore) throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("config"));
        int tcp;
        try (ServerSocket probe = new ServerSocket(0)) {
            tcp = probe.getLocalPort();
        }
        int udp;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            udp = probe.getLocalPort();
        }
        Files.writeString(
                config.resolve("njmule.properties"),
                "net.tcpPort=" + tcp + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=false\n");
        if (usedBefore) {
            Files.writeString(
                    Files.createDirectories(this.dir.resolve("gui")).resolve("gui.properties"), "language=en\n");
        }
        this.app = DesktopApp.open(LaunchOptions.parse("--config-dir", this.dir.toString()));
    }

    private void whenTheAppShows() throws Exception {
        FxTesting.onFx(() -> {
            this.stage = new Stage();
            this.app.show(this.stage, false);
        });
    }

    private void thenTheWizardShows(boolean shows) throws Exception {
        assertThat(FxTesting.onFx(() -> Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .anyMatch(w -> w instanceof Stage s
                                && this.stage.equals(s.getOwner())
                                && s.getScene().getRoot().getStyleClass().contains("wizard"))))
                .isEqualTo(shows);
    }
}
