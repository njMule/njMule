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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LaunchOptions;
import org.njmule.app.LinkHandoff;

/** What a desktop start does when a njMule already runs with its configuration. */
@Timeout(30)
class GuiMainTest {

    private static final String LINK = "ed2k://|file|a.iso|10|" + "AB".repeat(16) + "|/";

    @TempDir
    Path dir;

    private LinkHandoff running;
    private final List<List<String>> received = new CopyOnWriteArrayList<>();
    private boolean handedOver;

    @AfterEach
    void stop() {
        if (this.running != null) {
            this.running.close();
        }
    }

    @Test
    void shouldEndOnceANjmuleWithAWindowTookTheLinks() throws Exception {
        givenANjmuleRuns(false);

        whenStartedWith("--config-dir", this.dir.toString(), LINK);

        thenItEnds(true);
        thenTheLinksWentToIt();
    }

    @Test
    void shouldOpenToTellThatANjmuleWithoutAWindowTookTheLinks() throws Exception {
        givenANjmuleRuns(true);

        whenStartedWith("--config-dir", this.dir.toString(), LINK);

        thenItEnds(false);
        thenTheLinksWentToIt();
        thenTheWindowTells("without a window with this configuration, and the links went to it");
    }

    @Test
    void shouldOpenWhenNoNjmuleRuns() throws Exception {
        whenStartedWith("--config-dir", this.dir.toString(), LINK);

        thenItEnds(false);
    }

    @Test
    void shouldHandNothingOverForAWindowOnAnotherNjmule() throws Exception {
        givenANjmuleRuns(false);

        whenStartedWith("--config-dir", this.dir.toString(), "--remote-url", "http://192.0.2.1:4711/api/v1");

        thenItEnds(false);
        thenNothingWentToIt();
    }

    private void givenANjmuleRuns(boolean headless) throws Exception {
        LaunchOptions cli = LaunchOptions.parse("--config-dir", this.dir.toString());
        this.running = LinkHandoff.listen(DesktopApp.configDir(cli), headless, this.received::add);
    }

    private void whenStartedWith(String... args) throws Exception {
        this.handedOver = GuiMain.handedOver(LaunchOptions.parse(args));
    }

    private void thenItEnds(boolean ends) {
        assertThat(this.handedOver).isEqualTo(ends);
    }

    // The running njMule takes them on a thread of its own, a moment after it answered.
    private void thenTheLinksWentToIt() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!this.received.contains(List.of(LINK)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(this.received).contains(List.of(LINK));
    }

    private void thenNothingWentToIt() {
        assertThat(this.received).isEmpty();
    }

    private void thenTheWindowTells(String part) {
        assertThat(NjMuleFxApp.headless())
                .hasValueSatisfying(text -> assertThat(text).contains(part));
    }
}
