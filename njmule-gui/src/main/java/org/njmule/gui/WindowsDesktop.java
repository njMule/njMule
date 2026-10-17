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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.njmule.app.WindowsCommandLine;

/**
 * On Windows the native app is njmule.exe, a console program, as the command line must be one. njmule-gui.exe, a small
 * GUI program beside it, starts it without a console for the desktop, marked by {@value #DESKTOP_VARIABLE}. Its windows
 * carry the app's id and njmule-gui.exe as the command that starts them again, so that the taskbar pins and relaunches
 * the desktop app rather than the console program.
 */
final class WindowsDesktop {

    /** The id the installer's shortcuts carry too: the taskbar groups the windows with them. */
    static final String APP_ID = "org.njmule.njMule";

    static final String DESKTOP_VARIABLE = "NJMULE_DESKTOP";

    static final String LAUNCHER = "njmule-gui.exe";

    // System.AppUserModel properties, all in one format.
    private static final String APP_USER_MODEL = "9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3";
    private static final int RELAUNCH_COMMAND = 2;
    private static final int RELAUNCH_ICON = 3;
    private static final int RELAUNCH_NAME = 4;
    private static final int ID = 5;
    private static final String IID_PROPERTY_STORE = "886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99";
    private static final short VT_LPWSTR = 31;
    private static final long PROPVARIANT_SIZE = 24;
    // IPropertyStore after IUnknown's three: GetCount, GetAt, GetValue, SetValue, Commit.
    private static final int RELEASE = 2;
    private static final int SET_VALUE = 6;
    private static final int COMMIT = 7;
    private static final int GW_HWNDNEXT = 2;
    private static final int GW_OWNER = 4;
    private static final int MAX_WINDOWS = 100_000;

    private WindowsDesktop() {}

    /** Whether this is the native executable on Windows. */
    static boolean applies() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                && "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    /** Whether njmule-gui.exe started this process for the desktop; otherwise it is the command line. */
    static boolean startedForDesktop(Map<String, String> environment) {
        return "1".equals(environment.get(DESKTOP_VARIABLE));
    }

    /** Gives the process the app's id before it opens a window; {@code false} when the system refuses. */
    @SuppressWarnings("restricted") // the native call is the point; access is granted at build
    static boolean useAppId() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            MethodHandle setId = linker.downcallHandle(
                    SymbolLookup.libraryLookup("shell32", arena).findOrThrow("SetCurrentProcessExplicitAppUserModelID"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            return (int) setId.invokeExact(wide(arena, APP_ID)) == 0;
        } catch (Throwable e) {
            // Only how the taskbar groups the windows depends on it.
            return false;
        }
    }

    /**
     * Makes the shown top-level windows of this process relaunch through njmule-gui.exe with {@code arguments};
     * {@code false} when the launcher is not beside the executable or the system refuses.
     */
    static boolean relaunchThroughLauncher(String name, List<String> arguments) {
        Optional<Path> launcher = ProcessHandle.current()
                .info()
                .command()
                .map(command -> Path.of(command).resolveSibling(LAUNCHER))
                .filter(Files::isRegularFile);
        if (launcher.isEmpty()) {
            return false;
        }
        List<String> command = new ArrayList<>();
        command.add(launcher.get().toString());
        command.addAll(arguments);
        return describeWindows(launcher.get().toString(), WindowsCommandLine.of(command), name);
    }

