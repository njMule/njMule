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

import java.awt.Desktop;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application menu of macOS: its About, Settings and Quit lead into the app, Quit asking first as closing the
 * window does, instead of the system's defaults that would end it at once.
 */
final class MacMenu {

    /** The system's application menu. */
    interface AppMenu {
        boolean supported();

        void handle(Actions actions);
    }

    /** What the items of the menu do; the system calls them outside the interface's thread. */
    record Actions(Runnable about, Runnable settings, Runnable quit) {}

    private static final Logger logger = LoggerFactory.getLogger(MacMenu.class);

    private MacMenu() {}

    /** Takes the application menu on macOS; elsewhere does nothing. */
    static boolean install(Actions actions) {
        return install(System.getProperty("os.name", ""), new AwtAppMenu(), actions);
    }

    /** As above, on the system {@code osName} names, whose application menu {@code menu} is. */
    static boolean install(String osName, AppMenu menu, Actions actions) {
        if (!osName.toLowerCase(Locale.ROOT).startsWith("mac")) {
            return false;
        }
        try {
            if (!menu.supported()) {
                return false;
            }
            menu.handle(actions);
            return true;
        } catch (RuntimeException | LinkageError e) {
            logger.warn("The application menu keeps the system's actions: {}", e.toString());
            return false;
        }
    }

    /** AWT's application menu, touched on macOS only. */
    private static final class AwtAppMenu implements AppMenu {
        @Override
        public boolean supported() {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT);
        }

        @Override
        public void handle(Actions actions) {
            Desktop desktop = Desktop.getDesktop();
            desktop.setAboutHandler(e -> actions.about().run());
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                desktop.setPreferencesHandler(e -> actions.settings().run());
            }
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                // The app quits by itself once the user agrees.
                desktop.setQuitHandler((e, response) -> {
                    response.cancelQuit();
                    actions.quit().run();
                });
            }
        }
    }
}
