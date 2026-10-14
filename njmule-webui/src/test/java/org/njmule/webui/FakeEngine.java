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

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Function;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;

/** An engine that answers what a test sets, by method name; any other command fails. */
final class FakeEngine {

    final SubmissionPublisher<EngineEvent> events = new SubmissionPublisher<>();
    private final Map<String, Function<Object[], CompletableFuture<?>>> answers = new ConcurrentHashMap<>();

    void answer(String method, Function<Object[], CompletableFuture<?>> answer) {
        this.answers.put(method, answer);
    }

    NjMuleEngine engine() {
        return (NjMuleEngine) Proxy.newProxyInstance(
                NjMuleEngine.class.getClassLoader(), new Class<?>[] {NjMuleEngine.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    return switch (name) {
                        case "events" -> this.events;
                        case "close" -> {
                            this.events.close();
                            yield null;
                        }
                        case "toString" -> "FakeEngine";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> {
                            Function<Object[], CompletableFuture<?>> answer = this.answers.get(name);
                            yield answer != null
                                    ? answer.apply(args)
                                    : CompletableFuture.failedFuture(new IllegalStateException(name + " failed"));
                        }
                    };
                });
    }
}
