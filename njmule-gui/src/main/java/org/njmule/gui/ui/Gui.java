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
import java.util.function.Consumer;
import org.njmule.core.config.AppDirectories;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;

/**
 * What every screen needs: texts, formats, the engine model, the theme, the settings and a way to tell the user
 * something.
 *
 * @param inform shows a short message to the user
 * @param relaunch rebuilds the window, after a language change
 * @param subscriptions what the window follows of the model, ended when it is rebuilt
 */
public record Gui(
        I18n i18n,
        Formats formats,
        EngineModel model,
        Themes themes,
        GuiSettings settings,
        AppDirectories directories,
        Consumer<String> inform,
        Runnable relaunch,
        Subscriptions subscriptions) {

    public Gui {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(formats, "formats");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(themes, "themes");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(directories, "directories");
        Objects.requireNonNull(inform, "inform");
        Objects.requireNonNull(relaunch, "relaunch");
        Objects.requireNonNull(subscriptions, "subscriptions");
    }

    /** A context with subscriptions of its own. */
    public Gui(
            I18n i18n,
            Formats formats,
            EngineModel model,
            Themes themes,
            GuiSettings settings,
            AppDirectories directories,
            Consumer<String> inform,
            Runnable relaunch) {
        this(i18n, formats, model, themes, settings, directories, inform, relaunch, new Subscriptions());
    }

    public String t(String key) {
        return this.i18n.t(key);
    }

    public String f(String key, Object... args) {
        return this.i18n.f(key, args);
    }
}
