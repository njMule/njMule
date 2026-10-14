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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Ed2kLink;
import org.njmule.gui.FxTesting;

class ClipboardWatchTest {

    private static final String FILM = "ed2k://|file|my film.mkv|700000000|0123456789ABCDEF0123456789ABCDEF|/";
    private static final String SONG = "ed2k://|file|song.mp3|4000000|00112233445566778899AABBCCDDEEFF|/";

    @Test
    void takesTheFileLinksOfATextThatStartsWithOne() {
        assertThat(ClipboardWatch.fileLinks("  " + FILM + "\r\n" + SONG + " " + FILM + "\n"))
                .extracting(Ed2kLink.FileLink::name)
                .containsExactly("my film.mkv", "song.mp3");
    }

    @Test
    void theClipboardIsNotReadWhileTheOptionIsOff() throws Exception {
        AtomicBoolean on = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> clipboard = new AtomicReference<>("a password");
        List<List<Ed2kLink.FileLink>> found = new CopyOnWriteArrayList<>();
        ClipboardWatch watch = this.watch(
                on::get,
                () -> {
                    reads.incrementAndGet();
                    return clipboard.get();
                },
                found);

        clipboard.set(FILM);
        FxTesting.onFx(watch::tick);
        assertThat(reads).as("off, the default").hasValue(0);
        assertThat(found).isEmpty();

        on.set(true);
        FxTesting.onFx(watch::tick);
        assertThat(found).as("copied before the option was on").isEmpty();
        clipboard.set(SONG);
        FxTesting.onFx(watch::tick);
        assertThat(found)
                .singleElement()
                .satisfies(links ->
                        assertThat(links).extracting(Ed2kLink.FileLink::name).containsExactly("song.mp3"));
    }

    @Test
    void ourOwnTextIsLeftOnlyUntilTheClipboardChanges() throws Exception {
        AtomicReference<String> clipboard = new AtomicReference<>("");
        List<List<Ed2kLink.FileLink>> found = new CopyOnWriteArrayList<>();
        ClipboardWatch watch = this.watch(() -> true, clipboard::get, found);
        FxTesting.onFx(watch::tick);

        ClipboardWatch.ours(FILM);
        clipboard.set(FILM);
        FxTesting.onFx(watch::tick);
        assertThat(found).as("copied by njMule").isEmpty();
        clipboard.set("something else");
        FxTesting.onFx(watch::tick);
        clipboard.set(FILM);
        FxTesting.onFx(watch::tick);
        assertThat(found).as("the same link copied again in a browser").hasSize(1);
    }

    @Test
    void linksAreTakenOnlyWhenTheUserSaysSo() throws Exception {
        AtomicReference<String> clipboard = new AtomicReference<>("");
        List<List<Ed2kLink.FileLink>> asked = new CopyOnWriteArrayList<>();
        List<List<Ed2kLink.FileLink>> found = new CopyOnWriteArrayList<>();
        FxTesting.start();
        ClipboardWatch watch = FxTesting.onFx(() -> new ClipboardWatch(
                () -> true,
                clipboard::get,
                links -> {
                    asked.add(links);
                    return false;
                },
                Runnable::run,
                found::add));
        FxTesting.onFx(watch::tick);

        clipboard.set(FILM);
        FxTesting.onFx(watch::tick);

        assertThat(asked).hasSize(1);
        assertThat(found).as("the user said no").isEmpty();
    }

    private ClipboardWatch watch(
            BooleanSupplier wanted, Supplier<String> clipboard, List<List<Ed2kLink.FileLink>> found) throws Exception {
        FxTesting.start();
        // Never started: the tests tick it, and never touch the system clipboard.
        return FxTesting.onFx(() -> new ClipboardWatch(wanted, clipboard, links -> true, Runnable::run, found::add));
    }

    @Test
    void leavesOtherTexts() {
        assertThat(ClipboardWatch.fileLinks("see " + FILM))
                .as("not at the start")
                .isEmpty();
        assertThat(ClipboardWatch.fileLinks("ed2k://|server|10.0.0.1|4661|/")).isEmpty();
        assertThat(ClipboardWatch.fileLinks(FILM + " ed2k://|file|broken|/"))
                .extracting(Ed2kLink.FileLink::name)
                .containsExactly("my film.mkv");
    }
}
