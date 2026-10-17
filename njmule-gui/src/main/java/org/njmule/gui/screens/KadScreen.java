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
import java.net.URI;
import java.util.List;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.TableLayout;
import org.njmule.gui.ui.UrlBox;

/** The state of our Kad node in plain words and in numbers, and joining Kad from a list of nodes or a known one. */
public final class KadScreen implements Screen {

    private final Gui gui;
    private Node view;
    private final Label state = value();
    private final Label udp = value();
    private final Label buddy = value();
    private final Label contacts = value();
    private final Label verified = value();
    private final Label id = Fx.leftToRight(value());
    private final Label say = new Label();
    private final Icon sayIcon = new Icon(Icons.INFO);
    private final TitledPane join = new TitledPane();
    private Boolean wasConnected;
    private final TextField node = new TextField();
    private Input<ServerAddress> nodeInput;
    private UrlBox nodes;
    private final Button connect = new Button();
    private final Button recheck = new Button();
    private int ticks;

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
    public Icons icon() {
        return Icons.KAD;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        this.node.setPromptText(this.gui.i18n().embed(this.gui.t("nj.kad.nodePrompt")));
        this.node.setPrefColumnCount(20);
        this.node.setOnAction(e -> bootstrap());
        this.nodeInput = new Inputs(this.gui)
                .address(this.node, this.gui.t("IDS_BOOTSTRAP"))
                .quietWhenEmpty();
        this.connect.setOnAction(e -> connectOrDisconnect());
        this.recheck.setText(this.gui.t("IDS_KAD_RECHECKFW"));
        this.recheck.setGraphic(new Icon(Icons.SHIELD));
        this.recheck.setOnAction(e -> this.gui.model().recheckKadFirewall());
        var tools = Fx.toolRow(this.connect, this.recheck);
        FlowPane cards = new FlowPane(
                12,
                12,
                card(this.gui.t("nj.kad.state"), this.state),
                card(this.gui.t("nj.kad.udpState"), this.udp),
                card(this.gui.t("IDS_BUDDY"), this.buddy),
                card(this.gui.t("IDS_KADCONTACTLAB"), this.contacts),
                card(this.gui.t("nj.kad.verified"), this.verified),
                card(this.gui.t("IDS_KADID"), this.id));
        this.say.setGraphic(this.sayIcon);
        this.say.setWrapText(true);
        this.say.setMaxWidth(Double.MAX_VALUE);
        this.say.setMinHeight(Region.USE_PREF_SIZE);
        this.say.getStyleClass().add("kad-say");
        this.join.setText(this.gui.t("nj.kad.join"));
        this.join.getStyleClass().add(Styles.DENSE);
        this.join.setContent(ways());
        this.gui.subscriptions().onChange(this.gui.model().statusProperty(), (was, now) -> show(now));
        show(this.gui.model().statusProperty().get());
        TabPane lists = lists();
        VBox content = new VBox(16, this.say, cards, this.join, lists);
        VBox.setVgrow(lists, Priority.ALWAYS);
        // Shown under the network, which gives the title.
        return Fx.part(tools, content);
    }

