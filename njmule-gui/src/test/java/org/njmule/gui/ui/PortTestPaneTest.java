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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
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
    private final List<CompletableFuture<PortTest>> outcomes =
            List.of(new CompletableFuture<>(), new CompletableFuture<>());
    private final AtomicInteger asked = new AtomicInteger();
    private final StringProperty port = new SimpleStringProperty(Integer.toString(TCP));
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
    void shouldOfferANewTestWhenAPortChangesDuringOne() throws Exception {
        givenThePaneForThePorts();
        givenTheTestStarted();

        whenThePortIsChangedTo(4712);

        thenTheButtonCanTestAgain();
    }

    @Test
    void shouldNotShowTheEndOfATestLeftForANewOne() throws Exception {
        givenThePaneForThePorts();
        givenTheTestStarted();
        givenThePortChangedTo(4712);
        givenTheTestStarted();

        whenTheFirstTestEnds(new PortTest(TCP, UDP, Forwarding.OFF, "", Reach.OPEN, Reach.OPEN, true));

        thenTheButtonWaitsForTheTest();
        thenNoLineReads(reach("OPEN", "TCP", TCP));
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
                                args -> this.outcomes.get(this.asked.getAndIncrement()),
                                "portTest",
                                args -> CompletableFuture.completedFuture(Optional.ofNullable(this.progress.get())))));
        this.pane = FxTesting.onFx(() -> new PortTestPane(
                this.test.gui,
                () -> p -> p.toBuilder()
                        .tcpPort(Integer.parseInt(this.port.get()))
                        .udpPort(UDP)
                        .build(),
                this.port));
        this.test.show(this.pane.node());
    }

    private void givenTheTestWentAsFarAs(PortTest state) {
        this.progress.set(state);
    }

    private void givenTheTestStarted() throws Exception {
        whenTheTestIsStarted();
    }

    private void givenThePortChangedTo(int tcp) throws Exception {
        whenThePortIsChangedTo(tcp);
    }

    private void whenThePortIsChangedTo(int tcp) throws Exception {
        FxTesting.onFx(() -> this.port.set(Integer.toString(tcp)));
    }

    private void whenTheTestIsStarted() throws Exception {
        int before = this.asked.get();
        FxTesting.onFx(this.pane::test);
        FxTesting.await("the test asked for", () -> this.asked.get() > before);
        assertThat(this.test.engine.preferences().tcpPort()).isEqualTo(Integer.parseInt(this.port.get()));
        FxTesting.await("the progress read", () -> this.progress.get() == null || isShown(this.pane.lines()));
    }

    // The end comes to the FX thread before what is asked of it next.
    private void whenTheFirstTestEnds(PortTest last) throws Exception {
        this.outcomes.get(0).complete(last);
        FxTesting.onFx(() -> null);
    }

    private void whenTheTestEnds(PortTest last) throws Exception {
        this.outcomes.get(0).complete(last);
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

    private void thenNoLineReads(String text) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> this.pane.lines().stream().map(Label::getText).toList()))
                .doesNotContain(text);
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
