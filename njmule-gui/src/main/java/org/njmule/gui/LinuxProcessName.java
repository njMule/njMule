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
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * On Linux the lists of processes show the name of a process's first thread, which is the name of the file run until it
 * changes: the native desktop app takes the application's name, as its desktop entry calls it, under whatever name it
 * was started. The java launcher runs the application on another thread, so a JVM keeps its own name.
 */
final class LinuxProcessName {

    /** The system keeps 15 bytes of a name. */
    static final String NAME = "njMule";

    private LinuxProcessName() {}

    /** Whether this is a native executable on Linux, started on its first thread. */
    static boolean applies() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")
                && "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))
                && "main".equals(Thread.currentThread().getName());
    }

    /** Gives the calling thread the application's name; {@code false} when the system has no way or refuses. */
    @SuppressWarnings("restricted") // the native call is the point; access is granted at build
    static boolean nameThisThread() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup system = linker.defaultLookup();
            MethodHandle self = linker.downcallHandle(
                    system.findOrThrow("pthread_self"), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            MethodHandle setName = linker.downcallHandle(
                    system.findOrThrow("pthread_setname_np"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            return (int) setName.invokeExact((long) self.invokeExact(), arena.allocateFrom(NAME)) == 0;
        } catch (Throwable e) {
            // Only how the process is listed depends on it.
            return false;
        }
    }
}