    @SuppressWarnings("restricted") // the native calls are the point; access is granted at build
    private static boolean describeWindows(String launcher, String command, String name) {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            MethodHandle topWindow = linker.downcallHandle(
                    user32.findOrThrow("GetTopWindow"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle nextWindow = linker.downcallHandle(
                    user32.findOrThrow("GetWindow"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle processOf = linker.downcallHandle(
                    user32.findOrThrow("GetWindowThreadProcessId"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle visible = linker.downcallHandle(
                    user32.findOrThrow("IsWindowVisible"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MethodHandle storeOf = linker.downcallHandle(
                    SymbolLookup.libraryLookup("shell32", arena).findOrThrow("SHGetPropertyStoreForWindow"),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            long self = ProcessHandle.current().pid();
            MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
            boolean described = false;
            MemorySegment window = (MemorySegment) topWindow.invokeExact(MemorySegment.NULL);
            for (int i = 0; i < MAX_WINDOWS && window.address() != 0; i++) {
                int unusedThread = (int) processOf.invokeExact(window, pid);
                MemorySegment owner = (MemorySegment) nextWindow.invokeExact(window, GW_OWNER);
                if (Integer.toUnsignedLong(pid.get(ValueLayout.JAVA_INT, 0)) == self
                        && (int) visible.invokeExact(window) != 0
                        && owner.address() == 0) {
                    described |= describe(arena, linker, storeOf, window, launcher, command, name);
                }
                window = (MemorySegment) nextWindow.invokeExact(window, GW_HWNDNEXT);
            }
            return described;
        } catch (Throwable e) {
            // Only what a pinned window starts depends on it.
            return false;
        }
    }

    @SuppressWarnings("restricted") // the native calls are the point; access is granted at build
    private static boolean describe(
            Arena arena,
            Linker linker,
            MethodHandle storeOf,
            MemorySegment window,
            String launcher,
            String command,
            String name)
            throws Throwable {
        MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
        if ((int) storeOf.invokeExact(window, guid(arena, IID_PROPERTY_STORE), out) != 0) {
            return false;
        }
        MemorySegment store = out.get(ValueLayout.ADDRESS, 0);
        MemorySegment methods = store.reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0)
                .reinterpret(ValueLayout.ADDRESS.byteSize() * (COMMIT + 1));
        MethodHandle setValue = linker.downcallHandle(
                methods.getAtIndex(ValueLayout.ADDRESS, SET_VALUE),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle oneArgument =
                linker.downcallHandle(FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        try {
            // The relaunch properties count only on a window with an id of its own.
            boolean set = (int) setValue.invokeExact(store, key(arena, ID), text(arena, APP_ID)) == 0
                    && (int) setValue.invokeExact(store, key(arena, RELAUNCH_COMMAND), text(arena, command)) == 0
                    && (int) setValue.invokeExact(store, key(arena, RELAUNCH_NAME), text(arena, name)) == 0
                    && (int) setValue.invokeExact(store, key(arena, RELAUNCH_ICON), text(arena, launcher + ",0")) == 0;
            return set && (int) oneArgument.invokeExact(methods.getAtIndex(ValueLayout.ADDRESS, COMMIT), store) == 0;
        } finally {
            int unusedCount = (int) oneArgument.invokeExact(methods.getAtIndex(ValueLayout.ADDRESS, RELEASE), store);
        }
    }

    /** A GUID in its memory layout: three little-endian numbers, then eight bytes in order. */
    static byte[] guidBytes(String guid) {
        byte[] text = HexFormat.of().parseHex(guid.replace("-", ""));
        byte[] bytes = new byte[16];
        for (int i = 0; i < 4; i++) {
            bytes[i] = text[3 - i];
        }
        bytes[4] = text[5];
        bytes[5] = text[4];
        bytes[6] = text[7];
        bytes[7] = text[6];
        System.arraycopy(text, 8, bytes, 8, 8);
        return bytes;
    }

    private static MemorySegment guid(Arena arena, String guid) {
        return arena.allocateFrom(ValueLayout.JAVA_BYTE, guidBytes(guid));
    }

    // PROPERTYKEY: the format's GUID, then the property's number.
    private static MemorySegment key(Arena arena, int property) {
        MemorySegment key = arena.allocate(20, 4);
        key.copyFrom(guid(arena, APP_USER_MODEL));
        key.set(ValueLayout.JAVA_INT_UNALIGNED, 16, property);
        return key;
    }

    // A PROPVARIANT holding a wide string.
    private static MemorySegment text(Arena arena, String value) {
        MemorySegment variant = arena.allocate(PROPVARIANT_SIZE, 8);
        variant.set(ValueLayout.JAVA_SHORT, 0, VT_LPWSTR);
        variant.set(ValueLayout.ADDRESS, 8, wide(arena, value));
        return variant;
    }

    private static MemorySegment wide(Arena arena, String value) {
        return arena.allocateFrom(value, StandardCharsets.UTF_16LE);
    }
}
