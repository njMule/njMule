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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;
import org.njmule.core.api.Ed2kLink;

/**
 * Takes the eD2K file links copied anywhere, while {@code wanted} says so: the clipboard is read once a second, and
 * only a text that changed since and starts with a file link counts. What was copied before the watch began is left.
 */
public final class ClipboardWatch implements AutoCloseable {

    private static final String FILE_LINK = "ed2k://|file|";
    // Links copied together, apart by spaces or lines; a name may hold spaces of its own.
    private static final Pattern LINKS = Pattern.compile("\\s+(?=ed2k://)", Pattern.CASE_INSENSITIVE);

    // What njMule copied itself, which is not taken back.
    private static final AtomicReference<String> OURS = new AtomicReference<>("");

    private final Timeline timeline;
    private String last;

    private ClipboardWatch(BooleanSupplier wanted, Consumer<List<Ed2kLink.FileLink>> found) {
        Objects.requireNonNull(wanted, "wanted");
        Objects.requireNonNull(found, "found");
        this.last = text();
        this.timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            String now = text();
            if (now.equals(this.last)) {
                return;
            }
            this.last = now;
            List<Ed2kLink.FileLink> links = fileLinks(now);
            if (wanted.getAsBoolean() && !links.isEmpty() && !now.equals(OURS.get())) {
                found.accept(links);
            }
        }));
        this.timeline.setCycleCount(Animation.INDEFINITE);
    }

    /** Starts watching; FX thread. */
    public static ClipboardWatch start(BooleanSupplier wanted, Consumer<List<Ed2kLink.FileLink>> found) {
        ClipboardWatch watch = new ClipboardWatch(wanted, found);
        watch.timeline.play();
        return watch;
    }

    /** Puts {@code text} on the clipboard, where the watch leaves it: njMule's own links are not downloaded. */
    public static void copy(String text) {
        OURS.set(text);
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
