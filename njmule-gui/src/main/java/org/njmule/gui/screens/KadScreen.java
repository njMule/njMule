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

import java.net.URI;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.UrlBox;

/** The state of our Kad node and joining Kad through a known node. */
public final class KadScreen implements Screen {

    private final Gui gui;
    private Node view;
    private final Label state = value();
    private final Label contacts = value();
    private final Label verified = value();
    private final Label id = value();
    private final TextField node = new TextField();
    private Input<ServerAddress> nodeInput;
    private UrlBox nodes;
    private final Button connect = new Button();
    private final Button recheck = new Button();

    public KadScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "kad";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_KADEMLIA");
    }

    @Override
    public Ikon icon() {
        return Material2AL.DEVICE_HUB;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = this.build();
        }
        return this.view;
    }

    private Node build() {
        this.node.setPromptText(this.gui.t("nj.kad.nodePrompt"));
        this.node.setPrefColumnCount(20);
        this.node.setOnAction(e -> this.bootstrap());
        this.nodeInput = new Inputs(this.gui)
                .address(this.node, this.gui.t("IDS_BOOTSTRAP"))
                .quietWhenEmpty();
        Button start = Fx.primary(Fx.button(this.gui.t("IDS_BOOTSTRAP"), Material2AL.LOGIN, this::bootstrap));
        start.disableProperty().bind(this.nodeInput.validProperty().not());
        this.connect.setOnAction(e -> this.connectOrDisconnect());
        this.recheck.setText(this.gui.t("IDS_KAD_RECHECKFW"));
        this.recheck.setGraphic(new FontIcon(Material2MZ.SECURITY));
        this.recheck.setOnAction(e -> this.gui.model().recheckKadFirewall());
        var tools = Fx.toolRow(this.connect, this.recheck, Fx.spacer(), this.node, start);
        FlowPane cards = new FlowPane(
                12,
                12,
                card(this.gui.t("nj.kad.state"), this.state),
                card(this.gui.t("IDS_KADCONTACTLAB"), this.contacts),
                card(this.gui.t("nj.kad.verified"), this.verified),
                card(this.gui.t("IDS_KADID"), this.id));
        Label hint = new Label(this.gui.t("nj.kad.hint"));
        hint.setWrapText(true);
        this.gui.model().statusProperty().addListener((o, was, now) -> this.show(now));
        this.show(this.gui.model().statusProperty().get());
        return Fx.screen(this.title(), tools, new VBox(16, this.nodesRow(), cards, hint));
    }

    // Contacts from a nodes.dat on the web, from an address typed or used before.
    private Node nodesRow() {
        this.nodes = new UrlBox(this.gui, "nodesDat", this.gui.t("IDS_BOOTSRAPNODESDAT"));
        this.nodes.node().setPromptText("https://\u2026/nodes.dat");
        this.nodes.node().getEditor().setOnAction(e -> this.nodesFromUrl());
        Button take = Fx.button(this.gui.t("IDS_BOOTSTRAP"), Material2MZ.REFRESH, this::nodesFromUrl);
        take.disableProperty().bind(this.nodes.input().validProperty().not());
        HBox row = new HBox(8, new Label(this.gui.t("IDS_BOOTSRAPNODESDAT")), this.nodes.node(), take);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(this.nodes.node(), Priority.ALWAYS);
        return row;
    }

    void nodesFromUrl() {
        if (!this.nodes.input().isValid()) {
            return;
        }
        URI url = this.nodes.input().value();
        this.gui.inform().accept(this.gui.f("IDS_DOWNLOADING_NODESDAT_FROM", url));
        this.gui.model().addKadContacts(url, added -> {
            this.nodes.worked(url);
            this.gui.inform().accept(this.gui.f("nj.kad.fromUrl", added, url.getHost()));
        });
    }

    UrlBox nodesBox() {
        return this.nodes;
    }

    // Connect starts Kad from the known contacts; while it runs the button stops it.
    void connectOrDisconnect() {
        if (this.gui.model().statusProperty().get().kad().isPresent()) {
            this.gui.model().disconnectKad();
        } else {
            this.gui.model().connectKad();
        }
    }

    Button connectButton() {
        return this.connect;
    }

    Button recheckButton() {
        return this.recheck;
    }

    private void show(EngineStatus status) {
        boolean running = status.kad().isPresent();
        this.connect.setText(this.gui.t(running ? "IDS_MAIN_BTN_DISCONNECT" : "IDS_MAIN_BTN_CONNECT"));
        this.recheck.setDisable(!running);
        this.connect.setGraphic(new FontIcon(running ? Material2AL.LINK_OFF : Material2AL.LINK));
        Optional<KadInfo> kad = status.kad();
        if (kad.isEmpty()) {
            this.state.setText(this.gui.t("nj.kad.off"));
            this.contacts.setText("-");
            this.verified.setText("-");
            this.id.setText("-");
            return;
        }
        KadInfo k = kad.get();
        if (!k.connected()) {
            this.state.setText(this.gui.t("IDS_NOTCONNECTED"));
        } else {
            this.state.setText(k.firewalled() ? this.gui.t("IDS_FIREWALLED") : this.gui.t("IDS_KADOPEN"));
        }
        this.contacts.setText(this.gui.formats().count(k.contacts()));
        this.verified.setText(this.gui.formats().count(k.verifiedContacts()));
        this.id.setText(k.id());
    }

    void bootstrap() {
        if (!this.nodeInput.isValid()) {
            this.gui
                    .inform()
                    .accept(this.gui.f(
                            "nj.servers.badAddress", this.node.getText().strip()));
            return;
        }
        this.gui.model().kadBootstrap(this.nodeInput.value());
        this.node.clear();
    }

    Label stateLabel() {
        return this.state;
    }

    TextField nodeField() {
        return this.node;
    }

    private static Label value() {
        Label label = new Label("-");
        label.getStyleClass().add("stat-value");
        return label;
    }

    static VBox card(String name, Label value) {
        Label title = new Label(name);
        title.getStyleClass().add("stat-name");
        VBox box = new VBox(title, value);
        box.getStyleClass().add("stat-card");
        box.setMinWidth(170);
        return box;
    }
}
