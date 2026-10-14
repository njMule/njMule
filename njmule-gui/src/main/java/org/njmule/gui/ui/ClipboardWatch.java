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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;
import org.njmule.core.api.Ed2kLink;

/**
 * Takes the eD2K file links copied anywhere, while {@code wanted} says so: the clipboard is read once a second, and
 * only a text copied since the option is on that starts with a file link counts, once the user confirms it. While the
 * option is off the clipboard is not read at all.
 */
public final class ClipboardWatch implements AutoCloseable {

    private static final String FILE_LINK = "ed2k://|file|";
    // Links copied together, apart by spaces or lines; a name may hold spaces of its own.
    private static final Pattern LINKS = Pattern.compile("\\s+(?=ed2k://)", Pattern.CASE_INSENSITIVE);

    // What njMule copied itself, left while it stays on the clipboard; null once something else was copied.
    private static final AtomicReference<String> OURS = new AtomicReference<>();

    private final BooleanSupplier wanted;
    private final Supplier<String> clipboard;
    private final Predicate<List<Ed2kLink.FileLink>> confirm;
    private final Executor later;
    private final Consumer<List<Ed2kLink.FileLink>> found;
    private final Timeline timeline;
    // The text of the last look; null while the option is off, so that the first look after it only notes it.
    private String last;

    /**
     * A watch reading {@code clipboard}; {@code confirm} runs through {@code later}, out of the timer's pulse where no
     * dialog may wait. Tests drive it with {@link #tick}.
     */
    ClipboardWatch(
            BooleanSupplier wanted,
            Supplier<String> clipboard,
            Predicate<List<Ed2kLink.FileLink>> confirm,
            Executor later,
            Consumer<List<Ed2kLink.FileLink>> found) {
        this.wanted = Objects.requireNonNull(wanted, "wanted");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.confirm = Objects.requireNonNull(confirm, "confirm");
        this.later = Objects.requireNonNull(later, "later");
        this.found = Objects.requireNonNull(found, "found");
        this.timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> this.tick()));
        this.timeline.setCycleCount(Animation.INDEFINITE);
    }

    /** Starts watching; {@code found} gets the links {@code confirm} accepted. FX thread. */
    public static ClipboardWatch start(
            BooleanSupplier wanted,
            Predicate<List<Ed2kLink.FileLink>> confirm,
            Consumer<List<Ed2kLink.FileLink>> found) {
        ClipboardWatch watch = new ClipboardWatch(wanted, ClipboardWatch::text, confirm, Platform::runLater, found);
        watch.timeline.play();
        return watch;
    }

    // One look at the clipboard, once a second.
    void tick() {
        if (!this.wanted.getAsBoolean()) {
            this.last = null;
            return;
        }
        String now = this.clipboard.get();
        String before = this.last;
        this.last = now;
        if (now.equals(before)) {
            return;
        }
        if (now.equals(OURS.get())) {
            return;
        }
        OURS.set(null);
        List<Ed2kLink.FileLink> links = fileLinks(now);
        if (before != null && !links.isEmpty()) {
            this.later.execute(() -> {
                if (this.confirm.test(links)) {
                    this.found.accept(links);
                }
            });
        }
    }

    /** Marks {@code text} as copied by njMule, which the watch leaves while it stays on the clipboard. */
    static void ours(String text) {
        OURS.set(text);
    }

    /** Puts {@code text} on the clipboard, where the watch leaves it: njMule's own links are not downloaded. */
    public static void copy(String text) {
        ours(text);
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** The file links of a copied {@code text} that starts with one; the parts that are not links are left out. */
    static List<Ed2kLink.FileLink> fileLinks(String text) {
        String stripped = text.strip();
        if (!stripped.regionMatches(true, 0, FILE_LINK, 0, FILE_LINK.length())) {
            return List.of();
        }
        List<Ed2kLink.FileLink> links = new ArrayList<>();
        for (String one : LINKS.split(stripped, -1)) {
            if (Ed2kLink.tryParse(one.strip()).orElse(null) instanceof Ed2kLink.FileLink file
                    && !links.contains(file)) {
                links.add(file);
            }
        }
        return links;
    }

    @Override
    public void close() {
        this.timeline.stop();
    }

    private static String text() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        String text = clipboard.hasString() ? clipboard.getString() : null;
        return text == null ? "" : text;
    }
}
