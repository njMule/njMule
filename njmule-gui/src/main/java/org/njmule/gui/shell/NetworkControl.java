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
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;

/**
 * The networks in the top bar: their state in words on a neutral button, which opens a menu to connect or disconnect
 * eD2K, Kad or both, to choose another server and to open the network.
 */
final class NetworkControl {

    private final Gui gui;
    private final Button button = new Button();
    private final Circle dot = new Circle(4);
    private final Label state = new Label();
    private final Label detail = new Label();
    private final Label ed2k = chip();
    private final Label kad = chip();
    private final Label ed2kNote = note();
    private final Label kadNote = note();
    private final Button ed2kAction = new Button();
    private final Button kadAction = new Button();
    private final Button both = new Button();
    private final VBox menuBox;
    private final BooleanProperty compact = new SimpleBooleanProperty();
    private Popover menu;
    private EngineModel.Connection connection = EngineModel.Connection.DISCONNECTED;
    private boolean kadRuns;

    /**
     * {@code both} connects or disconnects both networks, {@code show} opens a screen by its id and {@code network} the
     * network where it was left; {@code networkKeys} are the keys of the latter.
     */
    NetworkControl(Gui gui, Runnable both, Consumer<String> show, Runnable network, String networkKeys) {
        this.gui = gui;
        this.button.setId("network");
        this.button.getStyleClass().add("network-control");
        this.dot.getStyleClass().add("network-dot");
        this.state.getStyleClass().add("network-state");
        this.detail.getStyleClass().add("network-detail");
        this.detail.setMaxWidth(260);
        VBox words = new VBox(this.state, this.detail);
        words.setAlignment(Pos.CENTER_LEFT);
        HBox graphic = new HBox(8, this.dot, words, new Icon(Icons.CHEVRON_DOWN));
        graphic.setAlignment(Pos.CENTER_LEFT);
        this.button.setGraphic(graphic);
        this.button.setOnAction(e -> showMenu());
        this.compact.addListener((o, was, now) -> showDetail());
        this.ed2kAction.setId("network-ed2k");
        this.ed2kAction.setOnAction(e -> {
            if (this.connection == EngineModel.Connection.DISCONNECTED) {
                gui.model().connect(Optional.empty());
            } else {
                gui.model().disconnect();
            }
        });
        this.kadAction.setId("network-kad");
        this.kadAction.setOnAction(e -> {
            if (this.kadRuns) {
                gui.model().disconnectKad();
            } else {
                gui.model().connectKad();
            }
        });
        this.both.setId("connect");
        this.both.setOnAction(e -> both.run());
        this.both.setMaxWidth(Double.MAX_VALUE);
        // A chip opens the network at the part it tells of.
        opens(this.ed2k, () -> show.accept("servers"));
        opens(this.kad, () -> show.accept("kad"));
        Button other = item(gui.t("nj.network.otherServer"), "", () -> show.accept("servers"));
        other.setId("network-servers");
        Button open = item(gui.t("IDS_NETWORK"), networkKeys, network);
        open.setId("network-open");
        this.menuBox = new VBox(
                6,
                row(this.ed2k, this.ed2kNote, this.ed2kAction),
                row(this.kad, this.kadNote, this.kadAction),
                new Separator(),
                other,
                open,
                this.both);
        this.menuBox.getStyleClass().add("network-menu");
        for (Region whole : List.of(this.button, this.ed2kAction, this.kadAction, this.both)) {
            Fx.whole(whole);
        }
    }

    Button view() {
        return this.button;
    }

    /** Keeps the button to the dot and the state while {@code narrow}. */
    void compactWhile(ObservableBooleanValue narrow) {
        this.compact.bind(narrow);
    }

