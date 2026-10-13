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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * On macOS AppKit, and so JavaFX, hands work to the process's first thread, which must run the main run loop. The java
 * launcher keeps that thread free for it; a native executable starts the application on it, so the application moves to
 * a thread of its own and the first one runs the run loop until the process exits.
 */
final class MacMainThread {

    private static final String CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    private MacMainThread() {}

    /** Whether this is a native executable on macOS, started on its first thread. */
    static boolean needed() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")
                && "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))
                && "main".equals(Thread.currentThread().getName());
    }

    /** Runs {@code app} on another thread and the run loop here; exits when {@code app} ends. */
    @SuppressWarnings("restricted") // the native call is the point; access is granted at build
    static void run(Runnable app) {
        Thread.ofPlatform().name("njmule-app").start(() -> {
            try {
                app.run();
            } finally {
                System.exit(0);
            }
        });
        MethodHandle runLoop = Linker.nativeLinker()
                .downcallHandle(
                        SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global())
                                .findOrThrow("CFRunLoopRun"),
                        FunctionDescriptor.ofVoid());
        try {
            runLoop.invokeExact();
        } catch (Throwable e) {
            throw new IllegalStateException("cannot run the main run loop", e);
        }
    }
}
