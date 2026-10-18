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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.event.Event;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.njmule.gui.dialogs.AboutDialog;
import org.njmule.gui.dialogs.CategoriesDialog;
import org.njmule.gui.dialogs.FirstRunWizard;
import org.njmule.gui.dialogs.ImportDialog;
import org.njmule.gui.dialogs.UpdateDialog;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.SoundPlayer;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.shell.MainWindow;
import org.njmule.gui.ui.Gui;
import org.njmule.webui.ApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks through every screen, popup, dialog and theme once and exits, so that the native-image tracing agent sees what
 * the interface reaches by reflection, JNI and resources. Started by the system property {@code njmule.gui.tour}.
 */
final class GuiTour {

    static final String PROPERTY = "njmule.gui.tour";

    private static final Logger logger = LoggerFactory.getLogger(GuiTour.class);
    private static final Duration STEP = Duration.millis(250);

    private final Gui gui;
    private final MainWindow window;
    private final Stage stage;
    private final List<Runnable> appTour;
    private final Runnable done;
    private final List<Runnable> steps = new ArrayList<>();

    private GuiTour(Gui gui, MainWindow window, Stage stage, List<Runnable> appTour, Runnable done) {
        this.gui = gui;
        this.window = window;
        this.stage = stage;
        this.appTour = List.copyOf(appTour);
        this.done = done;
    }

    static boolean requested() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Plays the tour on the FX thread, then runs {@code done} (the app exits); {@code appTour} comes first, with what
     * only the app has: its sounds, its web interface and the windows of the notification area.
     */
    static void start(Gui gui, MainWindow window, Stage stage, List<Runnable> appTour, Runnable done) {
        new GuiTour(gui, window, stage, appTour, done).play();
    }

    private void play() {
        // First, so that the sound and the requests of the web interface end long before the app does.
        this.steps.addAll(this.appTour);
        for (String id : this.window.screenIds()) {
            this.steps.add(() -> this.window.show(id));
            this.steps.add(() -> popups(this.stage.getScene().getRoot()));
            this.steps.add(this::closePopups);
        }
        // What a key or a button opens rather than the popups of the screens.
        this.steps.add(() -> this.window.show("transfers"));
        this.steps.add(() -> click("#details"));
        this.steps.add(() -> click("#details"));
        this.steps.add(() -> this.window.show("search"));
        this.steps.add(() -> click("#search-parameters"));
        this.steps.add(() -> click("#rates"));
        this.steps.add(this::closePopups);
        this.steps.add(() -> press(KeyCode.K, true));
        this.steps.add(() -> press(KeyCode.ESCAPE, false));
        this.steps.add(() -> press(KeyCode.F1, false));
        Themes themes = this.gui.themes();
        Themes.Family family = themes.family();
        GuiSettings.Mode mode = themes.mode();
        for (Themes.Family f : Themes.Family.values()) {
            for (GuiSettings.Mode m : GuiSettings.Mode.values()) {
                this.steps.add(() -> themes.set(f, m));
            }
        }
        this.steps.add(this::highContrast);
        this.steps.add(() -> themes.set(family, mode));
        this.steps.add(() -> new AboutDialog(this.gui, this.stage).show());
        this.steps.add(() -> new UpdateDialog(this.gui, this.stage).show());
        this.steps.add(() -> new ImportDialog(this.gui, this.stage).show());
        this.steps.add(() -> new CategoriesDialog(this.gui, this.stage).show());
        this.steps.add(() -> Optional.ofNullable(
                        this.gui.model().preferencesProperty().get())
                .ifPresent(p -> new FirstRunWizard(this.gui, this.stage, p, Optional.empty(), outcome -> {}).show()));
        this.steps.add(this::alert);
        this.steps.add(this::scripts);
        this.steps.add(this::closeWindows);
        this.steps.add(() -> this.window.message("Tour"));
        this.steps.add(() -> this.window.offer("Tour", "Tour", () -> {}));
        this.steps.add(GuiTour::languages);
        this.steps.add(GuiTour::picture);
        this.steps.add(GuiTour::clipboard);
        this.steps.add(() -> {
            logger.info("Tour finished");
            this.done.run();
        });
        Timeline timeline = new Timeline();
        for (int i = 0; i < this.steps.size(); i++) {
            Runnable step = this.steps.get(i);
            timeline.getKeyFrames().add(new KeyFrame(STEP.multiply(i + 1.0), e -> step.run()));
        }
        timeline.play();
    }

    // Opens the combo boxes, menus and context menus and the tooltips under node.
    private void popups(Node node) {
        if (node instanceof ComboBox<?> box && box.isVisible()) {
            box.show();
        }
        if (node instanceof MenuButton menu && menu.isVisible()) {
            menu.show();
        }
        if (node instanceof Control control && control.getScene() != null) {
            if (control.getContextMenu() != null) {
                control.getContextMenu().show(control, Side.BOTTOM, 0, 0);
            }
            if (control.getTooltip() != null) {
                control.getTooltip().show(this.stage);
            }
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(this::popups);
        }
    }

    private void click(String id) {
        if (this.stage.getScene().lookup(id) instanceof ButtonBase button) {
            button.fire();
        }
    }

