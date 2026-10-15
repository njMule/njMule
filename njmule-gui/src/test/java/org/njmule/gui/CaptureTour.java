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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Bounds;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.LogLine;
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
import org.njmule.gui.screens.SettingsScreen;
import org.njmule.gui.screens.SharedScreen;
import org.njmule.gui.shell.MainWindow;
import org.njmule.gui.shell.MiniMule;
import org.njmule.gui.shell.TrayMenu;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Subscriptions;

/**
 * Pictures every screen, tab, menu, sub-menu, context menu, drop-down list, column menu, icon-button tooltip and dialog
 * of the interface, with example data, for reviews of its design. Runs only when asked: {@code ./gradlew
 * :njmule-gui:test --tests CaptureTour -Pnjmule.capture=build/gui-capture}; {@code -Pnjmule.capture.sets} picks the
 * sets (see {@link #SETS}).
 */
@Timeout(1800)
@EnabledIfSystemProperty(named = "njmule.capture", matches = ".+")
class CaptureTour {

    /**
     * The sets pictured unless {@code njmule.capture.sets} lists others: language, then light or dark, then
     * {@code full} for everything or {@code screens} for the screens and their tabs, or {@code empty} for a
     * disconnected profile without data.
     */
    static final String SETS = "it-light-full,it-dark-screens,en-light-screens,ar-light-screens,it-light-empty";

    private static final long SETTLE = 160;
    private static final int TOOLTIPS_PER_SCREEN = 12;

    @TempDir
    Path dir;

    private Path out;
    private List<String> sets;
    private NjMuleEngine engine;
    private EngineModel model;
    private Gui gui;
    private MainWindow window;
    private Stage stage;
    private String set;
    private int counter;
    private final List<String> index = new ArrayList<>();
    private final List<String> missed = new ArrayList<>();
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicReference<MainWindow> forMessages = new AtomicReference<>();

    @Test
    void shouldPictureEveryStateOfTheInterface() throws Exception {
        givenTheSetsAsked();
        whenEachSetIsPictured();
        thenEveryPictureIsListedAndNothingFailedToOpen();
    }

    private void givenTheSetsAsked() throws Exception {
        this.out = Files.createDirectories(Path.of(System.getProperty("njmule.capture")));
        String asked = System.getProperty("njmule.capture.sets", "");
        this.sets = List.of((asked.isBlank() ? SETS : asked).split(","));
        FxTesting.start();
    }

