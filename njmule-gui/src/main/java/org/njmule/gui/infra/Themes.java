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
package org.njmule.gui.infra;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.Scene;

/**
 * The AtlantaFX theme of the interface, light or dark (by default as the system is), with njMule's own colour tokens in
 * {@code njmule.css}. FX thread only.
 */
public final class Themes {

    /** The AtlantaFX theme families; Nord is the default. */
    public enum Family {
        NORD,
        PRIMER,
        CUPERTINO,
        DRACULA
    }

    private static final String STYLESHEET = "/org/njmule/gui/njmule.css";
    private static final String DARK_CLASS = "dark";

    private Family family;
    private GuiSettings.Mode mode;
    private final ReadOnlyBooleanWrapper dark = new ReadOnlyBooleanWrapper();
    // Weakly held: closed dialogs go away.
    private final Set<Scene> scenes = Collections.newSetFromMap(new WeakHashMap<>());

    public Themes(Family family, GuiSettings.Mode mode) {
        this.family = Objects.requireNonNull(family, "family");
        this.mode = Objects.requireNonNull(mode, "mode");
        Platform.getPreferences().colorSchemeProperty().addListener((o, was, now) -> this.apply());
    }

    /** The theme of a family, light or dark; Dracula has only a dark one. */
    public static Theme theme(Family family, boolean dark) {
        return switch (family) {
            case NORD -> dark ? new NordDark() : new NordLight();
            case PRIMER -> dark ? new PrimerDark() : new PrimerLight();
            case CUPERTINO -> dark ? new CupertinoDark() : new CupertinoLight();
            case DRACULA -> new Dracula();
        };
    }

    /** Styles {@code scene} from now on, also when the theme changes. */
    public void attach(Scene scene) {
        this.scenes.add(Objects.requireNonNull(scene, "scene"));
        String css =
                Objects.requireNonNull(Themes.class.getResource(STYLESHEET)).toExternalForm();
        if (!scene.getStylesheets().contains(css)) {
            scene.getStylesheets().add(css);
        }
        scene.rootProperty().addListener((o, was, now) -> this.apply());
        this.apply();
    }

    public void set(Family family, GuiSettings.Mode mode) {
        this.family = Objects.requireNonNull(family, "family");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.apply();
    }

    public Family family() {
        return this.family;
    }

    public GuiSettings.Mode mode() {
        return this.mode;
    }

    public ReadOnlyBooleanProperty darkProperty() {
        return this.dark.getReadOnlyProperty();
    }

    private void apply() {
        boolean wantsDark = switch (this.mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        };
        Theme theme = theme(this.family, wantsDark);
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
        this.dark.set(theme.isDarkMode());
        for (Scene scene : this.scenes) {
            if (scene.getRoot() == null) {
                continue;
            }
            var classes = scene.getRoot().getStyleClass();
            if (theme.isDarkMode() && !classes.contains(DARK_CLASS)) {
                classes.add(DARK_CLASS);
            } else if (!theme.isDarkMode()) {
                classes.remove(DARK_CLASS);
            }
        }
    }
}
