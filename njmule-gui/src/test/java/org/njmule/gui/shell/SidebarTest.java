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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class SidebarTest {

    private static final Hash16 PEER = Hash16.parseHex("00112233445566778899AABBCCDDEEFF");

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldFoldToItsIconsWithTheShortcutAndRememberIt() throws Exception {
        givenTheWindow();
        givenTheSidebarFolded(false);

        whenTheShortcutIsPressed(KeyCode.B);

        thenTheSidebarIsFolded(true);
        thenThePlacesShowOnlyTheirIcons();
        thenTheSettingsRememberItFolded(true);
    }

    @Test
    void shouldOpenAgainAsTheUserLeftItOnceTheWindowIsWideAgain() throws Exception {
        givenTheWindow();
        givenTheWindowWide(false);
        givenTheSidebarFolded(true);

        whenTheWindowIsWide(true);

        thenTheSidebarIsFolded(false);
        thenTheSettingsRememberItFolded(false);
    }

    @Test
    void shouldFoldByItselfInANarrowWindow() throws Exception {
        givenTheWindow();
        givenTheSidebarFolded(false);

        whenTheWindowIsWide(false);

        thenTheSidebarIsFolded(true);
        thenTheSettingsRememberItFolded(false);
    }

    @Test
    void shouldShowACountAsADotWhileFolded() throws Exception {
        givenTheWindow();
        givenMessagesArrived(2);

        whenTheShortcutIsPressed(KeyCode.B);

        thenTheCountOfMessagesShows(false);
        thenTheDotOfMessagesShows(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepTheIconOfThePlaceShownInLineWithTheOthers(boolean folded) throws Exception {
        givenTheWindow();
        givenTheSidebarFoldedByTheShortcut(folded);

        whenThePlaceIsShown("search");

        thenTheIconsOfThePlacesStandInOneColumn();
    }

    @Test
    void shouldLineTheIconsUpAgainOnceOpenedAfterBeingFolded() throws Exception {
        givenTheWindow();
        givenTheSidebarFoldedByTheShortcut(true);

        whenTheShortcutIsPressed(KeyCode.B);

        thenTheSidebarIsFolded(false);
        thenTheIconsOfThePlacesStandInOneColumn();
    }

    @Test
    void shouldKeepTheButtonThatOpensTheSidebarAtTheTopWhenFolded() throws Exception {
        givenTheWindow();
        givenTheSidebarFolded(false);

        whenTheShortcutIsPressed(KeyCode.B);

        thenTheSidebarIsFolded(true);
        thenTheButtonThatOpensTheSidebarStandsAboveThePlaces();
    }

    @Test
    void shouldOfferToBuyACoffeeJustAboveTheOptions() throws Exception {
        givenTheWindow();

        whenThePlaceIsShown("transfers");

        thenTheButtonStandsJustAbove("coffee", "settings");
    }

    private void givenTheWindow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
    }

    private void givenTheSidebarFolded(boolean folded) throws Exception {
        FxTesting.await("the sidebar as it starts", () -> folded == collapsed());
    }

    private void givenTheSidebarFoldedByTheShortcut(boolean folded) throws Exception {
        givenTheSidebarFolded(false);
        if (folded) {
            whenTheShortcutIsPressed(KeyCode.B);
            thenTheSidebarIsFolded(true);
        }
    }

    private void whenThePlaceIsShown(String place) throws Exception {
        FxTesting.onFx(() -> {
            this.window.show(place);
            this.window.root().applyCss();
            this.window.root().layout();
        });
    }

    // The mark of the place shown never pushes its icon out of line.
    private void thenTheIconsOfThePlacesStandInOneColumn() throws Exception {
        List<Double> starts = FxTesting.onFx(() -> {
            this.window.root().applyCss();
            this.window.root().layout();
            return this.window.root().lookupAll(".nav-rail .toggle-button").stream()
                    .map(b -> ((ToggleButton) b).getGraphic())
                    .map(g -> g.localToScene(g.getLayoutBounds()).getMinX())
                    .toList();
        });
        assertThat(starts).hasSizeGreaterThan(5);
        assertThat(starts).allSatisfy(x -> assertThat(x).isCloseTo(starts.getFirst(), within(0.5)));
    }

    private void thenTheButtonThatOpensTheSidebarStandsAboveThePlaces() throws Exception {
        double[] tops = FxTesting.onFx(() -> {
            this.window.root().applyCss();
            this.window.root().layout();
            Node expand = this.window.root().lookup("#sidebar-expand");
            Node first = this.window.sidebar().button("transfers");
            return new double[] {
                expand.localToScene(expand.getLayoutBounds()).getMaxY(),
                first.localToScene(first.getLayoutBounds()).getMinY()
            };
        });
        assertThat(tops[0]).isLessThanOrEqualTo(tops[1]);
    }

    private void thenTheButtonStandsJustAbove(String upper, String lower) throws Exception {
        double[] edges = FxTesting.onFx(() -> {
            Node above = this.window.sidebar().button(upper);
            Node below = this.window.sidebar().button(lower);
            return new double[] {
                above.localToScene(above.getLayoutBounds()).getMaxY(),
                below.localToScene(below.getLayoutBounds()).getMinY()
            };
        });
        assertThat(edges[0]).isLessThanOrEqualTo(edges[1]).isCloseTo(edges[1], within(12.0));
    }

    private void givenTheWindowWide(boolean wide) throws Exception {
        whenTheWindowIsWide(wide);
    }

    private void givenMessagesArrived(int count) throws Exception {
        FxTesting.onFx(() -> {
            for (int i = 0; i < count; i++) {
                this.test.model.handle(
                        new EngineEvent.MessageReceived("10.0.0.1:4662", PEER, Optional.of("Bob"), "hi " + i));
            }
        });
    }

    private void whenTheShortcutIsPressed(KeyCode key) throws Exception {
        FxTesting.onFx(() -> this.window.root().fireEvent(FxTesting.key(key, true)));
    }

    private void whenTheWindowIsWide(boolean wide) throws Exception {
        double width = wide ? Sidebar.FOLD_BELOW + 100 : Sidebar.FOLD_BELOW - 100;
        FxTesting.onFx(() -> this.window.root().getScene().getWindow().setWidth(width));
        FxTesting.await(
                "the window's width",
                () -> wide == (this.window.root().getLayoutBounds().getWidth() >= Sidebar.FOLD_BELOW));
    }

    private void thenTheSidebarIsFolded(boolean folded) throws Exception {
        FxTesting.assertEventually("the sidebar folded " + folded, () -> folded == collapsed());
    }

    private void thenThePlacesShowOnlyTheirIcons() throws Exception {
        assertThat(FxTesting.onFx(() -> this.window.sidebar().button("search").getContentDisplay()))
                .isEqualTo(ContentDisplay.GRAPHIC_ONLY);
    }

    private void thenTheSettingsRememberItFolded(boolean folded) {
        assertThat(this.test.gui.settings().sidebarCollapsed()).isEqualTo(folded);
    }

    private void thenTheCountOfMessagesShows(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> mark("messages", ".rail-badge").isVisible()))
                .isEqualTo(shown);
    }

    private void thenTheDotOfMessagesShows(boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> mark("messages", ".rail-pip").isVisible()))
                .isEqualTo(shown);
    }

    private boolean collapsed() {
        return this.window.sidebar().collapsedProperty().get();
    }

    private Node mark(String place, String style) {
        return this.window.sidebar().button(place).getParent().lookup(style);
    }
}
