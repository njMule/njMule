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
package org.njmule.gui.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.Autostart;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class ImportAutostartTest {

    @TempDir
    Path dir;

    private final PretendAutostart system = new PretendAutostart();
    private TestGui test;
    private ImportDialog dialog;
    private Path emule;

    @AfterEach
    void close() throws Exception {
        if (this.dialog != null) {
            FxTesting.onFx(() -> this.dialog.stage().close());
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldStartAtLoginAfterImportingAnEmuleThatDid() throws Exception {
        givenAnEmuleProfile("AutoStart=1");
        givenTheImportDialog();

        whenTheProfileIsImported();

        thenNjMuleStartsAtLogin();
        thenTheUserIsTold("njMule now starts when you log in");
    }

    @Test
    void shouldLeaveTheStartAtLoginAloneWhenEmuleDidNotStartAtLogin() throws Exception {
        givenAnEmuleProfile("AutoStart=0");
        givenTheImportDialog();

        whenTheProfileIsImported();

        thenTheSystemWasNotChanged();
    }

    @Test
    void shouldLeaveTheStartAtLoginAloneInACheck() throws Exception {
        givenAnEmuleProfile("AutoStart=1");
        givenTheImportDialog();

        whenTheProfileIsChecked();

        thenTheSystemWasNotChanged();
    }

    private void givenAnEmuleProfile(String autoStart) throws Exception {
        this.emule = Files.createDirectories(this.dir.resolve("emule-config"));
        Files.writeString(this.emule.resolve("preferences.ini"), "[eMule]\r\nNick=FromEmule\r\n" + autoStart + "\r\n");
    }

    private void givenTheImportDialog() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), Optional.of(this.system));
        FxTesting.onFx(() -> {
            this.dialog = new ImportDialog(this.test.gui, new Stage());
            this.dialog.show();
            this.dialog.directoryField().setText(this.emule.toString());
        });
    }

    private void whenTheProfileIsImported() throws Exception {
        FxTesting.onFx(() -> this.dialog.run(false));
        FxTesting.await(
                "the import", () -> this.test.engine.preferences().nick().equals("FromEmule"));
        FxTesting.await(
                "the report", () -> !this.dialog.reportTable().getItems().isEmpty());
    }

    private void whenTheProfileIsChecked() throws Exception {
        FxTesting.onFx(() -> this.dialog.run(true));
        FxTesting.await(
                "the report", () -> !this.dialog.reportTable().getItems().isEmpty());
    }

    private void thenNjMuleStartsAtLogin() throws Exception {
        FxTesting.assertEventually("the start at login", () -> this.system.on && this.system.changes.get() == 1);
    }

    private void thenTheUserIsTold(String text) throws Exception {
        FxTesting.assertEventually("the message", () -> this.test.told.stream().anyMatch(m -> m.startsWith(text)));
    }

    private void thenTheSystemWasNotChanged() throws Exception {
        FxTesting.onFx(() -> {});
        assertThat(this.system.changes.get()).isZero();
    }

    /** A pretend start at login: the real one is never touched. */
    private static final class PretendAutostart implements Autostart {
        private volatile boolean on;
        private final AtomicInteger changes = new AtomicInteger();

        @Override
        public boolean isOn() {
            return this.on;
        }

        @Override
        public void turnOn() {
            this.on = true;
            this.changes.incrementAndGet();
        }

        @Override
        public void turnOff() {
            this.on = false;
            this.changes.incrementAndGet();
        }
    }
}
