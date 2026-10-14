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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import javafx.scene.control.TextField;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.EventCommands;

/** The kinds of values the interface asks for, each with its characters, its check and its hint. */
public final class Inputs {

    // Links pasted together, apart by spaces or lines; a name may hold spaces of its own.
    private static final Pattern LINKS = Pattern.compile("\\s+(?=ed2k://)", Pattern.CASE_INSENSITIVE);
    // One word: a space would end the attribute and make a keyword of the rest.
    private static final Pattern EXTENSION = Pattern.compile("\\.?[\\p{L}\\p{Nd}]+");

    private static final int MAX_HOST = 253;
    private static final int MAX_TEXT = 4096;
    // As many links as a later start hands over (256), each as long as a line it may send (64 Ki characters).
    static final int MAX_LINKS_TEXT = 256 * 64 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

    private final Gui gui;

    public Inputs(Gui gui) {
        this.gui = gui;
    }

    /** A whole number in {@code [min, max]}. */
    public Input<Integer> number(TextField field, String name, int min, int max) {
        return this.number(field, name, min, () -> max, max);
    }

    /**
     * A whole number in {@code [min, max]}, where {@code max} moves up to {@code largest}; check again after it did.
     */
    public Input<Integer> number(TextField field, String name, int min, IntSupplier max, int largest) {
        return Input.of(
                field,
                name,
                this.gui.f("nj.input.number", min, max.getAsInt()),
                Inputs::digit,
                digits(largest),
                t -> inRange(t, min, max.getAsInt()));
    }

    /** Nothing, or a whole number in {@code [min, max]}. */
    public Input<OptionalInt> optionalNumber(TextField field, String name, int min, int max) {
        return this.optionalNumber(field, name, () -> min, max);
    }

    /** Nothing, or a whole number in {@code [min, max]}, where {@code min} moves; check again after it did. */
    public Input<OptionalInt> optionalNumber(TextField field, String name, IntSupplier min, int max) {
        return Input.of(
                field,
                name,
                this.gui.f("nj.input.optionalNumber", min.getAsInt(), max),
                Inputs::digit,
                digits(max),
                t -> t.isEmpty() ? OptionalInt.empty() : OptionalInt.of(inRange(t, min.getAsInt(), max)));
    }

    /** Nothing, or a file extension of letters and digits, which may start with a dot. */
    public Input<String> extension(TextField field, String name, int maxLength) {
        return Input.of(field, name, this.gui.t("nj.input.extension"), Inputs::extensionChar, maxLength, t -> {
            if (!t.isEmpty() && !EXTENSION.matcher(t).matches()) {
                throw new IllegalArgumentException(t);
            }
            return t;
        });
    }

    /** A time of the day, {@code hh:mm}. */
    public Input<LocalTime> time(TextField field, String name) {
        return Input.of(field, name, this.gui.t("nj.input.time"), Inputs::timeChar, 5, t -> {
            try {
                return LocalTime.parse(t, TIME);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException(t, e);
            }
        });
    }

    /** {@code host:port}. */
    public Input<ServerAddress> address(TextField field, String name) {
        return Input.of(field, name, this.gui.t("nj.input.address"), Inputs::addressChar, MAX_HOST + 8, t -> {
            ServerAddress address = ServerAddress.parse(t);
            if (!ServerAddress.isValidHost(address.host())) {
                throw new IllegalArgumentException(t);
            }
            return address;
        });
    }