    private void whenEachSetIsPictured() throws Exception {
        for (String name : this.sets) {
            String[] parts = name.strip().split("-", -1);
            Locale language = Locale.forLanguageTag(parts[0]);
            GuiSettings.Mode mode = "dark".equals(parts[1]) ? GuiSettings.Mode.DARK : GuiSettings.Mode.LIGHT;
            String depth = parts.length > 2 ? parts[2] : "screens";
            open(language, mode, !"empty".equals(depth), name.strip());
            if ("full".equals(depth)) {
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

    private void open(Locale language, GuiSettings.Mode mode, boolean demo, String name) throws Exception {
        this.set = name;
        this.counter = 0;
        this.seen.clear();
        Path root = this.dir.resolve(name);
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        Files.createDirectories(dirs.gui());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        int udp;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            udp = probe.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=false\nsecurity.filterLan=false\n"
                        + "search.store=true\nuser.nick=Lupo\nlimits.uploadKiBps=250\nlimits.downloadKiBps=0\n"
                        + "net.upnp=false\nnotify.sound=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        if (demo) {
            this.engine.updateSchedule(CaptureDemo.schedule()).get(10, TimeUnit.SECONDS);
        }
        NjMuleEngine shown = demo ? Engines.overriding(this.engine, CaptureDemo.commands(root)) : this.engine;
        this.model = new EngineModel(shown, Platform::runLater);
        I18n i18n = I18n.of(language);
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
                new Themes(Themes.Family.NORD, mode),
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
        }
        for (int i = 0; i < 31; i++) {
            FxTesting.onFx(this.model::tick);
        }
        // Long enough for the banner of a lasting state to show.
        Thread.sleep(2500);
    }

    // What only events tell: the connection, the log, the chats and ten minutes of rates.
    private void feed() throws Exception {
        this.model.onNext(new EngineEvent.ServerConnecting(CaptureDemo.server(0)));
        for (LogLine line : CaptureDemo.log()) {
            this.model.onNext(new EngineEvent.Log(line));
        }
        this.model.onNext(new EngineEvent.ServerConnected(CaptureDemo.server(0), 0x17643C6AL, false, true));
        this.model.onNext(new EngineEvent.ServerMessage(
                CaptureDemo.server(0), "Welcome to Lighthouse! Rules: no fake files, no spam."));
        this.model.onNext(new EngineEvent.MessageReceived(
                "203.0.113.33:4672", CaptureDemo.hash("f1"), Optional.of("Owl"), "Hi! Do you have the 4K one too?"));
        this.model.onNext(new EngineEvent.MessageReceived(
                "203.0.113.33:4672", CaptureDemo.hash("f1"), Optional.of("Owl"), "Thanks for Sintel :)"));
        FxTesting.onFx(
                () -> this.model.sendMessage("203.0.113.33:4672", "You're welcome! The 4K one is in my shared files."));
        this.model.onNext(new EngineEvent.MessageReceived(
                "198.51.100.77:4662", CaptureDemo.hash("x9"), Optional.of("Fog"), "May I add you as a friend?"));
        this.model.onNext(new EngineEvent.CaptchaRequested("192.0.2.99:4662", CaptureDemo.hash("c9"), captcha()));
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

    private static byte[] captcha() throws IOException {
        BufferedImage img = new BufferedImage(120, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xEEEEEE));
        g.fillRect(0, 0, 120, 36);
        g.setColor(new Color(0x334455));
        g.setFont(g.getFont().deriveFont(22f));
        g.drawString("K7Q2M", 18, 27);
        g.drawLine(5, 20, 115, 12);
        g.dispose();
        ByteArrayOutputStream bmp = new ByteArrayOutputStream();
        ImageIO.write(img, "bmp", bmp);
        return bmp.toByteArray();
    }

    private void close() throws Exception {
        FxTesting.onFx(() -> List.copyOf(Window.getWindows()).forEach(Window::hide));
        this.forMessages.set(null);
        this.model.close();
        this.engine.close();
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

    private void everything() throws Exception {
        for (String id : this.window.screenIds()) {
            fx(() -> this.window.show(id));
            fx(() -> expandTrees(this.stage.getScene().getRoot()));
            shot(id, "Screen " + id + " as it opens");
            if (id.equals("search")) {
                // The parameters fold away until wanted: open them to picture their lists too.
                fx(() -> ((javafx.scene.control.ToggleButton)
                                this.stage.getScene().getRoot().lookup("#search-parameters"))
                        .fire());
                shot("search-parameters", "Search with its parameters open");
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
        fx(() -> this.stage.getScene().getRoot().fireEvent(FxTesting.key(javafx.scene.input.KeyCode.K, true)));
        shot("palette", "The command palette (Ctrl+K)");
        fx(() -> ((TextField) this.stage.getScene().getRoot().lookup(".command-palette .text-field"))
                .setText("big buck"));
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
            f.setText("ed2k://|file|Sintel.2010.1080p.x264.mkv|1245822976|…|/");
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

    private void settingsPage() throws Exception {
        fx(() -> this.window.show("settings"));
        Parent screen = screenRoot();
        Optional<ScrollPane> scroll = FxTesting.onFx(() -> find(screen, ScrollPane.class).stream()
                .filter(CaptureTour::visible)
                .findFirst());
        if (scroll.isEmpty()) {
            return;
        }
        for (int i = 0; i <= 10; i++) {
            double v = i / 10.0;
            fx(() -> scroll.get().setVvalue(v));
            shot(String.format(Locale.ROOT, "settings-scroll-%02d", i), "Options scrolled to " + i + "/10");
        }
        fx(() -> scroll.get().setVvalue(0));
        // The whole page at once: the content of the scroll pane on the screen's own background.
        FxTesting.onFx(() -> {
            BufferedImage base = image(this.stage.getScene());
            Color bg = new Color(base.getRGB(base.getWidth() - 40, 72), true);
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(javafx.scene.paint.Color.rgb(bg.getRed(), bg.getGreen(), bg.getBlue()));
            WritableImage page = scroll.get().getContent().snapshot(params, null);
            save("settings-full-page", toBuffered(page), "Options: the whole page");
            return null;
        });
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
            query.getEditor().setText("big buck bunny");
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
        fx(() -> ((javafx.scene.control.ToggleButton) transfers.lookup("#details")).fire());
        shot("transfers-details-none", "Transfers with the details open, nothing chosen");
        select(transfers, 0, 0);
        shot("transfers-details", "Transfers with the details of the download chosen");
        fx(() -> ((javafx.scene.control.ToggleButton) transfers.lookup("#details")).fire());
    }

    // ---------------------------------------------------------------- dialogs

    private void dialogs() throws Exception {
        window("dialog-about", "About njMule", () -> new AboutDialog(this.gui, this.stage).show(), w -> {});
        Path emule = Files.createDirectories(this.dir.resolve("eMule").resolve("config"));
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
                "dialog-transfers-cancel",
                "Cancel a download",
                () -> item(downloads.getContextMenu(), "IDS_CANCEL").fire());
        waiting(
                "dialog-transfers-add-source",
                "Add a source by hand",
                () -> item(downloads.getContextMenu(), "IDS_ADDSRCMANUALLY").fire());
        window(
                "dialog-comments-download",
                "Comments of a download",
                () -> item(downloads.getContextMenu(), "IDS_CMT_ADD").fire(),
                w -> {});
        TableView<?> uploads = FxTesting.onFx(() -> (TableView<?>) transfers.lookup("#uploads"));
        fx(() -> uploads.getSelectionModel().select(0));
        window(
                "dialog-client-files",
                "The files a client shares",
                () -> item(uploads.getContextMenu(), "IDS_VIEWFILES").fire(),
                w -> {});

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
        fx(() -> this.window.show("transfers"));
        Parent transfers = screenRoot();
        fx(() -> {
            TextField link = find(transfers, TextField.class).stream()
                    .filter(f -> f.getPromptText() != null && f.getPromptText().startsWith("ed2k://"))
                    .findFirst()
                    .orElseThrow();
            link.requestFocus();
            link.setText("ed2k://|file|broken|");
        });
        fx(() -> this.window.root().requestFocus());
        shot("transfers-invalid-link", "The link field with a wrong link");
        fx(() -> this.window.show("settings"));
        Parent settings = screenRoot();
        fx(() -> {
            TextField port = find(settings, TextField.class).stream()
                    .filter(f ->
                            f.getTooltip() != null && f.getTooltip().getText().contains("65535"))
                    .findFirst()
                    .orElseThrow();
            port.requestFocus();
            port.setText("99999");
        });
        fx(() -> this.window.root().requestFocus());
        shot("settings-invalid-field", "Options with a port out of range");
        fx(() -> find(settings, Button.class).stream()
                .filter(b -> b.getStyleClass().contains("accent"))
                .findFirst()
                .ifPresent(Button::fire));
        shot("settings-apply-invalid", "Options: Apply with a port out of range");
        fx(() -> button(settings, "IDS_PW_RESET").fire());
    }

    private void toasts() throws Exception {
        fx(() -> this.window.show("transfers"));
        fx(() -> {
            this.window.success(this.gui.f("nj.transfers.completed", "LibreOffice_25.8.1_Win_x86-64.msi"));
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
        for (String id : List.of("transfers", "search", "servers", "settings")) {
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
            double x = w.getX() + w.getScene().getX() - ox;
            double y = w.getY() + w.getScene().getY() - oy;
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
        for (int i = 0; i < images.size(); i++) {
            double[] at = places.get(i);
            int x = (int) Math.round(at[0] - minX);
            int y = (int) Math.round(at[1] - minY);
            BufferedImage img = images.get(i);
            if (at[2] > 0) {
                // A window gets a shadow and a frame, as the headless platform draws no decorations.
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
                g.setColor(Color.BLACK);
                g.fillRect(x + 6, y + 8, img.getWidth(), img.getHeight());
                g.setComposite(AlphaComposite.SrcOver);
                g.setColor(new Color(0x6B7280));
                g.drawRect(x - 1, y - 1, img.getWidth() + 1, img.getHeight() + 1);
            }
            g.drawImage(img, x, y, null);
        }
        g.dispose();
        return picture;
    }

    private static BufferedImage image(Scene scene) {
        return toBuffered(scene.snapshot(null));
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
        String file = String.format(Locale.ROOT, "%03d-%s.png", ++this.counter, slug);
        Path target = Files.createDirectories(this.out.resolve(this.set)).resolve(file);
        ImageIO.write(image, "png", target.toFile());
        this.index.add(this.set + "\t" + file + "\t" + image.getWidth() + "x" + image.getHeight() + "\t" + what);
    }
}
