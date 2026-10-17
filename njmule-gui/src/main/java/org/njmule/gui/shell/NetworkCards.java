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
package org.njmule.gui.shell;

import atlantafx.base.controls.Popover;
import atlantafx.base.theme.Styles;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.PortTestPane;

/**
 * The state of eD2K, Kad and our ports at the top of the network, in plain words with the technical term in a tooltip,
 * each with what to do about it.
 */
final class NetworkCards {

    private static final List<String> TONES = List.of("success", "warning", "connecting");

    private final Gui gui;
    private final Card ed2k;
    private final Card kad;
    private final Card ports;
    private final Button ed2kAction = new Button();
    private final Button kadAction = new Button();
    private final Button recheck;
    private final Button test;
    private final PortTestPane portTest;
    private final GridPane view = new GridPane();
    private Popover testPopover;
    private EngineModel.Connection connection = EngineModel.Connection.DISCONNECTED;
    private boolean kadRuns;

    /** {@code show} opens a screen by its id. */
    NetworkCards(Gui gui, Consumer<String> show) {
        this.gui = gui;
        this.ed2kAction.setId("card-ed2k-action");
        this.ed2kAction.setOnAction(e -> {
            if (this.connection == EngineModel.Connection.DISCONNECTED) {
                gui.model().connect(Optional.empty());
            } else {
                gui.model().disconnect();
            }
        });
        this.kadAction.setId("card-kad-action");
        this.kadAction.setOnAction(e -> {
            if (this.kadRuns) {
                gui.model().disconnectKad();
            } else {
                gui.model().connectKad();
            }
        });
        Button other = flat(Fx.button(gui.t("nj.network.otherServer"), Icons.SERVER, () -> show.accept("servers")));
        this.recheck = flat(Fx.button(gui.t("IDS_KAD_RECHECKFW"), Icons.SHIELD, gui.model()::recheckKadFirewall));
        this.recheck.setId("card-kad-recheck");
        // The ports as they are set: the test changes none of them.
        this.portTest = new PortTestPane(gui, UnaryOperator::identity);
        this.test = Fx.button(gui.t("nj.network.testPorts"), Icons.PORT_TEST, this::testPorts);
        this.test.setId("card-ports-test");
        Button options = flat(Fx.button(gui.t("IDS_EM_PREFS"), Icons.OPTIONS, () -> show.accept("settings")));
        this.ed2k = new Card("card-ed2k", "eD2K", Icons.SERVER, this.ed2kAction, other);
        this.kad = new Card("card-kad", "Kad", Icons.KAD, this.kadAction, this.recheck);
        this.ports = new Card("card-ports", gui.t("nj.network.ports"), Icons.PORT_TEST, this.test, options);
        this.view.addRow(0, this.ed2k.box, this.kad.box, this.ports.box);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints third = new ColumnConstraints();
            third.setPercentWidth(100.0 / 3);
            third.setHgrow(Priority.ALWAYS);
            this.view.getColumnConstraints().add(third);
        }
        this.view.getStyleClass().add("network-cards");
    }

    GridPane view() {
        return this.view;
    }

    /** Shows the state of both networks as {@code connection} and {@code status} tell, on the server named so. */
    void update(EngineModel.Connection connection, EngineStatus status, String name, Preferences preferences) {
        this.connection = connection;
        String server = this.gui.i18n().embed(name);
        switch (connection) {
            case CONNECTED -> {
                boolean low = status.lowId();
                this.ed2k.state(
                        this.gui.t(low ? "nj.network.unreachable" : "nj.network.reachable"),
                        this.gui.t(low ? "IDS_IDLOW" : "IDS_IDHIGH"),
                        low ? Icons.WARNING : Icons.CHECK_CIRCLE,
                        low ? "warning" : "success");
                String counts = status.server()
                        .map(s -> this.gui.f(
                                "nj.status.server",
                                this.gui.formats().count(s.users()),
                                this.gui.formats().count(s.files())))
                        .orElse("");
                this.ed2k.detail(counts.isEmpty() ? server : server + "\n" + counts);
            }
            case CONNECTING -> {
                this.ed2k.state(this.gui.t("IDS_CONNECTING"), "", Icons.HOURGLASS, "connecting");
                this.ed2k.detail(server);
            }
            case DISCONNECTED -> {
                this.ed2k.state(this.gui.t("IDS_NOTCONNECTED"), "", Icons.CLOUD_OFF, "");
                this.ed2k.detail("");
            }
        }
        this.ed2kAction.setText(
                switch (connection) {
                    case DISCONNECTED -> this.gui.t("IDS_MAIN_BTN_CONNECT");
                    case CONNECTING -> this.gui.t("IDS_MAIN_BTN_CANCEL");
                    case CONNECTED -> this.gui.t("IDS_MAIN_BTN_DISCONNECT");
                });
        this.ed2kAction.setGraphic(
                new Icon(connection == EngineModel.Connection.DISCONNECTED ? Icons.LINK : Icons.LINK_OFF));
        Optional<KadInfo> kadInfo = status.kad();
        this.kadRuns = kadInfo.isPresent();
        if (kadInfo.isEmpty()) {
            this.kad.state(this.gui.t("nj.kad.off"), "", Icons.CLOUD_OFF, "");
            this.kad.detail("");
        } else if (!kadInfo.get().connected()) {
            this.kad.state(this.gui.t("IDS_CONNECTING"), "", Icons.HOURGLASS, "connecting");
            this.kad.detail("");
        } else {
            KadInfo k = kadInfo.get();
            boolean firewalled = k.firewalled();
            this.kad.state(
                    this.gui.t(firewalled ? "nj.network.unreachable" : "nj.network.reachable"),
                    this.gui.t(firewalled ? "IDS_FIREWALLED" : "IDS_KADOPEN"),
                    firewalled ? Icons.WARNING : Icons.CHECK_CIRCLE,
                    firewalled ? "warning" : "success");
            // Behind a TCP firewall, whether UDP gets through tells if others still reach us, once tested.
            String udp = firewalled && (k.udpVerified() || k.udpFirewalled())
                    ? "\n" + this.gui.t(k.udpFirewalled() ? "nj.kad.udpFirewalled" : "nj.kad.udpOpen")
                    : "";
            this.kad.detail(
                    this.gui.t("IDS_KADCONTACTLAB") + ": " + this.gui.formats().count(k.contacts()) + udp);
        }
        this.kadAction.setText(this.gui.t(this.kadRuns ? "IDS_MAIN_BTN_DISCONNECT" : "IDS_MAIN_BTN_CONNECT"));
        this.kadAction.setGraphic(new Icon(this.kadRuns ? Icons.LINK_OFF : Icons.LINK));
        this.recheck.setDisable(kadInfo.isEmpty());
        // Ports: the words say which; the numbers read left to right in every language.
        String numbers =
                "TCP " + preferences.tcpPort() + (preferences.udpEnabled() ? " · UDP " + preferences.udpPort() : "");
        this.ports.state(numbers, "", null, "");
        this.ports.headline.setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
        this.ports.detail(this.gui.t("IDS_UPNP") + ": " + this.gui.t(preferences.upnp() ? "IDS_YES" : "IDS_NO"));
    }

    // The test of the ports in a popup under its button, its outcomes coming in as they are known.
    private void testPorts() {
        if (this.testPopover == null) {
            this.testPopover = new Popover(this.portTest.node());
            this.testPopover.setTitle(this.gui.t("nj.network.testPorts"));
            this.testPopover.setHeaderAlwaysVisible(true);
            this.testPopover.setDetachable(false);
            this.testPopover.setArrowLocation(
                    this.gui.orientation() == NodeOrientation.RIGHT_TO_LEFT
                            ? Popover.ArrowLocation.TOP_RIGHT
                            : Popover.ArrowLocation.TOP_LEFT);
        }
        if (this.test.getScene() != null && this.test.getScene().getWindow() != null) {
            this.testPopover.show(this.test);
        }
        this.portTest.test();
    }

    Label headline(String card) {
        return switch (card) {
            case "ed2k" -> this.ed2k.headline;
            case "kad" -> this.kad.headline;
            default -> this.ports.headline;
        };
    }

    Label detail(String card) {
        return switch (card) {
            case "ed2k" -> this.ed2k.detail;
            case "kad" -> this.kad.detail;
            default -> this.ports.detail;
        };
    }

    Button ed2kAction() {
        return this.ed2kAction;
    }

    Button kadAction() {
        return this.kadAction;
    }

    Button recheck() {
        return this.recheck;
    }

    PortTestPane portTest() {
        return this.portTest;
    }

    private static Button flat(Button button) {
        button.getStyleClass().add(Styles.FLAT);
        return button;
    }

    /** A card: the network's name, its state in a few words, a detail and two buttons. */
    private static final class Card {
        private final VBox box;
        private final Label headline = new Label();
        private final Icon mark = new Icon(Icons.DOT);
        private final Label detail = new Label();

        Card(String id, String title, Icons glyph, Button action, Button other) {
            StackPane tile = new StackPane(new Icon(glyph));
            tile.getStyleClass().add("card-tile");
            Label name = new Label(title);
            name.getStyleClass().add("card-title");
            this.headline.getStyleClass().add("card-headline");
            this.headline.setWrapText(true);
            this.headline.setMinHeight(Region.USE_PREF_SIZE);
            // The network's name over its state, beside its tile.
            VBox words = new VBox(name, this.headline);
            words.setMinWidth(0);
            HBox head = new HBox(10, tile, words);
            head.setAlignment(Pos.CENTER_LEFT);
            this.detail.getStyleClass().add("card-detail");
            this.detail.setWrapText(true);
            this.detail.setMinHeight(Region.USE_PREF_SIZE);
            this.detail.managedProperty().bind(this.detail.textProperty().isNotEmpty());
            this.detail.visibleProperty().bind(this.detail.textProperty().isNotEmpty());
            action.getStyleClass().add(Styles.SMALL);
            // In a narrow window the second button keeps to its icon, its words in the tooltip.
            other.getStyleClass().addAll(Styles.SMALL, "card-other");
            other.setTooltip(new Tooltip(other.getText()));
            Fx.whole(action);
            Fx.whole(other);
            FlowPane buttons = new FlowPane(6, 6, action, other);
            Region push = new Region();
            VBox.setVgrow(push, Priority.ALWAYS);
            this.box = new VBox(8, head, this.detail, push, buttons);
            this.box.setId(id);
            this.box.getStyleClass().add("network-card");
            this.box.setMinWidth(0);
            this.box.setMaxHeight(Double.MAX_VALUE);
        }

        // The state in words, with its icon besides its tone; the technical term, if any, in the tooltip.
        void state(String text, String term, Icons glyph, String tone) {
            this.headline.setText(text);
            this.headline.setTooltip(term.isEmpty() ? null : new Tooltip(term));
            if (glyph == null) {
                this.headline.setGraphic(null);
            } else {
                this.mark.setGlyph(glyph);
                this.headline.setGraphic(this.mark);
            }
            this.box.getStyleClass().removeAll(TONES);
            if (!tone.isEmpty()) {
                this.box.getStyleClass().add(tone);
            }
        }

        void detail(String text) {
            this.detail.setText(text);
        }
    }
}
