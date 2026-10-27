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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Ed2kLink;
import org.njmule.gui.FxTesting;

class ClipboardWatchTest {

    private static final String FILM = "ed2k://|file|my film.mkv|700000000|0123456789ABCDEF0123456789ABCDEF|/";
    private static final String SONG = "ed2k://|file|song.mp3|4000000|00112233445566778899AABBCCDDEEFF|/";

    private final AtomicBoolean on = new AtomicBoolean();
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicReference<String> clipboard = new AtomicReference<>("");
    private final List<List<Ed2kLink.FileLink>> asked = new CopyOnWriteArrayList<>();
    private final List<List<Ed2kLink.FileLink>> found = new CopyOnWriteArrayList<>();
    private ClipboardWatch watch;
    private List<Ed2kLink.FileLink> links;

    // What njMule copied is kept in a static: none may be left for the next test.
    @AfterEach
    void forgetOurOwnText() {
        ClipboardWatch.ours(null);
    }

    @Test
    void shouldTakeTheFileLinksOfATextThatStartsWithOne() {
        whenTheFileLinksAreTakenFrom("  " + FILM + "\r\n" + SONG + " " + FILM + "\n");

        thenTheFileLinksAre("my film.mkv", "song.mp3");
    }

    @Test
    void shouldLeaveATextWhoseLinkIsNotAtTheStart() {
        whenTheFileLinksAreTakenFrom("see " + FILM);

        thenNoFileLinksAreTaken("not at the start");
    }

    @Test
    void shouldFindTheLinksInsideOtherTextForTheDialogThatAddsLinks() {
        whenTheFileLinksAreFoundIn("Here: <a href=\"" + FILM + "\">film</a>, and " + SONG + ", then " + FILM + ".");

        thenTheFileLinksAre("my film.mkv", "song.mp3");
    }

    @Test
    void shouldLeaveAServerLink() {
        whenTheFileLinksAreTakenFrom("ed2k://|server|10.0.0.1|4661|/");

        thenNoFileLinksAreTaken("a server link");
    }

    @Test
    void shouldLeaveABrokenLinkAfterAGoodOne() {
        whenTheFileLinksAreTakenFrom(FILM + " ed2k://|file|broken|/");

        thenTheFileLinksAre("my film.mkv");
    }

    @Test
    void shouldNotReadTheClipboardWhileTheOptionIsOff() throws Exception {
        givenTheClipboardHolds("a password");
        givenAWatchWithTheOptionOff();
        givenTheClipboardHolds(FILM);

        whenTheWatchLooks();

        thenTheClipboardWasReadTimes(0, "off, the default");
        thenNothingWasFound("off, the default");
    }

    @Test
    void shouldLeaveWhatWasCopiedBeforeTheOptionWasOn() throws Exception {
        givenTheClipboardHolds("a password");
        givenAWatchWithTheOptionOff();
        givenTheClipboardHolds(FILM);
        givenTheWatchLooked();
        givenTheOptionTurnedOn();

        whenTheWatchLooks();

        thenNothingWasFound("copied before the option was on");
    }

    @Test
    void shouldTakeALinkCopiedOnceTheOptionIsOn() throws Exception {
        givenTheClipboardHolds("a password");
        givenAWatchWithTheOptionOff();
        givenTheClipboardHolds(FILM);
        givenTheWatchLooked();
        givenTheOptionTurnedOn();
        givenTheWatchLooked();
        givenTheClipboardHolds(SONG);

        whenTheWatchLooks();

        thenOneCopyWasFoundWithTheLinks("song.mp3");
    }

    @Test
    void shouldLeaveTheTextNjMuleCopiedItself() throws Exception {
        givenTheClipboardHolds("");
        givenAWatchWithTheOptionOn();
        givenTheWatchLooked();
        givenNjMuleCopied(FILM);

        whenTheWatchLooks();

        thenNothingWasFound("copied by njMule");
    }

    @Test
    void shouldTakeOurOwnTextAgainOnceTheClipboardChanged() throws Exception {
        givenTheClipboardHolds("");
        givenAWatchWithTheOptionOn();
        givenTheWatchLooked();
        givenNjMuleCopied(FILM);
        givenTheWatchLooked();
        givenTheClipboardHolds("something else");
        givenTheWatchLooked();
        givenTheClipboardHolds(FILM);

        whenTheWatchLooks();

        thenCopiesWereFound(1, "the same link copied again in a browser");
    }

    @Test
    void shouldTakeLinksOnlyWhenTheUserSaysSo() throws Exception {
        givenTheClipboardHolds("");
        givenAWatchWhoseUserSaysNo();
        givenTheWatchLooked();
        givenTheClipboardHolds(FILM);

        whenTheWatchLooks();

        thenTheUserWasAskedTimes(1);
        thenNothingWasFound("the user said no");
    }

    private void givenTheClipboardHolds(String text) {
        this.clipboard.set(text);
    }

    private void givenAWatchWithTheOptionOff() throws Exception {
        this.watch = watch(
                this.on::get,
                () -> {
                    this.reads.incrementAndGet();
                    return this.clipboard.get();
                },
                this.found);
    }

    private void givenAWatchWithTheOptionOn() throws Exception {
        this.watch = watch(() -> true, this.clipboard::get, this.found);
    }

    private void givenAWatchWhoseUserSaysNo() throws Exception {
        FxTesting.start();
        this.watch = FxTesting.onFx(() -> new ClipboardWatch(
                () -> true,
                this.clipboard::get,
                offered -> {
                    this.asked.add(offered);
                    return false;
                },
                Runnable::run,
                this.found::add));
    }

    private void givenTheOptionTurnedOn() {
        this.on.set(true);
    }

    private void givenNjMuleCopied(String text) {
        ClipboardWatch.ours(text);
        this.clipboard.set(text);
    }

    private void givenTheWatchLooked() throws Exception {
        FxTesting.onFx(this.watch::tick);
    }

    private void whenTheWatchLooks() throws Exception {
        FxTesting.onFx(this.watch::tick);
    }

    private void whenTheFileLinksAreTakenFrom(String text) {
        this.links = ClipboardWatch.fileLinks(text);
    }

    private void whenTheFileLinksAreFoundIn(String text) {
        this.links = ClipboardWatch.fileLinksIn(text);
    }

    private void thenTheFileLinksAre(String... names) {
        assertThat(this.links).extracting(Ed2kLink.FileLink::name).containsExactly(names);
    }

    private void thenNoFileLinksAreTaken(String why) {
        assertThat(this.links).as(why).isEmpty();
    }

    private void thenTheClipboardWasReadTimes(int times, String why) {
        assertThat(this.reads).as(why).hasValue(times);
    }

    private void thenNothingWasFound(String why) {
        assertThat(this.found).as(why).isEmpty();
    }

    private void thenOneCopyWasFoundWithTheLinks(String... names) {
        assertThat(this.found)
                .singleElement()
                .satisfies(copy ->
                        assertThat(copy).extracting(Ed2kLink.FileLink::name).containsExactly(names));
    }

    private void thenCopiesWereFound(int copies, String why) {
        assertThat(this.found).as(why).hasSize(copies);
    }

    private void thenTheUserWasAskedTimes(int times) {
        assertThat(this.asked).hasSize(times);
    }

    private ClipboardWatch watch(
            BooleanSupplier wanted, Supplier<String> clipboard, List<List<Ed2kLink.FileLink>> found) throws Exception {
        FxTesting.start();
        // Never started: the tests tick it, and never touch the system clipboard.
        return FxTesting.onFx(() -> new ClipboardWatch(wanted, clipboard, links -> true, Runnable::run, found::add));
    }
}
