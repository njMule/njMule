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
package org.njmule.app;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.http.WebUi;
import org.njmule.http.WebUiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The web interface as its settings ask: started, stopped or started again as they change while njMule runs. */
final class LiveWebUi implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LiveWebUi.class);

    private final NjMuleEngine engine;
    private final PrintStream out;
    private final Runnable exit;
    private Optional<WebUi> running = Optional.empty();
    private Optional<WebUiSettings> followed = Optional.empty();
    private boolean closed;

    /** {@code exit} stops njMule when the interface's API asks. */
    LiveWebUi(NjMuleEngine engine, PrintStream out, Runnable exit) {
        this.engine = engine;
        this.out = out;
        this.exit = exit;
    }

    /** Follows {@code settings}; the interface starts again only when they differ from those it runs with. */
    synchronized void apply(WebUiSettings settings) {
        if (this.closed || (this.running.isPresent() && this.followed.equals(Optional.of(settings)))) {
            return;
        }
        stopRunning();
        this.followed = Optional.of(settings);
        if (!settings.usable()) {
            return;
        }
        try {
            WebUi ui = WebUi.start(this.engine, settings, this.exit);
            this.out.println(
                    "Web interface on port " + ui.port() + (settings.api() ? ", with the remote-control API" : ""));
            this.running = Optional.of(ui);
        } catch (IOException e) {
            logger.warn("Cannot start the web interface on port {}: {}", settings.port(), e.toString());
        }
    }

    /** The port the interface answers on, while it runs. */
    synchronized Optional<Integer> port() {
        return this.running.map(WebUi::port);
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        stopRunning();
    }

    private void stopRunning() {
        this.running.ifPresent(WebUi::close);
        this.running = Optional.empty();
    }
}
