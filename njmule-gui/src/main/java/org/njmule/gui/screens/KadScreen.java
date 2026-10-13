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

import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Screen;

/** The state of our Kad node and joining Kad through a known node. */
public final class KadScreen implements Screen {

    private final Gui gui;
    private Node view;
    private final Label state = value();
    private final Label contacts = value();
    private final Label verified = value();
    private final Label id = value();
    private final TextField node = new TextField();

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
        var tools =
                Fx.toolRow(
                        this.node,
                        Fx.button(this.gui.t("IDS_BOOTSTRAP"), Material2AL.LOGIN, this::bootstrap));
        FlowPane cards =
                new FlowPane(
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
        return Fx.screen(this.title(), tools, new VBox(16, cards, hint));
    }

    private void show(EngineStatus status) {
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
            this.state.setText(
                    k.firewalled() ? this.gui.t("IDS_FIREWALLED") : this.gui.t("IDS_KADOPEN"));
        }
        this.contacts.setText(this.gui.formats().count(k.contacts()));
        this.verified.setText(this.gui.formats().count(k.verifiedContacts()));
        this.id.setText(k.id());
    }

    void bootstrap() {
        String text = this.node.getText().strip();
        try {
            this.gui.model().kadBootstrap(ServerAddress.parse(text));
            this.node.clear();
        } catch (IllegalArgumentException e) {
            this.gui.inform().accept(this.gui.f("nj.servers.badAddress", text));
        }
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
