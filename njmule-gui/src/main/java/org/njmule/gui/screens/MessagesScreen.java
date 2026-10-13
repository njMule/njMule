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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javax.imageio.ImageIO;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.Friend;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.model.Chats;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;

/** Friends on the left, a tab for each conversation on the right. */
public final class MessagesScreen implements Screen {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

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
    public Ikon icon() {
        return Material2MZ.MESSAGE;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = this.build();
        }
        return this.view;
    }

    private Node build() {
        this.friends.setItems(this.gui.model().friends().rows());
        this.friends.setPlaceholder(new Label(this.gui.t("nj.chat.noFriends")));
        this.friends.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(LiveList.Row<Friend> row, boolean empty) {
                super.updateItem(row, empty);
                this.setText(empty || row == null ? null : friendText(row.get()));
            }
        });
        Label friendsTitle = new Label(this.gui.t("IDS_PW_FRIENDS"));
        friendsTitle.getStyleClass().add("settings-section");
        VBox left = new VBox(
                6,
                friendsTitle,
                this.friends,
                Fx.toolRow(
                        Fx.button(this.gui.t("nj.chat.open"), Material2MZ.MESSAGE, this::chatWithFriend),
                        Fx.button(this.gui.t("IDS_REMOVE"), Material2AL.DELETE, this::removeFriend)));
        VBox.setVgrow(this.friends, Priority.ALWAYS);
        left.setPadding(new Insets(0, 8, 0, 0));
        left.setMinWidth(220);

        this.address.setPromptText(this.gui.t("nj.servers.addressPrompt"));
        this.address.setPrefColumnCount(18);
        this.address.setOnAction(e -> this.startChat());
        this.addressInput = new Inputs(this.gui)
                .address(this.address, this.gui.t("nj.chat.start"))
                .quietWhenEmpty();
        Button start = Fx.primary(Fx.button(this.gui.t("nj.chat.start"), Material2AL.ADD, this::startChat));
        start.disableProperty().bind(this.addressInput.validProperty().not());
        this.tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        Chats chats = this.gui.model().chats();
        chats.all().addListener((ListChangeListener<Chats.Chat>) c -> {
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
        var tools = Fx.toolRow(this.address, start);
        return Fx.screen(this.title(), tools, split);
    }

    private static String friendText(Friend f) {
        String name = f.name().isEmpty() ? f.userHash().toString() : f.name();
        return f.address()
                .map(a -> name + "  (" + a.host() + ":" + a.port() + ")")
                .orElse(name);
    }

    void startChat() {
        String text = this.address.getText().strip();
        if (!this.addressInput.isValid()) {
            this.gui.inform().accept(this.gui.f("nj.servers.badAddress", text));
            return;
        }
        this.select(this.gui.model().chats().with(this.addressInput.value().toString()));
        this.address.clear();
    }

    void chatWithFriend() {
        LiveList.Row<Friend> row = this.friends.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        Optional<ServerAddress> where = row.get().address();
        if (where.isEmpty()) {
            this.gui.inform().accept(this.gui.t("nj.chat.noAddress"));
            return;
        }
        this.select(this.gui
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
        this.open.put(chat, tab);
        this.tabs.getTabs().add(tab);
        this.tabs.getSelectionModel().select(tab);
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
        private final ImageView captcha = new ImageView();
        private final VBox captchaBox;

        ChatPane(Chats.Chat chat) {
            super(8);
            this.chat = chat;
            this.setPadding(new Insets(8, 0, 0, 0));
            ListView<Chats.Line> lines = new ListView<>(chat.lines());
            lines.setUserData(chat);
            lines.setCellFactory(l -> new LineCell());
            chat.lines().addListener((ListChangeListener<Chats.Line>)
                    c -> lines.scrollTo(chat.lines().size() - 1));
            VBox.setVgrow(lines, Priority.ALWAYS);
            Label request = new Label(MessagesScreen.this.gui.t("IDS_CAPTCHAREQUEST"));
            request.setWrapText(true);
            this.solution.setOnAction(e -> this.solve());
            this.captchaBox = new VBox(
                    6,
                    request,
                    new HBox(
                            8,
                            this.captcha,
                            this.solution,
                            Fx.button(MessagesScreen.this.gui.t("nj.chat.send"), Material2MZ.SEND, this::solve)));
            this.captchaBox.getStyleClass().add("stat-card");
            chat.captchaProperty().addListener((o, was, now) -> this.showCaptcha(now));
            this.showCaptcha(chat.captchaProperty().get());
            this.input.setOnAction(e -> this.send());
            HBox.setHgrow(this.input, Priority.ALWAYS);
            HBox row = new HBox(
                    8,
                    this.input,
                    Fx.primary(Fx.button(MessagesScreen.this.gui.t("nj.chat.send"), Material2MZ.SEND, this::send)),
                    Fx.button(MessagesScreen.this.gui.t("nj.chat.addFriend"), Material2MZ.PERSON_ADD, this::befriend));
            this.getChildren().addAll(lines, this.captchaBox, row);
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
                MessagesScreen.this.gui.inform().accept(MessagesScreen.this.gui.t("nj.chat.unknownYet"));
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

    /** A line: time, author and text; notices are translated. */
    private final class LineCell extends ListCell<Chats.Line> {
        @Override
        protected void updateItem(Chats.Line line, boolean empty) {
            super.updateItem(line, empty);
            this.getStyleClass().removeAll("chat-us", "chat-them", "chat-notice");
            if (empty || line == null) {
                this.setText(null);
                return;
            }
            String time = TIME.format(line.time());
            String text = switch (line.author()) {
                case US -> time + "  " + MessagesScreen.this.gui.t("nj.chat.me") + ": " + line.text();
                case THEM -> time + "  " + this.who() + ": " + line.text();
                case NOTICE -> time + "  " + MessagesScreen.this.gui.f(line.text(), line.detail());
            };
            this.setText(text);
            this.getStyleClass().add("chat-" + line.author().name().toLowerCase(Locale.ROOT));
        }

        private String who() {
            return this.getListView().getUserData() instanceof Chats.Chat chat
                            && !chat.nickProperty().get().isEmpty()
                    ? chat.nickProperty().get()
                    : "?";
        }
    }
}
