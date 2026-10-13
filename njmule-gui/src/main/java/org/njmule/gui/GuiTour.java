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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.MenuButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import org.njmule.gui.dialogs.AboutDialog;
import org.njmule.gui.dialogs.ImportDialog;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.shell.MainWindow;
import org.njmule.gui.ui.Gui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks through every screen, popup, dialog and theme once and exits, so that the native-image
 * tracing agent sees what the interface reaches by reflection, JNI and resources. Started by the
 * system property {@code njmule.gui.tour}.
 */
final class GuiTour {

    static final String PROPERTY = "njmule.gui.tour";

    private static final Logger LOG = LoggerFactory.getLogger(GuiTour.class);
    private static final Duration STEP = Duration.millis(250);

    private final Gui gui;
    private final MainWindow window;
    private final Stage stage;
    private final Runnable done;
    private final List<Runnable> steps = new ArrayList<>();

    private GuiTour(Gui gui, MainWindow window, Stage stage, Runnable done) {
        this.gui = gui;
        this.window = window;
        this.stage = stage;
        this.done = done;
    }

    static boolean requested() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Plays the tour on the FX thread, then runs {@code done} (the app exits). */
    static void start(Gui gui, MainWindow window, Stage stage, Runnable done) {
        new GuiTour(gui, window, stage, done).play();
    }

    private void play() {
        for (String id : this.window.screenIds()) {
            this.steps.add(() -> this.window.show(id));
            this.steps.add(() -> this.popups(this.stage.getScene().getRoot()));
            this.steps.add(this::closePopups);
        }
        Themes themes = this.gui.themes();
        Themes.Family family = themes.family();
        GuiSettings.Mode mode = themes.mode();
        for (Themes.Family f : Themes.Family.values()) {
            for (GuiSettings.Mode m : GuiSettings.Mode.values()) {
                this.steps.add(() -> themes.set(f, m));
            }
        }
        this.steps.add(() -> themes.set(family, mode));
        this.steps.add(() -> new AboutDialog(this.gui, this.stage).show());
        this.steps.add(() -> new ImportDialog(this.gui, this.stage).show());
        this.steps.add(this::alert);
        this.steps.add(this::closeWindows);
        this.steps.add(() -> this.window.message("Tour"));
        this.steps.add(GuiTour::languages);
        this.steps.add(GuiTour::picture);
        this.steps.add(GuiTour::clipboard);
        this.steps.add(
                () -> {
                    LOG.info("Tour finished");
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

    private void alert() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Tour", ButtonType.OK);
        alert.initOwner(this.stage);
        this.gui.themes().attach(alert.getDialogPane().getScene());
        alert.show();
    }

    private static void languages() {
        I18n.LANGUAGES.forEach(I18n::of);
        I18n.of(Locale.ROOT);
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
        boolean onlyText =
                clipboard.getContentTypes().isEmpty()
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
