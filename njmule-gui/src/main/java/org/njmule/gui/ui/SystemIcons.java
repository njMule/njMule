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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The icon Windows shows for a kind of file, which the program that opens it gives (a film shows the player's), read
 * once per extension through the shell on a thread of its own. Elsewhere, or when the shell gives none, there is none,
 * and the app's own icon of the kind stays.
 */
public final class SystemIcons {

    private static final Logger logger = LoggerFactory.getLogger(SystemIcons.class);
    // Read at 32 pixels and shown smaller: sharp on screens that scale.
    private static final int SIZE = 32;
    private static final int SHGFI_ICON = 0x100;
    private static final int SHGFI_LARGEICON = 0x0;
    private static final int SHGFI_USEFILEATTRIBUTES = 0x10;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x80;
    // SHFILEINFOW: the icon handle, its index, the attributes, the display name and the type name.
    private static final long FILE_INFO_SIZE = 8 + 4 + 4 + 520 + 160;
    // ICONINFO: fIcon, the hot spot, then the mask and colour bitmaps at 16 and 24.
    private static final long ICON_INFO_SIZE = 32;
    private static final int BITMAP_HEADER_SIZE = 40;
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final Map<String, Optional<Image>> ICONS = new ConcurrentHashMap<>();
    private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();
    // The shell wants its own COM apartment, which a thread of the platform keeps.
    private static final ExecutorService SHELL = WINDOWS
            ? Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("njmule-icons").daemon(true).factory())
            : null;

    private SystemIcons() {}

    /**
     * The icon of files named as {@code name}, at 32 pixels, once it is read; until then nothing, and {@code ready}
     * runs on the FX thread when it is.
     */
    public static Optional<Image> of(String name, Runnable ready) {
        String extension = extension(name);
        if (!WINDOWS || extension.isEmpty()) {
            return Optional.empty();
        }
        Optional<Image> known = ICONS.get(extension);
        if (known != null) {
            return known;
        }
        if (ASKED.add(extension)) {
            SHELL.execute(() -> {
                ICONS.put(extension, read(extension));
                Platform.runLater(ready);
            });
        }
        return Optional.empty();
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
        return extension.length() > 16 || !extension.chars().allMatch(Character::isLetterOrDigit)
                ? ""
                : extension.toLowerCase(Locale.ROOT);
    }

    // SHGetFileInfo with the extension only, as for a file that does not exist yet; the icon's bitmaps are copied out
    // and freed. Older icons without an alpha channel take their transparency from their mask.
    @SuppressWarnings("restricted") // the native calls are the point; access is granted at build
    private static Optional<Image> read(String extension) {
        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32", arena);
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", arena);
            SymbolLookup ole32 = SymbolLookup.libraryLookup("ole32", arena);
            MethodHandle coInitialize = linker.downcallHandle(
                    ole32.findOrThrow("CoInitializeEx"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle fileInfo = linker.downcallHandle(
                    shell32.findOrThrow("SHGetFileInfoW"),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            MethodHandle iconInfo = linker.downcallHandle(
                    user32.findOrThrow("GetIconInfo"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle destroyIcon = linker.downcallHandle(
                    user32.findOrThrow("DestroyIcon"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MethodHandle getDc = linker.downcallHandle(
                    user32.findOrThrow("GetDC"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle releaseDc = linker.downcallHandle(
                    user32.findOrThrow("ReleaseDC"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle bits = linker.downcallHandle(
                    gdi32.findOrThrow("GetDIBits"),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));
            MethodHandle deleteObject = linker.downcallHandle(
                    gdi32.findOrThrow("DeleteObject"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            // Once per thread; a second call only says it was done.
            checked("CoInitializeEx", (int) coInitialize.invokeExact(MemorySegment.NULL, 0x2) >= 0);
            MemorySegment info = arena.allocate(FILE_INFO_SIZE, 8);
            long found = (long) fileInfo.invokeExact(
                    arena.allocateFrom("file." + extension, StandardCharsets.UTF_16LE),
                    FILE_ATTRIBUTE_NORMAL,
                    info,
                    (int) FILE_INFO_SIZE,
                    SHGFI_ICON | SHGFI_LARGEICON | SHGFI_USEFILEATTRIBUTES);
            MemorySegment icon = info.get(ValueLayout.ADDRESS, 0);
            if (found == 0 || icon.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            try {
                MemorySegment parts = arena.allocate(ICON_INFO_SIZE, 8);
                if ((int) iconInfo.invokeExact(icon, parts) == 0) {
                    return Optional.empty();
                }
                MemorySegment mask = parts.get(ValueLayout.ADDRESS, 16);
                MemorySegment color = parts.get(ValueLayout.ADDRESS, 24);
                try {
                    MemorySegment dc = (MemorySegment) getDc.invokeExact(MemorySegment.NULL);
                    try {
                        byte[] pixels = pixels(arena, bits, dc, color);
                        if (pixels == null) {
                            return Optional.empty();
                        }
                        if (noAlpha(pixels)) {
                            byte[] holes = pixels(arena, bits, dc, mask);
                            for (int i = 3; i < pixels.length; i += 4) {
                                // Black in the mask is drawn, white lets the background through.
                                pixels[i] = holes != null && (holes[i - 1] & 0xFF) != 0 ? 0 : (byte) 0xFF;
                            }
                        }
                        WritableImage image = new WritableImage(SIZE, SIZE);
                        image.getPixelWriter()
                                .setPixels(0, 0, SIZE, SIZE, PixelFormat.getByteBgraInstance(), pixels, 0, SIZE * 4);
                        return Optional.of(image);
                    } finally {
                        checked("ReleaseDC", (int) releaseDc.invokeExact(MemorySegment.NULL, dc) != 0);
                    }
                } finally {
                    checked("DeleteObject", (int) deleteObject.invokeExact(mask) != 0);
                    checked("DeleteObject", (int) deleteObject.invokeExact(color) != 0);
                }
            } finally {
                checked("DestroyIcon", (int) destroyIcon.invokeExact(icon) != 0);
            }
        } catch (Throwable e) {
            logger.debug("No system icon for .{}: {}", extension, e.toString());
            return Optional.empty();
        }
    }

    // A call that frees or prepares, whose failure leaves nothing to undo but is worth a line when looking into it.
    private static void checked(String call, boolean done) {
        if (!done) {
            logger.debug("{} failed while reading a system icon", call);
        }
    }

    // The bitmap as 32-bit BGRA rows from the top, or null when there is none to read.
    private static byte[] pixels(Arena arena, MethodHandle bits, MemorySegment dc, MemorySegment bitmap)
            throws Throwable {
        if (bitmap.equals(MemorySegment.NULL)) {
            return null;
        }
        MemorySegment header = arena.allocate(BITMAP_HEADER_SIZE + 1024L, 4);
        header.set(ValueLayout.JAVA_INT, 0, BITMAP_HEADER_SIZE);
        header.set(ValueLayout.JAVA_INT, 4, SIZE);
        header.set(ValueLayout.JAVA_INT, 8, -SIZE);
        header.set(ValueLayout.JAVA_SHORT, 12, (short) 1);
        header.set(ValueLayout.JAVA_SHORT, 14, (short) 32);
        MemorySegment out = arena.allocate((long) SIZE * SIZE * 4, 4);
        int lines = (int) bits.invokeExact(dc, bitmap, 0, SIZE, out, header, 0);
        return lines == SIZE ? out.toArray(ValueLayout.JAVA_BYTE) : null;
    }

    private static boolean noAlpha(byte[] pixels) {
        for (int i = 3; i < pixels.length; i += 4) {
            if (pixels[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
