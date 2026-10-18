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

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javax.imageio.ImageIO;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.model.Chats;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.Segments;

/**
 * One list of the conversations and the friends, the newest first, with the conversation chosen beside it: its person,
 * its lines, a picture to answer when one is asked, and a box to write.
 */
public final class MessagesScreen implements Screen {

    /** Which of the list shows. */
    enum Show {
        ALL,
        FRIENDS
    }

    /** A row of the list: a conversation, a friend, or a conversation with a friend. */
    record Entry(Optional<Chats.Chat> chat, Optional<Friend> friend) {
        String key() {
            return this.chat
                    .map(c -> "chat " + c.address())
                    .orElseGet(() -> "friend " + this.friend.orElseThrow().userHash());
        }
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Gui gui;
    private StringBinding summary;
    private Node view;
    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final FilteredList<Entry> shownEntries = new FilteredList<>(this.entries);
    private final ListView<Entry> conversations = new ListView<>(this.shownEntries);
    private final CustomTextField find = new CustomTextField();
    private Segments<Show> showing;
    private final ToggleButton newOne = new ToggleButton();
    private final TextField address = new TextField();
    private Input<ServerAddress> addressInput;
    private final Map<Chats.Chat, ChatPane> panes = new HashMap<>();
    private final Map<Chats.Chat, Integer> unread = new HashMap<>();
    private final StackPane body = new StackPane();
    private final Label none = new Label();
    private final Label personName = new Label();
    private final Label personFacts = new Label();
    private final Label avatar = new Label();
    private final ToggleButton friendSlot = new ToggleButton();
    private final Button befriendButton = new Button();
    private final MenuButton more = new MenuButton();
    private final MenuItem removeFriendItem = new MenuItem();
    private final MenuItem closeItem = new MenuItem();
    private final VBox conversation = new VBox(12);
    private final VBox friendOnly = new VBox(12);
    private final Label friendNote = new Label();
    private final TextField friendInput = new TextField();
    private boolean listing;

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
    public ObservableValue<String> summary() {
        if (this.summary == null) {
            EngineModel model = this.gui.model();
            // The conversations open and the friends; nothing while there are none.
            this.summary = Bindings.createStringBinding(
                    () -> model.chats().all().isEmpty()
                                    && model.friends().rows().isEmpty()
                            ? ""
                            : this.gui.f(
                                    "nj.summary.messages",
                                    model.chats().all().size(),
                                    model.friends().rows().size()),
                    model.chats().all(),
                    model.friends().rows());
        }
        return this.summary;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    private Node build() {
        Chats chats = this.gui.model().chats();
        this.gui.subscriptions().onListChange(chats.all(), c -> {
            while (c.next()) {
                c.getAddedSubList().forEach(this::opened);
                c.getRemoved().forEach(this::closed);
            }
            listEntries();
        });
        chats.all().forEach(this::opened);
        this.gui.subscriptions().onListChange(this.gui.model().friends().rows(), c -> listEntries());
        this.gui.subscriptions().onChange(this.gui.model().friendSlotProperty(), (was, now) -> showChosen());
        HBox all = new HBox(16, side(), person());
        listEntries();
        return Fx.screen(all);
    }

    // The find box, the new conversation, which to show, and the list.
    private Node side() {
        this.find.setPromptText(this.gui.t("nj.chat.find"));
        this.find.setLeft(Fx.icon(Icons.SEARCH));
        this.find.textProperty().addListener((o, was, now) -> narrow());
        this.find.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                this.find.clear();
            }
        });
        HBox.setHgrow(this.find, Priority.ALWAYS);
        this.newOne.setGraphic(Fx.icon(Icons.ADD));
        this.newOne.getStyleClass().add(Styles.BUTTON_ICON);
        this.newOne.setTooltip(new Tooltip(this.gui.t("nj.chat.start")));
        this.newOne.setAccessibleText(this.gui.t("nj.chat.start"));
        this.newOne.setId("new-conversation");
        HBox top = new HBox(8, this.find, this.newOne);
        top.setAlignment(Pos.CENTER_LEFT);
        // A conversation with an address typed, under the button that asks for it.
        this.address.setPromptText(this.gui.i18n().embed(this.gui.t("nj.servers.addressPrompt")));
        this.address.setOnAction(e -> startChat());
        Fx.leftToRight(this.address);
        HBox.setHgrow(this.address, Priority.ALWAYS);
        this.addressInput = new Inputs(this.gui)
                .address(this.address, this.gui.t("nj.chat.start"))
                .quietWhenEmpty();
        Button start = Fx.button(this.gui.t("nj.chat.open"), Icons.MESSAGE, this::startChat);
        start.disableProperty().bind(this.addressInput.validProperty().not());
        Label where = new Label(this.gui.t("IDS_SV_ADDRESS"));
        where.setLabelFor(this.address);
        VBox ask = new VBox(6, where, new HBox(8, this.address, start));
        ask.getStyleClass().add("new-conversation");
        ask.visibleProperty().bind(this.newOne.selectedProperty());
        ask.managedProperty().bind(ask.visibleProperty());
        this.newOne.selectedProperty().addListener((o, was, now) -> {
            if (now) {
                this.address.requestFocus();
            }
        });
        this.showing = new Segments<>(List.of(Show.values()), this::showName);
        this.showing.node().setId("conversation-show");
        this.showing.valueProperty().addListener((o, was, now) -> narrow());
        this.conversations.setCellFactory(l -> new EntryCell());
        this.conversations.getStyleClass().add("conversations");
        this.conversations.setId("conversations");
        this.conversations.setPlaceholder(placeholder("nj.chat.none"));
        this.conversations.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (!this.listing) {
                showChosen();
            }
        });
        VBox side = new VBox(10, top, ask, this.showing.node(), this.conversations);
        VBox.setVgrow(this.conversations, Priority.ALWAYS);
        side.getStyleClass().add("conversations-side");
        side.setMinWidth(240);
        side.setPrefWidth(290);
        side.setMaxWidth(320);
        return side;
    }

    // The person of the conversation chosen above its lines, or a word while none is chosen.
    private Node person() {
        this.avatar.getStyleClass().add("avatar");
        this.avatar.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        this.personName.getStyleClass().addAll("person-name", Styles.TEXT_BOLD);
        Fx.ownDirection(this.personName);
        this.personFacts.getStyleClass().add("text-muted");
        Fx.leftToRight(this.personFacts);
        this.personFacts.managedProperty().bind(this.personFacts.textProperty().isNotEmpty());
        this.personFacts.visibleProperty().bind(this.personFacts.managedProperty());
        VBox who = new VBox(2, this.personName, this.personFacts);
        who.setMinWidth(0);
        HBox.setHgrow(who, Priority.ALWAYS);
        this.friendSlot.setText(this.gui.t("IDS_FRIENDSLOT"));
        this.friendSlot.setGraphic(Fx.icon(Icons.STAR));
        this.friendSlot.setOnAction(e -> toggleFriendSlot());
        this.befriendButton.setText(this.gui.t("nj.chat.addFriend"));
        this.befriendButton.setGraphic(Fx.icon(Icons.PERSON_ADD));
        this.befriendButton.setOnAction(e -> chosenChat().ifPresent(this::befriend));
        this.removeFriendItem.setText(this.gui.t("IDS_REMOVEFRIEND"));
        this.removeFriendItem.setGraphic(Fx.icon(Icons.DELETE));
        this.removeFriendItem.setOnAction(e -> removeFriend());
        this.closeItem.setText(this.gui.t("IDS_FD_CLOSE"));
        this.closeItem.setGraphic(Fx.icon(Icons.CLOSE));
        this.closeItem.setOnAction(e -> chosenChat().ifPresent(this::close));
        this.more.setGraphic(Fx.icon(Icons.MORE));
        this.more.getItems().setAll(this.removeFriendItem, this.closeItem);
        this.more.getStyleClass().addAll(Styles.BUTTON_ICON, Tweaks.NO_ARROW);
        this.more.setTooltip(new Tooltip(this.gui.t("IDS_MORE")));
        this.more.setAccessibleText(this.gui.t("IDS_MORE"));
        for (Region r : List.of(this.friendSlot, this.befriendButton)) {
            Fx.whole(r);
            r.managedProperty().bind(r.visibleProperty());
        }
        HBox head = new HBox(12, this.avatar, who, this.friendSlot, this.befriendButton, this.more);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("person-head");
        // A friend with no conversation yet: a word and a box that starts one.
        this.friendNote.setWrapText(true);
        this.friendNote.getStyleClass().add("text-muted");
        VBox.setVgrow(this.friendNote, Priority.ALWAYS);
        this.friendNote.setMaxHeight(Double.MAX_VALUE);
        this.friendNote.setAlignment(Pos.CENTER);
        this.friendInput.setOnAction(e -> sendToFriend());
        HBox.setHgrow(this.friendInput, Priority.ALWAYS);
        HBox friendRow = new HBox(
                8, this.friendInput, Fx.primary(Fx.button(this.gui.t("nj.chat.send"), Icons.SEND, this::sendToFriend)));
        this.friendOnly.getChildren().setAll(this.friendNote, friendRow);
        VBox.setVgrow(this.body, Priority.ALWAYS);
        this.conversation.getChildren().setAll(head, this.body);
        this.conversation.getStyleClass().add("conversation");
        this.none.setText(this.gui.t("nj.chat.none"));
        this.none.setWrapText(true);
        this.none.getStyleClass().add("text-muted");
        StackPane right = new StackPane(this.none, this.conversation);
        HBox.setHgrow(right, Priority.ALWAYS);
        return right;
    }

    private void narrow() {
        List<String> words = WHITESPACE
                .splitAsStream(this.find.getText().strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .toList();
        boolean friends = this.showing.getValue() == Show.FRIENDS;
        this.shownEntries.setPredicate(e -> (!friends || e.friend().isPresent())
                && words.stream().allMatch((name(e) + " " + where(e)).toLowerCase(Locale.ROOT)::contains));
        this.conversations.setPlaceholder(
                placeholder(friends && words.isEmpty() ? "nj.chat.noFriends" : "nj.chat.none"));
    }

    // The words of an empty list, wrapping in its narrow column rather than cut short.
    private Label placeholder(String key) {
        Label words = new Label(this.gui.t(key));
        words.setWrapText(true);
        words.setTextAlignment(TextAlignment.CENTER);
        words.getStyleClass().addAll(Styles.TEXT_MUTED, "list-placeholder");
        return words;
    }

    // The conversations, each with its friend if it has one, the newest first; then the friends with none.
    private void listEntries() {
        List<Friend> friends = this.gui.model().friends().rows().stream()
                .map(LiveList.Row::get)
                .toList();
        List<Entry> list = new ArrayList<>();
        List<Friend> left = new ArrayList<>(friends);
        List<Chats.Chat> chats = new ArrayList<>(this.gui.model().chats().all());
        // Two lines may share a moment: the conversation active last comes first then.
        chats.sort(Comparator.comparing(MessagesScreen::lastTime)
                .thenComparingLong(Chats.Chat::lastActive)
                .reversed());
        for (Chats.Chat chat : chats) {
            Optional<Friend> friend =
                    friends.stream().filter(f -> sameOne(f, chat)).findFirst();
            friend.ifPresent(left::remove);
            list.add(new Entry(Optional.of(chat), friend));
        }
        left.stream()
                .sorted(Comparator.comparing(f -> f.name().toLowerCase(Locale.ROOT)))
                .forEach(f -> list.add(new Entry(Optional.empty(), Optional.of(f))));
        String was = Optional.ofNullable(this.conversations.getSelectionModel().getSelectedItem())
                .map(Entry::key)
                .orElse("");
        this.listing = true;
        try {
            this.entries.setAll(list);
            this.shownEntries.stream()
                    .filter(e -> e.key().equals(was))
                    .findFirst()
                    .ifPresent(e -> this.conversations.getSelectionModel().select(e));
        } finally {
            this.listing = false;
        }
        this.showing.count(Show.ALL, showName(Show.ALL), this.entries.size());
        this.showing.count(
                Show.FRIENDS,
                showName(Show.FRIENDS),
                this.entries.stream().filter(e -> e.friend().isPresent()).count());
        this.conversations.refresh();
        showChosen();
    }

    // A friend is the person of a conversation by their hash, or by their address while the hash is unknown.
    private static boolean sameOne(Friend friend, Chats.Chat chat) {
        if (chat.userHash().isPresent()) {
            return chat.userHash().get().equals(friend.userHash());
        }
        return friend.address()
                .map(a -> (a.host() + ":" + a.port()).equals(chat.address()))
                .orElse(false);
    }

    private static Instant lastTime(Chats.Chat chat) {
        return chat.lines().isEmpty() ? Instant.EPOCH : chat.lines().getLast().time();
    }

    private Optional<Entry> chosen() {
        return Optional.ofNullable(this.conversations.getSelectionModel().getSelectedItem());
    }

    private Optional<Chats.Chat> chosenChat() {
        return chosen().flatMap(Entry::chat);
    }

    // The person and the lines of the row chosen; a conversation looked at has no news any more.
    private void showChosen() {
        Optional<Entry> entry = chosen();
        this.none.setVisible(entry.isEmpty());
        this.conversation.setVisible(entry.isPresent());
        if (entry.isEmpty()) {
            return;
        }
        Entry e = entry.get();
        this.personName.setText(name(e));
        this.personFacts.setText(besideName(e));
        this.avatar.setText(initials(name(e)));
        this.avatar.setGraphic(initials(name(e)).isEmpty() ? Fx.icon(Icons.MESSAGE) : null);
        boolean friend = e.friend().isPresent();
        this.friendSlot.setVisible(friend);
        this.friendSlot.setSelected(friend
                && this.gui
                        .model()
                        .friendSlotProperty()
                        .get()
                        .filter(e.friend().get().userHash()::equals)
                        .isPresent());
        this.befriendButton.setVisible(!friend && e.chat().isPresent());
        this.removeFriendItem.setVisible(friend);
        this.closeItem.setVisible(e.chat().isPresent());
        if (e.chat().isPresent()) {
            Chats.Chat chat = e.chat().get();
            this.body.getChildren().setAll(this.panes.computeIfAbsent(chat, ChatPane::new));
            this.unread.remove(chat);
            this.conversations.refresh();
        } else {
            boolean reachable = e.friend().flatMap(Friend::address).isPresent();
            this.friendNote.setText(reachable ? this.gui.f("nj.chat.empty", name(e)) : this.gui.t("nj.chat.noAddress"));
            this.friendInput.setDisable(!reachable);
            this.friendInput.setPromptText(reachable ? this.gui.f("nj.chat.write", name(e)) : "");
            this.body.getChildren().setAll(this.friendOnly);
        }
    }

    private String name(Entry e) {
        Optional<String> nick = e.chat().map(c -> c.nickProperty().get()).filter(n -> !n.isEmpty());
        if (nick.isPresent()) {
            return nick.get();
        }
        return e.friend()
                .map(f -> f.name().isEmpty() ? f.userHash().toString() : f.name())
                .orElseGet(() -> e.chat().orElseThrow().address());
    }

    private static String where(Entry e) {
        return e.chat()
                .map(Chats.Chat::address)
                .or(() -> e.friend().flatMap(Friend::address).map(a -> a.host() + ":" + a.port()))
                .orElse("");
    }

    // Where the person is, unless the name already says it.
    private String besideName(Entry e) {
        String where = where(e);
        return where.equals(name(e)) ? "" : where;
    }

    // The first letters of up to two words of the name, or none for an address.
    private static String initials(String name) {
        if (name.isEmpty() || Character.isDigit(name.charAt(0)) || name.startsWith("[")) {
            return "";
        }
        StringBuilder letters = new StringBuilder();
        WHITESPACE
                .splitAsStream(name.strip())
                .filter(w -> !w.isEmpty())
                .limit(2)
                .forEach(w -> letters.appendCodePoint(w.codePointAt(0)));
        return letters.toString().toUpperCase(Locale.ROOT);
    }

    private String showName(Show show) {
        return this.gui.t(show == Show.ALL ? "nj.all" : "IDS_FRIENDLIST");
    }

    // The friend chosen gets the friend slot, or loses it when it has it.
    void toggleFriendSlot() {
        chosen().flatMap(Entry::friend).ifPresent(f -> {
            Hash16 friend = f.userHash();
            boolean has = this.gui
                    .model()
                    .friendSlotProperty()
                    .get()
                    .filter(friend::equals)
                    .isPresent();
            this.gui.model().setFriendSlot(has ? Optional.empty() : Optional.of(friend));
        });
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
        this.newOne.setSelected(false);
    }

    /** Opens the conversation with the friend chosen, on their known address. */
    void chatWithFriend() {
        Optional<Friend> friend = chosen().flatMap(Entry::friend);
        if (friend.isEmpty()) {
            return;
        }
        startWith(friend.get()).ifPresent(this::select);
    }

    private Optional<Chats.Chat> startWith(Friend friend) {
        Optional<ServerAddress> where = friend.address();
        if (where.isEmpty()) {
            this.gui.warn().accept(this.gui.t("nj.chat.noAddress"));
            return Optional.empty();
        }
        return Optional.of(this.gui
                .model()
                .chats()
                .with(where.get().host() + ":" + where.get().port()));
    }

    // Writing to a friend with no conversation starts one.
    private void sendToFriend() {
        String text = this.friendInput.getText().strip();
        Optional<Friend> friend = chosen().flatMap(Entry::friend);
        if (text.isEmpty() || friend.isEmpty()) {
            return;
        }
        startWith(friend.get()).ifPresent(chat -> {
            this.friendInput.clear();
            select(chat);
            this.gui.model().sendMessage(chat.address(), text);
        });
    }

    void removeFriend() {
        chosen().flatMap(Entry::friend).ifPresent(f -> this.gui.model().removeFriend(f.userHash()));
    }

    void befriend(Chats.Chat chat) {
        if (chat.userHash().isEmpty()) {
            this.gui.warn().accept(this.gui.t("nj.chat.unknownYet"));
            return;
        }
        ServerAddress where = ServerAddress.parse(chat.address());
        this.gui
                .model()
                .addFriend(new Friend(chat.userHash().get(), chat.nickProperty().get(), Optional.of(where)));
    }

    void close(Chats.Chat chat) {
        this.gui.model().chats().close(chat);
    }

    private void select(Chats.Chat chat) {
        listEntries();
        this.entries.stream()
                .filter(e -> e.chat().filter(chat::equals).isPresent())
                .findFirst()
                .ifPresent(e -> {
                    if (!this.shownEntries.contains(e)) {
                        this.find.clear();
                        this.showing.setValue(Show.ALL);
                    }
                    this.conversations.getSelectionModel().select(e);
                });
    }

    private void opened(Chats.Chat chat) {
        if (this.panes.containsKey(chat)) {
            return;
        }
        this.panes.put(chat, new ChatPane(chat));
        this.gui.subscriptions().onChange(chat.nickProperty(), (was, now) -> listEntries());
        // What they wrote before this screen was first shown was not seen either.
        long before = chat.lines().stream()
                .filter(l -> l.author() == Chats.Author.THEM)
                .count();
        if (before > 0) {
            this.unread.merge(chat, (int) before, Integer::sum);
        }
        // A conversation that has news while another shows is marked until it is looked at.
        this.gui.subscriptions().onListChange(chat.lines(), c -> {
            while (c.next()) {
                long theirs = c.wasAdded()
                        ? c.getAddedSubList().stream()
                                .filter(l -> l.author() == Chats.Author.THEM)
                                .count()
                        : 0;
                if (theirs > 0 && !chosenChat().filter(chat::equals).isPresent()) {
                    this.unread.merge(chat, (int) theirs, Integer::sum);
                }
            }
            listEntries();
        });
        if (chosen().isEmpty()) {
            Platform.runLater(this::chooseNewest);
        }
    }

    // With nothing chosen yet, the newest conversation shows.
    private void chooseNewest() {
        if (chosen().isEmpty()) {
            this.shownEntries.stream()
                    .map(Entry::chat)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(this::select);
        }
    }

    private void closed(Chats.Chat chat) {
        this.panes.remove(chat);
        this.unread.remove(chat);
    }

    /** The news not yet looked at in {@code chat}. */
    int unread(Chats.Chat chat) {
        return this.unread.getOrDefault(chat, 0);
    }

    ListView<Entry> conversations() {
        return this.conversations;
    }

    TextField addressField() {
        return this.address;
    }

    TextField findField() {
        return this.find;
    }

    Segments<Show> showSegments() {
        return this.showing;
    }

    /** The pane of {@code chat}, for tests. */
    ChatPane paneOf(Chats.Chat chat) {
        return this.panes.get(chat);
    }

    /** The name a row shows, for tests. */
    String nameOf(Entry entry) {
        return name(entry);
    }

    /** Chooses the row of {@code name} in the list, for tests. */
    void choose(String name) {
        this.entries.stream()
                .filter(e -> name(e).equals(name))
                .findFirst()
                .ifPresent(e -> this.conversations.getSelectionModel().select(e));
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

    /** A row of the list: the person's letters, name and star, the time and the last line, and the news. */
    private final class EntryCell extends ListCell<Entry> {
        private final Label letters = new Label();
        private final Label who = new Label();
        private final Icon star = Fx.icon(Icons.STAR);
        private final Label time = new Label();
        private final Label last = new Label();
        private final Label news = new Label();
        private final HBox box;

        EntryCell() {
            this.letters.getStyleClass().add("avatar");
            this.letters.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            this.who.getStyleClass().add("who");
            this.who.setMinWidth(0);
            this.star.getStyleClass().add("friend-star");
            this.time.getStyleClass().add("text-muted");
            this.time.setMinWidth(Region.USE_PREF_SIZE);
            this.last.getStyleClass().add("text-muted");
            this.last.setMinWidth(0);
            this.news.getStyleClass().add("news-badge");
            this.news.setMinWidth(Region.USE_PREF_SIZE);
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox first = new HBox(6, this.who, this.star, gap, this.time);
            first.setAlignment(Pos.CENTER_LEFT);
            Region gap2 = new Region();
            HBox.setHgrow(gap2, Priority.ALWAYS);
            HBox second = new HBox(6, this.last, gap2, this.news);
            second.setAlignment(Pos.CENTER_LEFT);
            VBox words = new VBox(2, first, second);
            words.setMinWidth(0);
            HBox.setHgrow(words, Priority.ALWAYS);
            this.box = new HBox(10, this.letters, words);
            this.box.setAlignment(Pos.CENTER_LEFT);
            // As wide as the list, never wider: long lines end in an ellipsis.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            setText(null);
            getStyleClass().remove("has-news");
            if (empty || entry == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            MessagesScreen self = MessagesScreen.this;
            String name = self.name(entry);
            this.letters.setText(initials(name));
            this.letters.setGraphic(initials(name).isEmpty() ? Fx.icon(Icons.MESSAGE) : null);
            this.who.setText(name);
            this.star.setVisible(entry.friend().isPresent());
            this.star.setManaged(entry.friend().isPresent());
            Optional<Chats.Line> line = entry.chat()
                    .filter(c -> !c.lines().isEmpty())
                    .map(c -> c.lines().getLast());
            this.time.setText(line.map(l -> self.gui.formats().time(l.time())).orElse(""));
            this.last.setText(line.map(self::preview).orElseGet(() -> self.besideName(entry)));
            int news = entry.chat().map(self::unread).orElse(0);
            this.news.setText(String.valueOf(news));
            this.news.setVisible(news > 0);
            if (news > 0) {
                getStyleClass().add("has-news");
            }
            setAccessibleText(name
                    + (entry.friend().isPresent() ? ", " + self.gui.t("IDS_FRIENDLIST") : "")
                    + (news > 0 ? ", " + news : ""));
            setGraphic(this.box);
        }
    }

    // The last line in a few words: ours after "You", a notice as it reads.
    private String preview(Chats.Line line) {
        I18n i18n = this.gui.i18n();
        return switch (line.author()) {
            case US -> this.gui.f("nj.chat.you", i18n.embed(line.text()));
            case THEM -> i18n.embed(line.text());
            case NOTICE -> this.gui.f(line.text(), line.detail());
        };
    }

    /** One conversation: its lines, a picture to answer when one is asked, and the box to write. */
    final class ChatPane extends VBox {
        private final Chats.Chat chat;
        private final TextField input = new TextField();
        private final TextField solution = new TextField();
        // The picture must not be mirrored.
        private final ImageView captcha = Fx.leftToRight(new ImageView());
        private final VBox captchaBox;
        private final Label empty = new Label();

        ChatPane(Chats.Chat chat) {
            super(8);
            this.chat = chat;
            ListView<Chats.Line> lines = new ListView<>(chat.lines());
            lines.setUserData(chat);
            lines.getStyleClass().add("chat-lines");
            lines.setCellFactory(l -> new LineCell());
            MessagesScreen.this
                    .gui
                    .subscriptions()
                    .onListChange(chat.lines(), c -> lines.scrollTo(chat.lines().size() - 1));
            this.empty.getStyleClass().add("text-muted");
            this.empty.setWrapText(true);
            lines.setPlaceholder(this.empty);
            MessagesScreen.this.gui.subscriptions().onChange(chat.nickProperty(), (was, now) -> describe());
            describe();
            VBox.setVgrow(lines, Priority.ALWAYS);
            Label request = new Label(MessagesScreen.this.gui.t("IDS_CAPTCHAREQUEST"));
            request.setWrapText(true);
            // The list above gives way: the request is read whole, never cut short.
            request.setMinHeight(Region.USE_PREF_SIZE);
            request.setGraphic(Fx.icon(Icons.SHIELD));
            // The letters are drawn for a light page: on a light plate they read in every theme.
            StackPane plate = new StackPane(this.captcha);
            plate.getStyleClass().add("captcha-plate");
            plate.setMaxWidth(Region.USE_PREF_SIZE);
            this.solution.setOnAction(e -> solve());
            this.solution.setPromptText(MessagesScreen.this.gui.t("nj.chat.captchaLetters"));
            Fx.leftToRight(this.solution);
            HBox answer = new HBox(
                    8,
                    plate,
                    this.solution,
                    Fx.button(MessagesScreen.this.gui.t("nj.chat.send"), Icons.SEND, this::solve));
            answer.setAlignment(Pos.CENTER_LEFT);
            this.captchaBox = new VBox(8, request, answer);
            this.captchaBox.getStyleClass().add("captcha-card");
            this.captchaBox.setMinHeight(Region.USE_PREF_SIZE);
            MessagesScreen.this.gui.subscriptions().onChange(chat.captchaProperty(), (was, now) -> showCaptcha(now));
            showCaptcha(chat.captchaProperty().get());
            this.input.setOnAction(e -> send());
            HBox.setHgrow(this.input, Priority.ALWAYS);
            HBox row = new HBox(
                    8,
                    this.input,
                    Fx.primary(Fx.button(MessagesScreen.this.gui.t("nj.chat.send"), Icons.SEND, this::send)));
            row.getStyleClass().add("composer");
            getChildren().addAll(lines, this.captchaBox, row);
        }

        // The name the person goes by, in the empty list and in the box.
        private void describe() {
            String who = this.chat.nickProperty().get().isEmpty()
                    ? this.chat.address()
                    : this.chat.nickProperty().get();
            this.empty.setText(MessagesScreen.this.gui.f("nj.chat.empty", who));
            this.input.setPromptText(MessagesScreen.this.gui.f("nj.chat.write", who));
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
            MessagesScreen.this.befriend(this.chat);
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
