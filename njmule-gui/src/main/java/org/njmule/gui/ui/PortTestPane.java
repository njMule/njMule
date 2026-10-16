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

import atlantafx.base.controls.RingProgressIndicator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.Observable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.PortTest;
import org.njmule.core.config.Preferences;

/**
 * Tests our ports where they are chosen, and shows what the test finds as it goes: the router's answer through UPnP,
 * then whether others reach the TCP and the UDP port. The ports as the fields give them are applied first.
 */
public final class PortTestPane {

    private static final Duration POLL = Duration.seconds(1);
    private static final List<String> KINDS =
            List.of("port-open", "port-closed", "port-testing", "port-unknown", "port-warning", "port-off");

    private final Gui gui;
    private final Supplier<UnaryOperator<Preferences>> settings;
    private final Button start;
    private final RingProgressIndicator running =
            new RingProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS, false);
    private final Label forwarding = line();
    private final Label tcp = line();
    private final Label udp = line();
    private final VBox lines = new VBox(4, this.forwarding, this.tcp, this.udp);
    private final VBox node;
    private final Timeline poll = new Timeline(new KeyFrame(POLL, e -> read()));
    private boolean testing;
    // Which test the outcomes shown come from: the end of one left for a newer one is not shown.
    private int run;

    /**
     * {@code settings} gives the change of the preferences the fields make, applied before each test; a change of
     * {@code fields} while a test runs lets a new one start, on the ports then chosen.
     */
    public PortTestPane(Gui gui, Supplier<UnaryOperator<Preferences>> settings, Observable... fields) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.start = Fx.button(gui.t("IDS_STARTTEST"), Material2MZ.NETWORK_CHECK, this::test);
        for (Observable field : fields) {
            field.addListener(o -> {
                if (this.testing) {
                    this.start.setDisable(false);
                }
            });
        }
        this.running.getStyleClass().add("tab-spinner");
        this.running.setVisible(false);
        this.poll.setCycleCount(Timeline.INDEFINITE);
        // The room of the outcomes is kept from the start: the page does not grow when they come.
        this.lines.setVisible(false);
        HBox row = new HBox(10, this.start, this.running);
        row.setAlignment(Pos.CENTER_LEFT);
        this.node = new VBox(8, row, this.lines);
        this.node.getStyleClass().add("port-test");
    }

    public Node node() {
        return this.node;
    }

    /** Applies the ports and starts the test, whose outcomes show as they come. */
    public void test() {
        int mine = ++this.run;
        this.testing = true;
        this.start.setDisable(true);
        this.running.setVisible(true);
        this.gui.model().updatePreferences(this.settings.get(), p -> {
            this.gui.model().testPorts(test -> finished(mine, test));
            this.poll.play();
            read();
        });
    }

    Button startButton() {
        return this.start;
    }

    List<Label> lines() {
        return List.of(this.forwarding, this.tcp, this.udp);
    }

    private void read() {
        this.gui.model().portTest(test -> test.filter(t -> !t.done()).ifPresent(this::show));
    }

    private void finished(int of, PortTest test) {
        if (of != this.run) {
            return;
        }
        this.testing = false;
        this.poll.stop();
        show(test);
        this.start.setDisable(false);
        this.running.setVisible(false);
    }

    /** Shows what {@code test} found, as far as it went. */
    public void show(PortTest test) {
        this.lines.setVisible(true);
        PortTest.Forwarding f = test.forwarding();
        set(
                this.forwarding,
                switch (f) {
                    case OFF -> "off";
                    case ASKING -> "testing";
                    case FORWARDED -> "open";
                    case PARTLY, REFUSED, NO_ROUTER, FAILED -> "warning";
                },
                this.gui.f("nj.ports.forwarding." + f.name(), test.router()));
        reach(this.tcp, "TCP", test.tcpPort(), test.tcp(), "");
        if (test.udpPort() == 0) {
            set(this.udp, "off", this.gui.t("nj.ports.noKad"));
        } else {
            reach(this.udp, "UDP", test.udpPort(), test.udp(), " " + this.gui.t("nj.ports.udpSlow"));
        }
    }

    private void reach(Label line, String protocol, int port, PortTest.Reach reach, String whileTesting) {
        String text = this.gui.f("nj.ports.reach." + reach.name(), protocol, Integer.toString(port));
        set(line, reach.name().toLowerCase(Locale.ROOT), reach == PortTest.Reach.TESTING ? text + whileTesting : text);
    }

    // The icon tells the kind besides the words.
    private static void set(Label line, String kind, String text) {
        line.setText(text);
        line.getStyleClass().removeAll(KINDS);
        line.getStyleClass().add("port-" + kind);
        Ikon icon = switch (kind) {
            case "open" -> Material2AL.CHECK_CIRCLE;
            case "closed" -> Material2AL.BLOCK;
            case "testing" -> Material2AL.HOURGLASS_EMPTY;
            case "warning" -> Material2MZ.WARNING;
            case "off" -> Material2MZ.REMOVE_CIRCLE_OUTLINE;
            default -> Material2AL.HELP_OUTLINE;
        };
        ((FontIcon) line.getGraphic()).setIconCode(icon);
    }

    private static Label line() {
        Label line = new Label("", new FontIcon(Material2AL.HELP_OUTLINE));
        line.getStyleClass().add("port-line");
        line.setWrapText(true);
        line.setGraphicTextGap(8);
        return line;
    }
}