    /** Shows the state of both networks as {@code connection}, {@code status} and the server's {@code name} tell. */
    void update(EngineModel.Connection connection, EngineStatus status, String name) {
        this.connection = connection;
        String server = this.gui.i18n().embed(name);
        String ed2kText = switch (connection) {
            case DISCONNECTED -> this.gui.t("IDS_NOTCONNECTED");
            case CONNECTING -> this.gui.t("IDS_CONNECTING") + " " + server;
            case CONNECTED ->
                this.gui.i18n().embed(name + (status.lowId() ? " (LowID)" : status.clientId() != 0 ? " (HighID)" : ""));
        };
        state(
                this.ed2k,
                "eD2K: " + ed2kText,
                connection == EngineModel.Connection.CONNECTED,
                connection == EngineModel.Connection.CONNECTING);
        this.ed2kNote.setText(
                connection == EngineModel.Connection.CONNECTED
                        ? status.server().map(this::serverCounts).orElse("")
                        : "");
        this.ed2kAction.setText(
                switch (connection) {
                    case DISCONNECTED -> this.gui.t("IDS_MAIN_BTN_CONNECT");
                    case CONNECTING -> this.gui.t("IDS_MAIN_BTN_CANCEL");
                    case CONNECTED -> this.gui.t("IDS_MAIN_BTN_DISCONNECT");
                });
        Optional<KadInfo> kadInfo = status.kad();
        this.kadRuns = kadInfo.isPresent();
        boolean kadUp = kadInfo.isPresent() && kadInfo.get().connected();
        if (kadInfo.isEmpty()) {
            state(this.kad, "Kad: " + this.gui.t("nj.kad.off"), false, false);
        } else if (!kadUp) {
            state(this.kad, "Kad: " + this.gui.t("IDS_NOTCONNECTED"), false, true);
        } else {
            KadInfo k = kadInfo.get();
            boolean firewalled = k.firewalled();
            // Behind a TCP firewall, whether UDP gets through tells if others still reach us, once tested.
            String udp = firewalled && (k.udpVerified() || k.udpFirewalled())
                    ? ", " + this.gui.t(k.udpFirewalled() ? "nj.kad.udpFirewalled" : "nj.kad.udpOpen")
                    : "";
            state(
                    this.kad,
                    "Kad: " + (firewalled ? this.gui.t("IDS_FIREWALLED") : this.gui.t("IDS_KADOPEN")) + udp,
                    !firewalled,
                    firewalled);
        }
        this.kadNote.setText(
                kadUp
                        ? this.gui.t("IDS_KADCONTACTLAB") + ": "
                                + this.gui.formats().count(kadInfo.get().contacts())
                        : "");
        this.kadAction.setText(this.gui.t(this.kadRuns ? "IDS_MAIN_BTN_DISCONNECT" : "IDS_MAIN_BTN_CONNECT"));
        this.both.setText(
                switch (connection) {
                    case DISCONNECTED -> this.gui.t("IDS_MAIN_BTN_CONNECT");
                    case CONNECTING -> this.gui.t("IDS_MAIN_BTN_CANCEL");
                    case CONNECTED -> this.gui.t("IDS_MAIN_BTN_DISCONNECT");
                });
        this.both.setGraphic(new Icon(connection == EngineModel.Connection.DISCONNECTED ? Icons.LINK : Icons.POWER));
        // Connecting is what to do while disconnected: only then does the button take the accent.
        this.both.getStyleClass().remove(Styles.ACCENT);
        if (connection == EngineModel.Connection.DISCONNECTED) {
            this.both.getStyleClass().add(Styles.ACCENT);
        }
        boolean up = connection == EngineModel.Connection.CONNECTED || kadUp;
        boolean trying = !up && (connection == EngineModel.Connection.CONNECTING || kadInfo.isPresent());
        this.state.setText(this.gui.t(up ? "IDS_CONNECTED" : trying ? "IDS_CONNECTING" : "IDS_NOTCONNECTED"));
        this.dot.getStyleClass().removeAll("connected", "connecting");
        if (up) {
            this.dot.getStyleClass().add("connected");
        } else if (trying) {
            this.dot.getStyleClass().add("connecting");
        }
        String ed2kShort = switch (connection) {
            case CONNECTED ->
                status.lowId() ? this.gui.t("IDS_IDLOW") : status.clientId() != 0 ? this.gui.t("IDS_IDHIGH") : server;
            case CONNECTING -> "eD2K: " + this.gui.t("IDS_CONNECTING");
            case DISCONNECTED -> "eD2K: " + this.gui.t("IDS_NOTCONNECTED");
        };
        // Nothing to add while neither network runs: the state says it all.
        this.detail.setText(up || trying ? ed2kShort + " · " + this.kad.getText() : "");
        showDetail();
        String said = this.gui.t("IDS_NETWORK") + ": " + this.state.getText()
                + (this.detail.getText().isEmpty() ? "" : ", " + this.detail.getText());
        this.button.setAccessibleText(said);
        this.button.setTooltip(new Tooltip(this.ed2k.getText() + "\n" + this.kad.getText()));
    }

