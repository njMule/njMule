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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The links macOS opens, on a pretend desktop: AWT is never asked here. */
class MacLinksTest {

    private static final String FILE_LINK = "ed2k://|file|song.mp3|4000000|0123456789ABCDEF0123456789ABCDEF|/";
    private static final String SERVER_LINK = "ed2k://|server|81.2.3.4|4661|/";

    private final MacLinks links = new MacLinks();
    private final PretendDesktop desktop = new PretendDesktop();
    private final List<List<String>> received = new ArrayList<>();
    private boolean installed;

    @Test
    void shouldTakeTheLinksOfMacOs() {
        givenADesktopThatOpensLinks();

        whenTheHandlerIsInstalledOn("Mac OS X");

        thenTheHandlerIsSet();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Windows 11", "Linux", "FreeBSD", ""})
    void shouldLeaveTheDesktopAloneElsewhere(String osName) {
        givenADesktopThatOpensLinks();

        whenTheHandlerIsInstalledOn(osName);

        thenTheDesktopWasNeverAsked();
    }

    @Test
    void shouldSetNothingWhereTheDesktopOpensNoLinks() {
        givenADesktopWithoutLinks();

        whenTheHandlerIsInstalledOn("Mac OS X");

        thenNoHandlerIsSet();
    }

    @Test
    void shouldGoOnWhenTheDesktopFails() {
        givenADesktopThatFails();

        whenTheHandlerIsInstalledOn("Mac OS X");

        thenNoHandlerIsSet();
    }

    @Test
    void shouldKeepTheLinksThatCameBeforeTheWindow() {
        givenTheHandlerInstalledOnMacOs();
        givenMacOsOpened(FILE_LINK);
        givenMacOsOpened(SERVER_LINK);

        whenTheWindowTakesTheLinks();

        thenTheWindowReceived(List.of(FILE_LINK, SERVER_LINK));
    }

    @Test
    void shouldHandTheLaterLinksAtOnce() {
        givenTheHandlerInstalledOnMacOs();
        givenTheWindowTookTheLinks();

        whenMacOsOpens(FILE_LINK);

        thenTheWindowReceived(List.of(FILE_LINK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"njmule:exit", "https://example.org/", "magnet:?xt=urn:btih:abc", "ed2k://|nothing|/"})
    void shouldLeaveOutWhatIsNoEd2kLink(String uri) {
        givenTheHandlerInstalledOnMacOs();
        givenTheWindowTookTheLinks();

        whenMacOsOpens(uri);

        thenTheWindowReceivedNothing();
    }

    @Test
    void shouldKeepTheLinksForTheNextWindowOnceOneClosed() {
        givenTheHandlerInstalledOnMacOs();
        givenTheWindowTookTheLinks();
        givenTheWindowClosed();
        givenMacOsOpened(FILE_LINK);

        whenTheWindowTakesTheLinks();

        thenTheWindowReceived(List.of(FILE_LINK));
    }

    @Test
    void shouldKeepAtMostTheLinksALaterStartMayHandOver() {
        givenTheHandlerInstalledOnMacOs();
        givenMacOsOpenedManyLinks(MacLinks.MAX_WAITING + 10);

        whenTheWindowTakesTheLinks();

        thenTheWindowReceivedLinks(MacLinks.MAX_WAITING);
    }

    private void givenADesktopThatOpensLinks() {
        this.desktop.supported = true;
    }

    private void givenADesktopWithoutLinks() {
        this.desktop.supported = false;
    }

    private void givenADesktopThatFails() {
        this.desktop.fails = true;
    }

    private void givenTheHandlerInstalledOnMacOs() {
        givenADesktopThatOpensLinks();
        whenTheHandlerIsInstalledOn("Mac OS X");
    }

    private void givenMacOsOpened(String uri) {
        whenMacOsOpens(uri);
    }

    private void givenMacOsOpenedManyLinks(int count) {
        for (int i = 0; i < count; i++) {
            whenMacOsOpens(String.format("ed2k://|file|f%d.bin|10|%032X|/", i, i));
        }
    }

    private void givenTheWindowTookTheLinks() {
        whenTheWindowTakesTheLinks();
    }

    private void givenTheWindowClosed() {
        this.links.detach();
    }

    private void whenTheHandlerIsInstalledOn(String osName) {
        this.installed = this.links.install(osName, this.desktop);
    }

    private void whenMacOsOpens(String uri) {
        this.desktop.open(URI.create(escaped(uri)));
    }

    private void whenTheWindowTakesTheLinks() {
        this.links.attach(this.received::add);
    }

    private void thenTheHandlerIsSet() {
        assertThat(this.installed).isTrue();
        assertThat(this.desktop.handler).isNotNull();
    }

    private void thenNoHandlerIsSet() {
        assertThat(this.installed).isFalse();
        assertThat(this.desktop.handler).isNull();
    }

    private void thenTheDesktopWasNeverAsked() {
        assertThat(this.installed).isFalse();
        assertThat(this.desktop.asked).isFalse();
    }

    private void thenTheWindowReceived(List<String> links) {
        assertThat(this.received.stream().flatMap(List::stream).toList())
                .isEqualTo(links.stream().map(MacLinksTest::escaped).toList());
    }

    // A URI cannot hold the separators of ed2k links: browsers hand them over escaped.
    private static String escaped(String link) {
        return link.replace("|", "%7C");
    }

    private void thenTheWindowReceivedNothing() {
        assertThat(this.received).isEmpty();
    }

    private void thenTheWindowReceivedLinks(int count) {
        assertThat(this.received.stream().mapToInt(List::size).sum()).isEqualTo(count);
    }

    /** A desktop whose link events the test sends. */
    private static final class PretendDesktop implements MacLinks.UriEvents {
        private boolean supported;
        private boolean fails;
        private boolean asked;
        private Consumer<URI> handler;

        @Override
        public boolean supported() {
            this.asked = true;
            if (this.fails) {
                throw new UnsupportedOperationException("no desktop");
            }
            return this.supported;
        }

        @Override
        public void handle(Consumer<URI> handler) {
            this.handler = handler;
        }

        void open(URI uri) {
            this.handler.accept(uri);
        }
    }
}
