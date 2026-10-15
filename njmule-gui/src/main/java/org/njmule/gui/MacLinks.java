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
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.njmule.core.api.Ed2kLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On macOS a link opened in a browser comes to the app as an Apple Event, not on the command line, also when it starts
 * the app. AWT's open-URI handler takes it; it is set before JavaFX starts, so the event that launched the app is kept,
 * and its links wait until the window takes them. Elsewhere nothing is set.
 */
final class MacLinks {

    /** The system's events of opened links. */
    interface UriEvents {
        boolean supported();

        void handle(Consumer<URI> handler);
    }

    /** The links of this process. */
    static final MacLinks SYSTEM = new MacLinks();

    // As many links as a later start may hand over.
    static final int MAX_WAITING = 256;

    private static final Logger logger = LoggerFactory.getLogger(MacLinks.class);

    private final List<String> waiting = new ArrayList<>();
    private Consumer<List<String>> receiver;

    /** Takes the links macOS opens in this app; elsewhere does nothing. */
    static void install() {
        SYSTEM.install(System.getProperty("os.name", ""), new AwtUriEvents());
    }

    /** As above, on the system {@code osName} names, whose link events {@code events} delivers. */
    boolean install(String osName, UriEvents events) {
        if (!osName.toLowerCase(Locale.ROOT).startsWith("mac")) {
            return false;
        }
        try {
            if (!events.supported()) {
                return false;
            }
            events.handle(uri -> offer(uri.toString()));
            return true;
        } catch (RuntimeException | LinkageError e) {
            logger.warn("Links opened in the browser cannot reach njMule: {}", e.toString());
            return false;
        }
    }

    /** A link the system opened: the eD2K ones go to the window, or wait for it. */
    synchronized void offer(String link) {
        // Only what a later start may hand over: links, never its exit request.
        if (Ed2kLink.tryParse(link).isEmpty()) {
            logger.debug("Not an ed2k link: {}", link);
            return;
        }
        if (this.receiver != null) {
            this.receiver.accept(List.of(link));
        } else if (this.waiting.size() < MAX_WAITING) {
            this.waiting.add(link);
        }
    }

    /** Hands the links that came and those that come to {@code links}, until {@link #detach()}. */
    synchronized void attach(Consumer<List<String>> links) {
        this.receiver = links;
        if (!this.waiting.isEmpty()) {
            List<String> came = List.copyOf(this.waiting);
            this.waiting.clear();
            links.accept(came);
        }
    }

    synchronized void detach() {
        this.receiver = null;
    }

    /** AWT's link events, touched on macOS only. */
    private static final class AwtUriEvents implements UriEvents {
        @Override
        public boolean supported() {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI);
        }

        @Override
        public void handle(Consumer<URI> handler) {
            Desktop.getDesktop().setOpenURIHandler(event -> handler.accept(event.getURI()));
        }
    }
}
