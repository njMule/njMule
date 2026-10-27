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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.transform.Transform;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.gui.dialogs.AboutDialog;
import org.njmule.gui.dialogs.CategoriesDialog;
import org.njmule.gui.dialogs.FirstRunWizard;
import org.njmule.gui.dialogs.ImportDialog;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.SoundPlayer;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.screens.CaptureAccess;
import org.njmule.gui.screens.MessagesScreen;
import org.njmule.gui.screens.SettingsScreen;
import org.njmule.gui.screens.SharedScreen;
import org.njmule.gui.screens.TransfersScreen;
import org.njmule.gui.shell.MainWindow;
import org.njmule.gui.shell.MiniMule;
import org.njmule.gui.shell.TrayMenu;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Subscriptions;
import org.njmule.gui.ui.SystemIcons;
import org.njmule.testkit.FreePorts;

/**
 * Pictures every screen, tab, menu, sub-menu, context menu, drop-down list, column menu, icon-button tooltip and dialog
 * of the interface, with example data, for reviews of its design and for the documentation. Runs only when asked:
 * {@code ./gradlew :njmule-gui:test --tests CaptureTour -Pnjmule.capture=build/gui-capture};
 * {@code -Pnjmule.capture.sets} picks the sets (see {@link #SETS}) and {@code -Pnjmule.capture.stable=true} names each
 * picture by what it shows alone, without its number, as the documentation refers to them ({@code ./gradlew
 * :njmule-gui:docsCaptures}); {@code -Pnjmule.capture.scale=2} draws them with twice the pixels, for screens of high
 * density.
 */
@Timeout(1800)
@EnabledIfSystemProperty(named = "njmule.capture", matches = ".+")
class CaptureTour {

    /**
     * The sets pictured unless {@code njmule.capture.sets} lists others: language, then light, dark or contrast (a dark
     * high contrast scheme of the system), then {@code full} for everything or {@code screens} for the screens and
     * their tabs, {@code empty} for a disconnected profile without data, {@code readme} for the transfers alone once
     * the messages of the start have gone, or {@code docs} for the README's picture and then everything.
     */
    static final String SETS = "it-light-full,it-dark-screens,en-light-screens,ar-light-screens,it-light-empty";

    // Windows' black high contrast scheme.
    private static final Map<String, Object> HIGH_CONTRAST = Map.of(
            "Windows.SPI.HighContrast", true,
            "Windows.SysColor.COLOR_WINDOW", javafx.scene.paint.Color.web("#000000"),
            "Windows.SysColor.COLOR_WINDOWTEXT", javafx.scene.paint.Color.web("#ffffff"),
            "Windows.SysColor.COLOR_3DFACE", javafx.scene.paint.Color.web("#000000"),
            "Windows.SysColor.COLOR_BTNTEXT", javafx.scene.paint.Color.web("#ffffff"),
            "Windows.SysColor.COLOR_GRAYTEXT", javafx.scene.paint.Color.web("#3ff23f"),
            "Windows.SysColor.COLOR_HIGHLIGHT", javafx.scene.paint.Color.web("#1aebff"),
            "Windows.SysColor.COLOR_HIGHLIGHTTEXT", javafx.scene.paint.Color.web("#000000"),
            "Windows.SysColor.COLOR_HOTLIGHT", javafx.scene.paint.Color.web("#ffff00"));

    private static final long SETTLE = 160;
    // Longer than the window shows a message for.
    private static final Duration MESSAGES_GONE = Duration.ofSeconds(7);
    private static final int TOOLTIPS_PER_SCREEN = 12;

    @TempDir
    Path dir;

    private Path out;
    private Path profiles;
    private List<String> sets;
    private NjMuleEngine engine;
    private EngineModel model;
    private Gui gui;
    private MainWindow window;
    private Stage stage;
    private String set;
    private int counter;
    private boolean stable;
    private double scale;
    private final List<String> index = new ArrayList<>();
    private final List<String> missed = new ArrayList<>();
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicReference<MainWindow> forMessages = new AtomicReference<>();
    private final Locale systemLanguage = Locale.getDefault();

    @AfterEach
    void giveTheSystemIconsBack() {
        System.clearProperty(SystemIcons.PROPERTY);
    }

    @Test
    void shouldPictureEveryStateOfTheInterface() throws Exception {
        givenTheSetsAsked();
        whenEachSetIsPictured();
        thenEveryPictureIsListedAndNothingFailedToOpen();
    }

    private void givenTheSetsAsked() throws Exception {
        this.out = Files.createDirectories(Path.of(System.getProperty("njmule.capture")));
        // The icons of this computer's programs would differ elsewhere, and are theirs.
        System.setProperty(SystemIcons.PROPERTY, "false");
        String asked = System.getProperty("njmule.capture.sets", "");
        this.sets = List.of((asked.isBlank() ? SETS : asked).split(","));
        this.stable = Boolean.getBoolean("njmule.capture.stable");
        this.scale = Double.parseDouble(System.getProperty("njmule.capture.scale", "1"));
        // The documentation's pictures show the profile's folders: a plain path instead of the test's own.
        String plain = System.getProperty("njmule.capture.profiles", "");
        this.profiles = plain.isBlank() ? this.dir : Files.createDirectories(Path.of(plain));
        FxTesting.start();
    }

    private void whenEachSetIsPictured() throws Exception {
        for (String name : this.sets) {
            String[] parts = name.strip().split("-", -1);
            Locale language = Locale.forLanguageTag(parts[0]);
            GuiSettings.Mode mode = "dark".equals(parts[1]) ? GuiSettings.Mode.DARK : GuiSettings.Mode.LIGHT;
            String depth = parts.length > 2 ? parts[2] : "screens";
            open(language, mode, "contrast".equals(parts[1]), !"empty".equals(depth), name.strip());
            if ("full".equals(depth)) {
                everything();
            } else if ("readme".equals(depth)) {
                readme("transfers");
            } else if ("docs".equals(depth)) {
                readme("transfers-sources");
                everything();
            } else {
                screens("screens".equals(depth));
            }
            close();
        }
        Files.write(this.out.resolve("index.tsv"), this.index);
    }

