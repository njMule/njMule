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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.PortTest.Forwarding;
import org.njmule.core.api.PortTest.Reach;
import org.njmule.gui.Engines;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class PortTestPaneTest {

    private static final int TCP = 4711;
    private static final int UDP = 4721;

    @TempDir
    Path dir;

    private TestGui test;
    private PortTestPane pane;
    private final CompletableFuture<PortTest> outcome = new CompletableFuture<>();
    private final AtomicReference<PortTest> progress = new AtomicReference<>();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldApplyTheChosenPortsBeforeTheTest() throws Exception {
        givenThePaneForThePorts();

        whenTheTestIsStarted();

        thenTheEngineTakesThePorts();
        thenTheButtonWaitsForTheTest();
    }

    @Test
    void shouldShowHowTheTestGoesWhileItRuns() throws Exception {
        givenThePaneForThePorts();
        givenTheTestWentAsFarAs(new PortTest(TCP, UDP, Forwarding.ASKING, "", Reach.TESTING, Reach.TESTING, false));

        whenTheTestIsStarted();

        thenTheLinesRead(
                "nj.ports.forwarding.ASKING",
                reach("TESTING", "TCP", TCP),
                reach("TESTING", "UDP", UDP) + " " + text("nj.ports.udpSlow"));
        thenTheButtonWaitsForTheTest();
    }

    @Test
    void shouldShowWhatTheTestFoundOnceItIsOver() throws Exception {
        givenThePaneForThePorts();
        givenTheTestStarted();

        whenTheTestEnds(new PortTest(TCP, UDP, Forwarding.FORWARDED, "192.168.1.1", Reach.OPEN, Reach.CLOSED, true));

        thenTheLinesRead(
                String.format(text("nj.ports.forwarding.FORWARDED"), "192.168.1.1"),
                reach("OPEN", "TCP", TCP),
                reach("CLOSED", "UDP", UDP));
        thenTheButtonCanTestAgain();
    }

    @Test
    void shouldSayKadIsOffWhenThereIsNoUdpPortToTest() throws Exception {
        givenThePaneForThePorts();
        givenTheTestStarted();

        whenTheTestEnds(new PortTest(TCP, 0, Forwarding.OFF, "", Reach.UNKNOWN, Reach.UNKNOWN, true));

        thenTheLinesRead("nj.ports.forwarding.OFF", reach("UNKNOWN", "TCP", TCP), "nj.ports.noKad");
    }

    private void givenThePaneForThePorts() throws Exception {
        this.test = TestGui.start(
                this.dir.resolve("njmule"),
                real -> Engines.overriding(
                        real,
                        Map.of(
                                "testPorts",
                                args -> this.outcome,
                                "portTest",
                                args -> CompletableFuture.completedFuture(Optional.ofNullable(this.progress.get())))));
        this.pane = FxTesting.onFx(() -> new PortTestPane(
                this.test.gui,
                () -> p -> p.toBuilder().tcpPort(TCP).udpPort(UDP).build()));
        this.test.show(this.pane.node());
    }

    private void givenTheTestWentAsFarAs(PortTest state) {
        this.progress.set(state);
    }

    private void givenTheTestStarted() throws Exception {
        whenTheTestIsStarted();
    }

    private void whenTheTestIsStarted() throws Exception {
        FxTesting.onFx(this.pane::test);
        FxTesting.await(
                "the test asked for", () -> this.test.engine.preferences().tcpPort() == TCP);
        FxTesting.await("the progress read", () -> this.progress.get() == null || isShown(this.pane.lines()));
    }

    private void whenTheTestEnds(PortTest last) throws Exception {
        this.outcome.complete(last);
        FxTesting.await("the end shown", () -> !this.pane.startButton().isDisabled());
    }

    private void thenTheEngineTakesThePorts() {
        assertThat(this.test.engine.preferences().tcpPort()).isEqualTo(TCP);
        assertThat(this.test.engine.preferences().udpPort()).isEqualTo(UDP);
    }

    private void thenTheButtonWaitsForTheTest() throws Exception {
        assertThat(FxTesting.onFx(() -> this.pane.startButton().isDisabled())).isTrue();
    }

    private void thenTheButtonCanTestAgain() throws Exception {
        assertThat(FxTesting.onFx(() -> this.pane.startButton().isDisabled())).isFalse();
    }

    // A key whose text takes no argument is given as the key.
    private void thenTheLinesRead(String forwarding, String tcp, String udp) throws Exception {
        List<String> expected = List.of(
                forwarding.startsWith("nj.") ? text(forwarding) : forwarding,
                tcp,
                udp.startsWith("nj.") ? text(udp) : udp);
        assertThat(FxTesting.onFx(
                        () -> this.pane.lines().stream().map(Label::getText).toList()))
                .containsExactlyElementsOf(expected);
    }

    private String reach(String state, String protocol, int port) {
        return this.test.gui.f("nj.ports.reach." + state, protocol, Integer.toString(port));
    }

    private String text(String key) {
        return this.test.gui.t(key);
    }

    private static boolean isShown(List<Label> lines) {
        return lines.stream().allMatch(l -> !l.getText().isEmpty());
    }
}
