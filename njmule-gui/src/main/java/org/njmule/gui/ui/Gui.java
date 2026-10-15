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
package org.njmule.gui.ui;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import org.njmule.app.Autostart;
import org.njmule.core.config.AppDirectories;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.SoundPlayer;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;

/**
 * What every screen needs: texts, formats, the engine model, the theme, the settings and a way to tell the user
 * something.
 *
 * @param inform shows a short message to the user
 * @param warn shows a problem the user has to read: what failed or was refused, and why
 * @param relaunch rebuilds the window, after a language change
 * @param subscriptions what the window follows of the model, ended when it is rebuilt
 * @param sound plays the sound of the notifications
 * @param autostart starts njMule when the user logs in; empty where it cannot
 */
public record Gui(
        I18n i18n,
        Formats formats,
        EngineModel model,
        Themes themes,
        GuiSettings settings,
        AppDirectories directories,
        Consumer<String> inform,
        Consumer<String> warn,
        Runnable relaunch,
        Subscriptions subscriptions,
        SoundPlayer sound,
        Optional<Autostart> autostart) {

    public Gui {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(formats, "formats");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(themes, "themes");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(directories, "directories");
        Objects.requireNonNull(inform, "inform");
        Objects.requireNonNull(warn, "warn");
        Objects.requireNonNull(relaunch, "relaunch");
        Objects.requireNonNull(subscriptions, "subscriptions");
        Objects.requireNonNull(sound, "sound");
        Objects.requireNonNull(autostart, "autostart");
    }

    /**
     * A context with subscriptions of its own, which plays no sound, never starts at login and tells problems as it
     * tells the rest.
     */
    public Gui(
            I18n i18n,
            Formats formats,
            EngineModel model,
            Themes themes,
            GuiSettings settings,
            AppDirectories directories,
            Consumer<String> inform,
            Runnable relaunch) {
        this(
                i18n,
                formats,
                model,
                themes,
                settings,
                directories,
                inform,
                inform,
                relaunch,
                new Subscriptions(),
                SoundPlayer.SILENT,
                Optional.empty());
    }

    public String t(String key) {
        return this.i18n.t(key);
    }

    public String f(String key, Object... args) {
        return this.i18n.f(key, args);
    }

    /** The direction the interface runs in: right to left for the languages written so. */
    public NodeOrientation orientation() {
        return this.i18n.isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;
    }

    /** Styles {@code scene} with the theme and lays it out in the direction of the interface. */
    public Scene attach(Scene scene) {
        this.themes.attach(scene);
        scene.setNodeOrientation(this.orientation());
        return scene;
    }

    /**
     * Lays {@code dialog} out in the direction of the interface (it would follow its owner's, but the windows of the
     * notification area have none to give) and in the house style: no question-mark picture, the question is in the
     * words, and no header repeating the kind of alert. A header or picture set afterwards stays.
     */
    public <D extends Dialog<?>> D dialog(D dialog) {
        dialog.getDialogPane().setNodeOrientation(this.orientation());
        if (dialog instanceof TextInputDialog
                || dialog instanceof Alert alert && alert.getAlertType() == Alert.AlertType.CONFIRMATION) {
            dialog.setGraphic(null);
        }
        if (dialog instanceof Alert) {
            dialog.setHeaderText(null);
        }
        return dialog;
    }
}