    // The contacts of the routing table and the lookups running, read again while the screen shows.
    private TabPane lists() {
        TableView<LiveList.Row<KadContactInfo>> contacts =
                Fx.table(this.gui.model().kadContacts(), this.gui.t("nj.kad.off"));
        contacts.getColumns()
                .addAll(List.of(
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_ID"), 260, KadContactInfo::id)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_TYPE"), 60, KadContactInfo::type)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_KADDISTANCE"), 260, KadContactInfo::distance)),
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_IP"), 160, KadContactInfo::address)),
                        Fx.leftToRight(Fx.column("Kad", 50, KadContactInfo::version))));
        TableLayout.remember(contacts, "kadContacts", this.gui.settings());
        TableView<LiveList.Row<KadSearchInfo>> searches =
                Fx.table(this.gui.model().kadSearches(), this.gui.t("nj.kad.noSearches"));
        searches.getColumns()
                .addAll(List.of(
                        Fx.leftToRight(Fx.column(this.gui.t("IDS_KADID"), 260, KadSearchInfo::target)),
                        Fx.column(this.gui.t("IDS_TYPE"), 140, KadSearchInfo::kind, this::kindName),
                        Fx.leftToRight(Fx.column(this.gui.t("nj.kad.answers"), 80, KadSearchInfo::answers))));
        TableLayout.remember(searches, "kadSearches", this.gui.settings());
        Tab contactsTab = new Tab(this.gui.t("IDS_KADCONTACTLAB"), contacts);
        Tab searchesTab = new Tab(this.gui.t("IDS_KADSEARCHLAB"), searches);
        TabPane tabs = new TabPane(contactsTab, searchesTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinHeight(180);
        return tabs;
    }

    private String kindName(KadSearchInfo.Kind kind) {
        return this.gui.t(
                switch (kind) {
                    case NODE -> "IDS_KAD_NODE";
                    case KEYWORD -> "IDS_KAD_SEARCHKW";
                    case SOURCES -> "IDS_KAD_SEARCHSRC";
                    case NOTES -> "IDS_NOTES";
                    case STORE_KEYWORD -> "IDS_KAD_STOREKW";
                    case STORE_SOURCE -> "IDS_KAD_STOREFILE";
                    case STORE_NOTES -> "IDS_STORENOTES";
                });
    }

    @Override
    public void shown() {
        this.gui.model().refreshKadLists();
    }

    // The two ways in, explained together: a list of nodes from the web (an address typed or used before), or one
    // client known by its address.
    private Node ways() {
        this.nodes = new UrlBox(this.gui, "nodesDat", this.gui.t("IDS_BOOTSRAPNODESDAT"));
        this.nodes.node().setPromptText("https://\u2026/nodes.dat");
        Fx.onEnter(this.nodes.node(), this::nodesFromUrl);
        this.nodes.node().setMaxWidth(Double.MAX_VALUE);
        Button take = Fx.button(this.gui.t("IDS_DOWNLOAD"), Icons.DOWNLOAD, this::nodesFromUrl);
        take.disableProperty().bind(this.nodes.input().validProperty().not());
        this.node.setMaxWidth(Double.MAX_VALUE);
        Button start = Fx.button(this.gui.t("IDS_BOOTSTRAP"), Icons.IMPORT, this::bootstrap);
        start.disableProperty().bind(this.nodeInput.validProperty().not());
        Label hint = new Label(this.gui.t("nj.kad.joinHint"));
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        GridPane ways = new GridPane(8, 8);
        ways.addRow(0, Fx.whole(new Label(this.gui.t("IDS_BOOTSRAPNODESDAT"))), this.nodes.node(), Fx.whole(take));
        ways.addRow(1, Fx.whole(new Label(this.gui.t("nj.kad.fromClient"))), this.node, Fx.whole(start));
        ColumnConstraints grows = new ColumnConstraints();
        grows.setHgrow(Priority.ALWAYS);
        ways.getColumnConstraints().addAll(new ColumnConstraints(), grows);
        return new VBox(10, hint, ways);
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
        say(status);
        // Open while Kad is not connected, closed once it is; in between, as the user leaves it.
        boolean connected = status.kad().map(KadInfo::connected).orElse(false);
        if (!Boolean.valueOf(connected).equals(this.wasConnected)) {
            this.wasConnected = connected;
            this.join.setExpanded(!connected);
        }
        // The lists follow every few seconds while the screen shows.
        if (this.view != null && this.view.getScene() != null && this.view.isVisible() && ++this.ticks % 3 == 0) {
            this.gui.model().refreshKadLists();
        }
        this.connect.setText(this.gui.t(running ? "IDS_MAIN_BTN_DISCONNECT" : "IDS_MAIN_BTN_CONNECT"));
        this.recheck.setDisable(!running);
        this.connect.setGraphic(new Icon(running ? Icons.LINK_OFF : Icons.LINK));
        Optional<KadInfo> kad = status.kad();
        if (kad.isEmpty()) {
            this.state.setText(this.gui.t("nj.kad.off"));
            this.udp.setText("-");
            this.buddy.setText("-");
            this.contacts.setText("-");
            this.verified.setText("-");
            this.id.setText("-");
            return;
        }
        KadInfo k = kad.get();
        if (!k.connected()) {
            this.state.setText(this.gui.t("IDS_NOTCONNECTED"));
            this.udp.setText("-");
            this.buddy.setText("-");
        } else {
            this.state.setText(k.firewalled() ? this.gui.t("IDS_FIREWALLED") : this.gui.t("IDS_KADOPEN"));
            this.udp.setText(udpState(this.gui, k));
            this.buddy.setText(buddyState(this.gui, k));
        }
        this.contacts.setText(this.gui.formats().count(k.contacts()));
        this.verified.setText(this.gui.formats().count(k.verifiedContacts()));
        this.id.setText(k.id());
    }

    // The state in plain words, with what to do about it.
    private void say(EngineStatus status) {
        Optional<KadInfo> kad = status.kad();
        this.say.getStyleClass().removeAll("success", "warning");
        if (kad.isEmpty()) {
            this.sayIcon.setGlyph(Icons.CLOUD_OFF);
            this.say.setText(this.gui.t("nj.kad.sayOff"));
        } else if (!kad.get().connected()) {
            this.sayIcon.setGlyph(Icons.HOURGLASS);
            this.say.setText(this.gui.t("nj.kad.sayConnecting"));
        } else if (kad.get().firewalled()) {
            this.sayIcon.setGlyph(Icons.WARNING);
            this.say.setText(this.gui.f("nj.kad.sayFirewalled", status.tcpPort()));
            this.say.getStyleClass().add("warning");
        } else {
            this.sayIcon.setGlyph(Icons.CHECK_CIRCLE);
            this.say.setText(this.gui.t("nj.kad.sayOpen"));
            this.say.getStyleClass().add("success");
        }
    }

    Label sayLabel() {
        return this.say;
    }

    TitledPane joinPane() {
        return this.join;
    }

    /** Whether other nodes can send to our UDP port: untested until a test came to an outcome. */
    static String udpState(Gui gui, KadInfo k) {
        if (!k.udpVerified() && !k.udpFirewalled()) {
            return gui.t("IDS_UNKNOWN");
        }
        return gui.t(k.udpFirewalled() ? "IDS_FIREWALLED" : "IDS_KADOPEN");
    }

    /** Our Kad buddy, or the firewalled client we are the buddy of. */
    static String buddyState(Gui gui, KadInfo k) {
        return gui.t(
                switch (k.buddy()) {
                    case NONE -> "IDS_BUDDYNONE";
                    case CONNECTING -> "IDS_CONNECTING";
                    case CONNECTED -> "IDS_CONNECTED";
                    case SERVING -> "nj.kad.buddyServing";
                });
    }

    void bootstrap() {
        if (!this.nodeInput.isValid()) {
            this.gui
                    .warn()
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
