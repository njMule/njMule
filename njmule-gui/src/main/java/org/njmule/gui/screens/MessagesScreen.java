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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javax.imageio.ImageIO;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.model.Chats;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;

/** Friends on the left, a tab for each conversation on the right. */
public final class MessagesScreen implements Screen {

    private final Gui gui;
    private Node view;
    private final ListView<LiveList.Row<Friend>> friends = new ListView<>();
    private final TabPane tabs = new TabPane();
    private final TextField address = new TextField();
    private Input<ServerAddress> addressInput;
    private final Map<Chats.Chat, Tab> open = new HashMap<>();

    public MessagesScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "messages";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_MESSAGES");
    }

    @Override
    public Icons icon() {
        return Icons.MESSAGE;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        this.friends.setItems(this.gui.model().friends().rows());
        this.friends.getStyleClass().add("friends-list");
        this.friends.setPlaceholder(new Label(this.gui.t("nj.chat.noFriends")));
        this.friends.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(LiveList.Row<Friend> row, boolean empty) {
                super.updateItem(row, empty);
                setText(empty || row == null ? null : MessagesScreen.this.friendText(row.get()));
            }
        });
        this.gui.subscriptions().onChange(this.gui.model().friendSlotProperty(), (was, now) -> this.friends.refresh());
        Label friendsTitle = new Label(this.gui.t("IDS_FRIENDLIST"));
        friendsTitle.getStyleClass().add("settings-section");
        VBox left = new VBox(
                6,
                friendsTitle,
                this.friends,
                Fx.toolRow(
                        Fx.button(this.gui.t("nj.chat.open"), Icons.MESSAGE, this::chatWithFriend),
                        Fx.button(this.gui.t("IDS_REMOVE"), Icons.DELETE, this::removeFriend)),
                Fx.toolRow(Fx.button(this.gui.t("IDS_FRIENDSLOT"), Icons.STAR, this::toggleFriendSlot)));
        VBox.setVgrow(this.friends, Priority.ALWAYS);
        left.setPadding(new Insets(0, 8, 0, 0));
        left.setMinWidth(220);

        this.address.setPromptText(this.gui.i18n().embed(this.gui.t("nj.servers.addressPrompt")));
        this.address.setPrefColumnCount(18);
        this.address.setOnAction(e -> startChat());
        this.addressInput = new Inputs(this.gui)
                .address(this.address, this.gui.t("nj.chat.start"))
                .quietWhenEmpty();
        Button start = Fx.primary(Fx.button(this.gui.t("nj.chat.start"), Icons.ADD, this::startChat));
        start.disableProperty().bind(this.addressInput.validProperty().not());
        this.tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        Chats chats = this.gui.model().chats();
        this.gui.subscriptions().onListChange(chats.all(), c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(this::opened);
                c.getRemoved().forEach(this::closed);
            }
        });
        chats.all().forEach(this::opened);
        Label none = new Label(this.gui.t("nj.chat.none"));
        none.visibleProperty().bind(Bindings.isEmpty(this.tabs.getTabs()));
        SplitPane split = new SplitPane(left, new StackPane(none, this.tabs));
        split.setDividerPositions(0.26);
        // The address is named beside its box: the box has the cursor when the screen opens, which hides its hint.
        Label where = Fx.whole(new Label(this.gui.t("IDS_SV_ADDRESS")));
        where.setLabelFor(this.address);
        var tools = Fx.toolRow(where, this.address, start);
        return Fx.screen(title(), tools, split);
    }

    private String friendText(Friend f) {
        String name = f.name().isEmpty() ? f.userHash().toString() : f.name();
        String text = f.address()
                .map(a -> this.gui.i18n().embed(name) + "  (" + this.gui.i18n().embed(a.host() + ":" + a.port()) + ")")
                .orElse(name);
        boolean slot = this.gui
                .model()
                .friendSlotProperty()
                .get()
                .filter(f.userHash()::equals)
                .isPresent();
        return slot ? text + "  " + this.gui.t("IDS_FRIENDDETAIL") : text;
    }

    // The selected friend gets the friend slot, or loses it when it has it.
    void toggleFriendSlot() {
        LiveList.Row<Friend> row = this.friends.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        Hash16 friend = row.get().userHash();
        boolean has = this.gui
                .model()
                .friendSlotProperty()
                .get()
                .filter(friend::equals)
                .isPresent();
        this.gui.model().setFriendSlot(has ? Optional.empty() : Optional.of(friend));
    }

    void startChat() {
        String text = this.address.getText().strip();
        if (!this.addressInput.isValid()) {
            this.gui.warn().accept(this.gui.f("nj.servers.badAddress", text));
            return;
        }
        ServerAddress to = this.addressInput.value();
        // Clients are reached on their IPv4 address; only an IPv6 one holds colons.
        if (to.host().contains(":")) {
            this.gui.warn().accept(this.gui.f("nj.chat.ipv4Only", text));
            return;
        }
        select(this.gui.model().chats().with(to.toString()));
        this.address.clear();
    }

    void chatWithFriend() {
        LiveList.Row<Friend> row = this.friends.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        Optional<ServerAddress> where = row.get().address();
        if (where.isEmpty()) {
            this.gui.warn().accept(this.gui.t("nj.chat.noAddress"));
            return;
        }
        select(this.gui
                .model()
                .chats()
                .with(where.get().host() + ":" + where.get().port()));
    }

    void removeFriend() {
        LiveList.Row<Friend> row = this.friends.getSelectionModel().getSelectedItem();
        if (row != null) {
            this.gui.model().removeFriend(row.get().userHash());
        }
    }

    private void select(Chats.Chat chat) {
        Tab tab = this.open.get(chat);
        if (tab != null) {
            this.tabs.getSelectionModel().select(tab);
        }
    }

    private void opened(Chats.Chat chat) {
        if (this.open.containsKey(chat)) {
            return;
        }
        Tab tab = new Tab(chat.address(), new ChatPane(chat));
        tab.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> chat.nickProperty().get().isEmpty()
                                ? chat.address()
                                : chat.nickProperty().get(),
                        chat.nickProperty()));
        tab.setOnClosed(e -> this.gui.model().chats().close(chat));
        // A conversation that has news while another shows is marked until it is looked at.
        this.gui.subscriptions().onListChange(chat.lines(), c -> {
            while (c.next()) {
                boolean theirs =
                        c.wasAdded() && c.getAddedSubList().stream().anyMatch(l -> l.author() == Chats.Author.THEM);
                if (theirs && !tab.isSelected()) {
                    unread(tab, true);
                }
            }
        });
        tab.selectedProperty().addListener((o, was, now) -> {
            if (now) {
                unread(tab, false);
            }
        });
        this.open.put(chat, tab);
        this.tabs.getTabs().add(tab);
        this.tabs.getSelectionModel().select(tab);
    }

    private static void unread(Tab tab, boolean on) {
        tab.getStyleClass().remove("unread");
        if (on) {
            tab.getStyleClass().add("unread");
            Circle dot = new Circle(4);
            dot.getStyleClass().add("unread-dot");
            tab.setGraphic(dot);
        } else {
            tab.setGraphic(null);
        }
    }

    /** Whether {@code tab} is marked as having news, for tests. */
    static boolean isUnread(Tab tab) {
        return tab.getStyleClass().contains("unread");
    }

    private void closed(Chats.Chat chat) {
        Tab tab = this.open.remove(chat);
        if (tab != null) {
            this.tabs.getTabs().remove(tab);
        }
    }

    TabPane tabs() {
        return this.tabs;
    }

    TextField addressField() {
        return this.address;
    }

    ListView<LiveList.Row<Friend>> friendsList() {
        return this.friends;
    }

    /** A BMP picture as a JavaFX image; empty if it cannot be read. */
    static Optional<Image> picture(byte[] bmp) {
        try {
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(bmp));
            if (read == null) {
                return Optional.empty();
            }
            WritableImage image = new WritableImage(read.getWidth(), read.getHeight());
            PixelWriter pixels = image.getPixelWriter();
            for (int y = 0; y < read.getHeight(); y++) {
                for (int x = 0; x < read.getWidth(); x++) {
                    pixels.setArgb(x, y, read.getRGB(x, y));
                }
            }
            return Optional.of(image);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** One conversation: its lines, a CAPTCHA when one is asked for, and the input. */
    final class ChatPane extends VBox {
        private final Chats.Chat chat;
        private final TextField input = new TextField();
        private final TextField solution = new TextField();
        // The picture must not be mirrored.
        private final ImageView captcha = Fx.leftToRight(new ImageView());
        private final VBox captchaBox;

        ChatPane(Chats.Chat chat) {
            super(8);
            this.chat = chat;
            setPadding(new Insets(8, 0, 0, 0));
            ListView<Chats.Line> lines = new ListView<>(chat.lines());
            lines.setUserData(chat);
            lines.getStyleClass().add("chat-lines");
            lines.setCellFactory(l -> new LineCell());
            MessagesScreen.this
                    .gui
                    .subscriptions()
                    .onListChange(chat.lines(), c -> lines.scrollTo(chat.lines().size() - 1));
            VBox.setVgrow(lines, Priority.ALWAYS);
            Label request = new Label(MessagesScreen.this.gui.t("IDS_CAPTCHAREQUEST"));
            request.setWrapText(true);
            // The list above gives way: the request is read whole, never cut short.
            request.setMinHeight(Region.USE_PREF_SIZE);
            this.solution.setOnAction(e -> solve());
            this.captchaBox = new VBox(
                    6,
                    request,
                    new HBox(
                            8,
                            this.captcha,
                            this.solution,
                            Fx.button(MessagesScreen.this.gui.t("nj.chat.send"), Icons.SEND, this::solve)));
            this.captchaBox.getStyleClass().add("stat-card");
            this.captchaBox.setMinHeight(Region.USE_PREF_SIZE);
            MessagesScreen.this.gui.subscriptions().onChange(chat.captchaProperty(), (was, now) -> showCaptcha(now));
            showCaptcha(chat.captchaProperty().get());
            this.input.setOnAction(e -> send());
            HBox.setHgrow(this.input, Priority.ALWAYS);
            HBox row = new HBox(
                    8,
                    this.input,
                    Fx.primary(Fx.button(MessagesScreen.this.gui.t("nj.chat.send"), Icons.SEND, this::send)),
                    Fx.button(MessagesScreen.this.gui.t("nj.chat.addFriend"), Icons.PERSON_ADD, this::befriend));
            getChildren().addAll(lines, this.captchaBox, row);
        }

        private void showCaptcha(byte[] bmp) {
            Optional<Image> image = bmp == null ? Optional.empty() : picture(bmp);
            this.captchaBox.setVisible(image.isPresent());
            this.captchaBox.setManaged(image.isPresent());
            this.captcha.setImage(image.orElse(null));
        }

        void send() {
            MessagesScreen.this
                    .gui
                    .model()
                    .sendMessage(this.chat.address(), this.input.getText().strip());
            this.input.clear();
        }

        void solve() {
            MessagesScreen.this
                    .gui
                    .model()
                    .sendMessage(this.chat.address(), this.solution.getText().strip());
            this.solution.clear();
        }

        void befriend() {
            if (this.chat.userHash().isEmpty()) {
                MessagesScreen.this.gui.warn().accept(MessagesScreen.this.gui.t("nj.chat.unknownYet"));
                return;
            }
            ServerAddress where = ServerAddress.parse(this.chat.address());
            MessagesScreen.this
                    .gui
                    .model()
                    .addFriend(new Friend(
                            this.chat.userHash().get(), this.chat.nickProperty().get(), Optional.of(where)));
        }

        TextField inputField() {
            return this.input;
        }

        TextField solutionField() {
            return this.solution;
        }

        boolean showsCaptcha() {
            return this.captchaBox.isVisible();
        }
    }

    /**
     * A line as a bubble: ours at the end of the row, theirs at the start, with the time and the author above the
     * words; notices are translated and stand alone. Long lines wrap.
     */
    private final class LineCell extends ListCell<Chats.Line> {
        private final Label meta = new Label();
        private final Label words = new Label();
        private final VBox bubble = new VBox(2, this.meta, this.words);
        private final HBox row = new HBox(this.bubble);

        LineCell() {
            this.meta.getStyleClass().add("chat-meta");
            this.words.setWrapText(true);
            this.bubble.getStyleClass().add("chat-bubble");
            this.bubble.setMinWidth(Region.USE_PREF_SIZE);
            // The cell takes the width of the list rather than of its text, so that long lines wrap.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Chats.Line line, boolean empty) {
            super.updateItem(line, empty);
            getStyleClass().removeAll("chat-us", "chat-them", "chat-notice");
            setText(null);
            if (empty || line == null) {
                setGraphic(null);
                return;
            }
            String time = MessagesScreen.this.gui.formats().time(line.time());
            // What people wrote keeps its own direction, whatever the language of the interface.
            I18n i18n = MessagesScreen.this.gui.i18n();
            switch (line.author()) {
                case US -> {
                    this.meta.setText(time + "  " + i18n.t("nj.chat.me"));
                    this.words.setText(i18n.embed(line.text()));
                }
                case THEM -> {
                    this.meta.setText(time + "  " + i18n.embed(who()));
                    this.words.setText(i18n.embed(line.text()));
                }
                case NOTICE -> {
                    this.meta.setText(time);
                    this.words.setText(MessagesScreen.this.gui.f(line.text(), line.detail()));
                }
            }
            this.row.setAlignment(
                    switch (line.author()) {
                        case US -> Pos.CENTER_RIGHT;
                        case THEM -> Pos.CENTER_LEFT;
                        case NOTICE -> Pos.CENTER;
                    });
            if (getListView() != null && !this.bubble.maxWidthProperty().isBound()) {
                this.bubble
                        .maxWidthProperty()
                        .bind(getListView().widthProperty().multiply(0.75));
            }
            setGraphic(this.row);
            getStyleClass().add("chat-" + line.author().name().toLowerCase(Locale.ROOT));
        }

        private String who() {
            return getListView().getUserData() instanceof Chats.Chat chat
                            && !chat.nickProperty().get().isEmpty()
                    ? chat.nickProperty().get()
                    : "?";
        }
    }
}
