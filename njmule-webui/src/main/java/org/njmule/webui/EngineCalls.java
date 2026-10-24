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
package org.njmule.webui;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.njmule.core.api.NjMuleEngine;

/** The engine as the pages ask it: each answer awaited for a while, a failure told in words for the user. */
final class EngineCalls {

    private static final long WAIT_SECONDS = 10;

    private final NjMuleEngine engine;

    EngineCalls(NjMuleEngine engine) {
        this.engine = engine;
    }

    NjMuleEngine engine() {
        return this.engine;
    }

    <T> T await(CompletableFuture<T> work) {
        try {
            return work.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("Interrupted.");
        } catch (ExecutionException e) {
            throw new Failure(String.valueOf(e.getCause().getMessage()));
        } catch (TimeoutException e) {
            throw new Failure("The engine did not answer in time.");
        }
    }

    /** What the engine answers, or nothing when it cannot: for what a page shows only besides its subject. */
    <T> Optional<T> maybe(CompletableFuture<T> work) {
        try {
            return Optional.ofNullable(await(work));
        } catch (Failure e) {
            return Optional.empty();
        }
    }

    /** The engine could not do what a request asked; the message is meant for the user. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }
}
