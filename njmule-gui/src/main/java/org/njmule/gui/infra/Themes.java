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
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The AtlantaFX theme of the interface, light or dark (by default as the system is), with njMule's own colour tokens in
 * {@code njmule.css}; while the system's high contrast is on, its colours replace the theme's. FX thread only.
 */
public final class Themes {

    /** The AtlantaFX theme families; Nord is the default. */
    public enum Family {
        NORD,
        PRIMER,
        CUPERTINO,
        DRACULA
    }

    private static final Logger logger = LoggerFactory.getLogger(Themes.class);
    private static final String STYLESHEET = "/org/njmule/gui/njmule.css";
    // Manrope sets titles and the wordmark (style class "display"); text keeps the system's face, which has every
    // script of the translations.
    private static final List<String> FACES =
            List.of("/org/njmule/gui/fonts/Manrope-Bold.ttf", "/org/njmule/gui/fonts/Manrope-ExtraBold.ttf");
    private static boolean facesLoaded;
    private static final String DARK_CLASS = "dark";
    private static final String CONTRAST_CLASS = "high-contrast";

    private Family family;
    private GuiSettings.Mode mode;
    private final ReadOnlyBooleanWrapper dark = new ReadOnlyBooleanWrapper();
    private final ObservableMap<String, Object> system;
    // The stylesheet with the high contrast colours, empty while it is off.
    private String contrastSheet = "";
    // Weakly held: closed dialogs go away.
    private final Set<Scene> scenes = Collections.newSetFromMap(new WeakHashMap<>());

    public Themes(Family family, GuiSettings.Mode mode) {
        this(family, mode, Platform.getPreferences());
    }

    /** Follows {@code system} instead of the platform's preferences for high contrast, as tests and pictures do. */
    public Themes(Family family, GuiSettings.Mode mode, ObservableMap<String, Object> system) {
        this.family = Objects.requireNonNull(family, "family");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.system = Objects.requireNonNull(system, "system");
        Platform.getPreferences().colorSchemeProperty().addListener((o, was, now) -> apply());
        system.addListener((MapChangeListener<String, Object>) change -> {
            if (HighContrast.concerns(change.getKey())) {
                apply();
            }
        });
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
        loadFaces();
        String css =
                Objects.requireNonNull(Themes.class.getResource(STYLESHEET)).toExternalForm();
        if (!scene.getStylesheets().contains(css)) {
            scene.getStylesheets().add(css);
        }
        scene.rootProperty().addListener((o, was, now) -> apply());
        apply();
    }

    private static synchronized void loadFaces() {
        if (facesLoaded) {
            return;
        }
        facesLoaded = true;
        for (String face : FACES) {
            try (InputStream in = Themes.class.getResourceAsStream(face)) {
                if (in == null || Font.loadFont(in, 0) == null) {
                    logger.warn("Cannot load the font {}: titles fall back to the system's face", face);
                }
            } catch (IOException e) {
                logger.warn("Cannot read the font {}", face, e);
            }
        }
    }

    static String familyClass(Family family) {
        return "theme-" + family.name().toLowerCase(Locale.ROOT);
    }

    public void set(Family family, GuiSettings.Mode mode) {
        this.family = Objects.requireNonNull(family, "family");
        this.mode = Objects.requireNonNull(mode, "mode");
        apply();
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
        Optional<HighContrast> contrast = HighContrast.of(this.system);
        // A high contrast scheme is light or dark whatever the mode; the mode comes back when it is off.
        boolean wantsDark = contrast.map(HighContrast::dark).orElseGet(() -> switch (this.mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> Platform.getPreferences().getColorScheme() == ColorScheme.DARK;
        });
        Theme theme = theme(this.family, wantsDark);
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
        this.dark.set(theme.isDarkMode());
        String before = this.contrastSheet;
        this.contrastSheet = contrast.map(HighContrast::stylesheet).orElse("");
        for (Scene scene : this.scenes) {
            List<String> sheets = scene.getStylesheets();
            if (!before.equals(this.contrastSheet)) {
                sheets.remove(before);
            }
            if (!this.contrastSheet.isEmpty() && !sheets.contains(this.contrastSheet)) {
                sheets.add(this.contrastSheet);
            }
            if (scene.getRoot() != null) {
                mark(scene.getRoot().getStyleClass(), DARK_CLASS, theme.isDarkMode());
                mark(scene.getRoot().getStyleClass(), CONTRAST_CLASS, contrast.isPresent());
                // The family, for the few tones njmule.css strengthens; the system's scheme replaces them all.
                for (Family f : Family.values()) {
                    mark(scene.getRoot().getStyleClass(), familyClass(f), f == this.family && contrast.isEmpty());
                }
            }
        }
    }

    private static void mark(List<String> classes, String name, boolean on) {
        if (on && !classes.contains(name)) {
            classes.add(name);
        } else if (!on) {
            classes.remove(name);
        }
    }
}