    // A key pressed where the focus is, with Ctrl or Cmd if shortcut.
    private void press(KeyCode code, boolean shortcut) {
        Node focus = this.stage.getScene().getFocusOwner();
        Event.fireEvent(
                focus == null ? this.stage.getScene().getRoot() : focus,
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, shortcut));
    }

    private void closePopups() {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (!this.stage.equals(w) && !(w instanceof Stage)) {
                w.hide();
            }
        }
    }

    private void closeWindows() {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (!this.stage.equals(w)) {
                w.hide();
            }
        }
    }

    // A window in a high contrast scheme of the system, styled by the stylesheet made from its colours.
    private void highContrast() {
        Map<String, Object> scheme = Map.of(
                "Windows.SPI.HighContrast", true,
                "Windows.SysColor.COLOR_WINDOW", Color.BLACK,
                "Windows.SysColor.COLOR_WINDOWTEXT", Color.WHITE);
        Scene scene = new Scene(new VBox(new Label("Tour"), new Button("Tour")));
        new Themes(
                        this.gui.themes().family(),
                        this.gui.themes().mode(),
                        FXCollections.observableMap(new HashMap<>(scheme)))
                .attach(scene);
        Stage contrast = new Stage();
        contrast.initOwner(this.stage);
        contrast.setScene(scene);
        contrast.show();
    }

    private void alert() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Tour", ButtonType.OK);
        alert.initOwner(this.stage);
        this.gui.themes().attach(alert.getDialogPane().getScene());
        alert.show();
    }

    // Text in the writings of the languages, right to left: shaping and the fallback fonts.
    private void scripts() {
        Alert alert = new Alert(
                Alert.AlertType.INFORMATION,
                "العربية فارسی עברית 中文 日本語 한국어 Ελληνικά Русский Tiếng Việt",
                ButtonType.OK);
        alert.initOwner(this.stage);
        alert.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        this.gui.themes().attach(alert.getDialogPane().getScene());
        alert.show();
    }

    /** A tenth of a second of silence, written and played as a notification sound is. */
    static void sound(SoundPlayer player, Path dir) {
        Path file = dir.resolve("tour.wav");
        AudioFormat format = new AudioFormat(8000, 16, 1, true, false);
        byte[] silence = new byte[1600];
        try (AudioInputStream in = new AudioInputStream(
                new ByteArrayInputStream(silence), format, silence.length / format.getFrameSize())) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        } catch (IOException e) {
            logger.warn("Cannot write the sound of the tour: {}", e.toString());
            return;
        }
        player.play(file);
    }

    /** A few requests to the API of the web interface the settings in {@code configDir} start. */
    static void webInterface(Path configDir) {
        try {
            Optional<ApiClient> api = ApiClient.local(configDir);
            if (api.isEmpty()) {
                logger.warn("The web interface of the tour is off");
                return;
            }
            api.get().get("/status");
            api.get().get("/downloads");
            api.get().get("/servers");
            // What the API checks against its OpenAPI document: a query parameter, a body, a refused body.
            api.get().get("/log?after=0");
            if (api.get().get("/limits") instanceof Map<?, ?> limits
                    && limits.get("uploadKiBps") instanceof Long rate) {
                api.get().put("/limits", Map.of("uploadKiBps", rate));
            }
            refusedBody(api.get());
        } catch (IOException e) {
            logger.warn("The web interface of the tour did not answer: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void refusedBody(ApiClient api) throws IOException, InterruptedException {
        try {
            api.put("/limits", Map.of("uploadKiBps", -1));
            logger.warn("The API of the tour took a negative speed limit");
        } catch (ApiClient.Failure e) {
            logger.debug("The API of the tour refused a negative speed limit: {}", e.getMessage());
        }
    }

    // Also JavaFX's own texts in each language, which a language change makes the default.
    private static void languages() {
        I18n.LANGUAGES.forEach(I18n::of);
        I18n.of(Locale.ROOT);
        Locale shown = Locale.getDefault();
        for (Locale language : I18n.LANGUAGES) {
            I18n.makeDefault(language);
            logger.debug("JavaFX's Cancel in {}: {}", language, ButtonType.CANCEL.getText());
        }
        I18n.makeDefault(shown);
    }

    // The pictures of chat CAPTCHAs are BMP files read through ImageIO.
    private static void picture() {
        try {
            ByteArrayOutputStream bmp = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "bmp", bmp);
            ImageIO.read(new ByteArrayInputStream(bmp.toByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Copies a text the way the screens do, then puts the user's text back; other kinds of
    // content are left alone.
    private static void clipboard() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        boolean onlyText = clipboard.getContentTypes().isEmpty()
                || (clipboard.hasString() && clipboard.getContentTypes().size() == 1);
        if (!onlyText) {
            return;
        }
        String before = clipboard.hasString() ? clipboard.getString() : null;
        ClipboardContent content = new ClipboardContent();
        content.putString("njMule");
        clipboard.setContent(content);
        if (before == null) {
            clipboard.clear();
        } else {
            ClipboardContent back = new ClipboardContent();
            back.putString(before);
            clipboard.setContent(back);
        }
    }
}
