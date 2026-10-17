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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WindowsDesktopTest {

    private Map<String, String> environment;
    private boolean forDesktop;
    private byte[] bytes;
    private String installer;

    @Test
    void shouldRunForTheDesktopWhenTheLauncherMarksIt() {
        givenTheEnvironment(Map.of(WindowsDesktop.DESKTOP_VARIABLE, "1"));

        whenItIsAskedWhetherItRunsForTheDesktop();

        thenItRunsForTheDesktop(true);
    }

    @Test
    void shouldRunAsTheCommandLineWithoutTheMarkOfTheLauncher() {
        givenTheEnvironment(Map.of());

        whenItIsAskedWhetherItRunsForTheDesktop();

        thenItRunsForTheDesktop(false);
    }

    @Test
    void shouldLayOutAGuidAsWindowsKeepsItInMemory() {
        whenTheGuidIsLaidOut("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99");

        thenTheBytesAre("eb8e6d88f28c46448d02cdba1dbdcf99");
    }

    // The taskbar groups the windows with the installer's shortcuts, and pins them, only when both carry one id.
    @Test
    void shouldGiveTheWindowsTheIdOfTheInstallerShortcuts() throws IOException {
        whenTheInstallerIsRead();

        thenTheShortcutsCarry(WindowsDesktop.APP_ID);
    }

    private void givenTheEnvironment(Map<String, String> environment) {
        this.environment = environment;
    }

    private void whenItIsAskedWhetherItRunsForTheDesktop() {
        this.forDesktop = WindowsDesktop.startedForDesktop(this.environment);
    }

    private void whenTheGuidIsLaidOut(String guid) {
        this.bytes = WindowsDesktop.guidBytes(guid);
    }

    private void whenTheInstallerIsRead() throws IOException {
        this.installer = Files.readString(Path.of("..", "packaging", "windows", "njmule.wxs"));
    }

    private void thenItRunsForTheDesktop(boolean expected) {
        assertThat(this.forDesktop).isEqualTo(expected);
    }

    private void thenTheBytesAre(String hex) {
        assertThat(HexFormat.of().formatHex(this.bytes)).isEqualTo(hex);
    }

    private void thenTheShortcutsCarry(String id) {
        assertThat(this.installer).contains("<ShortcutProperty Key=\"System.AppUserModel.ID\" Value=\"" + id + "\"/>");
    }
}
