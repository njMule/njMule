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
package org.njmule.gui.screens;

import atlantafx.base.theme.Styles;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.ServerInfo;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.webui.WebUiSettings;

/** My Info: our state on eD2K and Kad, the server we are on and the web interface. */
final class MyInfoPane {

    /** A line of the pane: a section title, or a label with its value. */
    record Line(boolean title, String label, String value) {
        static Line title(String text) {
            return new Line(true, text, "");
        }

        static Line of(String label, String value) {
            return new Line(false, label, value);
        }
    }

    private final Gui gui;
    private final VBox box = new VBox(2);
    private List<Line> shown = List.of();
    private WebUiSettings web = WebUiSettings.DEFAULTS;
    private String lanAddress = "";

    MyInfoPane(Gui gui) {
        this.gui = gui;
    }

    Node node() {
        this.box.setPadding(new Insets(4, 8, 4, 8));
        this.box.getStyleClass().add("my-info");
        Label head = new Label(this.gui.t("IDS_MYINFO"));
        head.getStyleClass().add(Styles.TEXT_BOLD);
        ScrollPane scroll = new ScrollPane(this.box);
        scroll.setFitToWidth(true);
        HBox top = new HBox(head);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setMinHeight(30);
        VBox pane = new VBox(6, top, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        pane.setMinWidth(220);
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> this.refresh());
        this.gui.subscriptions().onChange(this.gui.model().connectionProperty(), (was, now) -> this.refresh());
        this.readWebUi();
        this.refresh();
        return pane;
    }

    /** The web interface settings can change in the settings screen: read again when the screen shows. */
    void readWebUi() {
        this.web = WebUiSettings.load(this.gui.directories().config());
        this.lanAddress = this.web.lan() ? lanAddress().orElse("") : "";
        this.refresh();
    }

    void refresh() {
        List<Line> lines = this.lines();
        if (lines.equals(this.shown)) {
            return;
        }
        this.shown = lines;
        this.box.getChildren().clear();
        GridPane grid = null;
        int row = 0;
        for (Line line : lines) {
            if (line.title()) {
                Label title = new Label(line.label());
                title.getStyleClass().add(Styles.TEXT_BOLD);
                if (!this.box.getChildren().isEmpty()) {
                    VBox.setMargin(title, new Insets(8, 0, 0, 0));
                }
                grid = grid();
                row = 0;
                this.box.getChildren().addAll(title, grid);
            } else if (grid != null) {
                // Addresses, numbers and names read in their own direction.
                Label value = Fx.ownDirection(new Label(line.value()));
                value.setWrapText(true);
                grid.addRow(row++, new Label(line.label()), value);
            }
        }
    }

    List<Line> lines() {
        EngineStatus status = this.gui.model().statusProperty().get();
        EngineModel.Connection connection =
                this.gui.model().connectionProperty().get();
        List<Line> lines = new ArrayList<>();
        String state = this.gui.t("IDS_STATUS") + ":";
        String ipPort = this.gui.t("IDS_IP") + ":" + this.gui.t("IDS_PORT") + ":";

        lines.add(Line.title("eD2K " + this.gui.t("IDS_NETWORK")));
        String ed2k = switch (connection) {
            case CONNECTED -> "IDS_CONNECTED";
            case CONNECTING -> "IDS_CONNECTING";
            case DISCONNECTED -> "IDS_DISCONNECTED";
        };
        lines.add(Line.of(state, this.gui.t(ed2k)));
        Optional<ServerInfo> server = status.server();
        if (connection == EngineModel.Connection.CONNECTED && server.isPresent()) {
            lines.add(Line.of(
                    ipPort,
                    status.publicIp().isEmpty()
                            ? this.gui.t("IDS_UNKNOWN")
                            : status.publicIp() + ":" + status.tcpPort()));
            lines.add(Line.of(this.gui.t("IDS_ID") + ":", Long.toString(status.clientId())));
            lines.add(Line.of("", this.gui.t(status.lowId() ? "IDS_IDLOW" : "IDS_IDHIGH")));

            ServerInfo s = server.get();
            lines.add(Line.title("eD2K " + this.gui.t("IDS_SERVER")));
            lines.add(Line.of(this.gui.t("IDS_SW_NAME") + ":", s.name()));
            lines.add(Line.of(this.gui.t("IDS_DESCRIPTION") + ":", s.description()));
            lines.add(Line.of(ipPort, s.address()));
            lines.add(Line.of(this.gui.t("IDS_VERSION") + ":", s.version()));
            lines.add(Line.of(this.gui.t("IDS_UUSERS") + ":", this.gui.formats().count(s.users())));
            lines.add(
                    Line.of(this.gui.t("IDS_PW_FILES") + ":", this.gui.formats().count(s.files())));
            lines.add(Line.of(
                    this.gui.t("IDS_CONNECTIONS") + ":",
                    this.gui.t(status.obfuscated() ? "IDS_OBFUSCATED" : "IDS_PRIONORMAL")));
        }

        lines.add(Line.title(this.gui.t("IDS_KADEMLIA") + " " + this.gui.t("IDS_NETWORK")));
        Optional<KadInfo> kad = status.kad();
        if (kad.isPresent() && kad.get().connected()) {
            KadInfo k = kad.get();
            lines.add(Line.of(state, this.gui.t(k.firewalled() ? "IDS_FIREWALLED" : "IDS_KADOPEN")));
            lines.add(Line.of("UDP " + state, KadScreen.udpState(this.gui, k)));
            lines.add(Line.of(this.gui.t("IDS_BUDDY") + ":", KadScreen.buddyState(this.gui, k)));
            lines.add(Line.of(
                    ipPort, k.publicIp().isEmpty() ? this.gui.t("IDS_UNKNOWN") : k.publicIp() + ":" + k.udpPort()));
            lines.add(Line.of(this.gui.t("IDS_ID") + ":", k.publicIp().isEmpty() ? "" : number(k.publicIp())));
        } else {
            lines.add(Line.of(state, this.gui.t(kad.isPresent() ? "IDS_CONNECTING" : "IDS_DISCONNECTED")));
        }

        lines.add(Line.title(this.gui.t("IDS_WEBSRV")));
        lines.add(Line.of(state, this.gui.t(this.web.usable() ? "IDS_ENABLED" : "IDS_DISABLED")));
        if (this.web.usable()) {
            String host = this.web.lan() && !this.lanAddress.isEmpty() ? this.lanAddress : "127.0.0.1";
            lines.add(Line.of("URL:", "http://" + host + ":" + this.web.port() + "/"));
        }
        return lines;
    }

    /** An IPv4 address as the unsigned number Kad uses for its id. */
    static String number(String ip) {
        byte[] b = InetAddress.ofLiteral(ip).getAddress();
        long n = 0;
        for (byte part : b) {
            n = (n << 8) | (part & 0xFF);
        }
        return Long.toString(n);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(2);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        grid.getColumnConstraints().addAll(labels, new ColumnConstraints());
        return grid;
    }

    // The address of this computer on its LAN: the one a datagram to a public address would leave from (none is sent).
    private static Optional<String> lanAddress() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(new InetSocketAddress(InetAddress.ofLiteral("192.0.2.1"), 9));
            InetAddress local = probe.getLocalAddress();
            return local.isAnyLocalAddress() ? Optional.empty() : Optional.of(local.getHostAddress());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