    /** Both networks' state in a line, for the network's place and its summary. */
    String summary() {
        return this.ed2k.getText() + " · " + this.kad.getText();
    }

    Label ed2kChip() {
        return this.ed2k;
    }

    Label kadChip() {
        return this.kad;
    }

    /** What connects or disconnects both networks. */
    Button bothButton() {
        return this.both;
    }

    /** The menu's content, for tests. */
    VBox menu() {
        return this.menuBox;
    }

    void showMenu() {
        if (this.menu == null) {
            this.menu = new Popover(this.menuBox);
            this.menu.setHeaderAlwaysVisible(false);
            this.menu.setDetachable(false);
            // Under the button, towards the window's inside.
            this.menu.setArrowLocation(
                    this.gui.orientation() == NodeOrientation.RIGHT_TO_LEFT
                            ? Popover.ArrowLocation.TOP_LEFT
                            : Popover.ArrowLocation.TOP_RIGHT);
        }
        this.menu.show(this.button);
    }

    private void hideMenu() {
        if (this.menu != null) {
            this.menu.hide();
        }
    }

    private void showDetail() {
        boolean shown = !this.compact.get() && !this.detail.getText().isEmpty();
        this.detail.setVisible(shown);
        this.detail.setManaged(shown);
    }

    private String serverCounts(ServerInfo server) {
        return this.gui.f(
                "nj.status.server",
                this.gui.formats().count(server.users()),
                this.gui.formats().count(server.files()));
    }

    private Node row(Label chip, Label note, Button action) {
        VBox words = new VBox(2, chip, note);
        words.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(words, Priority.ALWAYS);
        action.getStyleClass().add(Styles.SMALL);
        HBox row = new HBox(12, words, action);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("network-row");
        return row;
    }

    // A line of the menu: a flat button the width of the menu, with its keys at the end.
    private Button item(String text, String keys, Runnable action) {
        Button item = new Button(text);
        item.getStyleClass().addAll(Styles.FLAT, "network-item");
        item.setMaxWidth(Double.MAX_VALUE);
        item.setAlignment(Pos.CENTER_LEFT);
        if (!keys.isEmpty()) {
            Label shortcut = Fx.leftToRight(new Label(keys));
            shortcut.getStyleClass().add("network-keys");
            item.setGraphic(shortcut);
            item.setContentDisplay(ContentDisplay.RIGHT);
            item.setGraphicTextGap(24);
        }
        item.setOnAction(e -> {
            hideMenu();
            action.run();
        });
        return item;
    }

    private void opens(Label chip, Runnable show) {
        chip.setOnMouseClicked(e -> {
            hideMenu();
            show.run();
        });
        chip.setCursor(Cursor.HAND);
        chip.setTooltip(new Tooltip(this.gui.t("IDS_NETWORK")));
    }

    private static void state(Label chip, String text, boolean on, boolean partly) {
        chip.setText(text);
        chip.getStyleClass().removeAll("connected", "connecting");
        if (on) {
            chip.getStyleClass().add("connected");
        } else if (partly) {
            chip.getStyleClass().add("connecting");
        }
    }

    private static Label chip() {
        Label label = new Label("", new Icon(Icons.DOT));
        label.getStyleClass().add("connection-chip");
        return label;
    }

    private static Label note() {
        Label label = new Label();
        label.getStyleClass().add("network-note");
        label.managedProperty().bind(label.textProperty().isNotEmpty());
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        return label;
    }
}
