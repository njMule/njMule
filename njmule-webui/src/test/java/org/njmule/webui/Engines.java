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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;

/** A real engine with some commands replaced, for failures and delays the real one cannot be made to show. */
public final class Engines {

    /** A replaced command, answering with a future like every command of the engine. */
    @FunctionalInterface
    public interface Command {
        CompletableFuture<?> answer(Object[] args);
    }

    private Engines() {}

    /** {@code real}, telling the {@code events} given instead of its own. */
    public static NjMuleEngine withEvents(NjMuleEngine real, Flow.Publisher<EngineEvent> events) {
        return (NjMuleEngine) Proxy.newProxyInstance(
                NjMuleEngine.class.getClassLoader(), new Class<?>[] {NjMuleEngine.class}, (proxy, method, args) -> {
                    if (method.getName().equals("events")) {
                        return events;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** {@code real}, as a window on a njMule of another computer sees it. */
    public static NjMuleEngine elsewhere(NjMuleEngine real) {
        return (NjMuleEngine) Proxy.newProxyInstance(
                NjMuleEngine.class.getClassLoader(), new Class<?>[] {NjMuleEngine.class}, (proxy, method, args) -> {
                    if (method.getName().equals("onThisComputer")) {
                        return false;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** {@code real}, except that the commands named in {@code commands} answer as those do. */
    @SuppressWarnings("FutureReturnValueIgnored") // a proxy returns each answer as Object; the caller checks it
    public static NjMuleEngine overriding(NjMuleEngine real, Map<String, Command> commands) {
        return (NjMuleEngine) Proxy.newProxyInstance(
                NjMuleEngine.class.getClassLoader(), new Class<?>[] {NjMuleEngine.class}, (proxy, method, args) -> {
                    Command replaced = commands.get(method.getName());
                    if (replaced != null) {
                        return replaced.answer(args == null ? new Object[0] : args);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