    /** An http or https address with a host. */
    public Input<URI> url(TextField field, String name) {
        return Input.of(field, name, this.gui.t("nj.input.url"), Inputs::printable, MAX_TEXT, t -> {
            URI uri = URI.create(t);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new IllegalArgumentException(t);
            }
            return uri;
        });
    }

    /** A host name or an IP address; empty only while {@code required} says no. */
    public Input<String> host(TextField field, String name, BooleanSupplier required) {
        return Input.of(field, name, this.gui.t("nj.input.host"), Inputs::addressChar, MAX_HOST, t -> {
            if (t.isEmpty() ? required.getAsBoolean() : !ServerAddress.isValidHost(t)) {
                throw new IllegalArgumentException(t);
            }
            return t;
        });
    }

    /** Printable text of at most {@code maxLength} characters, which must not be empty when {@code required}. */
    public Input<String> text(TextField field, String name, int maxLength, boolean required) {
        String hint =
                required ? this.gui.f("nj.input.name", maxLength) : this.gui.f("nj.input.optionalName", maxLength);
        return Input.of(field, name, hint, Inputs::printable, maxLength, t -> {
            if (required && t.isEmpty()) {
                throw new IllegalArgumentException(name);
            }
            return t;
        });
    }

    /** A user name of 1 to {@code maxLength} printable characters, without text direction controls. */
    public Input<String> nick(TextField field, String name, int maxLength) {
        return Input.of(field, name, this.gui.f("nj.input.name", maxLength), Inputs::nickChar, maxLength, t -> {
            if (t.isEmpty()) {
                throw new IllegalArgumentException(name);
            }
            return t;
        });
    }

    /** Nothing, for the default folder, or the path of a folder, which need not exist yet. */
    public Input<Optional<Path>> folder(TextField field, String name) {
        return Input.of(field, name, this.gui.t("nj.input.folder"), Inputs::printable, MAX_TEXT, t -> {
            if (t.isEmpty()) {
                return Optional.empty();
            }
            Path path = Path.of(t).toAbsolutePath();
            if (Files.exists(path) && !Files.isDirectory(path)) {
                throw new IllegalArgumentException(t);
            }
            return Optional.of(path);
        });
    }

    /** The path of a folder that exists. */
    public Input<Path> existingFolder(TextField field, String name) {
        return Input.of(field, name, this.gui.t("nj.input.existingFolder"), Inputs::printable, MAX_TEXT, t -> {
            Path path = Path.of(t);
            if (t.isEmpty() || !Files.isDirectory(path)) {
                throw new IllegalArgumentException(t);
            }
            return path;
        });
    }

    /** One or more eD2K file links, apart by spaces or lines. */
    public Input<List<Ed2kLink.FileLink>> fileLinks(TextField field, String name) {
        return Input.of(field, name, this.gui.t("nj.input.fileLinks"), c -> true, MAX_LINKS_TEXT, t -> {
                    List<Ed2kLink.FileLink> links = new ArrayList<>();
                    for (String one : LINKS.split(t, -1)) {
                        if (one.isEmpty()) {
                            continue;
                        }
                        if (Ed2kLink.tryParse(one).orElse(null) instanceof Ed2kLink.FileLink file) {
                            links.add(file);
                        } else {
                            throw new IllegalArgumentException(one);
                        }
                    }
                    if (links.isEmpty()) {
                        throw new IllegalArgumentException(t);
                    }
                    return links;
                })
                .whenTooLong(() -> this.gui.inform().accept(this.gui.f("nj.input.tooLong", MAX_LINKS_TEXT)));
    }

    /** Nothing, or a program and its arguments, which may hold {@code placeholders}; see EventCommands. */
    public Input<String> commandLine(TextField field, String name, List<String> placeholders) {
        String hint = this.gui.f("nj.input.commandLine", String.join(" ", placeholders));
        return Input.of(field, name, hint, Inputs::printable, EventCommands.MAX_COMMAND_LINE, t -> {
            EventCommands.arguments(t);
            return t;
        });
    }

    /** A search expression in the eMule syntax. */
    public Input<String> searchExpression(TextField field, String name) {
        return Input.of(
                field,
                name,
                this.gui.t("nj.input.search"),
                Inputs::printable,
                SearchRequest.MAX_EXPRESSION_LENGTH,
                t -> {
                    if (t.isEmpty()) {
                        throw new IllegalArgumentException(name);
                    }
                    SearchRequest.checkExpression(t);
                    return t;
                });
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean extensionChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.';
    }

    private static boolean timeChar(char c) {
        return digit(c) || c == ':';
    }

    // Host names, IPv4 and IPv6 addresses, with a port.
    private static boolean addressChar(char c) {
        return (c < 128 && Character.isLetterOrDigit(c)) || c == '.' || c == '-' || c == ':' || c == '[' || c == ']';
    }

    private static boolean printable(char c) {
        return !Character.isISOControl(c);
    }

    // Embeddings, overrides and isolates would make a name read otherwise than it is: the engine refuses them.
    private static boolean nickChar(char c) {
        return printable(c) && !(c >= 0x202A && c <= 0x202E) && !(c >= 0x2066 && c <= 0x2069);
    }

    // Integer.parseInt fails with a NumberFormatException, itself an IllegalArgumentException.
    private static int inRange(String text, int min, int max) {
        int value = Integer.parseInt(text);
        if (value < min || value > max) {
            throw new IllegalArgumentException(text);
        }
        return value;
    }

    private static int digits(int max) {
        return String.valueOf(max).length();
    }
}