    private void thenEveryPictureIsListedAndNothingFailedToOpen() {
        assertThat(this.index).isNotEmpty();
        assertThat(this.missed).isEmpty();
    }

    // ---------------------------------------------------------------- sessions

    private void open(Locale language, GuiSettings.Mode mode, boolean contrast, boolean demo, String name)
            throws Exception {
        this.set = name;
        this.counter = 0;
        this.seen.clear();
        Path root = this.profiles.equals(this.dir) ? this.dir.resolve(name) : this.profiles.resolve("njMule");
        deleteTree(root);
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        Files.createDirectories(dirs.gui());
        int port = FreePorts.tcp();
        int udp = FreePorts.udp();
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=false\nsecurity.filterLan=false\n"
                        + "search.store=true\nuser.nick=Lupo\nlimits.uploadKiBps=250\nlimits.downloadKiBps=0\n"
                        + "net.upnp=false\nnotify.sound=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        if (demo) {
            this.engine.updateSchedule(CaptureDemo.schedule()).get(10, TimeUnit.SECONDS);
        }
        // The log is the demo's: the real engine's lines may name this computer's addresses.
        NjMuleEngine shown = demo
                ? Engines.withEvents(
                        Engines.overriding(this.engine, CaptureDemo.commands(root, this.engine::preferences)),
                        withoutLog(this.engine.events()))
                : this.engine;
        this.model = new EngineModel(shown, Platform::runLater);
        I18n i18n = I18n.of(language);
        // As the app does: JavaFX's own texts in the language of the set.
        I18n.makeDefault(i18n.locale());
        Consumer<String> toWindow = text -> {
            MainWindow w = this.forMessages.get();
            if (w != null) {
                w.message(text);
            }
        };
        Consumer<String> problemToWindow = text -> {
            MainWindow w = this.forMessages.get();
            if (w != null) {
                w.problem(text);
            }
        };
        this.gui = FxTesting.onFx(() -> new Gui(
                i18n,
                Formats.of(i18n),
                this.model,
                new Themes(
                        Themes.Family.NORD,
                        mode,
                        contrast ? FXCollections.observableMap(HIGH_CONTRAST) : Platform.getPreferences()),
                GuiSettings.load(dirs.gui()),
                dirs,
                toWindow,
                problemToWindow,
                (text, action, run) -> toWindow.accept(text),
                () -> {},
                new Subscriptions(),
                SoundPlayer.SILENT,
                Optional.empty()));
        this.model.onError(text -> {});
        this.model.start();
        this.window = FxTesting.onFx(() -> new MainWindow(this.gui));
        this.forMessages.set(this.window);
        FxTesting.onFx(() -> {
            this.stage = new Stage();
            this.stage.setTitle(NjMuleInfo.NAME + " " + NjMuleInfo.version());
            Scene scene = new Scene(this.window.root(), 1180, 760);
            this.gui.attach(scene);
            this.stage.setScene(scene);
            this.stage.setX(0);
            this.stage.setY(0);
            this.stage.show();
        });
        if (demo) {
            feed();
            // The conversation that asks for a CAPTCHA, for its picture; the others keep their news.
            FxTesting.await("the chats", () -> this.model.chats().all().size() == 3);
            FxTesting.onFx(() -> CaptureAccess.chooseConversation(
                    (MessagesScreen) this.window.screen("messages").orElseThrow(), "192.0.2.99:4662"));
        }
        for (int i = 0; i < 31; i++) {
            FxTesting.onFx(this.model::tick);
        }
        // Long enough for the banner of a lasting state to show.
        Thread.sleep(2500);
    }

    // The engine's events but its log lines, asking again for each one left out.
    private static Flow.Publisher<EngineEvent> withoutLog(Flow.Publisher<EngineEvent> events) {
        return subscriber -> events.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                subscriber.onSubscribe(s);
            }

            @Override
            public void onNext(EngineEvent event) {
                if (event instanceof EngineEvent.Log) {
                    this.subscription.request(1);
                } else {
                    subscriber.onNext(event);
                }
            }

            @Override
            public void onError(Throwable error) {
                subscriber.onError(error);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }

    // What only events tell (the demo's events and a reply to the first chat) and ten minutes of rates.
    private void feed() throws Exception {
        for (EngineEvent event : CaptureDemo.events()) {
            this.model.onNext(event);
            if (event instanceof EngineEvent.MessageReceived m && m.text().startsWith("Thanks")) {
                FxTesting.onFx(() -> this.model.sendMessage(
                        "203.0.113.33:4672", "You're welcome! The 4K one is in my shared files."));
            }
        }
        FxTesting.onFx(() -> {
            long start = Instant.now().getEpochSecond() - 600;
            for (int i = 0; i < 600; i++) {
                double wave = Math.sin(i / 37.0) * 0.35 + Math.sin(i / 9.0) * 0.1 + 0.6;
                long down = (long) (Math.max(0, wave) * 1_400_000 + (i % 13) * 9_000);
                long up = (long) (90_000 + Math.cos(i / 21.0) * 25_000);
                this.model.rates().add(new EngineModel.RateSample(Instant.ofEpochSecond(start + i), down, up));
            }
        });
    }

    private void close() throws Exception {
        FxTesting.onFx(() -> List.copyOf(Window.getWindows()).forEach(Window::hide));
        this.forMessages.set(null);
        this.model.close();
        this.engine.close();
        I18n.makeDefault(this.systemLanguage);
        if (!this.profiles.equals(this.dir)) {
            deleteTree(this.profiles.resolve("njMule"));
            deleteTree(this.profiles.resolve("eMule"));
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.exists(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    // ---------------------------------------------------------------- plans

    private void screens(boolean tabs) throws Exception {
        for (String id : this.window.screenIds()) {
            fx(() -> this.window.show(id));
            fx(() -> expandTrees(this.stage.getScene().getRoot()));
            shot(id, "Screen " + id);
            if (tabs) {
                tabs(id, screenRoot(), false);
            }
        }
    }

    // The picture of the README: no message over the lists, and the table at its whole width.
    private void readme(String slug) throws Exception {
        Thread.sleep(MESSAGES_GONE.toMillis());
        fx(() -> {
            this.window.show("transfers");
            if (this.stage.getScene().lookup("#details") instanceof ToggleButton details && details.isSelected()) {
                details.fire();
            }
            // The first download shows its sources under it.
            CaptureAccess.openOntoSources(
                    (TransfersScreen) this.window.screen("transfers").orElseThrow(), 0);
        });
        shot(slug, "The transfers, for the README");
    }

    private void everything() throws Exception {
        for (String id : this.window.screenIds()) {
            fx(() -> this.window.show(id));
            fx(() -> expandTrees(this.stage.getScene().getRoot()));
            shot(id, "Screen " + id + " as it opens");
            if (id.equals("search")) {
                // The filters show in a popup under their button.
                fx(() -> ((javafx.scene.control.Button)
                                this.stage.getScene().getRoot().lookup("#search-parameters"))
                        .fire());
                shot("search-parameters", "Search with its filters open");
                fx(CaptureTour::closePopups);
            }
            if (id.equals("servers")) {
                // The server in use chosen: its details and what it said beside the list.
                select(screenRoot(), 0, 0);
                shot("servers-panel", "Servers with the details of the one chosen");
                fx(() -> ((Button) this.stage.getScene().getRoot().lookup("#add-servers")).fire());
                shot("servers-add", "The ways to add servers under their button");
                fx(CaptureTour::closePopups);
            }
            tabs(id, screenRoot(), true);
            menus(id, screenRoot());
            tooltips(id, screenRoot());
        }
        fx(() -> this.window.show("transfers"));
        menus("topbar", this.stage.getScene().getRoot());
        tooltips("topbar", this.stage.getScene().getRoot());
        fx(() -> ((Button) this.stage.getScene().getRoot().lookup("#rates")).fire());
        shot("topbar-limits", "The limits under the rates of the top bar");
        fx(CaptureTour::closePopups);
        fx(() -> ((Button) this.stage.getScene().getRoot().lookup("#network")).fire());
        shot("topbar-network", "The menu of both networks under their control in the top bar");
        fx(CaptureTour::closePopups);
        fx(() -> ((Button) this.stage.getScene().getRoot().lookup("#limits")).fire());
        shot("strip-limits", "The limits over the status strip");
        fx(CaptureTour::closePopups);
        fx(() -> this.stage.getScene().getRoot().fireEvent(FxTesting.key(javafx.scene.input.KeyCode.B, true)));
        shot("sidebar-folded", "The sidebar folded to its icons (Ctrl+B)");
        fx(() -> this.stage.getScene().getRoot().fireEvent(FxTesting.key(javafx.scene.input.KeyCode.B, true)));
        shot("sidebar-reopened", "The sidebar open again after being folded");
        fx(() -> this.stage.getScene().getRoot().fireEvent(FxTesting.key(javafx.scene.input.KeyCode.K, true)));
        shot("palette", "The command palette (Ctrl+K)");
        fx(() -> ((TextField) this.stage.getScene().getRoot().lookup(".command-palette .text-field"))
                .setText("paper boats"));
        shot("palette-search", "The command palette offering a search of the network");
        fx(() -> this.stage
                .getScene()
                .getRoot()
                .lookup(".command-palette .text-field")
                .fireEvent(FxTesting.key(javafx.scene.input.KeyCode.ESCAPE, false)));
        fx(() -> this.stage.getScene().getRoot().fireEvent(FxTesting.key(javafx.scene.input.KeyCode.F1, false)));
        Thread.sleep(300);
        FxTesting.onFx(() -> {
            for (Window w : List.copyOf(Window.getWindows())) {
                if (w instanceof Stage s && !s.equals(this.stage) && s.isShowing()) {
                    save("shortcuts", image(s.getScene()), "The keyboard shortcuts (F1)");
                    s.hide();
                }
            }
            return null;
        });
        textFieldMenu();
        settingsPage();
        liveSearch();
        transfersDetails();
        dialogs();
        invalidInputs();
        toasts();
        trayWindows();
        smallWindow();
    }

    // ---------------------------------------------------------------- exploration

    private Parent screenRoot() throws Exception {
        return FxTesting.onFx(() ->
                (Parent) this.window.screen(this.window.current()).orElseThrow().view());
    }

    private void tabs(String context, Parent root, boolean deep) throws Exception {
        for (TabPane pane : FxTesting.onFx(() -> find(root, TabPane.class))) {
            if (!this.seen.add(pane)) {
                continue;
            }
            int was = FxTesting.onFx(() -> pane.getSelectionModel().getSelectedIndex());
            int size = FxTesting.onFx(() -> pane.getTabs().size());
            for (int i = 0; i < size; i++) {
                int at = i;
                Tab tab = FxTesting.onFx(() -> pane.getTabs().get(at));
                fx(() -> pane.getSelectionModel().select(at));
                fx(() -> expandTrees(this.stage.getScene().getRoot()));
                String title = FxTesting.onFx(tab::getText);
                shot(context + "-tab-" + slug(title), "Tab \"" + title + "\" of " + context);
                if (deep && FxTesting.onFx(tab::getContent) instanceof Parent content) {
                    tabs(context + "-" + slug(title), content, true);
                    menus(context + "-" + slug(title), content);
                }
            }
            fx(() -> pane.getSelectionModel().select(Math.max(0, was)));
        }
    }

    private void menus(String context, Parent root) throws Exception {
        contextMenus(context, root);
        menuButtons(context, root);
        dropDowns(context, root);
        columnMenus(context, root);
    }

    // Each context menu with a row chosen, as for a right click on it, and its sub-menus.
    private void contextMenus(String context, Parent root) throws Exception {
        List<Control> owners = FxTesting.onFx(() -> find(root, Control.class).stream()
                .filter(c -> c.getContextMenu() != null && visible(c))
                .toList());
        for (Control owner : owners) {
            ContextMenu menu = FxTesting.onFx(owner::getContextMenu);
            if (!this.seen.add(menu)) {
                continue;
            }
            String name = context + "-ctx-" + name(owner);
            fx(() -> choose(owner));
            fx(() -> {
                double[] at = anchor(owner);
                menu.show(owner, at[0], at[1]);
            });
            shot(name, "Context menu of " + name(owner) + " in " + context);
            submenus(name, FxTesting.onFx(() -> List.copyOf(menu.getItems())));
            fx(CaptureTour::closePopups);
        }
    }

    private void menuButtons(String context, Parent root) throws Exception {
        List<MenuButton> buttons = FxTesting.onFx(() -> find(root, MenuButton.class).stream()
                .filter(CaptureTour::visible)
                .toList());
        for (MenuButton button : buttons) {
            if (!this.seen.add(button)) {
                continue;
            }
            String name = context + "-menubutton-" + name(button);
            fx(button::show);
            shot(name, "Menu of the button " + name(button) + " in " + context);
            submenus(name, FxTesting.onFx(() -> List.copyOf(button.getItems())));
            fx(() -> {
                button.hide();
                closePopups();
            });
        }
    }

    private void dropDowns(String context, Parent root) throws Exception {
        List<Control> boxes = FxTesting.onFx(() -> find(root, Control.class).stream()
                .filter(c -> (c instanceof ComboBox<?> || c instanceof ChoiceBox<?>) && visible(c) && !c.isDisabled())
                .toList());
        for (Control box : boxes) {
            if (!this.seen.add(box)) {
                continue;
            }
            fx(() -> {
                if (box instanceof ComboBox<?> combo) {
                    combo.show();
                } else if (box instanceof ChoiceBox<?> choice) {
                    choice.show();
                }
            });
            shot(context + "-dropdown-" + name(box), "Drop-down list " + name(box) + " in " + context);
            fx(() -> {
                if (box instanceof ComboBox<?> combo) {
                    combo.hide();
                } else if (box instanceof ChoiceBox<?> choice) {
                    choice.hide();
                }
                closePopups();
            });
        }
    }

    // The menu behind the corner button of each table, pressed as the mouse does.
    private void columnMenus(String context, Parent root) throws Exception {
        List<TableView<?>> tables = FxTesting.onFx(() -> find(root, TableView.class).stream()
                .filter(CaptureTour::visible)
                .<TableView<?>>map(t -> t)
                .toList());
        for (TableView<?> table : tables) {
            Node corner = FxTesting.onFx(() -> table.lookup(".show-hide-columns-button"));
            if (corner == null || !this.seen.add(corner)) {
                continue;
            }
            fx(() -> {
                Bounds b = corner.localToScreen(corner.getBoundsInLocal());
                corner.fireEvent(new MouseEvent(
                        MouseEvent.MOUSE_PRESSED,
                        2,
                        2,
                        b.getMinX() + 2,
                        b.getMinY() + 2,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        null));
            });
            shot(context + "-columns-" + name(table), "Column menu of the table " + name(table));
            fx(CaptureTour::closePopups);
        }
    }

    private void submenus(String context, List<MenuItem> items) throws Exception {
        for (MenuItem item : items) {
            if (item instanceof Menu sub && !FxTesting.onFx(sub::isDisable)) {
                String title = FxTesting.onFx(sub::getText);
                fx(() -> hover(sub));
                shot(context + "-sub-" + slug(title), "Sub-menu \"" + title + "\"");
                submenus(context + "-sub-" + slug(title), FxTesting.onFx(() -> List.copyOf(sub.getItems())));
                fx(sub::hide);
            }
        }
    }

    // The tooltips of the controls without words of their own: their meaning shows only on hover.
    private void tooltips(String context, Parent root) throws Exception {
        List<Control> tipped = FxTesting.onFx(() -> find(root, Control.class).stream()
                .filter(c -> c.getTooltip() != null && visible(c))
                .filter(c -> !(c instanceof Labeled l)
                        || l.getText() == null
                        || l.getText().isBlank()
                        || c instanceof TextInputControl)
                .limit(TOOLTIPS_PER_SCREEN)
                .toList());
        for (Control c : tipped) {
            if (!this.seen.add(c.getTooltip())) {
                continue;
            }
            fx(() -> {
                Bounds b = c.localToScreen(c.getBoundsInLocal());
                c.getTooltip().show(c, b.getMinX(), b.getMaxY() + 4);
            });
            shot(context + "-tooltip-" + name(c), "Tooltip of " + name(c));
            fx(() -> {
                c.getTooltip().hide();
                closePopups();
            });
        }
    }

    private void textFieldMenu() throws Exception {
        fx(() -> this.window.show("transfers"));
        Parent screen = screenRoot();
        Optional<TextField> field = FxTesting.onFx(() -> find(screen, TextField.class).stream()
                .filter(CaptureTour::visible)
                .findFirst());
        if (field.isEmpty()) {
            return;
        }
        fx(() -> {
            TextField f = field.get();
            f.setText("ed2k://|file|Harbor.Lights.2024.1080p.x264.mkv|1245822976|…|/");
            f.requestFocus();
            f.selectAll();
            Bounds b = f.localToScreen(f.getBoundsInLocal());
            f.fireEvent(new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED, 40, 10, b.getMinX() + 40, b.getMinY() + 10, false, null));
        });
        shot("transfers-textfield-ctx", "Standard context menu of a text field");
        fx(() -> {
            closePopups();
            field.get().clear();
        });
    }

    // Each page of the options, then a search across them and the options changed from their defaults.
    private void settingsPage() throws Exception {
        fx(() -> this.window.show("settings"));
        Parent screen = screenRoot();
        ListView<?> pages = FxTesting.onFx(() -> (ListView<?>) screen.lookup("#settings-pages"));
        int count = FxTesting.onFx(() -> pages.getItems().size());
        for (int i = 0; i < count; i++) {
            int at = i;
            fx(() -> pages.getSelectionModel().select(at));
            shot(String.format(Locale.ROOT, "settings-page-%02d", i + 1), "Options, page " + (i + 1));
        }
        TextField find = FxTesting.onFx(() -> (TextField) screen.lookup("#settings-find"));
        fx(() -> find.setText("port"));
        shot("settings-find", "Options found by a word on every page");
        fx(find::clear);
        ToggleButton changed = FxTesting.onFx(() -> (ToggleButton) screen.lookup("#settings-changed-only"));
        fx(changed::fire);
        shot("settings-changed-only", "Only the options changed from their defaults");
        fx(changed::fire);
        fx(() -> pages.getSelectionModel().select(0));
    }

    private void liveSearch() throws Exception {
        fx(() -> this.window.show("search"));
        Parent root = screenRoot();
        fx(() -> {
            ComboBox<?> query = root.lookupAll(".combo-box").stream()
                    .filter(n -> n instanceof ComboBox<?> box && box.isEditable())
                    .map(n -> (ComboBox<?>) n)
                    .findFirst()
                    .orElseThrow();
            query.getEditor().setText("paper boats");
            find(root, Button.class).stream()
                    .filter(b -> this.gui.t("nj.search.start").equals(b.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        });
        Thread.sleep(400);
        this.model.onNext(new EngineEvent.SearchResults(CaptureDemo.LIVE_SEARCH, CaptureDemo.liveResults()));
        shot("search-live", "A search running, with its first results");
    }

    private void transfersDetails() throws Exception {
        fx(() -> this.window.show("transfers"));
        Parent transfers = screenRoot();
        javafx.scene.control.ToggleButton details =
                FxTesting.onFx(() -> (javafx.scene.control.ToggleButton) transfers.lookup("#details"));
        boolean was = FxTesting.onFx(details::isSelected);
        fx(() -> details.setSelected(true));
        fx(() -> ((TableView<?>) transfers.lookup("#downloads"))
                .getSelectionModel()
                .clearSelection());
        shot("transfers-details-none", "Transfers with the details open, nothing chosen");
        select(transfers, 0, 0);
        shot("transfers-details", "Transfers with the details of the download chosen");
        for (String tab : List.of("facts", "names", "comments")) {
            fx(() -> ((javafx.scene.control.ToggleButton) transfers.lookup("#details-tab-" + tab)).fire());
            shot("transfers-details-" + tab, "The " + tab + " tab of the details");
        }
        fx(() -> ((javafx.scene.control.ToggleButton) transfers.lookup("#details-tab-sources")).fire());
        // Several chosen: how many, and what can be done to all of them.
        fx(() -> ((TableView<?>) transfers.lookup("#downloads"))
                .getSelectionModel()
                .selectRange(0, 3));
        shot("transfers-details-several", "The details of several downloads chosen");
        // The chips narrow the list to a state.
        fx(() -> ((javafx.scene.control.ToggleButton) ((javafx.scene.layout.Pane) transfers.lookup("#state-chips"))
                        .getChildren()
                        .get(1))
                .fire());
        shot("transfers-downloading", "Only the downloads running, chosen by their chip");
        fx(() -> ((javafx.scene.control.ToggleButton) ((javafx.scene.layout.Pane) transfers.lookup("#state-chips"))
                        .getChildren()
                        .get(0))
                .fire());
        for (int shelf = 1; shelf < 4; shelf++) {
            int index = shelf;
            fx(() -> ((javafx.scene.control.ToggleButton) ((javafx.scene.layout.Pane) transfers.lookup("#shelves"))
                            .getChildren()
                            .get(index))
                    .fire());
            shot("transfers-shelf-" + shelf, "The list of the transfers shown by its segment");
        }
        fx(() -> ((javafx.scene.control.ToggleButton) ((javafx.scene.layout.Pane) transfers.lookup("#shelves"))
                        .getChildren()
                        .get(0))
                .fire());
        fx(() -> details.setSelected(was));
    }

    // ---------------------------------------------------------------- dialogs

    private void dialogs() throws Exception {
        window("dialog-about", "About njMule", () -> new AboutDialog(this.gui, this.stage).show(), w -> {});
        Path emule = Files.createDirectories(this.profiles.resolve("eMule").resolve("config"));
        // The first start, page by page, with an eMule found.
        for (int page = 0; page < 5; page++) {
            int nexts = page;
            window(
                    "dialog-wizard-" + (page + 1),
                    "First start wizard, page " + (page + 1),
                    () -> new FirstRunWizard(
                                    this.gui,
                                    this.stage,
                                    this.model.preferencesProperty().get(),
                                    Optional.of(emule),
                                    outcome -> {})
                            .show(),
                    w -> {
                        for (int i = 0; i < nexts; i++) {
                            button(w.getScene().getRoot(), "nj.wizard.next").fire();
                        }
                    });
        }
        window(
                "dialog-import",
                "Import from eMule, as it opens",
                () -> new ImportDialog(this.gui, this.stage).show(),
                w -> find(w.getScene().getRoot(), TextField.class).getFirst().setText(emule.toString()));
        window(
                "dialog-import-report",
                "Import from eMule after Check",
                () -> new ImportDialog(this.gui, this.stage).show(),
                w -> {
                    find(w.getScene().getRoot(), TextField.class).getFirst().setText(emule.toString());
                    button(w.getScene().getRoot(), "nj.import.check").fire();
                });
        window(
                "dialog-categories",
                "Categories",
                () -> new CategoriesDialog(this.gui, this.stage).show(),
                w -> find(w.getScene().getRoot(), ListView.class)
                        .getFirst()
                        .getSelectionModel()
                        .select(1));

        fx(() -> this.window.show("transfers"));
        Parent transfers = screenRoot();
        TableView<?> downloads = select(transfers, 0, 1);
        waiting(
                "dialog-transfers-add-source",
                "Add a source by hand",
                () -> item(downloads.getContextMenu(), "IDS_ADDSRCMANUALLY").fire());
        window(
                "dialog-comments-download",
                "Comments of a download",
                () -> item(downloads.getContextMenu(), "IDS_CMT_ADD").fire(),
                w -> {});
        // Links on the clipboard are in the box when the dialog opens.
        waiting("dialog-add-links", "Add downloads: the links copied and the files found in them", () -> {
            javafx.scene.input.ClipboardContent links = new javafx.scene.input.ClipboardContent();
            links.putString("ed2k://|file|Harbor.Lights.2024.4K.mkv|4939212800|9C4E2A07B1D35F8E61A0C2D47E93B5F1|/\n"
                    + "ed2k://|file|lumenos-4.1-desktop-amd64.iso|6217003008|E2B71C09A4F35D8E10C6A2B94D7F53E1|/");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(links);
            ((javafx.scene.control.Button) transfers.lookup("#add-links")).fire();
        });
        fx(() -> ((javafx.scene.control.ToggleButton) ((javafx.scene.layout.Pane) transfers.lookup("#shelves"))
                        .getChildren()
                        .get(1))
                .fire());
        TableView<?> uploads = FxTesting.onFx(() -> (TableView<?>) transfers.lookup("#uploads"));
        fx(() -> uploads.getSelectionModel().select(0));
        window(
                "dialog-client-files",
                "The files a client shares",
                () -> item(uploads.getContextMenu(), "IDS_VIEWFILES").fire(),
                w -> {});
        fx(() -> ((javafx.scene.control.ToggleButton) ((javafx.scene.layout.Pane) transfers.lookup("#shelves"))
                        .getChildren()
                        .get(0))
                .fire());

        fx(() -> this.window.show("servers"));
        TableView<?> servers = select(screenRoot(), 0, 0);
        waiting(
                "dialog-servers-remove-all",
                "Remove every server",
                () -> item(servers.getContextMenu(), "IDS_REMOVEALL").fire());

        fx(() -> this.window.show("shared"));
        TableView<?> shared = select(screenRoot(), 0, 1);
        waiting(
                "dialog-shared-rename",
                "Rename a shared file",
                () -> item(shared.getContextMenu(), "IDS_RENAME").fire());
        window(
                "dialog-comments-shared",
                "Comment of a shared file",
                () -> item(shared.getContextMenu(), "IDS_CMT_ADD").fire(),
                w -> {});
        SharedScreen sharedScreen = (SharedScreen) this.window.screen("shared").orElseThrow();
        waiting(
                "dialog-share-folder",
                "Share a folder",
                () -> CaptureAccess.confirmShare(sharedScreen, Path.of("D:\\Shared\\Films")));
        waiting(
                "dialog-share-sensitive",
                "Share a whole drive",
                () -> CaptureAccess.confirmShare(sharedScreen, Path.of("D:\\")));

        fx(() -> this.window.show("settings"));
        SettingsScreen settings =
                (SettingsScreen) this.window.screen("settings").orElseThrow();
        waiting(
                "dialog-server-list-addresses",
                "Addresses of the server lists",
                () -> CaptureAccess.editServerListAddresses(settings));
    }

    // Chooses one row of the table at {@code which} under root, alone.
    private TableView<?> select(Parent root, int which, int row) throws Exception {
        TableView<?> table = FxTesting.onFx(() -> find(root, TableView.class).get(which));
        fx(() -> {
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().select(row);
        });
        return table;
    }

    // A window that does not wait: shown, pictured alone and over the main window, closed.
    private void window(String slug, String what, Runnable open, Consumer<Window> fill) throws Exception {
        Set<Window> before = FxTesting.onFx(() -> Set.copyOf(Window.getWindows()));
        fx(open);
        Thread.sleep(500);
        Optional<Window> shown = FxTesting.onFx(() -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage && w.isShowing() && !before.contains(w))
                .findFirst());
        if (shown.isEmpty()) {
            this.missed.add(this.set + ": " + slug);
            return;
        }
        fx(() -> fill.accept(shown.get()));
        Thread.sleep(500);
        FxTesting.onFx(() -> {
            save(slug, image(shown.get().getScene()), what + " (alone)");
            save(slug + "-over", composite(), what + " (over the main window)");
            return null;
        });
        fx(() -> List.copyOf(Window.getWindows()).stream()
                .filter(w -> !before.contains(w))
                .forEach(Window::hide));
    }

    // A dialog that waits for an answer: pictured from inside its own event loop, then cancelled.
    private void waiting(String slug, String what, Runnable open) throws Exception {
        Set<Window> before = FxTesting.onFx(() -> Set.copyOf(Window.getWindows()));
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Thread watcher = Thread.ofVirtual().start(() -> watch(slug, what, before, done));
        FxTesting.onFx(open);
        watcher.join();
        if (!done.get()) {
            this.missed.add(this.set + ": " + slug);
        }
        Thread.sleep(SETTLE);
    }

    private void watch(String slug, String what, Set<Window> before, CompletableFuture<Boolean> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try {
            while (System.nanoTime() < deadline) {
                Thread.sleep(400);
                CompletableFuture<Boolean> got = new CompletableFuture<>();
                Platform.runLater(() -> got.complete(pictureAndCancel(slug, what, before)));
                if (got.get(10, TimeUnit.SECONDS)) {
                    done.complete(true);
                    return;
                }
            }
            done.complete(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done.complete(false);
        } catch (Exception e) {
            done.completeExceptionally(e);
        }
    }

    private boolean pictureAndCancel(String slug, String what, Set<Window> before) {
        Optional<Window> shown = Window.getWindows().stream()
                .filter(x -> x instanceof Stage && x.isShowing() && !before.contains(x))
                .findFirst();
        if (shown.isEmpty()) {
            return false;
        }
        try {
            save(slug, image(shown.get().getScene()), what + " (alone)");
            save(slug + "-over", composite(), what + " (over the main window)");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cancel(shown.get());
        return true;
    }

    private static void cancel(Window w) {
        if (w.getScene().getRoot().lookup(".dialog-pane") instanceof DialogPane pane) {
            for (ButtonType type : pane.getButtonTypes()) {
                ButtonBar.ButtonData data = type.getButtonData();
                if (data == ButtonBar.ButtonData.CANCEL_CLOSE || data == ButtonBar.ButtonData.NO) {
                    ((Button) pane.lookupButton(type)).fire();
                    return;
                }
            }
        }
        w.hide();
    }

    // ---------------------------------------------------------------- other states

    private void invalidInputs() throws Exception {
        fx(() -> this.window.show("settings"));
        Parent settings = screenRoot();
        ListView<?> pages = FxTesting.onFx(() -> (ListView<?>) settings.lookup("#settings-pages"));
        fx(() -> pages.getSelectionModel().select(1));
        TextField port = FxTesting.onFx(() -> find(settings, TextField.class).stream()
                .filter(f -> f.getTooltip() != null && f.getTooltip().getText().contains("65535"))
                .findFirst()
                .orElseThrow());
        String before = FxTesting.onFx(() -> port.getText());
        fx(() -> {
            port.setText("99999");
            port.fireEvent(new javafx.event.ActionEvent());
        });
        shot("settings-invalid-field", "Options with a port out of range, said under the option");
        fx(() -> {
            port.setText("4777");
            port.fireEvent(new javafx.event.ActionEvent());
        });
        shot("settings-pending", "Options: a new port waits for Apply and reconnect");
        fx(() -> ((Button) settings.lookup("#settings-discard")).fire());
        fx(() -> port.setText(before));
        fx(() -> pages.getSelectionModel().select(0));
    }

    private void toasts() throws Exception {
        fx(() -> this.window.show("transfers"));
        fx(() -> {
            this.window.success(this.gui.f("nj.transfers.completed", "Pinecone_Office_7.3_Win_x64.msi"));
            this.window.message(this.gui.f("nj.chat.from", "Owl"));
            this.window.problem("203.0.113.11:4232: connection refused");
        });
        shot("toasts", "A success, a piece of news and a problem, stacked");
    }

    private void trayWindows() throws Exception {
        Stage owner = FxTesting.onFx(() -> {
            Stage s = new Stage();
            s.setOpacity(0);
            s.setWidth(1);
            s.setHeight(1);
            s.show();
            return s;
        });
        Set<Window> before = FxTesting.onFx(() -> Set.copyOf(Window.getWindows()));
        TrayMenu menu = FxTesting.onFx(() -> new TrayMenu(this.gui, owner, new TrayMenu.Actions() {
            @Override
            public void restore() {
                // Pictured only.
            }

            @Override
            public void options() {
                // Pictured only.
            }

            @Override
            public void exit() {
                // Pictured only.
            }
        }));
        fx(() -> menu.show(1100, 700));
        Thread.sleep(400);
        newWindows("tray-menu", "Menu of the notification-area icon (right click)", before);
        fx(menu::hide);
        Set<Window> beforeMini = FxTesting.onFx(() -> Set.copyOf(Window.getWindows()));
        MiniMule mini = FxTesting.onFx(() -> new MiniMule(this.gui, owner, () -> this.dir, new MiniMule.Actions() {
            @Override
            public void openIncoming() {
                // Pictured only.
            }

            @Override
            public void options() {
                // Pictured only.
            }

            @Override
            public void restore() {
                // Pictured only.
            }
        }));
        fx(() -> {
            mini.show();
            mini.refresh();
        });
        Thread.sleep(400);
        newWindows("minimule", "MiniMule (click on the notification-area icon)", beforeMini);
        fx(() -> {
            mini.hide();
            owner.hide();
        });
    }

    private void newWindows(String slug, String what, Set<Window> before) throws Exception {
        FxTesting.onFx(() -> {
            int i = 0;
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && !before.contains(w) && w.getScene() != null) {
                    save(slug + (i++ == 0 ? "" : "-" + i), image(w.getScene()), what);
                }
            }
            return null;
        });
    }

    // The main window's content moved into a 900×600 window and back.
    private void smallWindow() throws Exception {
        Stage big = this.stage;
        Stage small = FxTesting.onFx(() -> {
            Parent root = big.getScene().getRoot();
            big.getScene().setRoot(new Region());
            Stage s = new Stage();
            Scene scene = new Scene(root, 900, 600);
            this.gui.attach(scene);
            s.setScene(scene);
            s.setX(0);
            s.setY(0);
            big.hide();
            s.show();
            return s;
        });
        this.stage = small;
        for (String id :
                List.of("transfers", "search", "shared", "messages", "servers", "kad", "statistics", "settings")) {
            fx(() -> this.window.show(id));
            shot(id + "-small-900x600", "A 900×600 window");
        }
        fx(() -> {
            Parent root = small.getScene().getRoot();
            small.getScene().setRoot(new Region());
            small.hide();
            big.getScene().setRoot(root);
            big.show();
        });
        this.stage = big;
    }

    // ---------------------------------------------------------------- helpers

    private Button button(Parent root, String key) {
        String text = this.gui.t(key);
        return find(root, Button.class).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + key));
    }

    private MenuItem item(ContextMenu menu, String key) {
        String text = this.gui.t(key);
        return all(menu.getItems()).stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no menu item " + key));
    }

    private static List<MenuItem> all(List<MenuItem> items) {
        List<MenuItem> found = new ArrayList<>();
        for (MenuItem i : items) {
            found.add(i);
            if (i instanceof Menu m) {
                found.addAll(all(m.getItems()));
            }
        }
        return found;
    }

    // Hovers the row of a sub-menu in the open menus, as the mouse would.
    private static void hover(Menu sub) {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (!(w instanceof PopupWindow) || !w.isShowing() || w.getScene() == null) {
                continue;
            }
            for (Node n : w.getScene().getRoot().lookupAll(".menu-item")) {
                boolean match = find(n, Label.class).stream()
                        .anyMatch(l -> sub.getText().equals(l.getText()));
                if (match) {
                    Bounds b = n.localToScreen(n.getBoundsInLocal());
                    n.fireEvent(new MouseEvent(
                            MouseEvent.MOUSE_ENTERED,
                            5,
                            5,
                            b.getMinX() + 5,
                            b.getMinY() + 5,
                            MouseButton.NONE,
                            0,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            false,
                            null));
                    return;
                }
            }
        }
    }

    private static void choose(Control c) {
        if (c instanceof TableView<?> t && !t.getItems().isEmpty()) {
            t.getSelectionModel().clearSelection();
            t.getSelectionModel().select(0);
        } else if (c instanceof ListView<?> l && !l.getItems().isEmpty()) {
            l.getSelectionModel().select(0);
        } else if (c instanceof TreeView<?> tree && tree.getRoot() != null) {
            tree.getSelectionModel().select(Math.min(1, tree.getExpandedItemCount() - 1));
        }
    }

    private static double[] anchor(Control c) {
        if (c instanceof TableView<?> t) {
            for (Node n : t.lookupAll(".table-row-cell")) {
                if (n instanceof TableRow<?> row && row.getIndex() == 0 && row.getItem() != null) {
                    Bounds b = row.localToScreen(row.getBoundsInLocal());
                    return new double[] {b.getMinX() + 120, b.getMinY() + b.getHeight() / 2};
                }
            }
        }
        Bounds b = c.localToScreen(c.getBoundsInLocal());
        return new double[] {
            b.getMinX() + Math.min(120, b.getWidth() / 2), b.getMinY() + Math.min(30, b.getHeight() / 2)
        };
    }

    private static void expandTrees(Parent root) {
        for (TreeView<?> tree : find(root, TreeView.class)) {
            expand(tree.getRoot());
        }
    }

    private static void expand(TreeItem<?> item) {
        if (item != null) {
            item.setExpanded(true);
            item.getChildren().forEach(CaptureTour::expand);
        }
    }

    private String name(Node n) {
        if (n.getId() != null && !n.getId().isBlank()) {
            return slug(n.getId());
        }
        if (n instanceof Control c && c.getAccessibleText() != null) {
            return slug(c.getAccessibleText());
        }
        if (n instanceof Control c && c.getTooltip() != null && c.getTooltip().getText() != null) {
            return slug(c.getTooltip().getText());
        }
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) {
            return slug(l.getText());
        }
        if (n instanceof TextInputControl t
                && t.getPromptText() != null
                && !t.getPromptText().isBlank()) {
            return slug(t.getPromptText());
        }
        if (n instanceof TableView<?> t && !t.getColumns().isEmpty()) {
            return "table-" + slug(t.getColumns().getFirst().getText());
        }
        if (n instanceof ComboBox<?> c && c.getValue() != null) {
            return slug("combo-" + c.getValue());
        }
        return n.getClass().getSimpleName().toLowerCase(Locale.ROOT) + "-" + this.counter;
    }

    private static String slug(String text) {
        String s = text == null
                ? "x"
                : text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", "-")
                        .replaceAll("^-|-$", "");
        return s.isEmpty() ? "x" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static boolean visible(Node n) {
        if (n.getScene() == null) {
            return false;
        }
        for (Node p = n; p != null; p = p.getParent()) {
            if (!p.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> find(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        walk(root, type, found);
        return found;
    }

    private static <T> void walk(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, type, found);
            }
        }
    }

    private static void closePopups() {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w instanceof PopupWindow) {
                w.hide();
            }
        }
    }

    private void fx(Runnable task) throws Exception {
        FxTesting.onFx(task);
        Thread.sleep(SETTLE);
    }

    private void shot(String slug, String what) throws Exception {
        Thread.sleep(SETTLE);
        FxTesting.onFx(() -> {
            save(slug, composite(), what);
            return null;
        });
    }

    // The main window with every other window over it, where they are on the screen.
    private BufferedImage composite() {
        Scene main = this.stage.getScene();
        double ox = this.stage.getX() + main.getX();
        double oy = this.stage.getY() + main.getY();
        BufferedImage base = image(main);
        List<Window> others = Window.getWindows().stream()
                .filter(w -> !w.equals(this.stage) && w.isShowing() && w.getScene() != null && w.getOpacity() > 0.01)
                .toList();
        double minX = 0;
        double minY = 0;
        double maxX = base.getWidth();
        double maxY = base.getHeight();
        List<BufferedImage> images = new ArrayList<>();
        List<double[]> places = new ArrayList<>();
        for (Window w : others) {
            BufferedImage img = image(w.getScene());
            double x = (w.getX() + w.getScene().getX() - ox) * this.scale;
            double y = (w.getY() + w.getScene().getY() - oy) * this.scale;
            images.add(img);
            places.add(new double[] {x, y, w instanceof Stage ? 1 : 0});
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + img.getWidth());
            maxY = Math.max(maxY, y + img.getHeight());
        }
        BufferedImage picture = new BufferedImage(
                (int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = picture.createGraphics();
        g.setColor(new Color(0x9AA3AF));
        g.fillRect(0, 0, picture.getWidth(), picture.getHeight());
        g.drawImage(base, (int) -minX, (int) -minY, null);
        int dropX = (int) Math.round(6 * this.scale);
        int dropY = (int) Math.round(8 * this.scale);
        for (int i = 0; i < images.size(); i++) {
            double[] at = places.get(i);
            int x = (int) Math.round(at[0] - minX);
            int y = (int) Math.round(at[1] - minY);
            BufferedImage img = images.get(i);
            if (at[2] > 0) {
                // A window gets a shadow and a frame, as the headless platform draws no decorations.
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
                g.setColor(Color.BLACK);
                g.fillRect(x + dropX, y + dropY, img.getWidth(), img.getHeight());
                g.setComposite(AlphaComposite.SrcOver);
                g.setColor(new Color(0x6B7280));
                g.drawRect(x - 1, y - 1, img.getWidth() + 1, img.getHeight() + 1);
            }
            g.drawImage(img, x, y, null);
        }
        g.dispose();
        return picture;
    }

    private BufferedImage image(Scene scene) {
        if (Double.compare(this.scale, 1) == 0) {
            return toBuffered(scene.snapshot(null));
        }
        // The scene drawn again with more pixels, not an enlarged picture of it.
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(this.scale, this.scale));
        params.setFill(scene.getFill());
        params.setViewport(new Rectangle2D(
                0, 0, Math.ceil(scene.getWidth() * this.scale), Math.ceil(scene.getHeight() * this.scale)));
        return toBuffered(scene.getRoot().snapshot(params, null));
    }

    private static BufferedImage toBuffered(WritableImage image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage picture = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                picture.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        return picture;
    }

    private void save(String slug, BufferedImage image, String what) throws IOException {
        Path folder = Files.createDirectories(this.out.resolve(this.set));
        String file = String.format(Locale.ROOT, "%03d-%s.png", ++this.counter, slug);
        if (this.stable) {
            // The same thing pictured again (a screen opened twice) gets the next free number after its name.
            file = slug + ".png";
            for (int again = 2; Files.exists(folder.resolve(file)); again++) {
                file = slug + "-" + again + ".png";
            }
        }
        Path target = folder.resolve(file);
        ImageIO.write(image, "png", target.toFile());
        this.index.add(this.set + "\t" + file + "\t" + image.getWidth() + "x" + image.getHeight() + "\t" + what);
    }
}
